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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * «الموصلات 🔌» — كل تكاملات التطبيق في شاشة واحدة بفحص حي حقيقي:
 * GitHub • Supabase • Firebase • OpenRouter • توزيع Firebase (دليل).
 * كل بطاقة: الحالة لحظياً + أين تُستخدم + زر فحص + زر إصلاح يقفز لمكانه.
 */
private data class ConnectorState(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val usedIn: String,
    var connected: Boolean? = null, // null = لم يُفحص
    var detail: String = "",
    var checking: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsSection(
    onJumpBuildCenter: () -> Unit = {},
    onJumpKeys: () -> Unit = {},
    onJumpHealth: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var states by remember {
        mutableStateOf(
            listOf(
                ConnectorState("github", "GitHub", Icons.Default.Source, "مركز البناء والمحرر والوكيل"),
                ConnectorState("supabase", "Supabase", Icons.Default.Cloud, "المستخدمون والمشاريع السحابية"),
                ConnectorState("firebase", "Firebase", Icons.Default.LocalFireDepartment, "الإشعارات والتحليلات والتوزيع"),
                ConnectorState("openrouter", "OpenRouter", Icons.Default.AllInclusive, "الوكيل والخطط والمساعد"),
                ConnectorState("dist", "توزيع Firebase", Icons.Default.Send, "إيصال نسخ الاختبار للعميل")
            )
        )
    }

    fun set(id: String, connected: Boolean, detail: String) {
        states = states.map {
            if (it.id == id) it.copy(connected = connected, detail = detail, checking = false) else it
        }
    }

    fun markChecking(id: String, checking: Boolean) {
        states = states.map { if (it.id == id) it.copy(checking = checking) else it }
    }

    fun check(c: ConnectorState) {
        markChecking(c.id, true)
        scope.launch {
            when (c.id) {
                "github" -> {
                    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
                    val token = prefs.getString("build_center_token", "") ?: ""
                    if (token.isBlank()) {
                        set("github", false, "لا رمز PAT محفوظ")
                    } else {
                        val user = GitHubRepoClient("-", "-", token).getAuthUser()
                        set("github", user != null, user?.let { "متصل كـ $it ✅" } ?: "الرمز مرفوض أو بلا شبكة")
                    }
                }
                "supabase" -> {
                    val ok = SupabaseServices.isSupabaseAvailable
                    set("supabase", ok, if (ok) "متصل ✅" else "غير مفعّل — أضف القيم في Secrets")
                }
                "firebase" -> {
                    val ok = runCatching {
                        com.google.firebase.FirebaseApp.getInstance() != null
                    }.getOrDefault(false)
                    set("firebase", ok, if (ok) "مهيأ ✅" else "غير مهيأ في هذا البناء")
                }
                "openrouter" -> {
                    val key = KeyVault.openrouter
                    if (key.isBlank()) {
                        set("openrouter", false, "لا مفتاح محفوظ")
                    } else {
                        val ok = OpenRouterService.validateKey(key)
                        set("openrouter", ok, if (ok) "المفتاح يعمل ✅" else "المفتاح مرفوض")
                    }
                }
                "dist" -> {
                    // إعداد يدوي في GitHub — لا يمكن فحص الأسرار من التطبيق (صدق)
                    set("dist", false, "يُضبط يدوياً في GitHub — راجع الدليل بالأسفل")
                }
            }
        }
    }

    fun checkAll() {
        states.forEach { check(it) }
    }

    LaunchedEffect(Unit) { checkAll() }

    // بطاقة التوزيع دليل فقط — تُستبعد من الدرجة حتى لا تظلم النتيجة
    val scored = states.filter { it.id != "dist" }
    val okCount = scored.count { it.connected == true }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterHeaderCard(
            badge = "$okCount/4",
            title = "حالة الموصلات",
            subtitle = "فحص حي حقيقي — لا أخضر وهمي",
            accent = if (okCount == 4) Color(0xFF10B981) else if (okCount >= 2) Color(0xFFF59E0B) else Color(0xFFEF4444),
            actionLabel = "🔄 فحص الكل",
            onAction = { checkAll() }
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            states.forEach { c ->
                val color = when (c.connected) {
                    true -> Color(0xFF10B981)
                    false -> Color(0xFFEF4444)
                    null -> Color(0xFFF59E0B)
                }
                Surface(
                    color = CardSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = color.copy(alpha = 0.12f), shape = CircleShape) {
                            Icon(c.icon, contentDescription = null, tint = color, modifier = Modifier.padding(8.dp).size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.title, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("يُستخدم في: ${c.usedIn}", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 10.sp)
                            Text(
                                if (c.checking) "يفحص…" else c.detail.ifBlank { "—" },
                                color = color, fontFamily = NotoSansFont, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (c.checking) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
                            } else {
                                TextButton(onClick = { check(c) }) {
                                    Text("فحص", color = color, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                            if (c.connected == false) {
                                TextButton(onClick = {
                                    when (c.id) {
                                        "github" -> onJumpBuildCenter()
                                        "openrouter" -> onJumpKeys()
                                        "supabase", "firebase" -> onJumpHealth()
                                        else -> onJumpBuildCenter()
                                    }
                                }) {
                                    Text("إصلاح ←", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // دليل التوزيع (يدوي بصدق)
            Surface(
                color = CardSurface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📦 تفعيل توزيع Firebase (مرة واحدة لكل عميل)", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "1) مشروع Firebase + App Distribution\n2) أسرار المستودع: FIREBASE_APP_ID + FIREBASE_SERVICE_ACCOUNT\n3) مجموعة مختبرين client-testers فيها بريد العميل\nبعدها كل بناء يصله تلقائياً — لا يمكن فحص الأسرار من التطبيق لأسباب أمنية.",
                        color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val report = states.joinToString("\n") {
                                "${it.title}: ${if (it.connected == true) "متصل" else "غير متصل"} — ${it.detail}"
                            }
                            clipboard.setText(AnnotatedString("🔌 تقرير الموصلات\n$report"))
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary)
                    ) {
                        Text("📋 نسخ التقرير", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
