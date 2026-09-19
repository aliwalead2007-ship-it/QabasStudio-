package com.qabas.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * «غرفة الوكيل 🤖» — تجهيز احترافي قبل التشغيل:
 * 1) شاشة المطلوبات: فحص حي لكل شرط (مفتاح/رمز/طلب/سعر/مستودع) مع زر إصلاح.
 * 2) شاشة الأدوات: مفاتيح تشغيل لكل أداة + نموذج + حد الجولات + وضع المحاكاة.
 *
 * إضافات القوة والدقة:
 * -dry-run: الوكيل يخطط فقط بلا كتابة (يستخدم أدوات القراءة حصراً).
 * - حد الجولات 1-10: يمنع الحلقات المكلفة.
 * - إصلاح تلقائي عند فشل البناء: الوكيل يقرأ الحالة ويعيد المحاولة.
 */
object AgentPrefs {
    const val MODEL = "agent_model"
    const val MAX_TURNS = "agent_max_turns"
    const val DRY_RUN = "agent_dry_run"
    const val AUTO_FIX = "agent_auto_fix"
    fun toolKey(name: String) = "tool_enabled_$name"

    fun isToolEnabled(context: Context, name: String): Boolean =
        context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            .getBoolean(toolKey(name), true)

    fun maxTurns(context: Context): Int =
        context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            .getInt(MAX_TURNS, 6).coerceIn(1, 10)

    fun isDryRun(context: Context): Boolean =
        context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            .getBoolean(DRY_RUN, false)

    fun isAutoFix(context: Context): Boolean =
        context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            .getBoolean(AUTO_FIX, true)

    fun model(context: Context): String? =
        context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            .getString(MODEL, null)?.takeIf { it.isNotBlank() }
}

private data class Requirement(
    val id: String,
    val title: String,
    val hint: String,
    var state: ReqState = ReqState.CHECKING,
    var detail: String = ""
)

