package com.qabas.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * أدوات وكيل الكود 🤖 — يستدعيها نموذج OpenRouter أثناء بناء تطبيق العميل:
 * تصفح الشجرة • قراءة ملف • كتابة ملف • تشغيل البناء • حالة البناء • فتح سحب.
 * تعمل على فرع التطوير فقط — الرئيسي محمي بالدمج اليدوي.
 */
object CodeTools {

    data class Ctx(
        val owner: String,
        val repo: String,
        val token: String,
        val branch: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun strProp(desc: String) = JSONObject().put("type", "string").put("description", desc)

    fun definitions(ctx: CodeTools.Ctx): List<AiTools.ToolDef> {
        val rc = GitHubRepoClient(ctx.owner, ctx.repo, ctx.token)
        return listOf(
            AiTools.ToolDef(
                "list_tree",
                "قائمة ملفات المستودع على فرع العمل (المسارات فقط).",
                JSONObject(), emptyList()
            ) { _ ->
                withContext(Dispatchers.IO) {
                    val tree = rc.getTree(ctx.branch)
                        ?: return@withContext "تعذر قراءة الشجرة."
                    tree.take(120).joinToString("\n") { it.path }.ifBlank { "الفرع فارغ." }
                }
            },
            AiTools.ToolDef(
                "read_file",
                "قراءة محتوى ملف من فرع العمل (حتى 8000 محرف).",
                JSONObject().put("path", strProp("مسار الملف، مثال: app/src/main/java/com/client/app/MainActivity.kt")),
                listOf("path")
            ) { args ->
                withContext(Dispatchers.IO) {
                    val path = args.optString("path")
                    val content = rc.getFileContent(path, ctx.branch)?.content
                        ?.let { runCatching { String(android.util.Base64.decode(it.replace("\\s".toRegex(), ""), android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() }
                    content?.take(8000) ?: "تعذر قراءة $path."
                }
            },
            AiTools.ToolDef(
                "write_file",
                "إنشاء ملف جديد أو استبداله كاملاً على فرع العمل. اكتب المحتوى الكامل لا فرقاً.",
                JSONObject()
                    .put("path", strProp("مسار الملف الكامل"))
                    .put("content", strProp("المحتوى الكامل للملف"))
                    .put("message", strProp("رسالة الـ commit القصيرة")),
                listOf("path", "content")
            ) { args ->
                withContext(Dispatchers.IO) {
                    val path = args.optString("path")
                    val content = args.optString("content")
                    val msg = args.optString("message").ifBlank { "وكيل قبس: $path" }
                    if (path.isBlank() || content.isBlank()) return@withContext "مسار أو محتوى فارغ."
                    if (".." in path || path.length > 200) return@withContext "مسار مرفوض."
                    val ok = rc.createFile(path, ctx.branch, msg, content)
                    if (ok) "حُفظ $path (${content.length} محرف) ✅" else "فشل حفظ $path."
                }
            },
            AiTools.ToolDef(
                "trigger_build",
                "تشغيل بناء APK (ملف android-ci.yml على الفرع الافتراضي).",
                JSONObject(), emptyList()
            ) { _ ->
                withContext(Dispatchers.IO) {
                    val body = JSONObject().put("ref", ctx.branch).toString()
                        .toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("https://api.github.com/repos/${ctx.owner}/${ctx.repo}/actions/workflows/android-ci.yml/dispatches")
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "Qabas-Studio")
                        .header("Authorization", "Bearer ${ctx.token}")
                        .post(body)
                        .build()
                    val code = runCatching { client.newCall(req).execute().use { it.code } }.getOrDefault(-1)
                    if (code == 204) "أُرسل أمر البناء ✅" else "تعذر التشغيل (كود $code) — تحقق من وجود android-ci.yml والصلاحيات."
                }
            },
            AiTools.ToolDef(
                "build_status",
                "حالة آخر تشغيلات البناء (ناجح/فاشل/يعمل) مع أول سطر سبب عند الفشل.",
                JSONObject(), emptyList()
            ) { _ ->
                withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("https://api.github.com/repos/${ctx.owner}/${ctx.repo}/actions/runs?per_page=5")
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "Qabas-Studio")
                        .header("Authorization", "Bearer ${ctx.token}")
                        .get()
                        .build()
                    val body = runCatching { client.newCall(req).execute().use { it.body?.string().orEmpty() } }.getOrDefault("")
                    if (body.isBlank()) return@withContext "تعذر جلب حالة البناء."
                    val arr = JSONObject(body).optJSONArray("workflow_runs") ?: return@withContext "لا تشغيلات بعد."
                    if (arr.length() == 0) return@withContext "لا تشغيلات بعد — شغّل البناء أولاً."
                    buildString {
                        for (i in 0 until arr.length()) {
                            val r = arr.getJSONObject(i)
                            append("#${r.optString("name")} [${r.optString("status")}/${r.optString("conclusion", "—")}] فرع ${r.optString("head_branch")} (${r.optString("created_at").take(10)})\n")
                        }
                    }
                }
            },
            AiTools.ToolDef(
                "create_pr",
                "فتح سحب من فرع العمل إلى الفرع الافتراضي بعد انتهائك.",
                JSONObject()
                    .put("title", strProp("عنوان السحب"))
                    .put("body", strProp("وصف التغييرات")),
                listOf("title")
            ) { args ->
                withContext(Dispatchers.IO) {
                    val n = rc.createPullRequest(
                        ctx.branch,
                        args.optString("title").ifBlank { "عمل الوكيل" },
                        args.optString("body"),
                        rc.getDefaultBranch()
                    )
                    if (n != null && n > 0) "فُتح السحب #$n ✅" else "تعذر فتح السحب (قد يوجد واحد مفتوح)."
                }
            },
            AiTools.ToolDef(
                "update_file",
                "تعديل ملف موجود على فرع العمل (يجلب sha تلقائياً). للملفات الجديدة استخدم write_file.",
                JSONObject()
                    .put("path", strProp("مسار الملف الكامل"))
                    .put("content", strProp("المحتوى الكامل الجديد للملف"))
                    .put("message", strProp("رسالة الـ commit القصيرة")),
                listOf("path", "content")
            ) { args ->
                withContext(Dispatchers.IO) {
                    val path = args.optString("path")
                    val content = args.optString("content")
                    val msg = args.optString("message").ifBlank { "وكيل قبس: تحديث $path" }
                    if (path.isBlank() || content.isBlank()) return@withContext "مسار أو محتوى فارغ."
                    if (".." in path || path.length > 200) return@withContext "مسار مرفوض."
                    val sha = rc.getFileContent(path, ctx.branch)?.sha
                        ?: return@withContext "الملف غير موجود على الفرع — استخدم write_file للإنشاء."
                    val ok = rc.createFile(path, ctx.branch, msg, content, sha)
                    if (ok) "حُدّث $path (${content.length} محرف) ✅" else "فشل تحديث $path."
                }
            },
            AiTools.ToolDef(
                "delete_file",
                "حذف ملف من فرع العمل نهائياً — استخدمه بحذر.",
                JSONObject()
                    .put("path", strProp("مسار الملف الكامل"))
                    .put("message", strProp("سبب الحذف")),
                listOf("path")
            ) { args ->
                withContext(Dispatchers.IO) {
                    val path = args.optString("path")
                    if (path.isBlank() || ".." in path) return@withContext "مسار مرفوض."
                    val sha = rc.getFileContent(path, ctx.branch)?.sha
                        ?: return@withContext "الملف غير موجود أصلاً."
                    val ok = rc.deleteFile(path, ctx.branch, args.optString("message").ifBlank { "وكيل قبس: حذف $path" }, sha)
                    if (ok) "حُذف $path ✅" else "فشل حذف $path."
                }
            },
            AiTools.ToolDef(
                "list_branches",
                "كل فروع المستودع — ليعرف الوكيل أين يعمل وأين دُمج.",
                JSONObject(), emptyList()
            ) { _ ->
                withContext(Dispatchers.IO) {
                    rc.getBranches()?.joinToString("\n") { it.name }?.ifBlank { "لا فروع." }
                        ?: "تعذر جلب الفروع."
                }
            },
            AiTools.ToolDef(
                "recent_commits",
                "آخر 10 commits على فرع العمل — سياق ما تم فعله.",
                JSONObject(), emptyList()
            ) { _ ->
                withContext(Dispatchers.IO) {
                    rc.getCommits(ctx.branch, 10)?.joinToString("\n") { "${it.sha.take(7)} ${it.message.take(80)}" }
                        ?.ifBlank { "لا commits." } ?: "تعذر جلب السجل."
                }
            },
            AiTools.ToolDef(
                "list_prs",
                "السحوبات المفتوحة — ليراجع الوكيل عمله قبل الدمج.",
                JSONObject(), emptyList()
            ) { _ ->
                withContext(Dispatchers.IO) {
                    rc.listPullRequests("open").joinToString("\n").ifBlank { "لا سحوبات مفتوحة." }
                }
            },
            AiTools.ToolDef(
                "pr_files",
                "ملفات سحب معين مع حجم التغيير — للمراجعة قبل الدمج.",
                JSONObject().put("number", strProp("رقم السحب")),
                listOf("number")
            ) { args ->
                withContext(Dispatchers.IO) {
                    val n = args.optString("number").toIntOrNull() ?: return@withContext "رقم غير صالح."
                    rc.getPullFiles(n).joinToString("\n").ifBlank { "لا ملفات أو سحب غير موجود." }
                }
            },
            AiTools.ToolDef(
                "merge_pr",
                "دمج سحب بعد مراجعة ملفاته — الخطوة الأخيرة قبل التسليم.",
                JSONObject().put("number", strProp("رقم السحب")),
                listOf("number")
            ) { args ->
                withContext(Dispatchers.IO) {
                    val n = args.optString("number").toIntOrNull() ?: return@withContext "رقم غير صالح."
                    if (rc.mergePullRequest(n)) "دُمج السحب #$n ✅" else "فشل الدمج — راجع الصلاحيات أو تعارضات الدمج."
                }
            },
            AiTools.ToolDef(
                "create_issue",
                "فتح قضية لتتبع عيب أو مهمة اكتشفها الوكيل أثناء العمل.",
                JSONObject()
                    .put("title", strProp("عنوان القضية"))
                    .put("body", strProp("وصف العيب أو المهمة")),
                listOf("title")
            ) { args ->
                withContext(Dispatchers.IO) {
                    if (rc.createIssue(args.optString("title"), args.optString("body"))) "فُتحت القضية ✅"
                    else "تعذر فتح القضية."
                }
            },
            AiTools.ToolDef(
                "list_issues",
                "القضايا المفتوحة — مهام بانتظار المعالجة.",
                JSONObject(), emptyList()
            ) { _ ->
                withContext(Dispatchers.IO) {
                    rc.listIssues().joinToString("\n").ifBlank { "لا قضايا مفتوحة 🎉" }
                }
            }
        )
    }

    fun toApiJson(ctx: CodeTools.Ctx): JSONArray = AiTools.toApiJson(definitions(ctx))
}
