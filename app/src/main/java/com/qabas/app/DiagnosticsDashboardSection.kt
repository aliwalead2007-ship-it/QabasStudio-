package com.qabas.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsDashboardSection(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<AppDiagnostics.DiagnosticReport?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedCategory by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        report = withContext(Dispatchers.IO) { AppDiagnostics.collect(context) }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    "تشخيص شامل 🏥",
                    color = GoldPrimary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = CairoFont
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = GoldPrimary)
                }
            },
            actions = {
                IconButton(onClick = {
                    scope.launch {
                        isLoading = true
                        report = withContext(Dispatchers.IO) { AppDiagnostics.collect(context) }
                        isLoading = false
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = AiCyan)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepSlate)
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = GoldPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("جاري التشخيص...", color = TextSecondary, fontFamily = CairoFont, fontSize = 14.sp)
                }
            }
        } else if (report == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("تعذر التشخيص", color = Color.Gray, fontFamily = CairoFont)
            }
        } else {
            val r = report!!

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // ── Overall Health Score ──
                item {
                    val criticalCount = r.issues.count { it.severity == AppDiagnostics.Issue.Severity.CRITICAL }
                    val warningCount = r.issues.count { it.severity == AppDiagnostics.Issue.Severity.WARNING }
                    val healthScore = when {
                        criticalCount > 0 -> "حرج 🔴"
                        warningCount > 3 -> "يحتاج انتباه 🟡"
                        warningCount > 0 -> "جيد مع ملاحظات 🟡"
                        else -> "ممتاز 🟢"
                    }
                    val healthColor = when {
                        criticalCount > 0 -> Color(0xFFEF4444)
                        warningCount > 0 -> Color(0xFFF59E0B)
                        else -> Color(0xFF10B981)
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = healthColor.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, healthColor.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(healthScore, color = healthColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "مشاكل: ${criticalCount} حرجة | ${warningCount} تحذيرات | ${r.issues.size} إجمالي",
                                color = TextSecondary, fontSize = 12.sp, fontFamily = NotoSansFont
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "آخر تشخيص: ${ProductionPipelineTracker.formatTimestamp(r.timestamp)}",
                                color = Color.Gray, fontSize = 10.sp, fontFamily = NotoSansFont
                            )
                        }
                    }
                }

                // ── Critical Issues ──
                if (r.issues.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("المشاكل المكتشفة (${r.issues.size}):", color = GoldPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    items(r.issues) { issue ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1320)),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, when (issue.severity) {
                                AppDiagnostics.Issue.Severity.CRITICAL -> Color(0xFFEF4444)
                                AppDiagnostics.Issue.Severity.WARNING -> Color(0xFFF59E0B)
                                AppDiagnostics.Issue.Severity.INFO -> AiCyan
                            }.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (issue.severity) {
                                            AppDiagnostics.Issue.Severity.CRITICAL -> "🔴"
                                            AppDiagnostics.Issue.Severity.WARNING -> "🟡"
                                            AppDiagnostics.Issue.Severity.INFO -> "🔵"
                                        },
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(issue.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(issue.category, color = Color.Gray, fontSize = 9.sp, fontFamily = NotoSansFont)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(issue.description, color = TextSecondary, fontSize = 11.sp, fontFamily = CairoFont)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("💡 ${issue.suggestion}", color = AiCyan.copy(alpha = 0.8f), fontSize = 10.sp, fontFamily = NotoSansFont)
                            }
                        }
                    }
                }

                // ── Device Info ──
                item {
                    diagnosticCategory("معلومات الجهاز 📱", "device", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRow("الموديل", "${r.deviceInfo.manufacturer} ${r.deviceInfo.model}")
                        DiagnosticRow("أندرويد", "${r.deviceInfo.androidVersion} (SDK ${r.deviceInfo.sdkInt})")
                        DiagnosticRow("التطبيق", "v${r.deviceInfo.appVersion} (${r.deviceInfo.appVersionCode})")
                        DiagnosticRow("أنوية المعالج", "${r.deviceInfo.cpuCores}")
                        DiagnosticRow("المعالجات المدعومة", r.deviceInfo.supportedAbis.joinToString(", "))
                    }
                }

                // ── Memory ──
                item {
                    diagnosticCategory("الذاكرة 💾", "memory", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRowWithBar("استخدام Heap", "${r.memoryInfo.usedHeapMB}/${r.memoryInfo.totalHeapMB}MB", r.memoryInfo.heapUsagePercent)
                        DiagnosticRow("ذاكرة متاحة", "${r.memoryInfo.availableRAM_MB}MB من ${r.memoryInfo.totalRAM_MB}MB")
                        DiagnosticRow("الذاكرة الأصلية", "${r.memoryInfo.nativeHeapMB}MB")
                        DiagnosticRow("جهاز ذاكرة منخفضة", if (r.memoryInfo.lowMemoryDevice) "نعم ⚠️" else "لا ✅")
                    }
                }

                // ── Storage ──
                item {
                    diagnosticCategory("التخزين 💿", "storage", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRow("مساحة داخلية متاحة", "${r.storageInfo.internalAvailableMB}MB من ${r.storageInfo.internalTotalMB}MB")
                        DiagnosticRow("حجم الكاش", "${r.storageInfo.cacheDirMB}MB")
                        DiagnosticRow("حجم ملفات التطبيق", "${r.storageInfo.filesDirMB}MB")
                        DiagnosticRow("مساحة خارجية متاحة", "${r.storageInfo.externalAvailableMB}MB")
                        DiagnosticRow("إجمالي حجم التطبيق", "${r.storageInfo.totalAppSizeMB}MB")
                    }
                }

                // ── Network ──
                item {
                    diagnosticCategory("الشبكة 🌐", "network", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRow("حالة الاتصال", if (r.networkInfo.isConnected) "متصل ✅" else "غير متصل ❌")
                        DiagnosticRow("نوع الاتصال", r.networkInfo.connectionType)
                        DiagnosticRow("بيانات مقيدة", if (r.networkInfo.isMetered) "نعم ⚠️" else "لا ✅")
                    }
                }

                // ── Battery ──
                item {
                    diagnosticCategory("البطارية 🔋", "battery", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRow("مستوى البطارية", "${r.batteryInfo.level}%")
                        DiagnosticRow("الشحن", if (r.batteryInfo.isCharging) "يشحن ⚡" else "غير متصل")
                        DiagnosticRow("الحرارة", "${r.batteryInfo.temperature}°C")
                        DiagnosticRow("الحالة", r.batteryInfo.health)
                    }
                }

                // ── API Health ──
                item {
                    diagnosticCategory("صحة APIs 🔑", "api", expandedCategory, { expandedCategory = it }) {
                        r.apiHealth.forEach { api ->
                            val statusIcon = when (api.status) {
                                AppDiagnostics.ApiHealth.Status.OK -> "✅"
                                AppDiagnostics.ApiHealth.Status.DEGRADED -> "🟡"
                                AppDiagnostics.ApiHealth.Status.DOWN -> "🔴"
                                AppDiagnostics.ApiHealth.Status.UNKNOWN -> "⚪"
                            }
                            DiagnosticRow(
                                "$statusIcon ${api.name}",
                                if (!api.isConfigured) "غير مُعد" else "${api.totalCalls} مكالمة | ${(api.successRate * 100).toInt()}% نجاح | ${api.lastLatencyMs}ms"
                            )
                        }
                    }
                }

                // ── Prefs Health ──
                item {
                    diagnosticCategory("صحة التفضيلات 📋", "prefs", expandedCategory, { expandedCategory = it }) {
                        r.prefsHealth.forEach { pref ->
                            DiagnosticRow(
                                if (pref.isHealthy) "✅ ${pref.name}" else "⚠️ ${pref.name}",
                                "${pref.sizeKB} | ${pref.keyCount} مفتاح"
                            )
                        }
                    }
                }

                // ── Database ──
                item {
                    diagnosticCategory("قاعدة البيانات 🗄️", "database", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRow("حجم قاعدة البيانات", "${r.dbHealth.totalSizeMB}MB")
                        r.dbHealth.tables.forEach { table ->
                            DiagnosticRow("  ${table.name}", "${table.rowCount} صف")
                        }
                    }
                }

                // ── Crashes ──
                item {
                    diagnosticCategory("الانهيارات 🛡️", "crashes", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRow("إجمالي ملفات الانهيار", "${r.crashHealth.totalCrashFiles}")
                        DiagnosticRow("آخر انهيار", r.crashHealth.lastCrashAge)
                        DiagnosticRow("انهيارات آخر 24 ساعة", "${r.crashHealth.crashCount24h}")
                    }
                }

                // ── Threads ──
                item {
                    diagnosticCategory("الخيوط 🧵", "threads", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRow("عدد الخيوط النشطة", "${r.threadInfo.activeThreads}")
                        r.threadInfo.threadNames.take(10).forEach { name ->
                            DiagnosticRow("  ", name)
                        }
                        if (r.threadInfo.threadNames.size > 10) {
                            DiagnosticRow("  ", "...و ${r.threadInfo.threadNames.size - 10} خيط آخر")
                        }
                    }
                }

                // ── Pipeline ──
                item {
                    diagnosticCategory("مسار الإنتاج 🎬", "pipeline", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRow("إجمالي الأحداث", "${r.pipelineHealth.totalEvents}")
                        DiagnosticRow("معدل النجاح", "${(r.pipelineHealth.recentSuccessRate * 100).toInt()}%")
                        if (r.pipelineHealth.lastFailure.isNotBlank()) {
                            DiagnosticRow("آخر فشل", r.pipelineHealth.lastFailure)
                        }
                        if (r.pipelineHealth.avgProductionTimeMs > 0) {
                            val mins = r.pipelineHealth.avgProductionTimeMs / 60000
                            val secs = (r.pipelineHealth.avgProductionTimeMs % 60000) / 1000
                            DiagnosticRow("متوسط وقت الإنتاج", "${mins}د ${secs}ث")
                        }
                    }
                }

                // ── Performance ──
                item {
                    val coldMs = PerfTracker.coldStartMs()
                    val screens = PerfTracker.screenTimes()
                    diagnosticCategory("الأداء ⚡", "performance", expandedCategory, { expandedCategory = it }) {
                        DiagnosticRow(
                            "زمن التشغيل (منذ الإقلاع)",
                            if (coldMs >= 0) DevDashboardFormatters.formatLatency(coldMs) else "لم يُسجَّل بعد"
                        )
                        if (screens.isEmpty()) {
                            DiagnosticRow("الشاشات", "لا قياسات بعد — تنقّل بين الشاشات")
                        } else {
                            DiagnosticRow("شاشات مقاسة", "${screens.size}")
                            screens.take(12).forEach { s ->
                                DiagnosticRow(
                                    "  ${s.screen}",
                                    "${DevDashboardFormatters.formatLatency(s.msFromStart)} • ${s.visits} زيارة"
                                )
                            }
                        }
                    }
                }

                // Bottom spacing
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun diagnosticCategory(
    title: String,
    key: String,
    expandedCategory: String?,
    onToggle: (String?) -> Unit,
    content: @Composable () -> Unit
) {
    val isExpanded = expandedCategory == key
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1320)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF1E293B))
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle(if (isExpanded) null else key) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = GoldPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, color = GoldPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont, modifier = Modifier.weight(1f))
            }
            if (isExpanded) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    HorizontalDivider(color = Color(0xFF1E293B))
                    Spacer(modifier = Modifier.height(8.dp))
                    content()
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp, fontFamily = CairoFont, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, fontSize = 11.sp, fontFamily = NotoSansFont)
    }
}

@Composable
private fun DiagnosticRowWithBar(label: String, value: String, percent: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSecondary, fontSize = 11.sp, fontFamily = CairoFont, modifier = Modifier.weight(1f))
            Text(value, color = TextPrimary, fontSize = 11.sp, fontFamily = NotoSansFont)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF1E293B))
        ) {
            Box(
                modifier = Modifier.fillMaxHeight().fillMaxWidth(percent / 100f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            percent > 85 -> Color(0xFFEF4444)
                            percent > 60 -> Color(0xFFF59E0B)
                            else -> Color(0xFF10B981)
                        }
                    )
            )
        }
    }
}
