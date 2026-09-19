package com.qabas.app

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import android.util.Log
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import kotlinx.coroutines.delay

enum class VideoQualityOption(
    val title: String,
    val subtitle: String,
    val resolution: String,
    val fps: String,
    val bitrate: String,
    val preset: ExportQualityPreset
) {
    FAST_720P("720p سريع (سوشيال)", "اقتصادي وسريع للتيك توك والستوري", "720x1280", "30 FPS", "4 Mbps", ExportQualityPreset.FAST),
    PRO_1080P("1080p 60fps المعيار الذهبي", "الموصى به لـ Instagram Reels & YouTube Shorts", "1080x1920", "60 FPS", "12 Mbps", ExportQualityPreset.HIGH),
    CINEMATIC_4K("4K Master فائقة الدقة", "أعلى نقاء للأرشفة وشاشات التلفاز", "2160x3840", "60 FPS", "28 Mbps", ExportQualityPreset.HIGH)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveShareScreen(
    scriptText: String,
    videoDuration: String,
    selectedRatio: String,
    scenes: List<Scene>,
    ambientSound: String,
    videoStyleAnalysis: VideoStyleAnalysis? = null,
    onBackToHome: () -> Unit,
    onBackToEditor: () -> Unit,
    onStartSeries: () -> Unit
) {
    val analyticsContext = LocalContext.current
    LaunchedEffect(Unit) { AppServices.getAnalyticsService(analyticsContext).logScreenView("CinematicExport") }

    var selectedQualityOption by remember {
        // الجودة الافتراضية من الإعدادات — أخيراً تُحترم بدل التثبيت على 1080p
        val saved = analyticsContext.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            .getString("defaultQuality", "1080p") ?: "1080p"
        mutableStateOf(
            when (saved) {
                "720p" -> VideoQualityOption.FAST_720P
                "4k" -> VideoQualityOption.CINEMATIC_4K
                else -> VideoQualityOption.PRO_1080P
            }
        )
    }
    var renderProgress by remember { mutableFloatStateOf(0f) }
    var currentLog by remember { mutableStateOf(Translator.tr("تهيئة محرك الريندر Qabas Engine v2.4...")) }
    val logs = remember { mutableStateListOf<String>() }
    var isRenderComplete by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var exportedFile by remember { mutableStateOf<File?>(null) }
    var customSeoTitle by remember { mutableStateOf(scriptText) }
    var activeHashtags by remember { mutableStateOf(SharePack.HASHTAGS_3) }
    var showDirectPublisher by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var showThumbnailGenerator by remember { mutableStateOf(false) }
    var showAutoSeriesGenerator by remember { mutableStateOf(false) }
    var showViralSeoDialog by remember { mutableStateOf(false) }

    val goldGradient = Brush.horizontalGradient(colors = listOf(GoldSecondary, GoldPrimary))

    // Pulse animation for radar
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseGlow"
    )

    LaunchedEffect(selectedQualityOption, retryTrigger) {
        logs.clear()
        val firstMedia = scenes.firstOrNull()?.mediaUrl.orEmpty()
        val alreadyRenderedFile = if (firstMedia.isNotBlank() && !firstMedia.startsWith("http")) {
            File(firstMedia).takeIf { it.exists() && VideoProcessor.isValidVideoFile(it.absolutePath, VideoProcessor.MIN_SCENE_SIZE) }
        } else null

        if (alreadyRenderedFile != null && retryTrigger == 0 && selectedQualityOption.title.contains("1080p")) {
            // Already compiled in ProcessingScreen — reuse immediately!
            exportedFile = alreadyRenderedFile
            logs.add(Translator.tr("[INFO] تم العثور على الفيديو المجهز مسبقاً من الاستوديو: ${alreadyRenderedFile.name}"))
            logs.add(Translator.tr("[SUCCESS] تم تحميل الفيديو بنجاح (${alreadyRenderedFile.length() / 1024} KB)"))
            currentLog = Translator.tr("الفيديو جاهز للمشاركة والحفظ!")
            renderProgress = 1.0f
            isRenderComplete = true
            exportFailed = false
            PointsManager.verifyAndAwardChallenge(analyticsContext, scriptText)
            return@LaunchedEffect
        }

        isRenderComplete = false
        exportFailed = false
        renderProgress = 0.05f
        val engine = VideoEngineManager(analyticsContext)
        VideoProcessor.currentQualityPreset = selectedQualityOption.preset
        
        logs.add(Translator.tr("[INFO] بدء تشغيل Qabas Engine (${selectedQualityOption.resolution} @ ${selectedQualityOption.fps})..."))
        if (videoStyleAnalysis != null) {
            logs.add(Translator.tr("[STYLE] تم تفعيل الخلطة المستنسخة: ${videoStyleAnalysis.dominantColors} | ${videoStyleAnalysis.transitionSpeed}"))
        }
        
        val resultFile = try {
            engine.processProject(
                scenes = scenes,
                videoQuality = selectedQualityOption.preset.name,
                ambientSound = ambientSound,
                styleAnalysis = videoStyleAnalysis,
                onProgress = { progress, message ->
                    renderProgress = progress
                    currentLog = message
                    if (progress == 1.0f) {
                        logs.add(Translator.tr("[SUCCESS] ") + message)
                    } else if ((progress * 100).toInt() % 20 == 0) {
                        if (logs.lastOrNull() != "[INFO] $message") {
                            logs.add("[INFO] $message")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("SaveShareScreen", "Error during engine process", e)
            null
        }
        
        if (resultFile != null && resultFile.exists() && resultFile.length() > 0) {
            exportedFile = resultFile
            logs.add(Translator.tr("[SUCCESS] تم تصدير الفيديو بنجاح: ${resultFile.name} (${resultFile.length() / 1024} KB)"))
            currentLog = Translator.tr("اكتمل التصدير بنجاح!")
            isRenderComplete = true
            exportFailed = false
            PointsManager.verifyAndAwardChallenge(analyticsContext, scriptText)
        } else {
            exportedFile = null
            isRenderComplete = false
            exportFailed = true
            logs.add(Translator.tr("[ERROR] فشل التصدير عبر FFmpeg: لم يتم إنتاج الفيديو."))
            currentLog = Translator.tr("حدث خطأ أثناء تصدير الفيديو")
        }
    }

    Scaffold(
        containerColor = Color(0xFF070B14),
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Translator.tr("محرك التصدير السينمائي"),
                            color = GoldPrimary,
                            fontWeight = FontWeight.Bold,
                            fontFamily = CairoFont,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = GoldPrimary,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                "ULTRA HD",
                                color = DeepSlate,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = RobotoMonoFont,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackToEditor) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Translator.tr("العودة"), tint = GoldPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B1120))
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(
                targetState = isRenderComplete,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(450, easing = EaseOutCubic)) + scaleIn(initialScale = 0.94f, animationSpec = tween(450, easing = EaseOutCubic)) + slideInVertically(initialOffsetY = { 30 }, animationSpec = tween(450)))
                        .togetherWith(fadeOut(animationSpec = tween(200, easing = EaseInCubic)) + scaleOut(targetScale = 0.96f, animationSpec = tween(200)))
                },
                label = "ExportStateCrossfade",
                modifier = Modifier.fillMaxWidth()
            ) { complete ->
                if (!complete) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Quality Preset Selector Pills
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("اختر جودة التصدير المطلوبة:", color = GoldPrimary, fontSize = 12.sp, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    VideoQualityOption.values().forEach { opt ->
                                        val isSelected = selectedQualityOption == opt
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) GoldPrimary else Color(0xFF151B2B),
                                            border = BorderStroke(1.dp, if (isSelected) GoldPrimary else Color(0xFF1E293B)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { selectedQualityOption = opt }
                                        ) {
                                            Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    opt.resolution,
                                                    color = if (isSelected) DeepSlate else Color.White,
                                                    fontSize = 11.sp,
                                                    fontFamily = RobotoMonoFont,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    opt.fps,
                                                    color = if (isSelected) DeepSlate.copy(alpha = 0.8f) else Color.Gray,
                                                    fontSize = 9.sp,
                                                    fontFamily = RobotoMonoFont
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Circular Progress Indicator (Radar style with pulse glow)
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawArc(
                                    color = Color(0xFF151B2B),
                                    startAngle = 140f,
                                    sweepAngle = 260f,
                                    useCenter = false,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                                )
                                drawArc(
                                    brush = goldGradient,
                                    startAngle = 140f,
                                    sweepAngle = 260f * renderProgress,
                                    useCenter = false,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${(renderProgress * 100).toInt()}%", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                                Text("RENDERING", color = GoldPrimary, fontSize = 12.sp, letterSpacing = 2.sp, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Render Terminal / Monitor Console
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(Color(0xFF0B1120), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF151B2B), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Terminal, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Qabas FFmpeg Processing Console", color = GoldPrimary, fontSize = 12.sp, fontFamily = NotoSansFont)
                                    }
                                    Text(selectedQualityOption.bitrate, color = Color.Gray, fontSize = 12.sp, fontFamily = NotoSansFont)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                
                                LazyColumn(modifier = Modifier.weight(1f)) {
                                    items(logs.size) { i ->
                                        val line = logs[i]
                                        val lineColor = when {
                                            line.startsWith("[SUCCESS]") -> Color(0xFF34D399)
                                            line.startsWith("[STYLE]") -> GoldPrimary
                                            else -> Color(0xFF94A3B8)
                                        }
                                        Text(line, color = lineColor, fontSize = 12.sp, fontFamily = NotoSansFont)
                                    }
                                }
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = GoldPrimary, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(currentLog, color = Color.White, fontSize = 12.sp, fontFamily = NotoSansFont)
                                }
                            }
                        }
                    }
                } else if (exportFailed) {
                    // Export Failed Alert Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1216)),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.5.dp, Color(0xFFEF4444).copy(alpha = 0.8f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFFEF4444).copy(alpha = 0.2f))
                                    .border(1.2.dp, Color(0xFFEF4444), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(32.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("تعذر إكمال التصدير السينمائي", color = Color.White, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("تأكد من توفر وسائط المشاهد أو جرب جودة 720p السريعة", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp, textAlign = TextAlign.Center)
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onBackToEditor,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, Color.Gray)
                                ) {
                                    Text("تعديل المشاهد", color = Color.LightGray, fontFamily = CairoFont, fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { retryTrigger++ },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("إعادة المحاولة", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    // Post-Export Success Hero Card (Luxury Gold Aesthetic & Interactive Preview)
                    var isPreviewPlaying by remember { mutableStateOf(false) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.5.dp, GoldPrimary.copy(alpha = 0.85f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(GoldPrimary.copy(alpha = 0.2f))
                                    .border(1.2.dp, GoldPrimary, RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(32.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("تم تصدير الفيديو بنجاح! 🎬✨", color = Color.White, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("مشروعك جاهز بجودة ${selectedQualityOption.resolution} للأثر والنشر المبارك", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                            
                            Spacer(modifier = Modifier.height(10.dp))

                            // Interactive Live Video Preview Box (9:16 Frame Preview)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF050B14))
                                    .border(1.dp, GoldPrimary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .clickable { isPreviewPlaying = !isPreviewPlaying },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isPreviewPlaying && exportedFile != null && exportedFile!!.exists()) {
                                    androidx.compose.ui.viewinterop.AndroidView(
                                        factory = { ctx ->
                                            android.widget.VideoView(ctx).apply {
                                                setVideoPath(exportedFile!!.absolutePath)
                                                setOnPreparedListener { mp ->
                                                    mp.isLooping = true
                                                    start()
                                                }
                                                setOnErrorListener { _, _, _ ->
                                                    Toast.makeText(ctx, "تعذر تشغيل المعاينة", Toast.LENGTH_SHORT).show()
                                                    true
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawCircle(
                                            color = GoldPrimary.copy(alpha = 0.08f),
                                            radius = size.minDimension / 2.2f,
                                            center = center
                                        )
                                    }

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(10.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(24.dp),
                                            color = GoldPrimary,
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.PlayArrow,
                                                    contentDescription = "تشغيل المعاينة",
                                                    tint = DeepSlate,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "اضغط لمشاهدة الفيديو النهائي والاستماع للصوت ▶",
                                            color = GoldPrimary,
                                            fontFamily = CairoFont,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Bottom tag
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("9:16 ${selectedQualityOption.resolution}", color = GoldPrimary, fontSize = 11.sp, fontFamily = NotoSansFont)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF151B2B), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                ExportStat(Translator.tr("الدقة"), selectedQualityOption.resolution)
                                ExportStat(Translator.tr("الإطارات"), selectedQualityOption.fps)
                                ExportStat(Translator.tr("المدة"), videoDuration)
                                ExportStat(Translator.tr("منطقة الأمان"), "9:16 Safe ✨")
                            }
                        }
                    }
                }
            }

            // Developer Watermark Badge if active
            val accountService = remember { AppServices.getAccountService(analyticsContext) }
            if (accountService.isDeveloperOrAdmin && accountService.isDevWatermarkEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "يتضمن الختم الذهبي للمطور: ${accountService.devWatermarkText}",
                            color = GoldPrimary,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            AnimatedContent(
                targetState = isRenderComplete,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(400, easing = EaseOutCubic)) + slideInVertically(initialOffsetY = { 40 }, animationSpec = tween(400)))
                        .togetherWith(fadeOut(animationSpec = tween(200, easing = EaseInCubic)))
                },
                label = "ExportActionsCrossfade",
                modifier = Modifier.fillMaxWidth()
            ) { complete ->
                if (complete) {
                    val clipboardManager = LocalClipboardManager.current
                    var isSavedToGallery by remember { mutableStateOf(false) }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 1. One-Tap Native Social Share Badges
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF151B2B),
                                border = BorderStroke(0.8.dp, GoldPrimary.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        SocialAccountManager.shareVideoNatively(
                                            analyticsContext,
                                            exportedFile,
                                            "$customSeoTitle\n\n$activeHashtags\n#ريلز #شورتس #قبس ✨"
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Reels 📸", color = GoldPrimary, fontSize = 12.sp, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF151B2B),
                                border = BorderStroke(0.8.dp, GoldPrimary.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        SocialAccountManager.shareVideoNatively(
                                            analyticsContext,
                                            exportedFile,
                                            "$customSeoTitle\n\n$activeHashtags\n#تيك_توك #قبس #دعوة"
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("TikTok 🎵", color = GoldPrimary, fontSize = 12.sp, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF151B2B),
                                border = BorderStroke(0.8.dp, GoldPrimary.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        SocialAccountManager.shareVideoNatively(
                                            analyticsContext,
                                            exportedFile,
                                            "$customSeoTitle\n\n$activeHashtags\n#Shorts #YouTube #Qabas"
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Shorts ▶️", color = GoldPrimary, fontSize = 12.sp, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // 2. Primary Action Row: Save to Device + General Native Share
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val saved = SocialAccountManager.saveVideoToGallery(analyticsContext, exportedFile)
                                    if (saved) {
                                        isSavedToGallery = true
                                        android.widget.Toast.makeText(analyticsContext, "تم حفظ الفيديو بنجاح في ألبوم الكاميرا والمعرض 📥✨", android.widget.Toast.LENGTH_LONG).show()
                                    } else {
                                        android.widget.Toast.makeText(analyticsContext, "جاري تجهيز وحفظ الملف بالجهاز 📱", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .bouncingClickable { },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSavedToGallery) Color(0xFF065F46) else Color(0xFF151B2B)
                                ),
                                border = BorderStroke(1.dp, if (isSavedToGallery) Color(0xFF10B981) else Color(0xFF1E293B)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    if (isSavedToGallery) Icons.Default.CheckCircle else Icons.Default.Download,
                                    contentDescription = null,
                                    tint = if (isSavedToGallery) Color(0xFF34D399) else GoldPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (isSavedToGallery) "محفوظ بالمعرض ✓" else "حفظ في المعرض 📥",
                                    color = Color.White,
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            Button(
                                onClick = {
                                    SocialAccountManager.shareVideoNatively(
                                        analyticsContext,
                                        exportedFile,
                                        "$customSeoTitle\n\n$activeHashtags\n#أثر_لا_ينقطع ✨"
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .bouncingClickable { },
                                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("مشاركة فورية 🚀", color = DeepSlate, fontWeight = FontWeight.Bold, fontFamily = NotoSansFont, fontSize = 12.sp)
                            }
                        }

                        // 3. Secondary Studio Actions: Copy Hashtags + Thumbnail Generator + Edit Again
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(activeHashtags))
                                    android.widget.Toast.makeText(analyticsContext, "تم نسخ الهاشتاغات الفيروسية للحافظة 📋✨", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF0B0F19))
                            ) {
                                Icon(Icons.Default.Tag, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("الهاشتاغات 📋", color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { showThumbnailGenerator = true },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF0B0F19))
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("صورة مصغرة 🖼️", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = onBackToEditor,
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF0B0F19))
                            ) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("تعديل 🎨", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                            }
                        }

                        // 4. Growth & Multi-Publish Bar
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                onClick = { showDirectPublisher = true },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF151B2B).copy(alpha = 0.8f),
                                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Language, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("نشر متعدد 🌐", color = Color.LightGray, fontFamily = NotoSansFont, fontSize = 12.sp)
                                }
                            }

                            Surface(
                                onClick = { showScheduleDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF151B2B).copy(alpha = 0.8f),
                                border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.6f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Alarm, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("جدولة ذكية ⏰", color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Surface(
                                onClick = { showViralSeoDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF151B2B).copy(alpha = 0.8f),
                                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.TrendingUp, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("الـ SEO 📈", color = Color.LightGray, fontFamily = NotoSansFont, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Button(
                            onClick = onBackToHome,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Home, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(Translator.tr("العودة للرئيسية"), color = Color.LightGray, fontFamily = NotoSansFont, fontSize = 12.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = onBackToEditor,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF151B2B)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(Translator.tr("إلغاء والعودة للتعديل"), color = Color.Gray, fontFamily = CairoFont)
                    }
                }
            }
        }
    }

    if (showDirectPublisher) {
        DirectPublisherDialog(
            scriptText = scriptText,
            videoFile = exportedFile,
            onDismiss = { showDirectPublisher = false }
        )
    }

    if (showScheduleDialog) {
        SmartPublishScheduleDialog(
            initialTitle = if (scriptText.isNotBlank()) scriptText.take(50) else "فيديو دعوي جديد عبر تطبيق قبس",
            initialHashtags = "#قبس #ريلز_إسلامي #تدبر #أثر_لا_ينقطع",
            onDismiss = { showScheduleDialog = false }
        )
    }

    if (showThumbnailGenerator) {
        AutoThumbnailGeneratorDialog(
            context = analyticsContext,
            initialTitle = if (scriptText.isNotBlank()) scriptText.take(50) else "تدبر آية ورسالة إيمانية",
            onDismiss = { showThumbnailGenerator = false }
        )
    }

    if (showAutoSeriesGenerator) {
        AutoSeriesGeneratorDialog(
            context = analyticsContext,
            initialSeriesTitle = if (scriptText.isNotBlank()) "سلسلة: " + scriptText.take(30) else "سلسلة صحابة رسول الله ﷺ",
            onDismiss = { showAutoSeriesGenerator = false },
            onExportSeriesToProjects = {
                showAutoSeriesGenerator = false
                onStartSeries()
            }
        )
    }

    if (showViralSeoDialog) {
        ViralSeoHashtagsDialog(
            context = analyticsContext,
            initialTopicOrScript = scriptText,
            onDismiss = { showViralSeoDialog = false },
            onApplySeoData = { title, hashtags ->
                if (title.isNotBlank()) customSeoTitle = title
                if (hashtags.isNotBlank()) activeHashtags = hashtags
                showViralSeoDialog = false
                Toast.makeText(analyticsContext, "تم تطبيق عنوان وهاشتاغات الـ SEO الذكية بنجاح! 📈✨", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun ExportStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 12.sp, fontFamily = NotoSansFont)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = NotoSansFont)
    }
}

