package com.qabas.app

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.qabas.app.ui.theme.*

/**
 * ProcessingScreen — تقدم حقيقي + سجل نشاط حي + واجهة قابلة للتمرير.
 * التقدم من مراحل حقيقية فقط. بدون رفع وهمي للنسبة أثناء FFmpeg.
 */
@Composable
fun ProcessingScreen(
    inputText: String,
    styleDescription: String,
    contentType: String = "",
    contentTone: String = "",
    videoDuration: String = "",
    selectedTemplate: String,
    videoQuality: String,
    ambientSound: String,
    videoStyleAnalysis: VideoStyleAnalysis? = null,
    existingScenes: List<Scene>? = null,
    onProcessingComplete: (List<Scene>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val pipelineRunId = remember { java.util.UUID.randomUUID().toString() }
    val prefs = remember(context) {
        context.getSharedPreferences("qabas_prefs", android.content.Context.MODE_PRIVATE)
    }

    LaunchedEffect(Unit) {
        try { AppServices.getAnalyticsService(context).logScreenView("Processing") } catch (_: Exception) {}
    }

    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf(Translator.tr("جاري التحضير...")) }
    var scenesProcessed by remember { mutableIntStateOf(0) }
    var totalScenes by remember { mutableIntStateOf(0) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var chosenStyleMessage by remember { mutableStateOf<String?>(null) }
    var styleTraitsSummary by remember { mutableStateOf<String?>(null) }
    var currentStage by remember { mutableStateOf("prepare") }
    var activityLog by remember { mutableStateOf(listOf<String>()) }
    var isFailed by remember { mutableStateOf(false) }

    fun pushActivity(msg: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        activityLog = (listOf("[$stamp] $msg") + activityLog).take(14)
    }

    val notificationService = remember { ProgressNotificationService(context) }
    val processingJobRef = remember { mutableStateOf<Job?>(null) }
    val elapsedJobRef = remember { mutableStateOf<Job?>(null) }

    var showCancelDialog by remember { mutableStateOf(false) }
    var finalScenes by remember { mutableStateOf<List<Scene>>(emptyList()) }
    val liveEngine by SceneEngineMonitor.engine.collectAsState()

    val doCancel = remember(onCancel) {
        {
            try { elapsedJobRef.value?.cancel(); processingJobRef.value?.cancel() } catch (_: Exception) {}
            try { VideoProcessor.cancelAll() } catch (_: Exception) {}
            try { notificationService.hideProgressNotification() } catch (_: Exception) {}
            onCancel()
        }
    }

    // زر الرجوع أثناء المعالجة → تأكيد بدلاً من الخروج المباشر
    BackHandler(enabled = progress < 1f) {
        showCancelDialog = true
    }

    // إبقاء الشاشة مضاءة أثناء المعالجة الطويلة
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try { elapsedJobRef.value?.cancel(); processingJobRef.value?.cancel() } catch (_: Exception) {}
        }
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            try { permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) {}
        }
    }

    val templateInfo = remember(selectedTemplate) {
        AppServices.templates.find { it.name == selectedTemplate } ?: AppServices.templates.first()
    }

    LaunchedEffect(Unit) {
        processingJobRef.value = coroutineContext[Job]
        elapsedJobRef.value = launch {
            try {
                while (isActive) { kotlinx.coroutines.delay(1000); elapsedSeconds += 1 }
            } catch (_: CancellationException) {}
        }

        try {
            SceneEngineMonitor.reset()
            // فحص مبكر: فشل رخيص في ثوانٍ بدل دقائق ترميز
            val preflightError = ProductionPowerKit.preflight(context, inputText)
            if (preflightError != null) {
                isFailed = true
                statusText = preflightError
                pushActivity("فحص مبكر: $preflightError")
                notificationService.showErrorNotification()
                try { elapsedJobRef.value?.cancel() } catch (_: Exception) {}
                return@LaunchedEffect
            }
            val pipelineStartMs = System.currentTimeMillis()
            ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.PIPELINE_START, ProductionPipelineTracker.Result.SUCCESS, "بدء تشغيلة إنتاج جديدة", inputText.take(80), 0, pipelineRunId)
            pushActivity("بدء مسار الإنتاج — تهيئة العقل")
            notificationService.showProgressNotification(5, 100, Translator.tr("جاري تحضير السيناريو..."))
            currentStage = "prepare"
            statusText = when {
                existingScenes != null -> Translator.tr("جاري تحضير التعديلات...")
                styleDescription.isNotEmpty() -> Translator.tr("جاري كتابة السيناريو بأسلوب مخصص...")
                else -> Translator.tr("جاري كتابة السيناريو...")
            }
            progress = 0.08f

            val durationSecs = videoDuration.filter { it.isDigit() }.toIntOrNull() ?: if (contentType.contains("Reels") || contentType.contains("Shorts") || contentType.contains("ريلز") || contentType.contains("قصير")) 15 else 30
            val duration = durationSecs
            val directStyle = videoStyleAnalysis?.let { a ->
                val populatedAnalysisFields = listOf(
                    a.detectedStyle,
                    a.dominantColors,
                    a.transitionSpeed,
                    a.movementPatterns,
                    a.overallRhythm,
                    a.audioStyle,
                    a.typographyStyle,
                    a.contentTone,
                    a.targetAudience
                ).count { it.isNotBlank() }
                val analysisScore = (populatedAnalysisFields * 100 / 9).coerceIn(0, 100)
                AbsorbedStyle(
                    id = "direct_applied",
                    name = a.detectedStyle.ifEmpty { "الأسلوب المستنسخ المباشر" },
                    sourceVideoPathOrUrl = "Direct",
                    analysis = "${a.dominantColors} | ${a.transitionSpeed} | ${a.typographyStyle}",
                    visualTraits = listOf(a.dominantColors.ifEmpty { "ألوان داكنة DeepSlate بهوية AI بنفسجية #8B5CF6 وإكسنت سيان #22D3EE" }),
                    motionTraits = listOf(
                        a.transitionSpeed.ifEmpty { "سريعة ومتوازنة" },
                        a.movementPatterns.ifEmpty { "حركة كاميرا زووم بطيء ناعم" }
                    ),
                    textTraits = listOf(a.typographyStyle.ifEmpty { "خط عربي عريض في المنتصف" }),
                    overallScore = analysisScore
                )
            }

            pushActivity("StyleBrain: اختيار أفضل أسلوب...")
            val styleStartMs = System.currentTimeMillis()
            val bestStyle = try {
                directStyle ?: StyleBrain.chooseBestStyleForIdea(inputText, duration, contentTone, "الجمهور العام")
            } catch (e: Exception) {
                Log.w("ProcessingScreen", "StyleBrain choose failed", e)
                pushActivity("StyleBrain: فشل — استخدام الافتراضي")
                ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.STYLE_SELECTION, ProductionPipelineTracker.Result.FALLBACK, "StyleBrain فشل — استخدام الافتراضي", e.message ?: "", System.currentTimeMillis() - styleStartMs, pipelineRunId)
                directStyle
            }
            val effectiveStyle = bestStyle ?: StyleBrain.getDefaultStyle()
            ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.STYLE_SELECTION, if (bestStyle != null) ProductionPipelineTracker.Result.SUCCESS else ProductionPipelineTracker.Result.FALLBACK, if (bestStyle != null) "أسلوب مختار: ${bestStyle.name}" else "الأسلوب الافتراضي (العقل فارغ)", "", System.currentTimeMillis() - styleStartMs, pipelineRunId)
            val effectiveStyleAnalysisFromStyle = videoStyleAnalysis ?: effectiveStyle.toVideoStyleAnalysis()

            val effectiveColors = if (effectiveStyle.visualTraits.isNotEmpty()) {
                effectiveStyle.visualTraits.joinToString("، ")
            } else {
                templateInfo.colors.ifBlank { "DeepSlate #0B0F19 مع هوية AI بنفسجية #8B5CF6 وإكسنت سيان #22D3EE" }
            }
            val effectiveTransition = effectiveStyle.resolveTransitionType()
            val effectiveTextAnim = effectiveStyle.resolveTextAnimation()
            val effectiveTempo = effectiveStyle.motionTraits.firstOrNull()
                ?: templateInfo.parameters["tempo"] ?: "متوسط"

            val finalStyleDescription = if (bestStyle != null) {
                chosenStyleMessage = "تم تفعيل الأسلوب: ${bestStyle.name}"
                styleTraitsSummary =
                    "بصري: ${bestStyle.visualTraits.take(2).joinToString(" · ").ifBlank { "—" }} | حركة: ${bestStyle.motionTraits.take(2).joinToString(" · ").ifBlank { "—" }}"
                pushActivity("StyleBrain نشط: ${bestStyle.name} (${bestStyle.overallScore})")
                pushActivity("حركة: ${bestStyle.motionTraits.joinToString("، ").ifBlank { "محايد" }}")
                val visualDetails =
                    "السمات البصرية: ${bestStyle.visualTraits.joinToString()} | سمات الحركة: ${bestStyle.motionTraits.joinToString()} | سمات الكابشن: ${bestStyle.textTraits.joinToString()}"
                if (styleDescription.isNotBlank()) "$styleDescription | $visualDetails"
                else "تطبيق الخلطة الإخراجية: $visualDetails"
            } else {
                chosenStyleMessage = "حالة محايدة (العقل فارغ)"
                styleTraitsSummary = "لا يوجد أسلوب ممتص — أضف فيديو مرجعي أولاً"
                pushActivity("StyleBrain: حالة محايدة، بانتظار فيديوهات مرجعية")
                styleDescription.ifBlank { "توليد محايد أساسي. أضف فيديوهات مرجعية لتكوين أسلوب الاستوديو." }
            }

            SystemLogsManager.addLog(
                "STYLE_BRAIN",
                "تطبيق سمات: ألوان [$effectiveColors] | انتقال [$effectiveTransition] | نص [$effectiveTextAnim]",
                Color(0xFFE8C547)
            )

            if (!isActive) return@LaunchedEffect

            currentStage = "script"
            statusText = Translator.tr("جاري بناء خطة المخرج والمشاهد...")
            progress = 0.15f
            notificationService.showProgressNotification(15, 100, statusText)
            pushActivity("MontageDirector: بناء خطة المشاهد...")

            val rawScenes = existingScenes ?: withContext(Dispatchers.IO) {
                val scriptStartMs = System.currentTimeMillis()
                try {
                    val directed = MontageDirector.directToScenes(
                        idea = inputText,
                        tone = contentTone.ifBlank { "خاشع" },
                        targetDurationSec = duration,
                        audience = "الجمهور العام",
                        styleDescription = finalStyleDescription,
                        preferredStyleId = bestStyle?.id
                    )
                    if (directed.isNotEmpty()) {
                        SystemLogsManager.addLog("DIRECTOR", "خطة المخرج: ${directed.size} مشاهد", Color(0xFF4CAF50))
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.SCRIPT_GENERATION, ProductionPipelineTracker.Result.SUCCESS, "خطة المخرج: ${directed.size} مشاهد", "", System.currentTimeMillis() - scriptStartMs, pipelineRunId)
                        directed
                    } else {
                        val fallbackScenes = AppServices.generateScript(inputText, finalStyleDescription, contentType, contentTone)
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.SCRIPT_GENERATION, ProductionPipelineTracker.Result.FALLBACK, "Gemini بديل: ${fallbackScenes.size} مشاهد", "MontageDirector أرجع فارغ", System.currentTimeMillis() - scriptStartMs, pipelineRunId)
                        fallbackScenes
                    }
                } catch (dirEx: Exception) {
                    if (dirEx is CancellationException) throw dirEx
                    Log.w("ProcessingScreen", "MontageDirector failed", dirEx)
                    val fallbackScenes = AppServices.generateScript(inputText, finalStyleDescription, contentType, contentTone)
                    ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.SCRIPT_GENERATION, ProductionPipelineTracker.Result.FALLBACK, "Gemini بديل بعد فشل Director: ${fallbackScenes.size} مشاهد", dirEx.message ?: "", System.currentTimeMillis() - scriptStartMs, pipelineRunId)
                    fallbackScenes
                }
            }

            if (!isActive) return@LaunchedEffect

            val scenes = rawScenes.map { s ->
                s.copy(
                    transitionType = if (s.transitionType.isBlank() || s.transitionType == "Fade") effectiveTransition else s.transitionType,
                    tempo = if (s.tempo.isBlank() || s.tempo == "متوسط") effectiveTempo else s.tempo,
                    visualEffect = if (s.visualEffect.isBlank() || s.visualEffect == "cinematic") effectiveColors else s.visualEffect
                )
            }

            finalScenes = scenes
            totalScenes = scenes.size
            pushActivity("تم اعتماد ${scenes.size} مشاهد")
            currentStage = "scenes"
            statusText = Translator.tr("جاري معالجة $totalScenes مشاهد...")
            progress = 0.20f
            notificationService.showProgressNotification(20, 100, statusText)

            // حفظ المشاهد في prefs للاسترجاع عند إعادة التشغيل
            try {
                val scenesJson = org.json.JSONArray().apply {
                    scenes.forEach { s ->
                        put(org.json.JSONObject().apply {
                            put("title", s.title)
                            put("description", s.description)
                            put("durationInSeconds", s.durationInSeconds)
                            put("visualEffect", s.visualEffect)
                            put("tempo", s.tempo)
                            put("transitionType", s.transitionType)
                            put("mediaUrl", s.mediaUrl ?: "")
                        })
                    }
                }
                prefs.edit().putString("cached_scenes", scenesJson.toString())
                    .putLong("cached_scenes_time", System.currentTimeMillis()).apply()
            } catch (_: Exception) {}

            val isParallelEnabled = prefs.getBoolean("enable_parallel_processing", true)
            val deviceCores = Runtime.getRuntime().availableProcessors()
            val parallelism = when {
                deviceCores <= 4 -> 2
                deviceCores <= 6 -> 3
                else -> 4
            }

            suspend fun processOneScene(index: Int, scene: Scene): Scene {
                var finalMedia = scene.mediaUrl ?: ""
                val alreadyHasMedia = !scene.mediaUrl.isNullOrBlank() &&
                    (scene.mediaUrl!!.startsWith("http") || java.io.File(scene.mediaUrl!!).exists())
                if (!alreadyHasMedia) {
                    val sceneStartMs = System.currentTimeMillis()
                    try {
                        val media = AppServices.fetchMedia("${scene.title} ${scene.description}")
                        finalMedia = if (media.isNotEmpty()) {
                            ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.BROLL_FETCH, ProductionPipelineTracker.Result.SUCCESS, "مشهد ${index + 1}: جلب وسائط ناجح", "${scene.title} ${scene.description}".take(60), System.currentTimeMillis() - sceneStartMs, pipelineRunId, index)
                            media
                        } else {
                            val genVideo = AppServices.generateVideo(
                                sceneDescription = "${scene.title} ${scene.description}",
                                durationInSeconds = scene.durationInSeconds,
                                tempo = scene.tempo,
                                colors = effectiveColors,
                                transitions = effectiveTransition,
                                textAnim = effectiveTextAnim,
                                visualEffect = scene.visualEffect
                            )
                            ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.BROLL_FETCH, if (genVideo.isNotEmpty()) ProductionPipelineTracker.Result.FALLBACK else ProductionPipelineTracker.Result.FAILURE, "مشهد ${index + 1}: ${if (genVideo.isNotEmpty()) "توليد بديل" else "فشل جلب + توليد"}", "${scene.title} ${scene.description}".take(60), System.currentTimeMillis() - sceneStartMs, pipelineRunId, index)
                            genVideo
                        }
                    } catch (sceneEx: Exception) {
                        if (sceneEx is CancellationException) throw sceneEx
                        Log.e("ProcessingScreen", "Error scene $index", sceneEx)
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.BROLL_FETCH, ProductionPipelineTracker.Result.FAILURE, "مشهد ${index + 1}: خطأ في الجلب", sceneEx.message ?: "", System.currentTimeMillis() - sceneStartMs, pipelineRunId, index)
                    }
                }
                withContext(Dispatchers.Main) {
                    scenesProcessed++
                    statusText = Translator.tr("معالجة المشاهد: $scenesProcessed / $totalScenes")
                    pushActivity("مشهد $scenesProcessed/$totalScenes: ${scene.title.take(36).ifBlank { "—" }}")
                    val prog = 0.20f + (0.55f * (scenesProcessed.toFloat() / totalScenes.toFloat().coerceAtLeast(1f)))
                    progress = prog
                    notificationService.showProgressNotification((prog * 100).toInt(), 100, statusText)
                }
                return scene.copy(mediaUrl = if (scene.mediaUrl.isNullOrBlank()) finalMedia else scene.mediaUrl)
            }

            val processedScenes = if (isParallelEnabled) {
                val semaphore = kotlinx.coroutines.sync.Semaphore(parallelism)
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.coroutineScope {
                        scenes.mapIndexed { index, scene ->
                            async {
                                semaphore.withPermit {
                                    if (!isActive) scene else processOneScene(index, scene)
                                }
                            }
                        }.awaitAll()
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    scenes.mapIndexed { index, scene ->
                        if (!isActive) scene else processOneScene(index, scene)
                    }
                }
            }

            if (!isActive) return@LaunchedEffect

            currentStage = "assemble"
            statusText = Translator.tr("جاري تجميع المشاهد...")
            progress = 0.78f
            notificationService.showProgressNotification(78, 100, statusText)
            pushActivity("VideoEngine: بدء التجميع (FFmpeg)")

            if (ambientSound != Translator.tr("بدون") && ambientSound.isNotBlank()) {
                statusText = Translator.tr("دمج الصوت المحيطي: $ambientSound...")
                pushActivity("دمج صوت: $ambientSound")
            }

            statusText = Translator.tr("جاري الإنتاج النهائي عبر محرك الفيديو...")
            progress = 0.88f
            notificationService.showProgressNotification(88, 100, statusText)

            val isShortForm = contentType.contains("Reels", ignoreCase = true) ||
                contentType.contains("Shorts", ignoreCase = true) ||
                contentType.contains("ريلز", ignoreCase = true)

            // مهلة ديناميكية كريمة 180–420ث حسب عدد المشاهد — الترميز السينمائي 1080×1920
            // مع 4 مراحل FFmpeg لكل مشهد (قص/صوت/نص/تلوين) يحتاج زمناً حقيقياً لا افتراضياً
            val engineTimeoutMs = if (isShortForm) {
                (processedScenes.size * 45_000L + 90_000L).coerceIn(180_000L, 420_000L)
            } else {
                (processedScenes.size * 60_000L + 120_000L).coerceIn(180_000L, 420_000L)
            }

            var finalProducedFile: java.io.File? = null
            try {
                // نص حي فقط — بدون رفع وهمي للتقدم
                val livingStatusJob = launch {
                    val msgs = listOf(
                        Translator.tr("جاري تركيب الطبقات البصرية..."),
                        Translator.tr("جاري مزامنة الصوت مع المشاهد..."),
                        Translator.tr("جاري تطبيق الانتقالات والأسلوب..."),
                        Translator.tr("جاري الترميز النهائي للفيديو..."),
                        Translator.tr("المحرّك يعمل — يرجى الانتظار...")
                    )
                    var i = 0
                    while (isActive) {
                        kotlinx.coroutines.delay(4000)
                        if (!isActive) break
                        if (progress < 0.90f) statusText = msgs[i % msgs.size]
                        i++
                    }
                }

                val engineStartMs = System.currentTimeMillis()
                finalProducedFile = withTimeoutOrNull(engineTimeoutMs) {
                    withContext(Dispatchers.IO) {
                        VideoEngineManager(context).processProject(
                            scenes = processedScenes,
                            videoQuality = videoQuality,
                            ambientSound = ambientSound,
                            styleAnalysis = effectiveStyleAnalysisFromStyle,
                            runId = pipelineRunId,
                            onProgress = { p, msg ->
                                val overallProg = (0.88f + p * 0.10f).coerceIn(0.88f, 0.98f)
                                progress = overallProg
                                if (msg.isNotBlank()) {
                                    statusText = msg
                                    pushActivity("محرك: $msg")
                                }
                                notificationService.showProgressNotification((overallProg * 100).toInt(), 100, statusText)
                            }
                        )
                    }
                }
                livingStatusJob.cancel()
                val engineDurationMs = System.currentTimeMillis() - engineStartMs

                if (finalProducedFile != null && VideoProcessor.isValidVideoFile(finalProducedFile.absolutePath)) {
                    val isDraft = finalProducedFile.name.startsWith("Qabas_Draft_")
                    pushActivity(if (isDraft) "مسودة بلا B-Roll حقيقي: ${finalProducedFile.name}" else "تصدير ناجح: ${finalProducedFile.name}")
                    SystemLogsManager.addLog("SUCCESS", "تم إنتاج الفيديو (${finalProducedFile.name})", Color(0xFF4CAF50))
                    ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.EXPORT, ProductionPipelineTracker.Result.SUCCESS, "تصدير ناجح: ${finalProducedFile.name}", "${processedScenes.size} مشاهد | $videoQuality", engineDurationMs, pipelineRunId)
                    try { 
                        StyleBrain.recordProductionResult(context, effectiveStyle.id, success = true)
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.STYLE_FEEDBACK, ProductionPipelineTracker.Result.SUCCESS, "تغذية راجعة: نجاح الأسلوب ${effectiveStyle.id}", "", 0)
                        if (StyleBrain.isContinuousLearningEnabled.value) {
                            launch(Dispatchers.IO) {
                                StyleBrain.runContinuousLearningAnalysis(context)
                            }
                        }
                    } catch (_: Exception) {}
                } else {
                    isFailed = true
                    pushActivity("تحذير: التصدير لم يكتمل")
                    SystemLogsManager.addLog("ERROR", "فشل إنتاج الفيديو النهائي أو الملف غير موجود", Color(0xFFEF4444))
                    ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.EXPORT, ProductionPipelineTracker.Result.FAILURE, "فشل التصدير — ملف غير صالح أو غير موجود", finalProducedFile?.name ?: "لا ملف", engineDurationMs, pipelineRunId)
                    try { StyleBrain.recordProductionResult(context, effectiveStyle.id, success = false) } catch (_: Exception) {}
                    statusText = Translator.tr("تعذر إكمال التصدير — لا يوجد ملف فيديو صالح")
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                isFailed = true
                pushActivity("خطأ: ${e.localizedMessage ?: e.message}")
                SystemLogsManager.addLog("ERROR", "فشل التجميع: ${e.message}", Color(0xFFEF4444))
                val isTimeout = e is kotlinx.coroutines.TimeoutCancellationException
                ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.VIDEO_ENGINE, if (isTimeout) ProductionPipelineTracker.Result.TIMEOUT else ProductionPipelineTracker.Result.FAILURE, if (isTimeout) "مهلة المحرك انتهت" else "خطأ في محرك الفيديو", e.message ?: "", 0, pipelineRunId)
                statusText = Translator.tr("حدث خطأ في الإنتاج النهائي")
            }

            if (!isActive) return@LaunchedEffect

            currentStage = "finalize"
            val finalOutputScenes = if (finalProducedFile != null && VideoProcessor.isValidVideoFile(finalProducedFile.absolutePath)) {
                processedScenes.map { it.copy(mediaUrl = finalProducedFile.absolutePath) }
            } else processedScenes

            progress = 1.0f
            ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.PIPELINE_END, if (!isFailed && finalProducedFile != null && VideoProcessor.isValidVideoFile(finalProducedFile.absolutePath)) ProductionPipelineTracker.Result.SUCCESS else ProductionPipelineTracker.Result.FAILURE, if (!isFailed && finalProducedFile != null) "اكتملت التشغيلة بنجاح" else "انتهت التشغيلة بالفشل", "الملف: ${finalProducedFile?.name ?: "لا ملف"}", System.currentTimeMillis() - pipelineStartMs, pipelineRunId)
            if (!isFailed && finalProducedFile != null && VideoProcessor.isValidVideoFile(finalProducedFile.absolutePath)) {
                statusText = if (finalProducedFile.name.startsWith("Qabas_Draft_")) Translator.tr("اكتمل كمسودة — تعذر جلب B-Roll حقيقي")
                else Translator.tr("اكتملت المعالجة بنجاح!")
                pushActivity(statusText)
                notificationService.showCompletionNotification()
                prefs.edit().remove("cached_scenes").remove("cached_scenes_time").apply()
                try { QabasUx.successHaptic(context) } catch (_: Exception) {}
                elapsedJobRef.value?.cancel()
                kotlinx.coroutines.delay(700)
                if (isActive) onProcessingComplete(finalOutputScenes)
            } else {
                statusText = Translator.tr("انتهت المعالجة بالفشل — راجع السجل أو تراجع")
                pushActivity(statusText)
                notificationService.showErrorNotification()
                try { QabasUx.warnHaptic(context) } catch (_: Exception) {}
                elapsedJobRef.value?.cancel()
                // Do not navigate to ReviewScreen on failure
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                elapsedJobRef.value?.cancel()
                return@LaunchedEffect
            }
            isFailed = true
            Log.e("ProcessingScreen", "Fatal error", e)
            statusText = Translator.tr("حدث خطأ أثناء المعالجة: ") + (e.localizedMessage ?: e.message)
            pushActivity("خطأ فادح: ${e.message}")
            notificationService.showErrorNotification()
            try { QabasUx.warnHaptic(context) } catch (_: Exception) {}
            elapsedJobRef.value?.cancel()
            // Do not pass empty or invalid scenes to ReviewScreen
        }
    }

    ProcessingScreenUI(
        chosenStyleMessage = chosenStyleMessage,
        styleTraitsSummary = styleTraitsSummary,
        engineName = liveEngine,
        progress = progress,
        statusText = statusText,
        scenesProcessed = scenesProcessed,
        totalScenes = totalScenes,
        elapsedSeconds = elapsedSeconds,
        currentStage = currentStage,
        activityLog = activityLog,
        showCancelDialog = showCancelDialog,
        isFailed = isFailed,
        isComplete = progress >= 1f && !isFailed,
        onViewVideo = { onProcessingComplete(finalScenes) },
        onRequestCancel = { showCancelDialog = true },
        onDismissCancel = { showCancelDialog = false },
        onConfirmCancel = {
            showCancelDialog = false
            doCancel()
        }
    )
}