private enum class ReqState { CHECKING, OK, FAIL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentRoomSection(onJumpBuildCenter: () -> Unit = {}, onJumpKeys: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterTabRow(
            tabs = listOf("✅ المطلوبات", "🛠️ الأدوات"),
            selected = tab,
            onSelect = { tab = it }
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (tab == 0) RequirementsTab(context, scope, onJumpBuildCenter, onJumpKeys)
        else ToolsTab(context)
    }
}

@Composable
private fun RequirementsTab(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onJumpBuildCenter: () -> Unit,
    onJumpKeys: () -> Unit
) {
    var reqs by remember {
        mutableStateOf(
            listOf(
                Requirement("or", "مفتاح OpenRouter", "عقل الوكيل — نماذج مجانية"),
                Requirement("pat", "رمز GitHub PAT", "يد الوكيل — صلاحية repo"),
                Requirement("req", "طلب مربوط نشط", "يعرف لمن يعمل"),
                Requirement("price", "سعر مقبول", "بوابة التوليد"),
                Requirement("repo", "مستودع مرتبط", "بيت المشروع")
            )
        )
    }
    var checking by remember { mutableStateOf(false) }

    fun runChecks() {
        checking = true
        reqs = reqs.map { it.copy(state = ReqState.CHECKING, detail = "") }
        scope.launch {
            // 1) OpenRouter
            val orKey = KeyVault.openrouter
            val orOk = orKey.isNotBlank() && OpenRouterService.validateKey(orKey)
            // 2) PAT
            val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            val owner = prefs.getString("build_center_owner", "") ?: ""
            val repo = prefs.getString("build_center_repo", "") ?: ""
            val token = prefs.getString("build_center_token", "") ?: ""
            val ghUser = if (token.isNotBlank()) {
                GitHubRepoClient(owner.ifBlank { "-" }, repo.ifBlank { "-" }, token).getAuthUser()
            } else null
            // 3-5) الطلب والسعر والمستودع
            val active = AppRequestService.getActiveBuildRequest(context)
            val priced = active != null &&
                (context.getSharedPreferences("qabas_requests_prefs", Context.MODE_PRIVATE)
                    .getBoolean("priced_${active.id}", false) ||
                    active.priceStatus == "accepted")
            val linkedRepo = active?.let {
                prefs.getString("repo_${it.id}", null)
                    ?: context.getSharedPreferences("qabas_requests_prefs", Context.MODE_PRIVATE)
                        .getString("repo_${it.id}", null)
            }
            reqs = reqs.map {
                when (it.id) {
                    "or" -> it.copy(
                        state = if (orOk) ReqState.OK else ReqState.FAIL,
                        detail = if (orOk) "المفتاح يعمل ✅" else "مفقود أو مرفوض"
                    )
                    "pat" -> it.copy(
                        state = if (ghUser != null) ReqState.OK else ReqState.FAIL,
                        detail = ghUser?.let { u -> "متصل كـ $u ✅" } ?: "مفقود أو مرفوض (تحقق من repo scope)"
                    )
                    "req" -> it.copy(
                        state = if (active != null) ReqState.OK else ReqState.FAIL,
                        detail = active?.title ?: "لا طلب مربوط — اربط من قسم الطلبات"
                    )
                    "price" -> it.copy(
                        state = if (priced) ReqState.OK else ReqState.FAIL,
                        detail = if (priced) "محسوم ✅" else "بانتظار قبول العميل"
                    )
                    else -> it.copy(
                        state = if (linkedRepo != null) ReqState.OK else ReqState.FAIL,
                        detail = linkedRepo ?: "أنشئ مستودعاً من مركز البناء"
                    )
                }
            }
            checking = false
        }
    }

    LaunchedEffect(Unit) { runChecks() }

    val done = reqs.count { it.state == ReqState.OK }
    CenterHeaderCard(
        badge = "$done/5",
        title = "جاهزية الوكيل",
        subtitle = if (done == 5) "كل الشروط خضراء — انطلق 🚀" else "أكمل الشروط الحمراء أولاً",
        accent = if (done == 5) Color(0xFF10B981) else Color(0xFFF59E0B),
        actionLabel = "🔄 فحص",
        onAction = { runChecks() }
    )
    Spacer(modifier = Modifier.height(10.dp))

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        reqs.forEach { r ->
            val color = when (r.state) {
                ReqState.OK -> Color(0xFF10B981)
                ReqState.FAIL -> Color(0xFFEF4444)
                else -> Color(0xFFF59E0B)
            }
            Surface(
                color = CardSurface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (r.state == ReqState.CHECKING || checking && r.state == ReqState.CHECKING) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
                    } else {
                        Box(
                            modifier = Modifier.size(12.dp)
                                .background(color, CircleShape)
                        ) {}
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.title, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${r.hint} — ${r.detail.ifBlank { "يفحص…" }}", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                    }
                    if (r.state == ReqState.FAIL && !checking) {
                        TextButton(onClick = {
                            if (r.id == "or") onJumpKeys() else onJumpBuildCenter()
                        }) {
                            Text("إصلاح ←", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsTab(context: Context) {
    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
    val dummyCtx = CodeTools.Ctx("-", "-", "-", "-")
    val allTools = remember { CodeTools.definitions(dummyCtx) + AiTools.definitions(context) }
    var states by remember { mutableStateOf(allTools.associate { it.name to AgentPrefs.isToolEnabled(context, it.name) }) }
    var model by remember { mutableStateOf(AgentPrefs.model(context)) }
    var maxTurns by remember { mutableStateOf(AgentPrefs.maxTurns(context).toFloat()) }
    var dryRun by remember { mutableStateOf(AgentPrefs.isDryRun(context)) }
    var autoFix by remember { mutableStateOf(AgentPrefs.isAutoFix(context)) }

    val arabicNames = mapOf(
        "list_tree" to "📁 تصفح الشجرة",
        "read_file" to "📖 قراءة ملف",
        "write_file" to "✏️ كتابة ملف",
        "trigger_build" to "🏗️ تشغيل البناء",
        "build_status" to "📊 حالة البناء",
        "create_pr" to "🔀 فتح سحب",
        "device_status" to "📱 حالة الجهاز",
        "diagnose" to "🩺 تشخيص شامل",
        "generate_script" to "🎬 توليد سكريبت",
        "search_quran" to "🔍 بحث قرآني",
        "get_tafsir" to "📖 تفسير آية"
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // إعدادات السرعة والدقة
        Surface(color = CardSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚙️ إعدادات الوكيل", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("النموذج:", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    listOf(null to "⚡ تلقائي", "meta-llama/llama-3.3-70b-instruct:free" to "🦙", "google/gemma-3-27b-it:free" to "💎", "qwen/qwen3-32b:free" to "🌊").forEach { (id, label) ->
                        FilterChip(
                            selected = model == id,
                            onClick = {
                                model = id
                                prefs.edit().putString(AgentPrefs.MODEL, id ?: "").apply()
                            },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("حد الجولات: ${maxTurns.toInt()} (يمنع الحلقات المكلفة)", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
                Slider(
                    value = maxTurns,
                    onValueChange = {
                        maxTurns = it
                        prefs.edit().putInt(AgentPrefs.MAX_TURNS, it.toInt()).apply()
                    },
                    valueRange = 1f..10f, steps = 8
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔍 وضع المحاكاة", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("يخطط فقط بأدوات القراءة — بلا كتابة", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp)
                    }
                    Switch(
                        checked = dryRun,
                        onCheckedChange = {
                            dryRun = it
                            prefs.edit().putBoolean(AgentPrefs.DRY_RUN, it).apply()
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔧 إصلاح تلقائي عند فشل البناء", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("يقرأ الحالة ويعيد المحاولة وحده", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp)
                    }
                    Switch(
                        checked = autoFix,
                        onCheckedChange = {
                            autoFix = it
                            prefs.edit().putBoolean(AgentPrefs.AUTO_FIX, it).apply()
                        }
                    )
                }
            }
        }

        Text("مفاتيح الأدوات (11):", color = TextSecondary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        allTools.forEach { tool ->
            val on = states[tool.name] == true
            Surface(
                color = CardSurface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, (if (on) Color(0xFF10B981) else Color(0xFF334155)).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            arabicNames[tool.name] ?: tool.name,
                            color = if (on) Color.White else TextSecondary,
                            fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                        Text(tool.description, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp, maxLines = 2)
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = {
                            states = states + (tool.name to it)
                            prefs.edit().putBoolean(AgentPrefs.toolKey(tool.name), it).apply()
                        }
                    )
                }
            }
        }
    }
}
