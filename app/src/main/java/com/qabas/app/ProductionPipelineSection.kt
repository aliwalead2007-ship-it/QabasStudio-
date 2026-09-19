package com.qabas.app

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * قسم «ملتقط مشاكل مسار الإنتاج» في لوحة المطور — رصد احترافي حي لكل خطوة،
 * مرتب كقمع تشخيصي: 1) صحة الملتقط 2) المشاكل الملتقطة 3) إجراءات الإصلاح
 * 4) شريط تدفق آخر تشغيلة 5) الأعطال المتكررة 6) الأداء والسجل الحي.
 * مع طبيب تشخيص مدمج (PipelineDoctor) يترجم كل فشل إلى مشكلة + سبب + حل،
 * وبعض الحلول قابلة للتنفيذ بضغطة واحدة (تنظيف الكاش، فحص FFmpeg الذاتي).
 *
 * المزايا:
 * - 🩺 مؤشر صحة عام لآخر تشغيلة (0-100) يلخّص الوضع بلمحة.
 * - تشخيص فوري مُجمّع حسب الخطورة: كل عطل كبطاقة مشكلة/سبب/حل، مع نسخ ومشاركة سريعين.
 * - إجراءات فعلية: تنظيف كاش المحرك، وفحص ذاتي لمحرك FFmpeg بلا انتظار تشغيلة كاملة.
 * - 📊 أكثر المشاكل تكراراً عبر كل التشغيلات — لرصد الأعطال المزمنة لا العرضية.
 * - التقاط مباشر: يتحدث تلقائياً كل ثانيتين فتظهر الخطوات لحظة حدوثها أثناء الإنتاج.
 * - بطاقة «آخر تشغيلة» مع شريط تدفق مرئي، إحصاءات لكل مرحلة، وسجل زمني كامل.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionPipelineSection(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val events = remember { mutableStateOf(ProductionPipelineTracker.getEvents(context)) }

    // التقاط مباشر: تحديث تلقائي كل ثانيتين فتُلتقط الخطوات لحظة وقوعها
    LaunchedEffect(Unit) {
        while (isActive) {
            kotlinx.coroutines.delay(2000)
            events.value = ProductionPipelineTracker.getEvents(context)
        }
    }

    val stats = remember(events.value) { ProductionPipelineTracker.getStageStats(context) }
    val avgDurations = remember(events.value) { ProductionPipelineTracker.getAvgDurationPerStage(context) }
    val runs = remember(events.value) { ProductionPipelineTracker.getRuns(context) }
    val latestRunEvents = remember(events.value) { ProductionPipelineTracker.getLatestRunEvents(context) }

    val latestRunIssues = remember(latestRunEvents) { PipelineDoctor.analyze(latestRunEvents) }
    val recurringIssues = remember(events.value) {
        PipelineDoctor.analyze(events.value).filter { it.occurrences > 1 || it.severity == IssueSeverity.CRITICAL }.take(5)
    }
    val healthScore = remember(latestRunIssues) {
        (100 - latestRunIssues.sumOf {
            when (it.severity) {
                IssueSeverity.CRITICAL -> 30
                IssueSeverity.WARNING -> 10
                IssueSeverity.INFO -> 2
            }
        }).coerceIn(0, 100)
    }

    var selectedStage by remember { mutableStateOf<ProductionPipelineTracker.Stage?>(null) }
    var severityFilter by remember { mutableStateOf<IssueSeverity?>(null) }
    val visibleIssues = remember(latestRunIssues, severityFilter) {
        if (severityFilter == null) latestRunIssues else latestRunIssues.filter { it.severity == severityFilter }
    }
    val groupedIssues = remember(visibleIssues) {
        listOf(IssueSeverity.CRITICAL, IssueSeverity.WARNING, IssueSeverity.INFO)
            .mapNotNull { sev -> visibleIssues.filter { it.severity == sev }.takeIf { it.isNotEmpty() }?.let { sev to it } }
    }
    val filteredEvents = if (selectedStage != null) events.value.filter { it.stage == selectedStage } else events.value

    // حالة الفحص الذاتي لمحرك FFmpeg
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf<PipelineActions.ActionResult?>(null) }

    // نبضة حية لمؤشر «التقاط مباشر»
    val pulseTransition = rememberInfiniteTransition(label = "livePulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ملتقط مشاكل مسار الإنتاج",
                        color = GoldPrimary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = CairoFont
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(6.dp).clip(CircleShape)
                                    .background(Color(0xFF10B981).copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "التقاط مباشر",
                                color = Color(0xFF10B981),
                                fontSize = 9.sp,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = GoldPrimary)
                }
            },
            actions = {
                IconButton(onClick = {
                    ProductionPipelineTracker.clearEvents(context)
                    events.value = emptyList()
                }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "مسح السجل", tint = Color(0xFFEF4444))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepSlate)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {

            // ── صحة آخر تشغيلة + ملخص عام ──
            if (latestRunEvents.isNotEmpty()) {
                item {
                    HealthScoreRow(score = healthScore, issueCount = latestRunIssues.size)
                }
            }

            item {
                val totalSuccess = events.value.count { it.result == ProductionPipelineTracker.Result.SUCCESS }
                val totalFailure = events.value.count { it.result == ProductionPipelineTracker.Result.FAILURE }
                val totalFallback = events.value.count { it.result == ProductionPipelineTracker.Result.FALLBACK }
                val overallRate = if (events.value.isNotEmpty())
                    (totalSuccess.toFloat() / events.value.size * 100).toInt() else 0

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryMiniCard("${runs.size}", "تشغيلات", Icons.Default.Movie, GoldPrimary, Modifier.weight(1f))
                    SummaryMiniCard("$totalSuccess", "نجاح", Icons.Default.CheckCircle, Color(0xFF10B981), Modifier.weight(1f))
                    SummaryMiniCard("${totalFailure + totalFallback}", "فشل/بديل", Icons.Default.WarningAmber, Color(0xFFF59E0B), Modifier.weight(1f))
                    SummaryMiniCard("$overallRate%", "معدل النجاح", Icons.Default.TrendingUp, Color(0xFF22D3EE), Modifier.weight(1f))
                }
            }

            // ── 📤 تصدير تقرير للمطور: زر واحد ينسخ كل شيء ──
            if (latestRunEvents.isNotEmpty()) {
                item {
                    var copied by remember { mutableStateOf(false) }
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            clipboard.setText(AnnotatedString(buildDeveloperReport(context, latestRunEvents, latestRunIssues, healthScore)))
                            copied = true
                        },
                        color = GoldPrimary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = GoldPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (copied) "تم النسخ ✓ — الصقه لي في المحادثة" else "📤 انسخ تقرير المشكلة وأرسله للمطور",
                                    color = GoldPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = CairoFont
                                )
                                Text(
                                    "يشمل الجهاز + المشاكل + آخر 20 سطر سجل — جاهز للصق",
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = NotoSansFont
                                )
                            }
                            Icon(Icons.Default.Share, contentDescription = null, tint = GoldPrimary.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // ── فحص ذاتي سريع لمحرك FFmpeg ──
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF0D1320),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22D3EE).copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = Color(0xFF22D3EE), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "2️⃣ إجراءات الإصلاح السريع",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = CairoFont
                                )
                            }
                            Button(
                                onClick = {
                                    selfTestRunning = true
                                    selfTestResult = null
                                    scope.launch {
                                        val result = PipelineActions.selfTestFFmpeg(context)
                                        selfTestResult = result
                                        selfTestRunning = false
                                    }
                                },
                                enabled = !selfTestRunning,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22D3EE), contentColor = Color.Black),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (selfTestRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.Black)
                                } else {
                                    Text("شغّل الفحص", fontSize = 11.sp, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        selfTestResult?.let { r ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = (if (r.success) Color(0xFF10B981) else Color(0xFFEF4444)).copy(alpha = 0.10f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    r.message,
                                    color = if (r.success) Color(0xFF10B981) else Color(0xFFEF4444),
                                    fontSize = 10.5.sp,
                                    fontFamily = CairoFont,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── 🩺 تشخيص آخر تشغيلة: مُجمّع حسب الخطورة، المشكلة + السبب + الحل ──
            if (latestRunEvents.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MedicalServices, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "1️⃣ المشاكل الملتقطة — آخر تشغيلة",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = CairoFont
                            )
                        }
                        if (latestRunIssues.isNotEmpty()) {
                            Text(
                                "نسخ تقرير كامل",
                                color = GoldPrimary,
                                fontSize = 10.sp,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    val report = visibleIssues.joinToString("\n\n---\n\n") { it.toShareText() }
                                    clipboard.setText(AnnotatedString(report))
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SeverityChip("الكل", latestRunIssues.size, severityFilter == null, GoldPrimary) { severityFilter = null }
                        SeverityChip("حرج", latestRunIssues.count { it.severity == IssueSeverity.CRITICAL }, severityFilter == IssueSeverity.CRITICAL, Color(0xFFEF4444)) { severityFilter = if (severityFilter == IssueSeverity.CRITICAL) null else IssueSeverity.CRITICAL }
                        SeverityChip("تحذير", latestRunIssues.count { it.severity == IssueSeverity.WARNING }, severityFilter == IssueSeverity.WARNING, Color(0xFFF59E0B)) { severityFilter = if (severityFilter == IssueSeverity.WARNING) null else IssueSeverity.WARNING }
                        SeverityChip("معلومة", latestRunIssues.count { it.severity == IssueSeverity.INFO }, severityFilter == IssueSeverity.INFO, Color(0xFF60A5FA)) { severityFilter = if (severityFilter == IssueSeverity.INFO) null else IssueSeverity.INFO }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (latestRunIssues.isEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF10B981).copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LottieBrainVisualizer(status = BrainStatus.SUCCESS, size = 40.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "لا مشاكل مكتشفة في آخر تشغيلة — كل المراحل التي عملت اكتملت بلا أعطال.",
                                    color = Color(0xFF10B981),
                                    fontSize = 11.sp,
                                    fontFamily = CairoFont,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                } else {
                    groupedIssues.forEach { (severity, issuesInGroup) ->
                        item(key = "sev_header_${severity.name}") {
                            SeverityGroupHeader(severity, issuesInGroup.size)
                        }
                        items(issuesInGroup, key = { "diag_${it.id}" }) { issue ->
                            DiagnosedIssueCard(
                                issue = issue,
                                clipboard = clipboard,
                                context = context,
                                scope = scope
                            )
                        }
                    }
                }
            }

            // ── 📊 الأكثر تكراراً عبر كل التشغيلات ──
            if (recurringIssues.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "3️⃣ أعطال متكررة عبر كل التشغيلات:",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = NotoSansFont,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                items(recurringIssues, key = { "recurring_${it.id}" }) { issue ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(issue.severity.colorHex).copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                issue.title,
                                color = Color(issue.severity.colorHex),
                                fontSize = 11.sp,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "×${issue.occurrences}",
                                color = Color(issue.severity.colorHex),
                                fontSize = 11.sp,
                                fontFamily = NotoSansFont,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ── بطاقة آخر تشغيلة + شريط تدفق المراحل ──
            item {
                LatestRunCard(
                    runs = runs,
                    latestRunEvents = latestRunEvents,
                    selectedStage = selectedStage,
                    onStageSelected = { stage ->
                        selectedStage = if (selectedStage == stage) null else stage
                    }
                )
            }

            // ── أداء المراحل ──
            item {
                Text(
                    "4️⃣ أداء المراحل:",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = NotoSansFont,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            items(ProductionPipelineTracker.CANONICAL_ORDER.filter { it != ProductionPipelineTracker.Stage.PIPELINE_START && it != ProductionPipelineTracker.Stage.PIPELINE_END }) { stage ->
                val stageStats = stats[stage]
                val total = stageStats?.values?.sum() ?: 0
                val success = stageStats?.get(ProductionPipelineTracker.Result.SUCCESS) ?: 0
                val rate = if (total > 0) (success.toFloat() / total * 100).toInt() else 0
                val avgMs = avgDurations[stage] ?: 0L

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stage.label,
                        color = if (selectedStage == stage) GoldPrimary else TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = CairoFont,
                        modifier = Modifier.weight(1f).clickable { selectedStage = if (selectedStage == stage) null else stage }
                    )
                    if (avgMs > 0) {
                        Text(
                            "⏱ ${formatDuration(avgMs)}",
                            color = Color.Gray,
                            fontSize = 9.sp,
                            fontFamily = NotoSansFont
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (total > 0) {
                        Box(
                            modifier = Modifier.width(80.dp).height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1E293B))
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight().fillMaxWidth(rate / 100f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when {
                                            rate >= 80 -> Color(0xFF10B981)
                                            rate >= 50 -> Color(0xFFF59E0B)
                                            else -> Color(0xFFEF4444)
                                        }
                                    )
                            )
                        }
                    } else {
                        Text("—", color = Color.Gray, fontSize = 11.sp, fontFamily = NotoSansFont)
                    }
                }
            }

            // ── السجل الحي ──
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selectedStage != null) "5️⃣ سجل «${selectedStage!!.label}»:" else "5️⃣ السجل الحي (كل التشغيلات):",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = NotoSansFont,
                        fontWeight = FontWeight.Bold
                    )
                    if (selectedStage != null) {
                        Text(
                            "إلغاء التصفية",
                            color = GoldPrimary,
                            fontSize = 10.sp,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { selectedStage = null }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (filteredEvents.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1320)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Radar,
                                contentDescription = null,
                                tint = GoldPrimary.copy(alpha = 0.6f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "بانتظار خطوات مسار الإنتاج…",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "ابدأ إنتاج فيديو وستُلتقط كل خطوة هنا لحظة حدوثها",
                                color = Color.Gray.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                fontFamily = NotoSansFont,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                // تجميع حسب التشغيلة مع ترويسة لكل مجموعة
                val grouped = filteredEvents.take(80).groupBy { it.runId }
                grouped.forEach { (runId, runEvents) ->
                    item(key = "run_header_$runId") { RunHeaderCard(runId, runEvents) }
                    items(runEvents, key = { if (it.runId.isNotBlank()) "${it.runId}_${it.timestamp}_${it.stage.name}" else "x_${it.timestamp}_${it.stage.name}_${it.message.hashCode()}" }) { event ->
                        EventCard(event)
                    }
                }
            }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/** صف مؤشر الصحة العام لآخر تشغيلة: دائرة نسبة + وصف حالة. */
@Composable
private fun HealthScoreRow(score: Int, issueCount: Int) {
    val color = when {
        score >= 80 -> Color(0xFF10B981)
        score >= 50 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
    val label = when {
        score >= 80 -> "سليم"
        score >= 50 -> "يحتاج انتباه"
        else -> "حرج"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(54.dp)) {
                CircularProgressIndicator(
                    progress = { score / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = color,
                    trackColor = color.copy(alpha = 0.15f),
                    strokeWidth = 5.dp,
                    strokeCap = StrokeCap.Round
                )
                Text("$score", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = NotoSansFont)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    "صحة آخر تشغيلة: $label",
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = CairoFont
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (issueCount > 0) "$issueCount مشكلة مكتشفة — التفاصيل بالأسفل" else "كل المراحل عملت بلا أعطال",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = NotoSansFont
                )
            }
        }
    }
}

/** ترويسة مجموعة مشاكل بحسب الخطورة. */
/** يبني تقريراً نصياً جاهزاً للصق في المحادثة: جهاز + صحة + مشاكل + آخر السجل. */
private fun buildDeveloperReport(
    context: android.content.Context,
    runEvents: List<ProductionPipelineTracker.PipelineEvent>,
    issues: List<DiagnosedIssue>,
    healthScore: Int
): String = buildString {
    append("🩺 تقرير ملتقط مشاكل مسار الإنتاج\n")
    append("━━━━━━━━━━━━━━\n")
    append("الجهاز: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})\n")
    append("صحة آخر تشغيلة: $healthScore/100 | الأحداث: ${runEvents.size} | المشاكل: ${issues.size}\n\n")
    if (issues.isEmpty()) {
        append("لا مشاكل مشخصة — كل المراحل سليمة.\n\n")
    } else {
        issues.forEachIndexed { i, issue ->
            append("مشكلة ${i + 1}: ${issue.title}\n")
            append("المرحلة: ${issue.stage.label} | الخطورة: ${issue.severity.label} | التكرار: ${issue.occurrences}\n")
            if (issue.affectedScenes.isNotEmpty()) append("المشاهد: ${issue.affectedScenes.joinToString(", ") { (it + 1).toString() }}\n")
            append("السبب: ${issue.cause}\n")
            append("الحل المقترح: ${issue.solution}\n\n")
        }
    }
    append("── آخر 20 سطر سجل ──\n")
    runEvents.takeLast(20).forEach { e ->
        append("[${ProductionPipelineTracker.formatTimestamp(e.timestamp)}] ${e.stage.label} ${e.result} مشهد=${if (e.sceneIndex >= 0) e.sceneIndex + 1 else "—"}: ${e.message}\n")
        if (e.detail.isNotBlank()) append("   ↳ ${e.detail.take(300)}\n")
    }
}

@Composable
private fun SeverityChip(label: String, count: Int, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (selected) color.copy(alpha = 0.22f) else Color(0xFF0D1320),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = if (selected) 0.8f else 0.3f))
    ) {
        Text(
            "$label ($count)",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = CairoFont,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SeverityGroupHeader(severity: IssueSeverity, count: Int) {
    val color = Color(severity.colorHex)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            severity.label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = CairoFont
        )
        Spacer(modifier = Modifier.width(6.dp))
        Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
            Text(
                "$count",
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = NotoSansFont,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

/** بطاقة مشكلة مُشخَّصة: العنوان + الخطورة + السبب + الحل، مع نسخ/مشاركة، وإجراء فعلي عند توفره. */
@Composable
private fun DiagnosedIssueCard(
    issue: DiagnosedIssue,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var expanded by remember(issue.id) { mutableStateOf(true) }
    var actionRunning by remember(issue.id) { mutableStateOf(false) }
    var actionResult by remember(issue.id) { mutableStateOf<PipelineActions.ActionResult?>(null) }
    val severityColor = Color(issue.severity.colorHex)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1320)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, severityColor.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    Icon(
                        if (issue.severity == IssueSeverity.CRITICAL) Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = severityColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            issue.title,
                            color = TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = CairoFont,
                            lineHeight = 17.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${issue.stage.label} · تكرر ${issue.occurrences} مرة", color = TextSecondary, fontSize = 9.sp, fontFamily = NotoSansFont)
                        if (issue.affectedScenes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                "المشاهد المتأثرة: " + issue.affectedScenes.joinToString(", ") { (it + 1).toString() },
                                color = GoldSecondary,
                                fontSize = 9.sp,
                                fontFamily = NotoSansFont
                            )
                        }
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(color = Color.White.copy(alpha = 0.03f), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("السبب المحتمل", color = severityColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(issue.cause, color = TextPrimary, fontSize = 11.sp, fontFamily = CairoFont, lineHeight = 16.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(color = Color(0xFF10B981).copy(alpha = 0.06f), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("الحل المقترح", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(issue.solution, color = TextPrimary, fontSize = 11.sp, fontFamily = CairoFont, lineHeight = 16.sp)
                        }
                    }

                    if (issue.actionId != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                actionRunning = true
                                actionResult = null
                                scope.launch {
                                    val result = when (issue.actionId) {
                                        "clean_cache" -> PipelineActions.cleanEngineCache(context)
                                        "self_test_ffmpeg" -> PipelineActions.selfTestFFmpeg(context)
                                        else -> PipelineActions.ActionResult(false, "إجراء غير معروف")
                                    }
                                    actionResult = result
                                    actionRunning = false
                                }
                            },
                            enabled = !actionRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            if (actionRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.Black)
                            } else {
                                Text(
                                    when (issue.actionId) {
                                        "clean_cache" -> "نظّف الكاش الآن"
                                        "self_test_ffmpeg" -> "فحص ذاتي لـ FFmpeg"
                                        else -> "تنفيذ الحل"
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        actionResult?.let { r ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                r.message,
                                color = if (r.success) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 10.sp,
                                fontFamily = CairoFont,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                try {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, issue.toShareText())
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "مشاركة التشخيص").apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } catch (_: Exception) { }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("مشاركة", color = TextSecondary, fontSize = 10.sp, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { clipboard.setText(AnnotatedString(issue.toShareText())) }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("نسخ", color = GoldPrimary, fontSize = 10.sp, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/** بطاقة آخر تشغيلة: حالة المسار + شريط تدفق مرئي لكل مرحلة. */
@Composable
private fun LatestRunCard(
    runs: List<ProductionPipelineTracker.RunSummary>,
    latestRunEvents: List<ProductionPipelineTracker.PipelineEvent>,
    selectedStage: ProductionPipelineTracker.Stage?,
    onStageSelected: (ProductionPipelineTracker.Stage) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (runs.isEmpty()) {
                Text(
                    "لا توجد تشغيلات بعد — آخر تشغيلة ستظهر هنا فور بدء الإنتاج",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = NotoSansFont
                )
                return@Column
            }

            val latest = runs.first()
            val runDurationMs = latest.endTime - latest.startTime

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (latest.completed && !latest.hasFailure) Icons.Default.PlayCircle
                        else if (latest.completed) Icons.Default.ErrorOutline
                        else Icons.Default.Pending,
                        contentDescription = null,
                        tint = when {
                            !latest.completed -> GoldPrimary
                            latest.hasFailure -> Color(0xFFEF4444)
                            else -> Color(0xFF10B981)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "آخر تشغيلة",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = CairoFont
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "#${latest.runId.takeLast(6)}",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = NotoSansFont
                    )
                }
                Text(
                    when {
                        !latest.completed -> "قيد التشغيل…"
                        latest.hasFailure -> "انتهت بمشاكل"
                        else -> "مكتملة ✓"
                    },
                    color = when {
                        !latest.completed -> GoldPrimary
                        latest.hasFailure -> Color(0xFFEF4444)
                        else -> Color(0xFF10B981)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = CairoFont
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${ProductionPipelineTracker.formatTimestamp(latest.startTime)} → ${ProductionPipelineTracker.formatTimestamp(latest.endTime)}",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = NotoSansFont
                )
                Text(
                    "المدة: ${formatDuration(runDurationMs)}",
                    color = GoldSecondary,
                    fontSize = 9.sp,
                    fontFamily = NotoSansFont,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // شريط تدفق المراحل — كل مرحلة كنقطة مرقمة تتلون حسب حالتها في آخر تشغيلة
            Text(
                "تدفق المراحل (اضغط للتصفية):",
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = NotoSansFont
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                ProductionPipelineTracker.CANONICAL_ORDER.forEachIndexed { i, stage ->
                    val status = stageStatusInRun(latestRunEvents, stage)
                    val stageColor = when (status) {
                        ProductionPipelineTracker.Result.SUCCESS -> Color(0xFF10B981)
                        ProductionPipelineTracker.Result.FAILURE -> Color(0xFFEF4444)
                        ProductionPipelineTracker.Result.TIMEOUT -> Color(0xFF8B5CF6)
                        ProductionPipelineTracker.Result.FALLBACK -> Color(0xFFF59E0B)
                        ProductionPipelineTracker.Result.SKIPPED -> Color(0xFF6B7280)
                        null -> Color(0xFF334155)
                    }
                    Column(
                        modifier = Modifier
                            .width(64.dp)
                            .clickable { onStageSelected(stage) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // الرقم + موصل الخط
                        Box(contentAlignment = Alignment.Center) {
                            if (i > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth().height(2.dp)
                                        .background(Color(0xFF334155))
                                        .align(Alignment.CenterStart)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(stageColor.copy(alpha = if (status != null) 1f else 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${i + 1}",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = NotoSansFont
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            stage.label,
                            color = if (status != null) TextPrimary else Color.Gray.copy(alpha = 0.5f),
                            fontSize = 8.sp,
                            fontFamily = CairoFont,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/** حالة المرحلة داخل تشغيلة معينة (أولوية: فشل > مهلة > نجاح > بديل > تخطي). */
private fun stageStatusInRun(
    runEvents: List<ProductionPipelineTracker.PipelineEvent>,
    stage: ProductionPipelineTracker.Stage
): ProductionPipelineTracker.Result? {
    val evts = runEvents.filter { it.stage == stage }
    return when {
        evts.isEmpty() -> null
        evts.any { it.result == ProductionPipelineTracker.Result.FAILURE } -> ProductionPipelineTracker.Result.FAILURE
        evts.any { it.result == ProductionPipelineTracker.Result.TIMEOUT } -> ProductionPipelineTracker.Result.TIMEOUT
        evts.any { it.result == ProductionPipelineTracker.Result.SUCCESS } -> ProductionPipelineTracker.Result.SUCCESS
        evts.any { it.result == ProductionPipelineTracker.Result.FALLBACK } -> ProductionPipelineTracker.Result.FALLBACK
        else -> ProductionPipelineTracker.Result.SKIPPED
    }
}

/** ترويسة مجموعة أحداث تشغيلة واحدة. */
@Composable
private fun RunHeaderCard(runId: String, runEvents: List<ProductionPipelineTracker.PipelineEvent>) {
    val sorted = runEvents.sortedBy { it.timestamp }
    val durationMs = sorted.lastOrNull()?.let { it.timestamp - sorted.first().timestamp } ?: 0L
    val success = runEvents.count { it.result == ProductionPipelineTracker.Result.SUCCESS }
    val failure = runEvents.count { it.result == ProductionPipelineTracker.Result.FAILURE }
    val label = if (runId.isBlank()) "خطوات غير مصنفة" else "تشغيلة #${runId.takeLast(6)}"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF111827),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            if (failure > 0) Color(0xFFEF4444).copy(alpha = 0.4f) else GoldPrimary.copy(alpha = 0.25f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = if (failure > 0) Color(0xFFEF4444) else GoldPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = CairoFont
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✅ $success", color = Color(0xFF10B981), fontSize = 9.sp, fontFamily = NotoSansFont)
                Spacer(modifier = Modifier.width(6.dp))
                if (failure > 0) {
                    Text("❌ $failure", color = Color(0xFFEF4444), fontSize = 9.sp, fontFamily = NotoSansFont)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    ProductionPipelineTracker.formatTimestamp(sorted.firstOrNull()?.timestamp ?: 0),
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontFamily = NotoSansFont
                )
            }
        }
    }
}

/** بطاقة حدث واحد في السجل الحي. */
@Composable
private fun EventCard(event: ProductionPipelineTracker.PipelineEvent) {
    val resultColor = Color(ProductionPipelineTracker.resultColor(event.result))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1320)),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, resultColor.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        when (event.result) {
                            ProductionPipelineTracker.Result.SUCCESS -> Icons.Default.CheckCircle
                            ProductionPipelineTracker.Result.FAILURE -> Icons.Default.Cancel
                            ProductionPipelineTracker.Result.FALLBACK -> Icons.Default.WarningAmber
                            ProductionPipelineTracker.Result.SKIPPED -> Icons.Default.SkipNext
                            ProductionPipelineTracker.Result.TIMEOUT -> Icons.Default.Timer
                        },
                        contentDescription = null,
                        tint = resultColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        event.stage.label,
                        color = resultColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = CairoFont
                    )
                    if (event.sceneIndex >= 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = GoldPrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "مشهد ${event.sceneIndex + 1}",
                                color = GoldSecondary,
                                fontSize = 8.sp,
                                fontFamily = CairoFont,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.durationMs > 0) {
                        Text(
                            formatDuration(event.durationMs),
                            color = Color(0xFF22D3EE),
                            fontSize = 9.sp,
                            fontFamily = NotoSansFont,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        ProductionPipelineTracker.formatTimestamp(event.timestamp),
                        color = Color.Gray,
                        fontSize = 9.sp,
                        fontFamily = NotoSansFont
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                event.message,
                color = TextPrimary,
                fontSize = 11.sp,
                fontFamily = CairoFont,
                lineHeight = 16.sp
            )
            if (event.detail.isNotBlank()) {
                Text(
                    event.detail,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = NotoSansFont,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun SummaryMiniCard(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.height(3.dp))
            Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = NotoSansFont)
            Text(label, color = color.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = CairoFont)
        }
    }
}

private fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${"%.1f".format(ms / 1000f)}ث"
        ms < 3600_000 -> "${ms / 60000}د ${ms % 60000 / 1000}ث"
        else -> "${ms / 3600000}س ${(ms % 3600000) / 60000}د"
    }
}
