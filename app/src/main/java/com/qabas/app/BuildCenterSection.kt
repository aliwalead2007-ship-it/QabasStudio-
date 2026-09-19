package com.qabas.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * غرفة تحكم GitHub الكاملة — من داخل لوحة المطور:
 * البناء (تشغيل/إعادة/إلغاء) • الإصدارات (عرض + تنزيل وتثبيت APK) •
 * الفروع • سجل الـ commits • معلومات المستودع.
 * رمز PAT يُحفظ على الجهاز فقط. القراءة العامة تعمل بدونه.
 */
@Composable
fun BuildCenterSection(context: Context, onNavigateTo: (AppState) -> Unit = {}) {
    val prefs = remember { context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    var owner by remember { mutableStateOf(prefs.getString("build_center_owner", "aliwalead2007-ship-it") ?: "aliwalead2007-ship-it") }
    var repo by remember { mutableStateOf(prefs.getString("build_center_repo", "QabasStudio-") ?: "QabasStudio-") }
    var token by remember { mutableStateOf(prefs.getString("build_center_token", "") ?: "") }
    var showToken by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("اضغط «تحديث» لجلب بيانات المستودع") }
    var tab by remember { mutableStateOf(0) } // 0 بناء • 1 إصدارات • 2 فروع • 3 سجل

    var repoInfo by remember { mutableStateOf<RepoInfo?>(null) }
    var runs by remember { mutableStateOf(listOf<BuildRun>()) }
    var releases by remember { mutableStateOf(listOf<ReleaseInfo>()) }
    var branches by remember { mutableStateOf(listOf<BranchInfo>()) }
    var commits by remember { mutableStateOf(listOf<CommitInfo>()) }
    var installingTag by remember { mutableStateOf<String?>(null) }
    var installingProgress by remember { mutableStateOf(0) }
    var lastRefresh by remember { mutableStateOf<String?>(null) }

    fun saveSettings() {
        prefs.edit()
            .putString("build_center_owner", owner.trim())
            .putString("build_center_repo", repo.trim())
            .putString("build_center_token", token.trim())
            .apply()
    }

    suspend fun apiGet(path: String, auth: Boolean): String? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url("https://api.github.com$path")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Qabas-Studio")
            if (auth && token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            val response = client.newCall(builder.build()).execute()
            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) { status = "خطأ GitHub: ${response.code}" }
                return@withContext null
            }
            response.body?.string()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { status = "تعذر الاتصال: ${e.message}" }
            null
        }
    }

    suspend fun apiPost(path: String, body: String?, expectCode: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val rb = (body ?: "").toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://api.github.com$path")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Qabas-Studio")
                .header("Authorization", "Bearer $token")
                .post(rb)
                .build()
            client.newCall(req).execute().use { it.code == expectCode }
        }.getOrDefault(false)
    }

    fun refreshAll() {
        if (owner.isBlank() || repo.isBlank()) {
            Toast.makeText(context, "أدخل المالك والمستودع أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        saveSettings()
        busy = true
        status = "جاري جلب بيانات المستودع..."
        scope.launch {
            val o = owner.trim()
            val r = repo.trim()
            val authed = token.isNotBlank()

            // معلومات المستودع
            apiGet("/repos/$o/$r", auth = authed)?.let { body ->
                val j = JSONObject(body)
                repoInfo = RepoInfo(
                    fullName = j.optString("full_name", "$o/$r"),
                    description = j.optString("description", ""),
                    isPrivate = j.optBoolean("private", false),
                    defaultBranch = j.optString("default_branch", "main"),
                    stars = j.optInt("stargazers_count", 0),
                    openIssues = j.optInt("open_issues_count", 0)
                )
            }
            // التشغيلات
            apiGet("/repos/$o/$r/actions/runs?per_page=8", auth = authed)?.let { body ->
                val arr = JSONObject(body).optJSONArray("workflow_runs")
                runs = buildList {
                    for (i in 0 until (arr?.length() ?: 0)) {
                        val run = arr!!.getJSONObject(i)
                        add(
                            BuildRun(
                                id = run.optLong("id", 0),
                                name = run.optString("display_title", run.optString("name", "بناء")),
                                status = run.optString("status", ""),
                                conclusion = run.optString("conclusion", ""),
                                branch = run.optString("head_branch", ""),
                                created = run.optString("created_at", "").take(16).replace("T", " ")
                            )
                        )
                    }
                }
            }
            // الإصدارات
            apiGet("/repos/$o/$r/releases?per_page=10", auth = authed)?.let { body ->
                val arr = JSONArray(body)
                releases = buildList {
                    for (i in 0 until arr.length()) {
                        val rel = arr.getJSONObject(i)
                        val assets = rel.optJSONArray("assets")
                        val apk = (0 until (assets?.length() ?: 0))
                            .map { assets!!.getJSONObject(it) }
                            .firstOrNull { it.optString("name", "").endsWith(".apk") }
                        add(
                            ReleaseInfo(
                                tag = rel.optString("tag_name", ""),
                                name = rel.optString("name", "").ifBlank { rel.optString("tag_name", "") },
                                notes = rel.optString("body", ""),
                                published = rel.optString("published_at", "").take(10),
                                apkUrl = apk?.optString("browser_download_url"),
                                apkAssetId = apk?.optLong("id", 0) ?: 0,
                                apkSize = apk?.optLong("size", 0) ?: 0,
                                isDraft = rel.optBoolean("draft", false),
                                isPrerelease = rel.optBoolean("prerelease", false)
                            )
                        )
                    }
                }
            }
            // الفروع
            apiGet("/repos/$o/$r/branches?per_page=20", auth = authed)?.let { body ->
                val arr = JSONArray(body)
                branches = buildList {
                    for (i in 0 until arr.length()) {
                        val b = arr.getJSONObject(i)
                        add(
                            BranchInfo(
                                name = b.optString("name", ""),
                                protected = b.optBoolean("protected", false),
                                sha = b.optJSONObject("commit")?.optString("sha", "")?.take(7) ?: ""
                            )
                        )
                    }
                }
            }
            // سجل الـ commits
            apiGet("/repos/$o/$r/commits?per_page=12&sha=main", auth = authed)?.let { body ->
                val arr = JSONArray(body)
                commits = buildList {
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        val inner = c.optJSONObject("commit")
                        add(
                            CommitInfo(
                                sha = c.optString("sha", "").take(7),
                                message = inner?.optString("message", "")?.lineSequence()?.firstOrNull() ?: "",
                                author = inner?.optJSONObject("author")?.optString("name", "") ?: "",
                                date = inner?.optJSONObject("author")?.optString("date", "")?.take(10) ?: ""
                            )
                        )
                    }
                }
            }
            status = "تم التحديث — $o/$r"
            lastRefresh = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            busy = false
        }
    }

    fun triggerBuild() {
        if (token.isBlank()) {
            Toast.makeText(context, "أدخل رمز PAT بصلاحية actions:write أولاً", Toast.LENGTH_LONG).show()
            return
        }
        saveSettings()
        busy = true
        status = "جاري إرسال أمر البناء..."
        scope.launch {
            val ok = apiPost(
                "/repos/${owner.trim()}/${repo.trim()}/actions/workflows/android-ci.yml/dispatches",
                JSONObject().put("ref", "main").toString(), 204
            )
            status = if (ok) "أُرسل أمر البناء — حدّث بعد دقيقة لرؤية التشغيل" else "فشل إرسال الأمر (تحقق من الرمز والصلاحيات)"
            busy = false
            if (ok) {
                AuditLogger.log(context, "build_trigger", "تشغيل بناء APK من غرفة تحكم GitHub")
                // ربط التقدم بالطلب النشط + إشعار العميل تلقائياً (لا رجوع للخلف)
                AppRequestService.getActiveBuildRequest(context)?.let { req ->
                    val earlyPrefs = context.getSharedPreferences("qabas_requests_prefs", Context.MODE_PRIVATE)
                    if (req.progress < 80) {
                        AppRequestService.updateRequestProgressAndPayment(context, req.id, progress = 80)
                    }
                    if (!earlyPrefs.getBoolean("silent_${req.id}", false)) {
                        AppRequestService.sendMessage(
                            context,
                            AppRequestService.ChatMessage(
                                requestId = req.id, senderEmail = "dev", isDeveloper = true,
                                message = "بدأ بناء نسختك الأولى 🏗️ — ستصلك فور جاهزيتها للاختبار."
                            )
                        )
                    }
                }
                kotlinx.coroutines.delay(15000)
                refreshAll()
            }
        }
    }

    fun rerunRun(id: Long) {
        if (token.isBlank()) {
            Toast.makeText(context, "إعادة التشغيل تحتاج رمز PAT", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        scope.launch {
            val ok = apiPost("/repos/${owner.trim()}/${repo.trim()}/actions/runs/$id/rerun", "", 201)
            Toast.makeText(context, if (ok) "أُعيد التشغيل" else "فشلت إعادة التشغيل", Toast.LENGTH_SHORT).show()
            if (ok) AuditLogger.log(context, "build_rerun", "إعادة تشغيل البناء رقم $id")
            busy = false
            if (ok) { kotlinx.coroutines.delay(8000); refreshAll() }
        }
    }

    fun cancelRun(id: Long) {
        if (token.isBlank()) {
            Toast.makeText(context, "الإلغاء يحتاج رمز PAT", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        scope.launch {
            val ok = apiPost("/repos/${owner.trim()}/${repo.trim()}/actions/runs/$id/cancel", "", 202)
            Toast.makeText(context, if (ok) "أُلغي التشغيل" else "فشل الإلغاء", Toast.LENGTH_SHORT).show()
            if (ok) AuditLogger.log(context, "build_cancel", "إلغاء البناء رقم $id")
            busy = false
            if (ok) refreshAll()
        }
    }

    fun installRelease(rel: ReleaseInfo) {
        if (rel.apkUrl == null) {
            Toast.makeText(context, "لا يوجد APK في هذا الإصدار", Toast.LENGTH_SHORT).show()
            return
        }
        installingTag = rel.tag
        val hasToken = token.isNotBlank()
        // مستودع خاص + رمز متوفر → رابط API للأصل (يقبل التوكن).
        // غير ذلك → رابط التنزيل المباشر العام.
        val downloadUrl = if (hasToken && rel.apkAssetId > 0) {
            "https://api.github.com/repos/${owner.trim()}/${repo.trim()}/releases/assets/${rel.apkAssetId}"
        } else {
            rel.apkUrl!!
        }
        val info = UpdateManager.UpdateInfo(
            versionName = rel.tag.removePrefix("v"),
            versionCode = parseTagCode(rel.tag),
            deltaUrl = null,
            apkUrl = downloadUrl,
            releaseNotes = rel.notes,
            deltaSize = 0,
            apkSize = rel.apkSize
        )
        UpdateManager.downloadAndInstall(
            context, info,
            onProgress = { installingProgress = it.percent },
            onDone = { ok, msg ->
                installingTag = null
                installingProgress = 0
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                if (ok) AuditLogger.log(context, "release_install", "تثبيت ${rel.tag} من غرفة GitHub")
            },
            authToken = token.takeIf { hasToken }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── بطاقة البطل: المستودع ──
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = GoldPrimary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Source, contentDescription = null, tint = GoldPrimary, modifier = Modifier.padding(10.dp).size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            repoInfo?.fullName ?: "${owner.trim()}/${repo.trim()}",
                            color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 15.sp
                        )
                        Text(
                            repoInfo?.description?.take(80) ?: status,
                            color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp, maxLines = 1
                        )
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Tune, contentDescription = "الإعداد", tint = TextSecondary)
                    }
                }
                // شارات الحالة
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BuildPill(
                        text = when {
                            repoInfo == null -> "غير متصل"
                            repoInfo!!.isPrivate -> "خاص"
                            else -> "عام"
                        },
                        tint = if (repoInfo?.isPrivate == true) Color(0xFFF59E0B) else Color(0xFF10B981)
                    )
                    if (repoInfo != null) {
                        BuildPill(text = "الفرع: ${repoInfo!!.defaultBranch}", tint = Color(0xFF38BDF8))
                        if (token.isNotBlank()) BuildPill(text = "رمز محفوظ", tint = Color(0xFF8B5CF6))
                    }
                    if (lastRefresh != null) {
                        BuildPill(text = "آخر تحديث $lastRefresh", tint = TextSecondary)
                    }
                }
                // إحصاءات سريعة
                if (repoInfo != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(DeepSlate, RoundedCornerShape(12.dp))
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        BuildStat(value = repoInfo!!.stars.toString(), label = "نجوم")
                        BuildStat(value = releases.size.toString(), label = "إصدارات")
                        BuildStat(value = branches.size.toString(), label = "فروع")
                        BuildStat(value = runs.count { it.conclusion == "success" }.toString(), label = "بناء ناجح")
                    }
                }
                if (showSettings) {
                    HorizontalDivider(color = Color(0xFF222222))
                    OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("المالك") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("المستودع") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text("رمز PAT (على جهازك فقط)") }, singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null, tint = TextSecondary)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "بدون رمز: قراءة عامة فقط. بالرمز: تشغيل/إلغاء البناء + المستودعات الخاصة (يحتاج Contents: Read).",
                        color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp
                    )
                }
                // ── مستودع جديد لطلب عميل ──
                val linkedForRepo = remember { AppRequestService.getActiveBuildRequest(context) }
                var newRepoName by remember(linkedForRepo?.id) {
                    mutableStateOf(
                        linkedForRepo?.let { GitHubRepoClient.sanitizeRepoName(it.title) } ?: ""
                    )
                }
                var newRepoPrivate by remember { mutableStateOf(true) }
                var creatingRepo by remember { mutableStateOf(false) }
                Surface(
                    color = GoldPrimary.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (linkedForRepo != null) "📦 مستودع جديد للطلب: ${linkedForRepo.title}"
                            else "📦 إنشاء مستودع جديد",
                            color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = newRepoName, onValueChange = { newRepoName = it },
                            label = { Text("اسم المستودع", fontSize = 11.sp) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = newRepoPrivate,
                                onCheckedChange = { newRepoPrivate = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary, checkedTrackColor = GoldSecondary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (newRepoPrivate) "خاص 🔒 (مستحسن لعمل العملاء)" else "عام 🌍",
                                color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = {
                                if (token.isBlank()) {
                                    Toast.makeText(context, "أدخل رمز PAT أولاً (يحتاج صلاحية repo)", Toast.LENGTH_LONG).show()
                                    return@Button
                                }
                                val clean = GitHubRepoClient.sanitizeRepoName(newRepoName)
                                if (clean.isBlank()) {
                                    Toast.makeText(context, "أدخل اسماً صالحاً للمستودع", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                creatingRepo = true
                                scope.launch {
                                    val fullName = GitHubRepoClient.createUserRepo(
                                        token.trim(), clean,
                                        linkedForRepo?.let { "تطبيق العميل: ${it.title} — ${it.goal}" } ?: "",
                                        newRepoPrivate
                                    )
                                    withContext(Dispatchers.Main) {
                                        creatingRepo = false
                                        if (fullName != null) {
                                            val parts = fullName.split("/")
                                            owner = parts[0]
                                            repo = parts.getOrNull(1) ?: clean
                                            saveSettings()
                                            val msg = "أُنشئ المستودع ✅ $fullName — صار مستودع العمل الحالي"
                                            status = msg
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                            AuditLogger.log(
                                                context, "repo_created",
                                                "$fullName للطلب: ${linkedForRepo?.title ?: "يدوي"}"
                                            )
                                            linkedForRepo?.let {
                                                context.getSharedPreferences("qabas_requests_prefs", Context.MODE_PRIVATE)
                                                    .edit().putString("repo_${it.id}", fullName).apply()
                                            }
                                            refreshAll()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "تعذر الإنشاء — تحقق من الرمز (صلاحية repo) والاسم (غير مكرر)",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            },
                            enabled = !creatingRepo && !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (creatingRepo) {
                                CircularProgressIndicator(color = DeepSlate, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("إنشاء المستودع 📦", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        Text(
                            "يحتاج رمز PAT الكلاسيكي بصلاحية repo (وليس Contents فقط).",
                            color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::refreshAll, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تحديث", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Button(
                        onClick = ::triggerBuild, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تشغيل البناء", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = GoldPrimary, trackColor = Color(0xFF1E293B)
                    )
                }
            }
        }

        // ── الجسر: الطلب النشط قيد البناء ──
        val activeReq = remember { mutableStateOf(AppRequestService.getActiveBuildRequest(context)) }
        var scaffolding by remember { mutableStateOf(false) }
        var scaffoldMsg by remember { mutableStateOf<String?>(null) }
        activeReq.value?.let { req ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = GoldPrimary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "🛠️ قيد البناء الآن",
                                color = GoldPrimary, fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                            Text(
                                "${req.title} — ${req.userEmail}",
                                color = Color.White, fontFamily = NotoSansFont, fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        Button(
                            onClick = { tab = 4 },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("المحرر ✏️", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(onClick = {
                            AppRequestService.clearActiveBuildRequest(context)
                            activeReq.value = null
                        }) {
                            Text("إنهاء", color = TextSecondary, fontFamily = CairoFont, fontSize = 11.sp)
                        }
                    }
                    // الوضع الصامت 🔇: يعمل الوكيل دون أي رسالة للعميل (تُكسر عند التسليم)
                    val reqPrefs = context.getSharedPreferences("qabas_requests_prefs", Context.MODE_PRIVATE)
                    fun isSilentNow(): Boolean =
                        reqPrefs.getBoolean("silent_${req.id}", false)
                    var silentMode by remember(req.id) { mutableStateOf(isSilentNow()) }
                    if (silentMode) {
                        Surface(
                            color = Color(0xFF1F2C34), shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                reqPrefs.edit().putBoolean("silent_${req.id}", false).apply()
                                silentMode = false
                            }
                        ) {
                            Text(
                                "🔇 وضع صامت — لا رسائل للعميل (اضغط لإلغائه)",
                                color = TextSecondary, fontFamily = CairoFont, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                    // ── 1) التسعير أولاً: لا عمل مجاني دون انتباه ──
                    // السعر محسوم فقط بقبول العميل (أو مجاني معتمد) — العرض وحده لا يفتح التوليد
                    fun isPricedNow(): Boolean {
                        val fresh = AppRequestService.getActiveBuildRequest(context)
                        return reqPrefs.getBoolean("priced_${req.id}", false) || fresh?.priceStatus == "accepted"
                    }
                    var priced by remember(req.id) { mutableStateOf(isPricedNow()) }
                    var showPriceDialog by remember { mutableStateOf(false) }
                    var priceDraft by remember(req.id) { mutableStateOf(if (req.cost > 0) req.cost.toString() else "") }
                    if (!priced) {
                        var checkTick by remember { mutableStateOf(0) }
                        var liveStatus by remember(req.id) { mutableStateOf(req.priceStatus) }
                        // انتظار قبول العميل: فحص كل 3 ثوانٍ حتى يقبل (لا توليد قبلها)
                        LaunchedEffect(checkTick) {
                            while (!isPricedNow()) {
                                kotlinx.coroutines.delay(3000)
                                liveStatus = AppRequestService.getActiveBuildRequest(context)?.priceStatus ?: liveStatus
                            }
                            priced = true
                        }
                        Button(
                            onClick = { showPriceDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (liveStatus == "rejected") "🔁 العميل رفض — اعرض سعراً جديداً"
                                else "💰 تحديد سعر الطلب أولاً",
                                color = Color.Black, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                        }
                        Text(
                            "التوليد مقفل حتى يقبل العميل العرض من تفاصيل طلبه.",
                            color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp
                        )
                    }
                    if (showPriceDialog) {
                        AlertDialog(
                            onDismissRequest = { showPriceDialog = false },
                            title = { Text("سعر: ${req.title}", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp) },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = priceDraft,
                                        onValueChange = { priceDraft = it.filter { c -> c.isDigit() } },
                                        label = { Text("السعر بالدولار (0 = مجاني)", fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val amount = priceDraft.toIntOrNull() ?: 0
                                        val free = amount <= 0
                                        AppRequestService.updateRequestProgressAndPayment(
                                            context, req.id, cost = amount,
                                            priceStatus = if (free) "accepted" else "offered"
                                        )
                                        if (free) {
                                            reqPrefs.edit().putBoolean("priced_${req.id}", true).apply()
                                        }
                                        AuditLogger.log(context, "request_priced", "${req.title}: $${amount} (${if (free) "مقبول-مجاني" else "معروض"})")
                                        priced = free || reqPrefs.getBoolean("priced_${req.id}", false)
                                        showPriceDialog = false
                                        AppRequestService.sendMessage(
                                            context,
                                            AppRequestService.ChatMessage(
                                                requestId = req.id,
                                                senderEmail = "dev",
                                                isDeveloper = true,
                                                message = if (free) "تم قبول طلبك ✅ (مجاناً). بدأنا العمل على تطبيقك."
                                                else "عرض سعر 📋: تكلفة تطبيقك $${amount}. افتح تفاصيل الطلب للقبول أو الرفض — لن نبدأ قبل موافقتك."
                                            )
                                        )
                                        activeReq.value = AppRequestService.getActiveBuildRequest(context)
                                        Toast.makeText(
                                            context,
                                            if (free) "اعتُمد مجانياً ✅" else "أُرسل العرض للعميل 📋",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                                ) { Text("اعتماد", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPriceDialog = false }) {
                                    Text("إلغاء", color = GoldPrimary, fontFamily = CairoFont)
                                }
                            },
                            containerColor = CardSurface
                        )
                    }
                    // ── 2) التوليد على فرع + سحب تلقائي ──
                    var prNumber by remember(req.id) { mutableStateOf(reqPrefs.getInt("pr_${req.id}", -1)) }
                    Button(
                        onClick = {
                            val orKey = KeyVault.openrouter
                            if (orKey.isBlank()) {
                                Toast.makeText(context, "أدخل مفتاح OpenRouter أولاً (شاشة المحادثة أو المفاتيح)", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            if (token.isBlank()) {
                                Toast.makeText(context, "أدخل رمز PAT للحفظ في المستودع", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            scaffolding = true
                            scaffoldMsg = null
                            scope.launch {
                                // طلب طازج — نسخة البانر قد تسبق توليد الخطة فتفوّتها
                                val live = AppRequestService.getActiveBuildRequest(context) ?: req
                                val plan = live.generatedPrompts?.takeIf { it.isNotBlank() }
                                    ?: LocalPromptPlanner.generatePlan(live)
                                val files = OpenRouterService.generateStarterFiles(live, plan, orKey)
                                var created = 0
                                var pr: Int? = null
                                if (files.isNotEmpty()) {
                                    val rc = GitHubRepoClient(owner.trim(), repo.trim(), token.trim())
                                    val baseBranch = rc.getDefaultBranch()
                                    val branch = "dev-" + GitHubRepoClient.sanitizeRepoName(live.title).take(30)
                                    // إعادة التوليد تتسامح مع وجود الفرع مسبقاً
                                    val base = rc.getBranchSha(baseBranch)
                                    val branchReady = base != null &&
                                        (rc.createBranch(branch, base) || rc.getBranchSha(branch) != null)
                                    if (branchReady) {
                                        for ((path, code) in files) {
                                            if (path == ".github/workflows/android-ci.yml") continue // القالب المجرب يحل محله
                                            if (rc.createFile(path, branch, "ملفات البداية: $path", code)) created++
                                        }
                                        // حقن القوالب المجرّبة (CI + Dependabot + Release Drafter) — مضمونة بدل المولدة
                                        var templated = 0
                                        for ((path, code) in ClientRepoTemplates.all()) {
                                            if (rc.createFile(path, branch, "قوالب GitHub: $path", code)) templated++
                                        }
                                        created += templated
                                        if (created >= 5) {
                                            pr = rc.createPullRequest(
                                                branch,
                                                "هيكل البداية: ${live.title}",
                                                "توليد تلقائي بـ OpenRouter للطلب من ${live.userEmail}. راجع الفرق ثم ادمج.",
                                                baseBranch
                                            )
                                            // حماية الفرع الأساسي (أفضل جهد — تحتاج صلاحية إدارة)
                                            val protected = rc.protectBranch(baseBranch)
                                            AuditLogger.log(
                                                context, "branch_protected",
                                                "$baseBranch في ${repo.trim()}: ${if (protected) "مفعّلة" else "تعذّرت (تحتاج صلاحية إدارة)"}"
                                            )
                                        }
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    scaffolding = false
                                    if (created > 0) {
                                        if (pr != null && pr!! > 0) {
                                            reqPrefs.edit().putInt("pr_${live.id}", pr!!).apply()
                                            prNumber = pr!!
                                        }
                                        AppRequestService.updateRequestProgressAndPayment(context, live.id, progress = 30)
                                        if (!reqPrefs.getBoolean("silent_${live.id}", false)) {
                                            AppRequestService.sendMessage(
                                                context,
                                                AppRequestService.ChatMessage(
                                                    requestId = live.id, senderEmail = "dev", isDeveloper = true,
                                                    message = "كتبنا هيكل تطبيقك الأولي ✅ ($created ملفات) وهو الآن قيد المراجعة قبل الدمج."
                                                )
                                            )
                                        }
                                        AuditLogger.log(context, "scaffold_created", "$created ملفات + سحب #${pr ?: "?"} للطلب: ${live.title}")
                                        scaffoldMsg = if (pr != null && pr!! > 0)
                                            "وُلّدت $created ملفات على فرع + سحب #$pr ✅ — راجع ثم ادمج"
                                        else
                                            "وُلّدت $created ملفات ✅ لكنها ناقصة (أقل من 5) — لم يُفتح سحب. أعد التوليد أو أكمل يدوياً من المحرر"
                                    } else {
                                        scaffoldMsg = "تعذر التوليد — تحقق من مفتاح OpenRouter والرمز (صلاحية repo) والإنترنت"
                                    }
                                    Toast.makeText(context, scaffoldMsg, Toast.LENGTH_LONG).show()
                                    refreshAll()
                                }
                            }
                        },
                        enabled = !scaffolding && !busy && priced,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (scaffolding) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("OpenRouter يكتب الملفات…", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        } else {
                            Text("🚀 توليد هيكل قابل للبناء بـ OpenRouter", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    // ── 2.5) وكيل الكود: يكمل البناء بأدوات GitHub الحقيقية ──
                    var agentRunning by remember { mutableStateOf(false) }
                    var agentReport by remember { mutableStateOf<String?>(null) }
                    Button(
                        onClick = {
                            val orKey = KeyVault.openrouter
                            if (orKey.isBlank() || token.isBlank()) {
                                Toast.makeText(context, "يلزم مفتاح OpenRouter ورمز PAT", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            agentRunning = true
                            agentReport = null
                            scope.launch {
                                val live = AppRequestService.getActiveBuildRequest(context) ?: req
                                val rc = GitHubRepoClient(owner.trim(), repo.trim(), token.trim())
                                val baseBranch = rc.getDefaultBranch()
                                val branch = "dev-" + GitHubRepoClient.sanitizeRepoName(live.title).take(30)
                                val base = rc.getBranchSha(baseBranch)
                                if (base != null && (rc.createBranch(branch, base) || rc.getBranchSha(branch) != null)) {
                                    val plan = live.generatedPrompts?.takeIf { it.isNotBlank() }
                                        ?: LocalPromptPlanner.generatePlan(live)
                                    val system = "أنت وكيل بناء تطبيقات أندرويد. تعمل بأدوات GitHub فقط، خطوة بخطوة، وتختم بملخص عربي قصير لما فعلته."
                                    // من غرفة الوكيل: الأدوات المفعّلة + النموذج + حد الجولات
                                    val codeTools = CodeTools.definitions(CodeTools.Ctx(owner.trim(), repo.trim(), token.trim(), branch))
                                        .filter { t -> AgentPrefs.isToolEnabled(context, t.name) }
                                    val task = """
                                        أكمل بناء تطبيق العميل على الفرع $branch.
                                        الطلب: ${live.title} — ${live.description} (الهدف: ${live.goal}).
                                        الخطة: ${plan.take(2500)}
                                        اعمل بأدواتك: افحص الشجرة أولاً، اقرأ الملفات الناقصة، اكتب الجديد بـ write_file
                                        وعدّل الموجود بـ update_file (لا تعيد إنشاءه)، وثّق العيوب بـ create_issue،
                                        ثم شغّل البناء وتحقق من حالته، افتح سحباً، راجع ملفاته بـ pr_files، وادمجه بـ merge_pr. لا تسأل — نفّذ.
                                    """.trimIndent()
                                    try {
                                        val (summary, used) = OpenRouterService.chatWithTools(
                                            orKey, system, listOf(true to task), codeTools,
                                            AgentPrefs.model(context), AgentPrefs.maxTurns(context)
                                        )
                                        AgentRunLog.record(context, live.title, used, summary, used.isNotEmpty() || summary.isNotBlank())
                                        withContext(Dispatchers.Main) {
                                            agentRunning = false
                                            agentReport = if (used.isEmpty() && summary.isBlank()) {
                                                "تعذر تشغيل الوكيل — تحقق من المفتاح والرمز."
                                            } else {
                                                "🤖 أدوات مستخدمة: ${used.joinToString("، ")}\n$summary"
                                            }
                                            AuditLogger.log(context, "code_agent", "وكيل ${live.title}: ${used.joinToString(",")}")
                                            Toast.makeText(context, "انتهى الوكيل — راجع التقرير", Toast.LENGTH_LONG).show()
                                            refreshAll()
                                        }
                                    } catch (e: Exception) {
                                        val err = "${e.javaClass.simpleName}: ${e.message}"
                                        AgentRunLog.record(context, live.title, emptyList(), "", false, err)
                                        withContext(Dispatchers.Main) {
                                            agentRunning = false
                                            agentReport = "❌ تعطل الوكيل: $err\nسُجّل في «غرفة الوكيل ← السجل» وسجل الانهيارات."
                                            Toast.makeText(context, "تعطل الوكيل — راجع السجل", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        agentRunning = false
                                        agentReport = "تعذر تجهيز فرع العمل."
                                    }
                                }
                            }
                        },
                        enabled = !agentRunning && !busy && priced,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (agentRunning) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("الوكيل يعمل… (يفحص/يكتب/يبني)", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        } else {
                            Text("🤖 وكيل الكود يكمل البناء", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    agentReport?.let {
                        Text(it, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                    }
                    // ── 3) الدمج بعد المراجعة ──                    if (prNumber > 0) {
                        var merging by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                merging = true
                                scope.launch {
                                    val rc = GitHubRepoClient(owner.trim(), repo.trim(), token.trim())
                                    val ok = rc.mergePullRequest(prNumber)
                                    withContext(Dispatchers.Main) {
                                        merging = false
                                        if (ok) {
                                            AppRequestService.updateRequestProgressAndPayment(context, req.id, progress = 60)
                                            if (!reqPrefs.getBoolean("silent_${req.id}", false)) {
                                                AppRequestService.sendMessage(
                                                    context,
                                                    AppRequestService.ChatMessage(
                                                        requestId = req.id, senderEmail = "dev", isDeveloper = true,
                                                        message = "اعتُمد الهيكل ودُمج ✅ — ننتقل الآن لبناء نسختك الأولى."
                                                    )
                                                )
                                            }
                                            AuditLogger.log(context, "pr_merged", "دمج سحب #$prNumber للطلب: ${req.title}")
                                            Toast.makeText(context, "دُمج في main ✅", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "تعذر الدمج — راجعه من GitHub مباشرة", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            enabled = !merging && !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (merging) "جاري الدمج…" else "🔀 دمج السحب #$prNumber في main",
                                color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                        }
                    }
                    // ── 4) التسليم: آخر إصدار ← محادثة العميل + إغلاق ──
                    var delivering by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            delivering = true
                            scope.launch {
                                val body = apiGet("/repos/${owner.trim()}/${repo.trim()}/releases/latest", auth = token.isNotBlank())
                                withContext(Dispatchers.Main) {
                                    delivering = false
                                    val parsed = body?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                                    val tag = parsed?.optString("tag_name").orEmpty()
                                    val name = parsed?.optString("name").orEmpty()
                                    if (tag.isNotBlank()) {
                                        AppRequestService.sendMessage(
                                            context,
                                            AppRequestService.ChatMessage(
                                                requestId = req.id, senderEmail = "dev", isDeveloper = true,
                                                message = "تطبيقك جاهز للاختبار 🎉 الإصدار: ${name.ifBlank { tag }} — حمّله من صفحة الإصدارات وأخبرنا بملاحظاتك."
                                            )
                                        )
                                        AppRequestService.updateRequestProgressAndPayment(
                                            context, req.id, status = "completed", progress = 100
                                        )
                                        context.getSharedPreferences("qabas_requests_prefs", Context.MODE_PRIVATE).edit().putBoolean("silent_${req.id}", false).apply()
                                        // silentMode banner refreshes on next recomposition
                                        AppRequestService.clearActiveBuildRequest(context)
                                        activeReq.value = null
                                        AuditLogger.log(context, "request_delivered", "تسليم $tag للطلب: ${req.title}")
                                        Toast.makeText(context, "سُلّم $tag للعميل وأُغلق الطلب ✅", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "لا إصدار بعد — ابنِ أولاً ثم سلّم", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !delivering && !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (delivering) "جاري الجلب…" else "📤 تسليم آخر إصدار للعميل وإغلاق الطلب",
                            color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                    }
                    scaffoldMsg?.let {
                        Text(it, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                    }
                }
            }
        }

        // ── التبويبات ──
        val tabTitles = listOf("البناء", "الإصدارات", "الفروع", "السجل", "المحرر")
        val tabCounts = listOf(runs.size, releases.size, branches.size, commits.size, -1)
        TabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            contentColor = GoldPrimary
        ) {
            tabTitles.forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Text(
                            if (tabCounts[i] > 0) "$title (${tabCounts[i]})" else title,
                            fontFamily = CairoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        }

        when (tab) {
            0 -> {
                if (runs.isEmpty()) {
                    Text("لا تشغيلات بعد — اضغط «تحديث» أو «تشغيل البناء».", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                }
                runs.forEach { run ->
                    val tint = when {
                        run.conclusion == "success" -> Color(0xFF10B981)
                        run.conclusion == "failure" -> Color(0xFFEF4444)
                        run.status == "in_progress" || run.status == "queued" -> Color(0xFFF59E0B)
                        else -> TextSecondary
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(run.name, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                                    Text("${run.branch} • ${run.created}", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                                }
                                Text(
                                    text = when {
                                        run.conclusion == "success" -> "ناجح"
                                        run.conclusion == "failure" -> "فاشل"
                                        run.status == "in_progress" -> "يعمل..."
                                        run.status == "queued" -> "بالانتظار"
                                        else -> run.status
                                    },
                                    color = tint, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp
                                )
                            }
                            if (run.status == "in_progress" || run.status == "queued" || run.conclusion == "failure") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (run.conclusion == "failure") {
                                        OutlinedButton(onClick = { rerunRun(run.id) }, enabled = !busy) {
                                            Text("إعادة التشغيل", fontFamily = CairoFont, fontSize = 11.sp)
                                        }
                                    }
                                    if (run.status == "in_progress" || run.status == "queued") {
                                        OutlinedButton(onClick = { cancelRun(run.id) }, enabled = !busy) {
                                            Text("إلغاء", fontFamily = CairoFont, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (releases.isEmpty()) {
                    Text("لا إصدارات بعد — اضغط «تحديث».", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                }
                releases.forEachIndexed { index, rel ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (index == 0) GoldPrimary.copy(alpha = 0.55f) else Color(0xFF38BDF8).copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(rel.name, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    if (index == 0 && !rel.isDraft) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        BuildPill(text = "الأحدث", tint = GoldPrimary)
                                    }
                                }
                                if (rel.isDraft) BuildPill(text = "مسودة", tint = Color(0xFFF59E0B))
                                else if (rel.isPrerelease) BuildPill(text = "تجريبي", tint = Color(0xFF38BDF8))
                            }
                            Text(
                                "${rel.tag} • ${rel.published}" + if (rel.apkSize > 0) " • ${UpdateManager.formatSize(rel.apkSize)}" else "",
                                color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp
                            )
                            if (rel.notes.isNotBlank()) {
                                Text(rel.notes.take(200), color = Color(0xFFCBD5E1), fontFamily = NotoSansFont, fontSize = 11.sp, maxLines = 3)
                            }
                            if (rel.apkUrl != null) {
                                if (installingTag == rel.tag) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        LinearProgressIndicator(
                                            progress = { (installingProgress / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(6.dp),
                                            color = Color(0xFF10B981), trackColor = Color(0xFF1E293B)
                                        )
                                        Text("جاري التنزيل... $installingProgress٪", color = Color(0xFF10B981), fontFamily = NotoSansFont, fontSize = 11.sp)
                                    }
                                } else {
                                    Button(
                                        onClick = { installRelease(rel) },
                                        enabled = installingTag == null,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "تنزيل وتثبيت ${rel.tag}",
                                            color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (branches.isEmpty()) {
                    Text("لا فروع بعد — اضغط «تحديث».", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                }
                branches.forEach { branch ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardSurface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AccountTree, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(branch.name, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(branch.sha, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                                }
                            }
                            if (branch.protected) Text("محمي", color = Color(0xFFF59E0B), fontFamily = NotoSansFont, fontSize = 11.sp)
                        }
                    }
                }
            }
            3 -> {
                if (commits.isEmpty()) {
                    Text("لا سجل بعد — اضغط «تحديث».", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                }
                commits.forEachIndexed { index, commit ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // الخط الزمني
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(if (index == 0) GoldPrimary else Color(0xFF38BDF8), androidx.compose.foundation.shape.CircleShape)
                            )
                            if (index < commits.size - 1) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(28.dp)
                                        .background(Color(0xFF1E293B))
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardSurface),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(commit.message, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "${commit.sha} • ${commit.author} • ${commit.date}",
                                    color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
            4 -> {
                // المحرر يحمل LazyColumn وweight داخلياً — يحتاج ارتفاعاً محدوداً
                // داخل العمود الممرر وإلا تنهار قياساته وتتداخل البطاقات
                Box(modifier = Modifier.fillMaxWidth().height(560.dp)) {
                    AiEditorSection(
                        context = context,
                        owner = owner.trim(),
                        repo = repo.trim(),
                        token = token.trim(),
                        onFileCommitted = { refreshAll() },
                        onRequestBuild = { tab = 0; triggerBuild() },
                        onNavigateTo = onNavigateTo
                    )
                }
            }
        }
    }

@Composable
private fun BuildPill(text: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.4f))
    ) {
        Text(
            text = text, color = tint, fontFamily = CairoFont,
            fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun BuildStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp)
    }
}
private fun parseTagCode(tag: String): Int {
    // يطابق UpdateManager: رقم البناء بعد + أولاً، ثم v1.2.3 الكلاسيكي
    val buildPart = tag.substringAfter("+", "")
    if (buildPart.isNotEmpty()) {
        return buildPart.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
    val parts = tag.removePrefix("v").trim().split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 10000 + minor * 100 + patch
}

private data class RepoInfo(
    val fullName: String,
    val description: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val stars: Int,
    val openIssues: Int
)

private data class BuildRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String,
    val branch: String,
    val created: String
)

private data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val published: String,
    val apkUrl: String?,
    val apkAssetId: Long,
    val apkSize: Long,
    val isDraft: Boolean,
    val isPrerelease: Boolean
)

private data class BranchInfo(
    val name: String,
    val protected: Boolean,
    val sha: String
)

private data class CommitInfo(
    val sha: String,
    val message: String,
    val author: String,
    val date: String
)