@Composable
private fun ProcessingScreenUI(
    chosenStyleMessage: String?,
    styleTraitsSummary: String?,
    engineName: String = "",
    progress: Float,
    statusText: String,
    scenesProcessed: Int,
    totalScenes: Int,
    elapsedSeconds: Int,
    currentStage: String,
    activityLog: List<String>,
    showCancelDialog: Boolean,
    isFailed: Boolean,
    isComplete: Boolean = false,
    onViewVideo: () -> Unit = {},
    onRequestCancel: () -> Unit,
    onDismissCancel: () -> Unit,
    onConfirmCancel: () -> Unit
) {
    val scrollState = rememberScrollState()

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = onDismissCancel,
            title = {
                Text(
                    Translator.tr("إلغاء المعالجة؟"),
                    color = GoldPrimary,
                    fontFamily = CairoFont,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    Translator.tr("سيتم إيقاف الإنتاج الحالي. لن يُحفظ الفيديو إن لم يكتمل بعد."),
                    color = Color.White,
                    fontFamily = CairoFont,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmCancel) {
                    Text(
                        Translator.tr("نعم، إلغاء"),
                        color = Color(0xFFEF4444),
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCancel) {
                    Text(
                        Translator.tr("متابعة العمل"),
                        color = GoldPrimary,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            containerColor = Color(0xFF151B2B)
        )
    }

    Scaffold(containerColor = DeepSlate) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ProcessingProgressRing(progress, scenesProcessed, totalScenes)
            Spacer(modifier = Modifier.height(16.dp))
            BreatheIn(0) { ProcessingDetailsText(statusText, progress, elapsedSeconds, currentStage) }
            Spacer(modifier = Modifier.height(14.dp))
            BreatheIn(1) { ProcessingStagePipeline(currentStage, progress) }
            Spacer(modifier = Modifier.height(14.dp))

            // شريط تقدم فاخر — هوية قبس
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.28f))
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            Translator.tr("تقدم المعالجة والرندرة"),
                            color = GoldPrimary,
                            fontSize = 13.sp,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            color = if (progress >= 1f) Color(0xFF10B981) else GoldPrimary,
                            fontSize = 15.sp,
                            fontFamily = TajawalFont,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // شريط تقدم خطي فاخر — LinearProgressIndicator بهوية قبس
                    val animatedLinearProgress by animateFloatAsState(
                        targetValue = progress.coerceIn(0f, 1f),
                        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                        label = "linear_progress"
                    )
                    LinearProgressIndicator(
                        progress = { animatedLinearProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(50)),
                        color = if (progress >= 1f) Color(0xFF10B981) else GoldPrimary,
                        trackColor = Color(0xFF0F172A),
                        strokeCap = StrokeCap.Round
                    )
                    if (totalScenes > 0 && progress in 0.20f..0.78f) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                Translator.tr("المشاهد"),
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 12.sp,
                                fontFamily = CairoFont
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "$scenesProcessed",
                                color = GoldPrimary,
                                fontSize = 14.sp,
                                fontFamily = TajawalFont,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                " / $totalScenes",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 13.sp,
                                fontFamily = CairoFont
                            )
                        }
                    }
                    if (progress >= 1f) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            Translator.tr("اكتملت المعالجة بنجاح"),
                            color = Color(0xFF10B981),
                            fontSize = 13.sp,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { if (isComplete) onViewVideo() },
                            enabled = isComplete,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                Translator.tr("عرض الفيديو ومشاركته"),
                                color = DeepSlate,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = CairoFont
                            )
                        }
                    }
                }
            }

            if (engineName.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = if (engineName == "محلي") Color(0xFFE8C547).copy(alpha = 0.12f) else Color(0xFF10B981).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(
                        1.dp,
                        if (engineName == "محلي") Color(0xFFE8C547).copy(alpha = 0.5f) else Color(0xFF10B981).copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        if (engineName == "محلي") "المحرك: محلي — أضف مفتاحاً لجودة أعلى"
                        else "المحرك الحي: $engineName ⚡",
                        color = if (engineName == "محلي") Color(0xFFE8C547) else Color(0xFF10B981),
                        fontSize = 12.sp,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
            if (!chosenStyleMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    chosenStyleMessage,
                    color = GoldPrimary.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontFamily = CairoFont,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
            if (!styleTraitsSummary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    styleTraitsSummary,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    fontFamily = CairoFont,
                    textAlign = TextAlign.Center
                )
            }

            if (activityLog.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1120)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val pulse = rememberInfiniteTransition(label = "dot")
                            val alpha by pulse.animateFloat(
                                0.35f, 1f,
                                infiniteRepeatable(tween(900), RepeatMode.Reverse),
                                label = "a"
                            )
                            Box(modifier = Modifier.size(10.dp).background(GoldPrimary.copy(alpha = alpha), CircleShape))
                            Text(
                                Translator.tr("نشاط العقل والمحرك (حي)"),
                                color = GoldPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = CairoFont
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        activityLog.take(10).forEach { line ->
                            val lineColor = when {
                                line.contains("✅") || line.contains("نجاح") || line.contains("تم") -> Color(0xFF10B981)
                                line.contains("❌") || line.contains("فشل") || line.contains("خطأ") -> Color(0xFFEF4444)
                                line.contains("⚠️") || line.contains("تحذير") -> Color(0xFFF59E0B)
                                line.contains("Gemini") || line.contains("توليد") || line.contains("StyleBrain") -> GoldPrimary
                                line.contains("FFmpeg") || line.contains("محرك") -> AiCyan
                                else -> Color.White.copy(alpha = 0.72f)
                            }
                            Text(
                                line,
                                color = lineColor,
                                fontSize = 11.sp,
                                fontFamily = CairoFont,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            if (isFailed) {
                AutomatedErrorFeedback(statusText = statusText, activityLog = activityLog)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onConfirmCancel,
                    modifier = Modifier.fillMaxWidth(0.85f).height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                    border = BorderStroke(1.5.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        Translator.tr("رجوع / إعادة المحاولة"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = CairoFont
                    )
                }
            } else if (progress < 1.0f) {
                CancelProcessingButton(onRequestCancel)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProcessingStagePipeline(currentStage: String, progress: Float) {
    val stages = listOf(
        "prepare" to Translator.tr("تحضير"),
        "script" to Translator.tr("سيناريو"),
        "scenes" to Translator.tr("مشاهد"),
        "assemble" to Translator.tr("تجميع"),
        "finalize" to Translator.tr("إنهاء")
    )
    val order = stages.map { it.first }
    val currentIdx = order.indexOf(currentStage).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            stages.forEachIndexed { idx, (_, label) ->
                val done = progress >= 1f || idx < currentIdx
                val active = idx == currentIdx && progress < 1f
                val color = when {
                    done -> Color(0xFF10B981)
                    active -> GoldPrimary
                    else -> Color(0xFF475569)
                }
                if (idx > 0) {
                    // خط واصل بين المراحل — positional Modifier to avoid named-parameter resolution issues
                    Box(
                        Modifier
                            .weight(0.35f)
                            .height(2.dp)
                            .background(
                                if (done || (active && idx <= currentIdx)) GoldPrimary.copy(alpha = 0.55f)
                                else Color(0xFF1E293B)
                            )
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(if (active) 22.dp else 16.dp)
                            .background(color.copy(alpha = if (active) 0.2f else 0.12f), CircleShape)
                            .border(1.5.dp, color, CircleShape)
                    ) {
                        Box(
                            Modifier
                                .size(if (active) 10.dp else 7.dp)
                                .background(color, CircleShape)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        label,
                        color = color,
                        fontSize = 10.sp,
                        fontFamily = CairoFont,
                        fontWeight = if (active || done) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessingProgressRing(progress: Float, scenesProcessed: Int, totalScenes: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "progAnim"
    )
    val done = progress >= 1f
    val ringColor = if (done) Color(0xFF10B981) else GoldPrimary

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        // هالة ذهبية نابضة
        Box(
            Modifier
                .size(178.dp)
                .scale(if (done) 1f else glowPulse)
                .background(ringColor.copy(alpha = 0.12f), CircleShape)
        )
        Box(
            Modifier
                .size(148.dp)
                .scale(if (done) 1f else glowPulse * 1.03f)
                .background(ringColor.copy(alpha = 0.08f), CircleShape)
        )
        // مسار الخلفية — positional Modifier (avoids "No parameter with name 'modifier'" with some Compose overloads)
        CircularProgressIndicator(
            progress = { 1f },
            Modifier.size(160.dp),
            color = Color(0xFF1E293B),
            strokeWidth = 12.dp,
            strokeCap = StrokeCap.Round
        )
        // التقدم الفعلي
        CircularProgressIndicator(
            progress = { animatedProgress },
            Modifier.size(160.dp),
            color = ringColor,
            strokeWidth = 12.dp,
            strokeCap = StrokeCap.Round
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(animatedProgress * 100).toInt()}%",
                color = ringColor,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = TajawalFont
            )
            if (done) {
                Text(
                    Translator.tr("مكتمل"),
                    color = Color(0xFF10B981),
                    fontSize = 13.sp,
                    fontFamily = CairoFont,
                    fontWeight = FontWeight.Bold
                )
            } else if (totalScenes > 0) {
                Text(
                    "$scenesProcessed / $totalScenes",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontFamily = CairoFont
                )
            } else {
                Text(
                    Translator.tr("جاري العمل"),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    fontFamily = CairoFont
                )
            }
        }
    }
}

@Composable
private fun ProcessingDetailsText(statusText: String, progress: Float, elapsedSeconds: Int, currentStage: String) {
    val stageLabel = when (currentStage) {
        "prepare" -> Translator.tr("التحضير")
        "script" -> Translator.tr("بناء السيناريو")
        "scenes" -> Translator.tr("معالجة المشاهد")
        "assemble" -> Translator.tr("التجميع والإنتاج")
        "finalize" -> Translator.tr("الإنهاء")
        else -> ""
    }
    // تقدير الوقت المتبقي من نسبة التقدم (تقريبي لكنه أفضل من الصمت)
    val etaText = remember(progress, elapsedSeconds) {
        if (progress in 0.08f..0.97f && elapsedSeconds >= 5) {
            val remaining = ((elapsedSeconds / progress) * (1f - progress)).toInt().coerceIn(5, 900)
            val m = remaining / 60
            val s = remaining % 60
            String.format(Translator.tr("متبقي تقريباً: %02d:%02d"), m, s)
        } else null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(statusText, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = CairoFont, textAlign = TextAlign.Center)
        if (stageLabel.isNotBlank() && progress < 1f) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(stageLabel, color = GoldSecondary.copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = CairoFont)
        }
        if (progress < 1f) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    String.format(Translator.tr("المنقضي: %02d:%02d"), elapsedSeconds / 60, elapsedSeconds % 60),
                    color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, fontFamily = CairoFont
                )
                if (etaText != null) {
                    Text(
                        etaText,
                        color = GoldSecondary.copy(alpha = 0.75f), fontSize = 12.sp, fontFamily = CairoFont
                    )
                }
            }
        }
    }
}

@Composable
private fun CancelProcessingButton(onCancel: () -> Unit) {
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(0.85f).height(56.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
        border = BorderStroke(1.5.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(Icons.Default.Close, Translator.tr("إلغاء المعالجة"), modifier = Modifier.padding(end = 8.dp))
        Text(Translator.tr("إلغاء المعالجة"), fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun AutomatedErrorFeedback(statusText: String, activityLog: List<String>) {
    val errorAnalysis = remember(statusText, activityLog) {
        val log = activityLog.joinToString(" ").lowercase()
        val status = statusText.lowercase()
        
        when {
            log.contains("api") || log.contains("مفتاح") || log.contains("gemini") || status.contains("gemini") ->
                Translator.tr("تعذر التواصل مع الذكاء الاصطناعي (Gemini). يرجى التأكد من اتصالك بالإنترنت أو مراجعة مفتاح API في الإعدادات.")
            log.contains("ffmpeg") || log.contains("assemble") || log.contains("تجميع") || log.contains("تصدير") ->
                Translator.tr("حدث خطأ أثناء دمج الفيديو (FFmpeg). قد يكون بسبب نقص مساحة التخزين، أو تعطل أثناء معالجة لقطات B-Roll. حاول مجدداً بنص أقصر أو جودة أقل.")
            log.contains("b-roll") || log.contains("pexels") || log.contains("pixabay") ->
                Translator.tr("تعذر جلب اللقطات السينمائية من المصدر. تأكد من اتصالك بالإنترنت القوي، أو تأكد من تفعيل المفاتيح.")
            log.contains("صوت") || log.contains("tts") ->
                Translator.tr("حدث خطأ أثناء توليد التعليق الصوتي. يرجى التأكد من توفر خدمة الإنترنت.")
            else -> Translator.tr("يبدو أن هناك عائقاً تقنياً منع إكمال الفيديو. يرجى التحقق من اتصال الإنترنت وإعادة المحاولة.")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A080C)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = Translator.tr("تحليل تلقائي لسبب الفشل"),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = CairoFont
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorAnalysis,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontFamily = CairoFont,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "الخطأ البرمجي: $statusText",
                color = Color(0xFFEF4444).copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontFamily = CairoFont,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(8.dp).fillMaxWidth()
            )
        }
    }
}
