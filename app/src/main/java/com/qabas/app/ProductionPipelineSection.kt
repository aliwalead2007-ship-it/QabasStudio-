package com.qabas.app

import androidx.compose.animation.core.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.isActive

/**
 * قسم «مسار الإنتاج» في لوحة المطور — رصد احترافي حي لكل خطوة في مسار الإنتاج.
 *
 * المزايا:
 * - التقاط مباشر: يتحدث تلقائياً كل ثانيتين فتظهر الخطوات لحظة حدوثها أثناء الإنتاج
 * - بطاقة «آخر تشغيلة» مع شريط تدفق مرئي يوضح حالة كل مرحلة (تمت/فشلت/بديلة/معلقة)
 * - إحصاءات لكل مرحلة: معدل النجاح + متوسط المدة + عدد المحاولات
 * - سجل زمني مُجمّع حسب التشغيلة مع مدة كل خطوة
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionPipelineSection(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
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

    var selectedStage by remember { mutableStateOf<ProductionPipelineTracker.Stage?>(null) }
    val filteredEvents = if (selectedStage != null) events.value.filter { it.stage == selectedStage } else events.value

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
                        "مسار الإنتاج",
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

            // ── بطاقات الملخص العام ──
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
                    "أداء المراحل:",
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "$rate% ($total)",
                            color = when {
                                rate >= 80 -> Color(0xFF10B981)
                                rate >= 50 -> Color(0xFFF59E0B)
                                else -> Color(0xFFEF4444)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = NotoSansFont
                        )
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
                        if (selectedStage != null) "سجل «${selectedStage!!.label}»:" else "السجل الحي (كل التشغيلات):",
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
                                        .background(if (i > 0) stageColor.copy(alpha = 0.35f) else Color.Transparent)
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = stageColor.copy(alpha = 0.18f),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selectedStage == stage) 2.dp else 1.dp,
                                    stageColor
                                )
                            ) {
                                Text(
                                    "${i + 1}",
                                    color = if (status == null) Color(0xFF64748B) else stageColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = NotoSansFont,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stage.label,
                            color = if (status == null) Color(0xFF475569) else stageColor,
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
