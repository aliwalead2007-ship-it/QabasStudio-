package com.qabas.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import java.io.File

/**
 * «مركز الصحة 🩺» — دمج قوي لخمسة أقسام متشابهة في شاشة واحدة:
 * طبيب التطبيق + تشخيص شامل + سجلات النظام + سجل الانهيارات + فحص صحة المفاتيح.
 *
 * أقوى من مجرد تبويبات:
 * - درجة صحة موحدة (0-100) تجمع الطبيب والانهيارات والسجلات في رقم واحد.
 * - زر «📤 نسخ التقرير الصحي» يجمع كل شيء في نص واحد جاهز للإرسال للمطور.
 * - التبويب المختار يُحفظ أثناء الجلسة (لا ضياع مكانك عند التنقل).
 */
enum class HealthTab(val title: String, val icon: ImageVector) {
    DOCTOR("🩺 الطبيب", Icons.Default.MedicalServices),
    DIAGNOSTICS("🔍 التشخيص", Icons.Default.HealthAndSafety),
    LOGS("📋 السجلات", Icons.Default.List),
    CRASHES("💥 الانهيارات", Icons.Default.BugReport),
    KEYS("🔑 المفاتيح", Icons.Default.VpnKey)
}

object HealthCenterState {
    var lastTab: Int = 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCenterSection(
    devLogs: SnapshotStateList<SystemLogEntry>,
    initialTab: Int = HealthCenterState.lastTab
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var selectedTab by remember { mutableStateOf(initialTab.coerceIn(0, HealthTab.values().size - 1)) }
    var doctorReports by remember { mutableStateOf<List<DoctorReport>>(emptyList()) }
    var doctorDone by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        doctorReports = runCatching { AppSelfDoctor.runFullDiagnosis(context) }.getOrDefault(emptyList())
        doctorDone = true
    }

    val crashCount = remember {
        runCatching { File(context.filesDir, "crash_logs").listFiles()?.size ?: 0 }.getOrDefault(0)
    }
    val errorLogCount = devLogs.count {
        it.level.contains("ERROR", ignoreCase = true) || it.level.contains("WARN", ignoreCase = true)
    }

    // درجة موحدة: حرج −30، تحسين −5، كل انهيار −10 (بحد أقصى −30)، أخطاء السجل −1 لكل 10 (بحد أقصى −10)
    val criticalCount = doctorReports.count { it.severity == "حرج" }
    val improvementCount = doctorReports.count { it.severity == "تحسين" }
    val unifiedScore = (100 - criticalCount * 30 - improvementCount * 5 -
        minOf(crashCount * 10, 30) - minOf(errorLogCount / 10, 10)).coerceIn(0, 100)
    val scoreColor = when {
        unifiedScore >= 80 -> Color(0xFF10B981)
        unifiedScore >= 50 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── الرأس الموحد: الدرجة + التقرير ──
        CenterHeaderCard(
            badge = if (!doctorDone) "…" else "$unifiedScore",
            title = "صحة التطبيق الموحدة",
            subtitle = if (!doctorDone) "جاري الفحص…"
                else "حرج: $criticalCount • تحسينات: $improvementCount • انهيارات: $crashCount • أخطاء سجل: $errorLogCount",
            accent = scoreColor,
            actionLabel = "📤 تقرير",
            actionDoneLabel = "تم ✓",
            actionDone = copied,
            onAction = {
                clipboard.setText(
                    AnnotatedString(
                        buildHealthReport(context, doctorReports, crashCount, errorLogCount, devLogs)
                    )
                )
                copied = true
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ── التبويبات ──
        CenterTabRowWithIcons(
            titles = HealthTab.values().map { it.title },
            icons = HealthTab.values().map { it.icon },
            selected = selectedTab,
            onSelect = {
                selectedTab = it
                HealthCenterState.lastTab = it
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── المحتوى ──
        Box(modifier = Modifier.fillMaxSize()) {
            when (HealthTab.values()[selectedTab]) {
                HealthTab.DOCTOR -> AppDoctorSection(context = context)
                HealthTab.DIAGNOSTICS -> DiagnosticsDashboardSection()
                HealthTab.LOGS -> LogsSection(devLogs = devLogs)
                HealthTab.CRASHES -> CrashLogsSection(context = context)
                HealthTab.KEYS -> LiveHealthCheckPanel()
            }
        }
    }
}

/** تقرير صحي موحد: طبيب + انهيارات + سجل — جاهز للصق في المحادثة. */
private fun buildHealthReport(
    context: android.content.Context,
    doctorReports: List<DoctorReport>,
    crashCount: Int,
    errorLogCount: Int,
    devLogs: List<SystemLogEntry>
): String = buildString {
    append("🩺 التقرير الصحي الموحد — قبس\n")
    append("━━━━━━━━━━━━━━\n")
    append("الجهاز: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n")
    append("حرج: ${doctorReports.count { it.severity == "حرج" }} • تحسينات: ${doctorReports.count { it.severity == "تحسين" }} • انهيارات: $crashCount • أخطاء سجل: $errorLogCount\n\n")
    append("── الطبيب ──\n")
    if (doctorReports.isEmpty()) {
        append("لا تقارير (لم يكتمل الفحص أو لا مشاكل).\n")
    } else {
        doctorReports.forEach { r ->
            append("[${r.severity}] ${r.title}: ${r.message}\n")
        }
    }
    append("\n── آخر 15 سطر سجل ──\n")
    devLogs.takeLast(15).forEach { e ->
        append("[${e.level} ${e.time}] ${e.message.take(200)}\n")
    }
    if (crashCount > 0) {
        append("\n⚠️ توجد $crashCount ملفات انهيار — التفاصيل في تبويب الانهيارات.\n")
    }
}
