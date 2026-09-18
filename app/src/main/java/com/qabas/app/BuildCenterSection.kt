package com.qabas.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
            onProgress = { installingProgress = it },
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
