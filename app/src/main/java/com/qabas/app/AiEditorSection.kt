package com.qabas.app

import android.content.Context
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * محرر المحادثة — تأمر النموذج بالعربية فيعدّل ملفاً من المستودع:
 * تختار الملف ← يُحمَّل محتواه ← تأمر («اجعل الزر أخضر») ←
 * النموذج يقترح النسخة الجديدة ← تراجعها ← حفظ كـ commit ← بناء ← تثبيت.
 * يستخدم سلسلة AppServices.chatWithAssistant (OpenAI ← Groq ← Gemini).
 */
@Composable
fun AiEditorSection(
    context: Context,
    owner: String,
    repo: String,
    token: String,
    onFileCommitted: () -> Unit,
    onRequestBuild: () -> Unit,
    onNavigateTo: (AppState) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    var filePath by remember { mutableStateOf("README.md") }
    var fileSha by remember { mutableStateOf<String?>(null) }
    var fileLoaded by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<ChatMsg>()) }
    var input by remember { mutableStateOf("") }
    var lastOrder by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var opMode by remember { mutableStateOf(true) }
    var proposal by remember { mutableStateOf<String?>(null) }
    var commitMsg by remember { mutableStateOf("") }
    var loadedFileContent by remember { mutableStateOf("") }
    var answerSource by remember { mutableStateOf<String?>(null) }
    var lastUndo by remember { mutableStateOf<UndoState?>(null) }
    var showDiff by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<AiFailure?>(null) }
    var chatError by remember { mutableStateOf<AiFailure?>(null) }
    var saveError by remember { mutableStateOf<AiFailure?>(null) }
    var lastHttpCode by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()

    // ── مستودع GitHub ──
    var repoSubTab by remember { mutableStateOf(0) } // 0=files, 1=commits, 2=branches
    var repoTree by remember { mutableStateOf<List<RepoFile>>(emptyList()) }
    var repoTreeLoading by remember { mutableStateOf(false) }
    var repoTreeError by remember { mutableStateOf<AiFailure?>(null) }
    var repoPath by remember { mutableStateOf("") }
    var repoCommits by remember { mutableStateOf<List<RepoCommitInfo>>(emptyList()) }
    var commitsLoading by remember { mutableStateOf(false) }
    var repoBranches by remember { mutableStateOf<List<RepoBranchInfo>>(emptyList()) }
    var branchesLoading by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var branchCreating by remember { mutableStateOf(false) }
    var selectedRepoFile by remember { mutableStateOf<RepoFile?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var fileContentLoading by remember { mutableStateOf(false) }

    val repoClient = remember {
        GitHubRepoClient(owner = owner, repo = repo, token = if (token.isNotBlank()) token else "")
    }

    // الفرع العامل الحقيقي (main أو master) — يُكتشف من GitHub ولا يُفترض أبداً
    var workBranch by remember(owner, repo) { mutableStateOf("main") }
    LaunchedEffect(owner, repo) {
        workBranch = withContext(Dispatchers.IO) {
            runCatching { repoClient.getDefaultBranch() }.getOrDefault("main")
        }
    }

    suspend fun loadRepoTree(path: String = "") {
        repoTreeLoading = true
        repoTreeError = null
        scope.launch {
            val tree = repoClient.getTree(branch = workBranch, path = path)
            withContext(Dispatchers.Main) {
                repoTree = tree ?: emptyList()
                repoTreeLoading = false
                repoPath = path
                repoTreeError = if (tree == null) AiFailure("تعذر تحميل المستودع", "تحقق من الرمز والإنترنت ثم أعد المحاولة.", retry = "tree") else null
            }
        }
    }

    suspend fun loadCommits() {
        commitsLoading = true
        scope.launch {
            val commits = repoClient.getCommits(workBranch)
            withContext(Dispatchers.Main) {
                repoCommits = commits ?: emptyList()
                commitsLoading = false
            }
        }
    }

    suspend fun loadBranches() {
        branchesLoading = true
        scope.launch {
            val branches = repoClient.getBranches()
            withContext(Dispatchers.Main) {
                repoBranches = branches ?: emptyList()
                branchesLoading = false
            }
        }
    }

    suspend fun loadFileContent(file: RepoFile) {
        fileContentLoading = true
        scope.launch {
            val content = repoClient.getFileContent(file.path, workBranch)
            withContext(Dispatchers.Main) {
                fileContent = content?.let {
                    if (content.encoding == "base64") {
                        String(Base64.decode(content.content.replace("\\s".toRegex(), ""), Base64.DEFAULT), Charsets.UTF_8)
                    } else content.content
                } ?: ""
                fileContentLoading = false
                selectedRepoFile = file
            }
        }
    }

    suspend fun createBranch() {
        if (newBranchName.isBlank()) return
        branchCreating = true
        scope.launch {
            val sha = repoClient.getBranchSha(workBranch)
            val ok = sha?.let { repoClient.createBranch(newBranchName.trim(), it) } ?: false
            withContext(Dispatchers.Main) {
                branchCreating = false
                if (ok) {
                    loadBranches()
                    newBranchName = ""
                }
            }
        }
    }

    suspend fun refreshRepoData() {
        loadRepoTree()
        loadCommits()
        loadBranches()
    }

    suspend fun apiGet(path: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url("https://api.github.com$path")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Qabas-Studio")
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            val response = client.newCall(builder.build()).execute()
            withContext(Dispatchers.Main) { lastHttpCode = response.code }
            if (!response.isSuccessful) return@withContext null
            JSONObject(response.body?.string() ?: return@withContext null)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { lastHttpCode = null }
            null
        }
    }

    fun describeHttpFailure(code: Int?, action: String): AiFailure {
        return when (code) {
            null -> AiFailure("انقطع الاتصال", "تحقق من الإنترنت ثم أعد المحاولة.", retry = action)
            401 -> AiFailure("الرمز مرفوض (401)", "رمز PAT غير صالح أو منتهي. حدّثه من إعدادات الغرفة.", retry = action)
            403 -> AiFailure("ممنوع (403)", "الرمز تنقصه صلاحية، أو تجاوزت حد GitHub المجاني — انتظر دقائق.", retry = action)
            404 -> AiFailure("غير موجود (404)", "المسار أو المستودع خاطئ، أو الرمز لا يرى مستودعاً خاصاً.", retry = action)
            409 -> AiFailure("تعارض (409)", "الملف تغيّر على GitHub أثناء التحرير — أعد تحميله.", retry = action)
            422 -> AiFailure("طلب مرفوض (422)", "بيانات الـ commit ناقصة (SHA قديم غالباً) — أعد تحميل الملف.", retry = action)
            429 -> AiFailure("كثرة الطلبات (429)", "النموذج المجاني مزدحم — انتظر دقيقة أو استخدم مفتاحك.", retry = action)
            else -> AiFailure("خطأ $code", "حدث خطأ غير متوقع من الخادم.", retry = action)
        }
    }

    fun loadFile() {
        if (filePath.isBlank()) {
            Toast.makeText(context, "أدخل مسار الملف أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        fileError = null
        scope.launch {
            val enc = URLEncoder.encode(filePath.trim(), "UTF-8").replace("+", "%20")
            val json = apiGet("/repos/$owner/$repo/contents/$enc?ref=$workBranch")
            if (json == null || json.optString("type", "") != "file") {
                withContext(Dispatchers.Main) {
                    fileError = if (json == null) describeHttpFailure(lastHttpCode, "load")
                    else AiFailure("ليس ملفاً", "المسار يشير لمجلد — أدخل مسار ملف كامل.", retry = "load")
                    busy = false
                }
                return@launch
            }
            val raw = json.optString("content", "").replace("\\s".toRegex(), "")
            val text = runCatching { String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: ""
            withContext(Dispatchers.Main) {
                fileSha = json.optString("sha", "")
                fileLoaded = text.isNotBlank()
                messages = messages + ChatMsg(
                    fromUser = false,
                    text = if (fileLoaded) "حمّلت «$filePath» (${text.lines().size} سطر). اامرني بما تريد تعديله." else "الملف فارغ أو تعذر فك ترميزه."
                )
                busy = false
            }
            loadedFileContent = text
        }
    }

    suspend fun openAiChat(url: String, bearer: String, model: String, prompt: String, system: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("model", model)
                .put("messages", org.json.JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", prompt))
                })
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $bearer")
                .header("User-Agent", "Qabas-Studio")
                .post(body)
                .build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val text = JSONObject(response.body?.string() ?: return@withContext null)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                return@withContext text?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun pollinationsChat(prompt: String, system: String): String? =
        openAiChat("https://text.pollinations.ai/openai", "unused", "openai", prompt, system)

    suspend fun llm7Chat(prompt: String, system: String): String? =
        openAiChat("https://api.llm7.io/v1/chat/completions", "unused", "fast", prompt, system)

    fun navigateFor(order: String): AppState? {
        val lower = order.lowercase()
        return when {
            "الإعدادات" in lower || "settings" in lower -> AppState.SETTINGS
            "الملف الشخصي" in lower || "profile" in lower -> AppState.PROFILE
            "المشاريع" in lower || "المشروعات" in lower -> AppState.PROJECTS
            "القرآن" in lower || "quran" in lower -> AppState.QURAN_HUB
            "الرئيسية" in lower || "البيت" in lower || "home" in lower -> AppState.HOME
            "الإشعارات" in lower || "notifications" in lower -> AppState.NOTIFICATIONS
            "رجوع" in lower || "خلف" in lower -> AppState.HOME
            else -> null
        }
    }

    fun operatorSend(order: String) {
        val finalOrder = order.trim()
        if (finalOrder.isBlank() || busy) return
        val navTarget = navigateFor(finalOrder)
        if (navTarget != null) {
            messages = messages + ChatMsg(fromUser = true, text = finalOrder)
            onNavigateTo(navTarget)
            return
        }
        input = ""
        lastOrder = finalOrder
        proposal = null
        answerSource = null
        fileError = null
        chatError = null
        messages = messages + ChatMsg(fromUser = true, text = finalOrder)
        messages = messages + ChatMsg(fromUser = false, text = "…", thinking = true)
        busy = true
        scope.launch {
            val intent = AiOperatorRouter.classify(finalOrder)
            val result = try {
                AiOperatorRouter.execute(context, intent) { stage ->
                    withContext(Dispatchers.Main) {
                        messages = messages.dropLast(1) + ChatMsg(fromUser = false, text = stage, thinking = true)
                    }
                }
            } catch (e: Exception) {
                "تعذر التنفيذ: ${e.message ?: "خطأ غير معروف"}"
            }
            withContext(Dispatchers.Main) {
                messages = messages.dropLast(1) + ChatMsg(fromUser = false, text = result.take(2400))
                busy = false
            }
        }
    }

    fun send(order: String = input.trim()) {
        val finalOrder = order.trim()
        if (finalOrder.isBlank() || busy) return
        if (opMode) { operatorSend(finalOrder); return }
        if (!fileLoaded) {
            Toast.makeText(context, "حمّل الملف أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        input = ""
        lastOrder = finalOrder
        proposal = null
        chatError = null
        messages = messages + ChatMsg(fromUser = true, text = finalOrder)
        messages = messages + ChatMsg(fromUser = false, text = "…", thinking = true)
        busy = true
        scope.launch {
            // سياق طلب البناء النشط (إن وُجد) — يُحقن في البرومبت
            val activeReq = AppRequestService.getActiveBuildRequest(context)
            val prompt = buildString {
                append("أنت محرر كود خبير في مشروع أندرويد (Kotlin + Jetpack Compose).\n")
                if (activeReq != null) {
                    append("سياق مهمة البناء الحالية — طلب العميل: «${activeReq.title}»: ${activeReq.description} (الهدف: ${activeReq.goal}).\n")
                    if (!activeReq.generatedPrompts.isNullOrBlank()) {
                        append("خطة التنفيذ المعتمدة:\n${activeReq.generatedPrompts.take(2000)}\n")
                    }
                }
                append("الملف: $filePath\n")
                append("محتواه الحالي كاملاً:\n```\n$loadedFileContent\n```\n")
                append("طلب المستخدم: $finalOrder\n\n")
                append("القواعد: أعد كتابة الملف كاملاً بعد التعديل داخل كتلة كود واحدة فقط (```)، بلا شرح خارجها إلا سطر واحد يصف ما فعلته قبل الكتلة. لا تحذف أي جزء لا يخص الطلب. حافظ على البنية والاستيرادات.")
            }
            val system = "أنت محرر كود محترف. تجيب بسطر وصف واحد ثم الملف الكامل داخل كتلة كود واحدة."
            // 1) OpenRouter بمفتاحك (نماذج مجانية قوية) • 2) مجاني بلا مفتاح • 3) مفاتيحك الأخرى
            var source: String? = null
            val orKey = KeyVault.openrouter
            var answer: String? = if (orKey.isNotBlank()) {
                OpenRouterService.chat(orKey, system, prompt)?.also { source = "OpenRouter 🆓" }
            } else null
            if (answer == null) {
                answer = pollinationsChat(prompt, system)?.also { source = "مجاني" }
            }
            if (answer == null) {
                answer = AppServices.chatWithAssistant(listOf(Pair(true, prompt)), system).also { source = "مفتاحك" }
            }
            val code = answer?.let { extractCodeFence(it) }
            val isServiceError = answer.isNullOrBlank() ||
                answer.startsWith("عذراً") || answer.startsWith("حدث خطأ")
            withContext(Dispatchers.Main) {
                messages = messages.dropLast(1) + ChatMsg(
                    fromUser = false,
                    text = (answer ?: "تعذر الحصول على رد.").take(600) + if (code != null) "\n\n[اقتراح جاهز للمراجعة بالأسفل]" else ""
                )
                answerSource = source
                proposal = code
                showDiff = false
                chatError = when {
                    isServiceError -> AiFailure(
                        "النموذج لم يرد",
                        "المجانيّان مزدحمان ولا مفتاح احتياطي. أدخل مفتاح Gemini/Groq من شاشة المفاتيح أو انتظر.",
                        retry = "send"
                    )
                    code == null -> AiFailure(
                        "رد بلا كود",
                        "النموذج أجاب نصاً دون كتلة كود — أعد الصياغة (مثال: «أعد كتابة الملف كاملاً»).",
                        retry = "send"
                    )
                    else -> null
                }
                busy = false
            }
        }
    }

    fun commitProposal() {
        val code = proposal ?: return
        if (fileSha.isNullOrBlank()) {
            Toast.makeText(context, "SHA الملف مفقود — أعد تحميله", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        saveError = null
        scope.launch {
            var saveCode: Int? = null
            var newSha: String? = null
            val previousContent = loadedFileContent
            val previousSha = fileSha
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val body = JSONObject()
                        .put("message", commitMsg.ifBlank { "تعديل $filePath عبر محرر المحادثة" })
                        .put("content", Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                        .put("sha", fileSha)
                        .put("branch", workBranch)
                        .toString()
                        .toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("https://api.github.com/repos/$owner/$repo/contents/${URLEncoder.encode(filePath.trim(), "UTF-8").replace("+", "%20")}")
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "Qabas-Studio")
                        .header("Authorization", "Bearer $token")
                        .put(body)
                        .build()
                    client.newCall(req).execute().use {
                        saveCode = it.code
                        newSha = runCatching {
                            JSONObject(it.body?.string().orEmpty())
                                .optJSONObject("content")?.optString("sha")
                        }.getOrNull()?.takeIf { s -> s.isNotBlank() }
                        it.code == 200 || it.code == 201
                    }
                }.getOrDefault(false)
            }
            withContext(Dispatchers.Main) {
                busy = false
                if (ok) {
                    messages = messages + ChatMsg(fromUser = false, text = "تم حفظ التعديل كـ commit على $workBranch. يمكنك الآن بناء النسخة الجديدة.")
                    // للتراجع: نحفظ الحالة السابقة + نحدّث الحالية لمواصلة التحرير بلا إعادة تحميل
                    lastUndo = UndoState(previousContent, previousSha, commitMsg.ifBlank { "تعديل $filePath عبر محرر المحادثة" })
                    loadedFileContent = code
                    if (!newSha.isNullOrBlank()) fileSha = newSha
                    proposal = null
                    commitMsg = ""
                    AuditLogger.log(context, "ai_edit_commit", "حفظ تعديل $filePath عبر محرر المحادثة")
                    onFileCommitted()
                } else {
                    saveError = describeHttpFailure(saveCode, "save")
                }
            }
        }
    }

    /** تراجع عن آخر حفظ: يعيد المحتوى السابق كـ commit جديد. */
    fun undoLastCommit() {
        val undo = lastUndo ?: return
        if (undo.sha.isNullOrBlank()) return
        busy = true
        scope.launch {
            var saveCode: Int? = null
            var newSha: String? = null
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val body = JSONObject()
                        .put("message", "تراجع عن: ${undo.message}")
                        .put("content", Base64.encodeToString(undo.content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                        .put("sha", fileSha)
                        .put("branch", workBranch)
                        .toString()
                        .toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("https://api.github.com/repos/$owner/$repo/contents/${URLEncoder.encode(filePath.trim(), "UTF-8").replace("+", "%20")}")
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "Qabas-Studio")
                        .header("Authorization", "Bearer $token")
                        .put(body)
                        .build()
                    client.newCall(req).execute().use {
                        saveCode = it.code
                        newSha = runCatching {
                            JSONObject(it.body?.string().orEmpty())
                                .optJSONObject("content")?.optString("sha")
                        }.getOrNull()?.takeIf { s -> s.isNotBlank() }
                        it.code == 200 || it.code == 201
                    }
                }.getOrDefault(false)
            }
            withContext(Dispatchers.Main) {
                busy = false
                if (ok) {
                    loadedFileContent = undo.content
                    if (!newSha.isNullOrBlank()) fileSha = newSha
                    lastUndo = null
                    messages = messages + ChatMsg(fromUser = false, text = "تم التراجع عن آخر حفظ ✅")
                    AuditLogger.log(context, "ai_edit_undo", "تراجع عن تعديل $filePath")
                    onFileCommitted()
                } else {
                    saveError = describeHttpFailure(saveCode, "save")
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── ترويسة المحرر ──
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = GoldPrimary.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Default.SmartToy, contentDescription = null, tint = GoldPrimary, modifier = Modifier.padding(8.dp).size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("مساعد التحرير", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            if (opMode) "وضع التشغيل • نفّذ أوامر حقيقية داخل التطبيق"
                            else if (fileLoaded) "$filePath • ${loadedFileContent.lines().size} سطر"
                            else "اختر ملفاً ثم اامرني بالعربية",
                            color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp, maxLines = 1
                        )
                    }
                    if (answerSource != null) {
                        AiPill(text = if (answerSource == "مجاني") "رد مجاني" else "رد مفتاحك", tint = if (answerSource == "مجاني") Color(0xFF10B981) else GoldPrimary)
                    }
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { messages = emptyList(); proposal = null; answerSource = null }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "مسح المحادثة", tint = TextSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OperatorModeTab(
                        text = "تشغيل في التطبيق",
                        selected = opMode,
                        onClick = { opMode = true },
                        modifier = Modifier.weight(1f)
                    )
                    OperatorModeTab(
                        text = "تحرير الشيفرة",
                        selected = !opMode && repoSubTab == 0,
                        onClick = { opMode = false; repoSubTab = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    OperatorModeTab(
                        text = "المستودع",
                        selected = repoSubTab == 1 || repoSubTab == 2,
                        onClick = { repoSubTab = 1; scope.launch { loadRepoTree(); loadCommits(); loadBranches() } },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!opMode && repoSubTab == 0) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = filePath, onValueChange = { filePath = it },
                            label = { Text("مسار الملف", fontSize = 11.sp) }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = ::loadFile, enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تحميل", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        // ── سياق مهمة البناء (من قسم الطلبات) ──
        val linkedReq = remember { AppRequestService.getActiveBuildRequest(context) }
        if (linkedReq != null && !opMode) {
            Surface(
                color = GoldPrimary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Build, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "تبني: ${linkedReq.title} — أوامرك تُغذّى بخطة الطلب تلقائياً",
                        color = GoldPrimary, fontFamily = CairoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (opMode) {
            Text(
                "المحادثة تعمل بلا مفتاح ذكاء اصطناعي (نموذج مجاني، ثم مفاتيحك إن وُجدت). الحفظ يحتاج رمز PAT بصلاحية Contents: Write.",
                color = Color(0xFF10B981), fontFamily = NotoSansFont, fontSize = 11.sp
            )
            if (token.isBlank()) {
                Text("تنبيه: بدون رمز PAT لن يعمل تحميل الملفات الخاصة ولا الحفظ.", color = Color(0xFFF59E0B), fontFamily = NotoSansFont, fontSize = 11.sp)
            }
        }

        // ── المستودع ──
        if (!opMode && repoSubTab != 0) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OperatorModeTab(
                    text = "الملفات",
                    selected = repoSubTab == 0,
                    onClick = { repoSubTab = 0; scope.launch { loadRepoTree() } },
                    modifier = Modifier.weight(1f)
                )
                OperatorModeTab(
                    text = "التاريخ",
                    selected = repoSubTab == 1,
                    onClick = { repoSubTab = 1; scope.launch { loadCommits() } },
                    modifier = Modifier.weight(1f)
                )
                OperatorModeTab(
                    text = "الفروع",
                    selected = repoSubTab == 2,
                    onClick = { repoSubTab = 2; scope.launch { loadBranches() } },
                    modifier = Modifier.weight(1f)
                )
            }
            when (repoSubTab) {
                0 -> {
                    // Files tree
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = repoPath, onValueChange = { repoPath = it; scope.launch { loadRepoTree(repoPath) } },
                            label = { Text("المسار", fontSize = 11.sp) }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { scope.launch { loadRepoTree() } }, enabled = !repoTreeLoading, shape = RoundedCornerShape(10.dp)) {
                            Text("🔄", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    if (repoTreeLoading) {
                        Text("جاري تحميل المستودع...", color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    } else if (repoTreeError != null) {
                        AiErrorCard(failure = repoTreeError!!, onRetry = { scope.launch { loadRepoTree() } }, onDismiss = { repoTreeError = null })
                    } else {
                        LazyColumn(state = rememberLazyListState(), modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(repoTree) { file ->
                                val isDir = file.isDirectory
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isDir) scope.launch { loadRepoTree(file.path) }
                                            else scope.launch { loadFileContent(file) }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (isDir) GoldPrimary else TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.name, color = Color.White, fontFamily = CairoFont, fontSize = 12.sp)
                                        if (file.size > 0 && !isDir) {
                                            Text("${file.size / 1024} KB", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Commits
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { loadCommits() } },
                            enabled = !commitsLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)
                        ) {
                            Text("🔄 حدّث", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    if (commitsLoading) {
                        Text("جاري تحميل التاريخ...", color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    } else if (repoCommits.isEmpty()) {
                        Text("لا توجد سجلات.", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(repoCommits) { commit ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(commit.sha.take(8), color = GoldPrimary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp)
                                        Text(commit.message.take(60), color = Color.White, fontFamily = CairoFont, fontSize = 12.sp, maxLines = 1)
                                        Text("${commit.authorName} · ${commit.date.take(10)}", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // Branches
                    if (branchesLoading) {
                        Text("جاري تحميل الفروع...", color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(repoBranches) { branch ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { scope.launch { loadRepoTree(); loadCommits() } }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(branch.name, color = if (branch.name == workBranch) GoldPrimary else Color.White, fontFamily = CairoFont, fontSize = 12.sp)
                                    }
                                    if (branch.name == workBranch) {
                                        AiPill(text = "الرئيسي", tint = Color(0xFF10B981))
                                    }
                                }
                            }
                        }
                        // Create branch section
                        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newBranchName, onValueChange = { newBranchName = it },
                                label = { Text("اسم الفرع الجديد", fontSize = 11.sp) }, singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { scope.launch { createBranch() } },
                                enabled = newBranchName.isNotBlank() && !branchCreating,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (branchCreating) "..." else "إنشاء", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── المحادثة ──
        fileError?.let { failure ->
            AiErrorCard(
                failure = failure,
                onRetry = {
                    fileError = null
                    when (failure.retry) {
                        "load" -> loadFile()
                    }
                },
                onDismiss = { fileError = null }
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Bottom
                ) {
                    if (!msg.fromUser) {
                        Surface(color = GoldPrimary.copy(alpha = 0.14f), shape = androidx.compose.foundation.shape.CircleShape) {
                            Icon(Icons.Default.SmartToy, contentDescription = null, tint = GoldPrimary, modifier = Modifier.padding(6.dp).size(14.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Surface(
                        color = if (msg.fromUser) GoldPrimary.copy(alpha = 0.18f) else CardSurface,
                        shape = RoundedCornerShape(
                            topStart = 14.dp, topEnd = 14.dp,
                            bottomStart = if (msg.fromUser) 14.dp else 4.dp,
                            bottomEnd = if (msg.fromUser) 4.dp else 14.dp
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (msg.fromUser) GoldPrimary.copy(alpha = 0.35f) else Color(0xFF1E293B)
                        ),
                        modifier = Modifier.fillMaxWidth(0.82f)
                    ) {
                        Text(
                            if (msg.thinking) "يكتب الآن..." else msg.text.take(2400),
                            color = if (msg.thinking) GoldPrimary else Color.White,
                            fontFamily = NotoSansFont, fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    if (msg.fromUser) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(color = Color(0xFF38BDF8).copy(alpha = 0.14f), shape = androidx.compose.foundation.shape.CircleShape) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.padding(6.dp).size(14.dp))
                        }
                    }
                }
            }
        }

        // ── الاقتراح الجاهز ──
        saveError?.let { failure ->
            AiErrorCard(
                failure = failure,
                onRetry = {
                    saveError = null
                    if (failure.retry == "save" && proposal != null) commitProposal()
                },
                onDismiss = { saveError = null }
            )
        }
        chatError?.let { failure ->
            AiErrorCard(
                failure = failure,
                onRetry = {
                    chatError = null
                    if (lastOrder.isNotBlank()) send(lastOrder)
                },
                onDismiss = { chatError = null }
            )
        }
        if (proposal != null && !opMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "اقتراح جاهز • ${proposal!!.lines().size} سطر",
                            color = Color(0xFF10B981), fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        AiPill(text = answerSource ?: "", tint = TextSecondary)
                    }
                    // ── الفرق قبل الحفظ: ماذا تغيّر فعلاً ──
                    val diffData = remember(proposal, loadedFileContent) {
                        computeDiff(loadedFileContent, proposal!!)
                    }
                    val diffLines = diffData.first
                    val addedCount = diffData.second.first
                    val removedCount = diffData.second.second
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OperatorModeTab(
                            text = "📄 كامل",
                            selected = !showDiff,
                            onClick = { showDiff = false },
                            modifier = Modifier.weight(1f)
                        )
                        OperatorModeTab(
                            text = "🔍 الفرق (+$addedCount −$removedCount)",
                            selected = showDiff,
                            onClick = { showDiff = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Surface(
                        color = DeepSlate, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    ) {
                        if (!showDiff) {
                            Text(
                                proposal!!.take(1200) + if (proposal!!.length > 1200) "\n..." else "",
                                color = Color(0xFFCBD5E1), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 10.sp, modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState())
                            )
                        } else {
                            LazyColumn(modifier = Modifier.padding(8.dp)) {
                                items(diffLines.take(250).withIndex().toList()) { (idx, dl) ->
                                    if (dl.kind != DiffKind.SAME || idx < 3) {
                                        Text(
                                            (when (dl.kind) {
                                                DiffKind.ADDED -> "+ "
                                                DiffKind.REMOVED -> "− "
                                                else -> "  "
                                            }) + dl.text.take(120),
                                            color = when (dl.kind) {
                                                DiffKind.ADDED -> Color(0xFF4ADE80)
                                                DiffKind.REMOVED -> Color(0xFFF87171)
                                                else -> Color(0xFF64748B)
                                            },
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            maxLines = 2
                                        )
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = commitMsg, onValueChange = { commitMsg = it },
                        label = { Text("رسالة الـ commit", fontSize = 11.sp) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = ::commitProposal, enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)
                        ) {
                            Text("حفظ كـ commit", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { proposal = null }, shape = RoundedCornerShape(10.dp)) {
                            Text("تجاهل", fontFamily = CairoFont, fontSize = 12.sp)
                        }
                    }
                    Button(
                        onClick = onRequestBuild,
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("بناء النسخة الجديدة", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
        // ── تراجع عن آخر حفظ ──
        if (lastUndo != null && !busy) {
            OutlinedButton(
                onClick = ::undoLastCommit,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
            ) {
                Icon(Icons.Default.Undo, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تراجع عن آخر حفظ", color = Color(0xFFF59E0B), fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        if (opMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("أمثلة تُنفَّذ فعلاً:", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("• «افحص» — تشخيص حقيقي كامل للتطبيق والخدمات", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                    Text("• «سكريبت عن الصبر» — يكتب سكريبتاً حقيقياً", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                    Text("• «معلومات الجهاز» — تقرير فعلي بالذاكرة والتخزين", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                    Text("• أو اسألني أي سؤال حر", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                }
            }
        }

        // ── الإدخال ──
        Surface(
            color = CardSurface, shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text(if (opMode) "اطلب فعلاً: افحص… سكريبت عن… معلومات الجهاز…" else "اامرني: اجعل... أضف... أصلح...", fontSize = 12.sp) },
                    singleLine = false, maxLines = 3,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (input.isNotBlank() && !busy) GoldPrimary else Color(0xFF1E293B),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    modifier = Modifier.size(44.dp)
                ) {
                    IconButton(onClick = ::send, enabled = !busy && input.isNotBlank()) {
                        Icon(Icons.Default.Send, contentDescription = "إرسال", tint = if (input.isNotBlank() && !busy) DeepSlate else TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OperatorModeTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) GoldPrimary.copy(alpha = 0.18f) else Color(0xFF1E293B),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) GoldPrimary else Color(0xFF334155)
        ),
        modifier = modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            color = if (selected) GoldPrimary else TextSecondary,
            fontFamily = CairoFont,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun AiErrorCard(
    failure: AiFailure,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1215)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(failure.title, color = Color(0xFFFCA5A5), fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(failure.hint, color = Color(0xFFD6C2C2), fontFamily = NotoSansFont, fontSize = 11.sp)
            }
            TextButton(onClick = onRetry) {
                Text("إعادة", color = Color(0xFFEF4444), fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private data class AiFailure(
    val title: String,
    val hint: String,
    val retry: String
)

@Composable
private fun AiPill(text: String, tint: Color) {
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

private data class ChatMsg(
    val fromUser: Boolean,
    val text: String,
    val thinking: Boolean = false
)

private data class UndoState(
    val content: String,
    val sha: String?,
    val message: String
)

private enum class DiffKind { SAME, ADDED, REMOVED }

private data class DiffLine(val kind: DiffKind, val text: String)

/**
 * فرق سطري (LCS) بين المحتوى القديم والجديد — يعرض ما تغيّر فعلاً قبل الحفظ.
 * للملفات الضخمة (>1500 سطر) يُرجع ملخصاً بدل المصفوفة الكاملة.
 */
private fun computeDiff(oldText: String, newText: String): Pair<List<DiffLine>, Pair<Int, Int>> {
    val a = oldText.lines()
    val b = newText.lines()
    if (a.size > 1500 || b.size > 1500) {
        val added = b.size - a.size
        return Pair(
            listOf(DiffLine(DiffKind.SAME, "ملف كبير (${a.size} ← ${b.size} سطر) — الفرق التفصيلي معطّل، راجع المعاينة الكاملة.")),
            Pair(maxOf(added, 0), maxOf(-added, 0))
        )
    }
    val n = a.size
    val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val out = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    var added = 0
    var removed = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { out.add(DiffLine(DiffKind.SAME, a[i])); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { out.add(DiffLine(DiffKind.REMOVED, a[i])); removed++; i++ }
            else -> { out.add(DiffLine(DiffKind.ADDED, b[j])); added++; j++ }
        }
    }
    while (i < n) { out.add(DiffLine(DiffKind.REMOVED, a[i])); removed++; i++ }
    while (j < m) { out.add(DiffLine(DiffKind.ADDED, b[j])); added++; j++ }
    return Pair(out, Pair(added, removed))
}

private fun extractCodeFence(answer: String): String? {
    val first = answer.indexOf("```")
    if (first == -1) return null
    val start = answer.indexOf("\n", first)
    if (start == -1) return null
    val end = answer.indexOf("```", start)
    if (end == -1) return null
    return answer.substring(start + 1, end).trim().takeIf { it.isNotBlank() }
}
