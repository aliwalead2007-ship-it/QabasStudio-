package com.qabas.app

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DevicePerformanceProfile(
    val isLowEnd: Boolean,
    val availableRamMB: Long,
    val totalRamMB: Long,
    val cpuCores: Int,
    val reason: String
)

object DevicePerformanceGuardian {
    fun inspectDevice(context: Context): DevicePerformanceProfile {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val availMemMB = memInfo.availMem / (1024 * 1024)
        val totalMemMB = memInfo.totalMem / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        val isLowMem = memInfo.lowMemory || (totalMemMB in 1..2500) || (availMemMB < 450) || cores <= 4

        val reason = when {
            memInfo.lowMemory -> "الذاكرة المتاحة منخفضة جداً للنظام (< ${availMemMB}MB)"
            totalMemMB in 1..2500 -> "إجمالي ذاكرة الجهاز ($totalMemMB MB) محدودة"
            availMemMB < 450 -> "الذاكرة العشوائية الحرة الحالية ($availMemMB MB) منخفضة"
            cores <= 4 -> "عدد الأنوية ($cores أنوية) اقتصادي"
            else -> "جهاز عالي الأداء ($totalMemMB MB / $cores أنوية)"
        }

        return DevicePerformanceProfile(
            isLowEnd = isLowMem,
            availableRamMB = availMemMB,
            totalRamMB = totalMemMB,
            cpuCores = cores,
            reason = reason
        )
    }
}

class VideoEngineManager(private val context: Context) {

    private val TAG = "VideoEngineManager"

    suspend fun processProject(
        scenes: List<Scene>,
        videoQuality: String,
        ambientSound: String,
        styleAnalysis: VideoStyleAnalysis? = null,
        runId: String = "",
        onProgress: (Float, String) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val validScenes = if (scenes.isEmpty()) {
                listOf(
                    Scene(
                        title = "المشهد الرئيسي",
                        description = "قبس - إخراج سينمائي ذكي",
                        durationInSeconds = 5,
                        transitionType = "Dissolve"
                    )
                )
            } else scenes

            val totalSteps = validScenes.size * 3 + 2 // 3 steps per scene + concat + final
            var currentStep = 0

            val processedVideoPaths = mutableListOf<String>()
            val sceneAudioPaths = mutableListOf<String>()
            var fallbackSceneCount = 0

            val resolvedStyleAnalysis = styleAnalysis ?: StyleBrain.getCoreStyle().toAbsorbedStyle().toVideoStyleAnalysis()
            val deviceProfile = DevicePerformanceGuardian.inspectDevice(context)

            // مستوى التدهور المتدرج المعلن: سينمائي → موحد → مسودة
            val renderLevel = ProductionPowerKit.decideLevel(deviceProfile.isLowEnd, validScenes.size, videoQuality)
            
            // مسار سريع مضمون: أولوية للنجاح على الجودة فقط عندما يكون الجهاز ضعيفاً جداً أو يطلب المستخدم Fast صراحة
            val preferGuaranteedPath = renderLevel != RenderLevel.CINEMATIC

            VideoProcessor.currentQualityPreset = when (renderLevel) {
                RenderLevel.DRAFT, RenderLevel.STANDARD -> ExportQualityPreset.FAST
                RenderLevel.CINEMATIC -> if (videoQuality.contains("عالي") || videoQuality.contains("High") || videoQuality.contains("4K")) ExportQualityPreset.HIGH else ExportQualityPreset.BALANCED
            }

            SystemLogsManager.addLog(
                "INFO",
                "مستوى الإنتاج: $renderLevel — جودة: ${VideoProcessor.currentQualityPreset.label}",
                Color(if (renderLevel == RenderLevel.CINEMATIC) 0xFF4CAF50 else 0xFFE8C547)
            )

            // فحص مساحة التخزين قبل بدء المعالجة
            val cacheDir = File(context.cacheDir, "qabas_engine")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val usableBytes = cacheDir.usableSpace
            val usableMB = usableBytes / (1024 * 1024)
            if (usableMB < 200L) {
                onProgress(0f, "مساحة التخزين منخفضة جداً (${usableMB}MB) — يرجى تحرير مساحة")
                SystemLogsManager.addLog("ERROR", "فشل: مساحة التخزين منخفضة (${usableMB}MB < 200MB)", Color(0xFFEF4444))
                ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.VIDEO_ENGINE, ProductionPipelineTracker.Result.FAILURE, "نقص مساحة تخزين", "${usableMB}MB متاح (الحد 200MB)", 0, runId)
                return@withContext null
            } else if (usableMB < 500L) {
                onProgress(0f, "تنبيه: مساحة التخزين محدودة (${usableMB}MB) — قد تتأثر الجودة")
                SystemLogsManager.addLog("WARN", "مساحة التخزين محدودة (${usableMB}MB) — جودة قد تتأثر", Color(0xFFE8C547))
            }
            cacheDir.listFiles()?.forEach { f ->
                val ageMs = System.currentTimeMillis() - f.lastModified()
                if (ageMs > 3_600_000L) f.delete() // حذف الأقدم من ساعة فقط
            }

            validScenes.forEachIndexed { index, scene ->
                if (!this@withContext.isActive) {
                    Log.w(TAG, "Video processing job cancelled before scene $index")
                    return@withContext null
                }
                val scenePrepStartMs = System.currentTimeMillis()

                // بصمة المشهد: إعادة استخدام المخزن عند عدم التغيير (ثوانٍ بدل دقائق)
                val fingerprint = ProductionPowerKit.sceneFingerprint(scene, VideoProcessor.currentQualityPreset.label)
                val cachedScene = ProductionPowerKit.cachedSceneFile(context, fingerprint)
                if (cachedScene.exists() && VideoProcessor.isValidVideoFile(cachedScene.absolutePath, minSizeBytes = VideoProcessor.MIN_SCENE_SIZE)) {
                    processedVideoPaths.add(cachedScene.absolutePath)
                    currentStep += 3
                    onProgress(currentStep.toFloat() / totalSteps, "المشهد ${index + 1}: مُعاد استخدامه من الكاش ⚡")
                    SystemLogsManager.addLog("INFO", "المشهد ${index + 1}: كاش صالح — تخطي الترميز", Color(0xFF4CAF50))
                    return@forEachIndexed
                }

                onProgress(
                    currentStep.toFloat() / totalSteps,
                    "معالجة المشهد ${index + 1}/${validScenes.size}..."
                )

                try {
                    // 1. Prepare Base Media
                    var mediaToUse = scene.mediaUrl?.takeIf { 
                        it.isNotBlank() && (it.startsWith("http") || File(it).exists()) 
                    } ?: scene.visualEffect.takeIf { it.startsWith("http") }

                    // Priority 1: Real Pexels / Pixabay (with predictive prefetch cache first)
                    if (mediaToUse.isNullOrBlank()) {
                        val query = "${scene.title} ${scene.description}".trim()
                        val prefetched = BrollPrefetch.cached(query) ?: BrollPrefetch.cached(scene.title) ?: BrollPrefetch.cached(scene.description.take(60))
                        if (!prefetched.isNullOrBlank()) {
                            mediaToUse = prefetched
                            SystemLogsManager.addLog("INFO", "وسائط تنبؤية جاهزة للمشهد ${index + 1} ⚡", Color(0xFF4CAF50))
                        }
                    }
                    if (mediaToUse.isNullOrBlank()) {
                        try {
                            val query = "${scene.title} ${scene.description}".trim()
                            val fetched = AppServices.fetchMedia(query, "video")
                            if (fetched.isNotBlank()) {
                                mediaToUse = fetched
                                SystemLogsManager.addLog(
                                    "INFO",
                                    "تم جلب وسائط حقيقية للمشهد ${index + 1} من المكتبة ✅",
                                    Color(0xFF4CAF50)
                                )
                            }
                        } catch (fetchEx: Exception) {
                            if (fetchEx is CancellationException) throw fetchEx
                            Log.w(TAG, "fetchMedia failed for scene $index: ${fetchEx.message}")
                        }
                    }

                    // Priority 1b: مصادر حرة بلا مفاتيح (Wikimedia/NASA)
                    if (mediaToUse.isNullOrBlank()) {
                        try {
                            val query = "${scene.title} ${scene.description}".trim()
                            val free = FreeStockSources.searchFree(query)
                            val pick = free.firstOrNull { it.videoUrl.startsWith("http") }
                            if (pick != null && pick.videoUrl.isNotBlank()) {
                                mediaToUse = pick.videoUrl
                                SystemLogsManager.addLog("INFO", "وسائط حرة بلا مفتاح للمشهد ${index + 1} 🌍", Color(0xFF4CAF50))
                            }
                        } catch (_: Exception) { }
                    }

                    // Priority 2: Local curated B-Roll library
                    if (mediaToUse.isNullOrBlank()) {
                        val queryText = "${scene.title} ${scene.description}"
                        val matchedBRoll = BRollEngine.matchBRoll(queryText)
                        mediaToUse = matchedBRoll.videoUrl.ifBlank { matchedBRoll.thumbnailUrl }
                        if (!mediaToUse.isNullOrBlank()) {
                            SystemLogsManager.addLog(
                                "INFO",
                                "استخدام B-Roll محلي متطابق للمشهد ${index + 1}",
                                Color(0xFFE8C547)
                            )
                        }
                    }

                    val sceneLabel = scene.title.ifBlank { scene.description }.take(60)
                    val mediaFile = if (mediaToUse.isNullOrBlank()) {
                        SystemLogsManager.addLog(
                            "WARN",
                            "لا توجد وسائط للمشهد ${index + 1} — استخدام إطار سينمائي محلي",
                            Color(0xFFE8C547)
                        )
                        generateSolidColorVideo(cacheDir, index, scene.durationInSeconds, resolvedStyleAnalysis, sceneLabel)
                    } else {
                        downloadMedia(mediaToUse, cacheDir, index, resolvedStyleAnalysis)
                    }

                    currentStep++
                    onProgress(
                        currentStep.toFloat() / totalSteps,
                        "تجهيز المرئيات للمشهد ${index + 1}..."
                    )

                    // Convert Image → Video or trim Video. Always verify output.
                    val isImage = mediaFile.extension.lowercase(Locale.ROOT) in listOf("jpg", "jpeg", "png", "webp")
                    var videoReadyPath = if (isImage) {
                        val out = File(cacheDir, "ready_vid_$index.mp4").absolutePath
                        val ok = VideoProcessor.generateVideoFromImage(
                            context, mediaFile.absolutePath, scene.durationInSeconds, out, resolvedStyleAnalysis
                        )
                        if (ok) out else ""
                    } else {
                        val out = File(cacheDir, "ready_vid_$index.mp4").absolutePath
                        val ok = VideoProcessor.trimVideo(context, mediaFile.absolutePath, 0, scene.durationInSeconds, out)
                        if (ok) out else ""
                    }

                    // Hard check: generation failed or invalid video → solid fallback
                    if (videoReadyPath.isBlank() || !VideoProcessor.isValidVideoFile(videoReadyPath, minSizeBytes = VideoProcessor.MIN_SCENE_SIZE)) {
                        fallbackSceneCount++
                        SystemLogsManager.addLog(
                            "WARN",
                            "فشل توليد مشهد ${index + 1} من الوسائط — استخدام إطار سينمائي محلي",
                            Color(0xFFE8C547)
                        )
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.SCENE_PROCESSING, ProductionPipelineTracker.Result.FALLBACK, "مشهد ${index + 1}: إطار محلي بديل", "تعذر تجهيز الوسائط الأصلية", System.currentTimeMillis() - scenePrepStartMs, runId, index)
                        val solid = generateSolidColorVideo(
                            cacheDir, index, scene.durationInSeconds, resolvedStyleAnalysis, sceneLabel
                        )
                        videoReadyPath = solid.absolutePath
                    } else {
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.SCENE_PROCESSING, ProductionPipelineTracker.Result.SUCCESS, "مشهد ${index + 1}: مرئيات جاهزة (${if (isImage) "صورة→فيديو" else "قص فيديو"})", mediaFile.name, System.currentTimeMillis() - scenePrepStartMs, runId, index)
                    }

                    // TTS — يُجمَّع لكل مشهد ثم يُدمج كمسار واحد موحد بعد الدمج (بلا تقطع).
                    // مهلة 60ث لكل مشهد: الفاشل يتحول لإطار ويُكمل الباقي بدل قتل المشروع.
                    val spokenArabicText = if (scene.title.isNotBlank()) scene.title else scene.description
                    val ttsStartMs = System.currentTimeMillis()
                    val ttsSource = if (preferGuaranteedPath) "AndroidTTS محلي" else "خدمة سحابية"
                    val perSceneAudio: String? = kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                        if (preferGuaranteedPath) {
                            try { AndroidTTSService.synthesizeSpeech(spokenArabicText) } catch (localTtsEx: Exception) {
                                if (localTtsEx is CancellationException) throw localTtsEx
                                Log.w(TAG, "Local system TTS unavailable in guaranteed path for scene $index")
                                null
                            }
                        } else {
                            try { AppServices.generateVoiceover(spokenArabicText) } catch (ttsEx: Exception) {
                                if (ttsEx is CancellationException) throw ttsEx
                                Log.w(TAG, "TTS failed for scene $index", ttsEx)
                                null
                            }
                        }
                    }
                    if (!perSceneAudio.isNullOrBlank() && File(perSceneAudio).exists() && File(perSceneAudio).length() > 1000) {
                        sceneAudioPaths.add(perSceneAudio)
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.TTS_GENERATION, ProductionPipelineTracker.Result.SUCCESS, "مشهد ${index + 1}: تعليق صوتي جاهز", ttsSource, System.currentTimeMillis() - ttsStartMs, runId, index)
                    } else {
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.TTS_GENERATION, ProductionPipelineTracker.Result.FAILURE, "مشهد ${index + 1}: فشل توليد الصوت", "المسار: $ttsSource — سيستمر المشهد بلا تعليق", System.currentTimeMillis() - ttsStartMs, runId, index)
                    }
                    val videoWithAudioPath = videoReadyPath

                    currentStep++
                    onProgress(
                        currentStep.toFloat() / totalSteps,
                        "دمج النصوص للمشهد ${index + 1}..."
                    )

                    // Text Overlay — non-blocking
                    val textOverlayPath = File(cacheDir, "text_vid_$index.mp4").absolutePath
                    val textSuccess = try {
                        VideoProcessor.addStyledTextOverlay(
                            context = context,
                            videoPath = videoWithAudioPath,
                            text = spokenArabicText.take(80),
                            styleAnalysis = resolvedStyleAnalysis,
                            outputPath = textOverlayPath
                        )
                    } catch (txtEx: Exception) {
                        if (txtEx is CancellationException) throw txtEx
                        false
                    }
                    var scenePathAfterText = if (textSuccess && VideoProcessor.isValidVideoFile(textOverlayPath, minSizeBytes = VideoProcessor.MIN_SCENE_SIZE)) {
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.TEXT_OVERLAY, ProductionPipelineTracker.Result.SUCCESS, "مشهد ${index + 1}: طبقة نص مطبقة", spokenArabicText.take(50), 0, runId, index)
                        textOverlayPath
                    } else {
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.TEXT_OVERLAY, ProductionPipelineTracker.Result.FALLBACK, "مشهد ${index + 1}: تخطي طبقة النص", "استمرار بلا كابشن", 0, runId, index)
                        videoWithAudioPath
                    }

                    // تطبيق فلتر الألوان (Mood Color Grading)
                    val sceneFilter = scene.visualEffect.trim()
                    if (sceneFilter.isNotBlank() &&
                        !sceneFilter.startsWith("http") &&
                        !File(sceneFilter).exists()
                    ) {
                        val gradedPath = File(cacheDir, "filter_vid_$index.mp4").absolutePath
                        val gradeOk = try {
                            VideoProcessor.applyCinematicColorGrading(
                                context, scenePathAfterText, sceneFilter, gradedPath
                            )
                        } catch (_: Exception) {
                            false
                        }
                        if (gradeOk && VideoProcessor.isValidVideoFile(gradedPath, minSizeBytes = VideoProcessor.MIN_SCENE_SIZE)) {
                            scenePathAfterText = gradedPath
                            ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.COLOR_GRADING, ProductionPipelineTracker.Result.SUCCESS, "مشهد ${index + 1}: تلوين سينمائي مطبق", sceneFilter, 0, runId, index)
                            SystemLogsManager.addLog(
                                "INFO",
                                "تطبيق فلتر الاستوديو ($sceneFilter) على المشهد ${index + 1}",
                                Color(0xFFE8C547)
                            )
                        } else {
                            ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.COLOR_GRADING, ProductionPipelineTracker.Result.FALLBACK, "مشهد ${index + 1}: تعذر التلوين", "الاستمرار بدون فلتر", 0, runId, index)
                        }
                    } else {
                        ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.COLOR_GRADING, ProductionPipelineTracker.Result.SKIPPED, "مشهد ${index + 1}: بلا فلتر لوني", "", 0, runId, index)
                    }

                    processedVideoPaths.add(scenePathAfterText)
                    try {
                        if (VideoProcessor.isValidVideoFile(scenePathAfterText, minSizeBytes = VideoProcessor.MIN_SCENE_SIZE)) {
                            File(scenePathAfterText).copyTo(cachedScene, overwrite = true)
                        }
                    } catch (_: Exception) {}
                    currentStep++
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to process scene $index, using solid fallback", e)
                    fallbackSceneCount++
                    SystemLogsManager.addLog(
                        "ERROR",
                        "فشل المشهد ${index + 1}: ${e.localizedMessage ?: e.message} — إطار محلي",
                        Color(0xFFEF4444)
                    )
                    val fallbackLabel = scene.title.ifBlank { scene.description }.take(60)
                    val fallback = generateSolidColorVideo(
                        cacheDir, index, scene.durationInSeconds, resolvedStyleAnalysis, fallbackLabel
                    )
                    processedVideoPaths.add(fallback.absolutePath)
                    currentStep += 3
                }
            }

            onProgress(
                currentStep.toFloat() / totalSteps,
                "تطبيق انتقال وخلطة أسلوب قبس (${resolvedStyleAnalysis.transitionSpeed})..."
            )

            // 3. Concatenate all scenes with Cinematic Transitions (or stream copy on low-end)
            // أولوية لانتقال الاستوديو المتقدم الذي اختاره المستخدم على المشاهد
            val userChosenTransition = validScenes
                .map { it.transitionType.trim() }
                .firstOrNull { it.isNotBlank() && !it.equals("Fade", ignoreCase = true) && !it.equals("بدون", ignoreCase = true) }
            val styleTransition = when {
                !userChosenTransition.isNullOrBlank() -> userChosenTransition
                resolvedStyleAnalysis.transitionSpeed.contains("خاطف") || resolvedStyleAnalysis.movementPatterns.contains("Glitch") -> "Glitch"
                resolvedStyleAnalysis.movementPatterns.contains("Zoom") || resolvedStyleAnalysis.movementPatterns.contains("زووم") -> "ZoomIn"
                resolvedStyleAnalysis.movementPatterns.contains("Slide") || resolvedStyleAnalysis.movementPatterns.contains("سحب") -> "SlideLeft"
                resolvedStyleAnalysis.movementPatterns.contains("Wipe") || resolvedStyleAnalysis.movementPatterns.contains("مسح") -> "WipeLeft"
                resolvedStyleAnalysis.movementPatterns.contains("FadeBlack") || resolvedStyleAnalysis.movementPatterns.contains("إظلام") -> "FadeBlack"
                resolvedStyleAnalysis.transitionSpeed.contains("بطيء") || resolvedStyleAnalysis.movementPatterns.contains("Dissolve") -> "Dissolve"
                else -> "Dissolve"
            }
            val rawConcatPath = File(cacheDir, "raw_concat_output.mp4").absolutePath
            // تصفية المشاهد الصالحة فقط قبل الدمج — ملف غير صالح واحد يفشل السلسلة كلها
            val validPaths = processedVideoPaths.filter { VideoProcessor.isValidVideoFile(it, minSizeBytes = VideoProcessor.MIN_LENIENT_SIZE) }
            if (validPaths.isEmpty()) {
                SystemLogsManager.addLog(
                    "ERROR",
                    "لا يوجد أي مشهد صالح للدمج — فشل التصدير 🔴",
                    Color(0xFFEF4444)
                )
                ProductionPipelineTracker.record(context, ProductionPipelineTracker.Stage.FFmpeg_MERGE, ProductionPipelineTracker.Result.FAILURE, "لا توجد مشاهد صالحة للدمج", "عدد المشاهد المعالجة: ${processedVideoPaths.size}", 0, runId)
                return@withContext null
            }
            var concatSuccess = if (deviceProfile.isLowEnd) {
                // Stream-copy concat without heavy filter graph re-encoding to save memory
                VideoProcessor.concatenateVideos(context, validPaths, rawConcatPath)
            } else {
                VideoProcessor.concatenateVideosWithTransitions(
                    context = context,
                    videoPaths = validPaths,
                    transitionType = styleTransition,
                    outputPath = rawConcatPath
                )
            }

            // دمج بديل بسيط بلا انتقالات عند فشل الدمج السينمائي — أولوية للإنتاج على الجمال
            if (!concatSuccess) {
                SystemLogsManager.addLog(
                    "WARN",
                    "فشل الدمج بالانتقالات — إعادة المحاولة بدمج مباشر بلا انتقالات",
                    Color(0xFFE8C547)
                )
                concatSuccess = VideoProcessor.concatenateVideos(context, validPaths, rawConcatPath)
            }

            val mergeValid = concatSuccess && VideoProcessor.isValidVideoFile(rawConcatPath, minSizeBytes = VideoProcessor.MIN_SCENE_SIZE)
            ProductionPipelineTracker.record(
                context,
                ProductionPipelineTracker.Stage.FFmpeg_MERGE,
                if (mergeValid) ProductionPipelineTracker.Result.SUCCESS else ProductionPipelineTracker.Result.FAILURE,
                if (mergeValid) "دمج ${validPaths.size} مشاهد" else "فشل دمج ${validPaths.size} مشاهد",
                "جهاز: ${if (deviceProfile.isLowEnd) "ضعيف" else "عادي"}",
                0,
                runId
            )

            if (!mergeValid) {
                Log.e(TAG, "Failed to concatenate videos")
                SystemLogsManager.addLog(
                    "ERROR",
                    "فشل دمج المشاهد نهائياً حتى بالدمج المباشر — فشل التصدير 🔴",
                    Color(0xFFEF4444)
                )
                return@withContext null
            }

            // Apply Cinematic Color Grading & Contrast from StyleDirective
            val concatOutputPath = File(cacheDir, "graded_concat_output.mp4").absolutePath
            val gradedOk = try {
                VideoProcessor.applyStyleClonedFilters(
                    context = context,
                    videoPath = rawConcatPath,
                    styleAnalysis = resolvedStyleAnalysis,
                    outputPath = concatOutputPath
                )
            } catch (_: Exception) {
                false
            }
            if (!gradedOk || !VideoProcessor.isValidVideoFile(concatOutputPath, minSizeBytes = VideoProcessor.MIN_SCENE_SIZE)) {
                File(rawConcatPath).copyTo(File(concatOutputPath), overwrite = true)
            }

            currentStep++

            onProgress(
                currentStep.toFloat() / totalSteps,
                "تصدير الفيديو وإضافة المؤثرات..."
            )

            // 4. Export to App's External Files Directory (no permission required)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val isDraft = validScenes.isNotEmpty() && fallbackSceneCount * 2 >= validScenes.size
            val finalFileName = if (isDraft) "Qabas_Draft_${timestamp}.mp4" else "Qabas_Project_$timestamp.mp4"
            if (isDraft) {
                SystemLogsManager.addLog("WARN", "مسودة بلا B-Roll حقيقي ($fallbackSceneCount/${validScenes.size}) — وسم الملف كمسودة", Color(0xFFE8C547))
            }
            val publicDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "movies")
            if (!publicDir.exists()) publicDir.mkdirs()
            
            val finalOutputFile = File(publicDir, finalFileName)
            
            var currentWorkingVideoPath = concatOutputPath

            // دمج التعليق الصوتي الموحد (مسار واحد بلا تقطع) قبل الصوت المحيطي
            // تمريرة واحدة: نص كامل باستدعاء واحد، وعند الفشل نعود لدمج المقاطع
            val validAudios = sceneAudioPaths.filter { File(it).exists() && File(it).length() > 1000 }
            val narrationPath = File(cacheDir, "final_narration.m4a").absolutePath
            var narrationReady = false
            try {
                val fullText = validScenes.map { if (it.title.isNotBlank()) it.title else it.description }.joinToString(". ")
                if (fullText.length > 3) {
                    val singleAudio: String? = kotlinx.coroutines.withTimeoutOrNull(90_000L) {
                        if (preferGuaranteedPath) {
                            try { AndroidTTSService.synthesizeSpeech(fullText) } catch (_: Exception) { null }
                        } else {
                            try { AppServices.generateVoiceover(fullText) } catch (_: Exception) { null }
                        }
                    }
                    if (!singleAudio.isNullOrBlank() && File(singleAudio).exists() && File(singleAudio).length() > 1000) {
                        try { File(singleAudio).copyTo(File(narrationPath), overwrite = true); narrationReady = true } catch (_: Exception) {}
                        SystemLogsManager.addLog("INFO", "تعليق موحد بتمريرة واحدة ✅", Color(0xFF4CAF50))
                    }
                }
            } catch (_: Exception) {}
            if (!narrationReady && validAudios.isNotEmpty()) {
                narrationReady = try { VideoProcessor.concatAudios(context, validAudios, narrationPath) } catch (_: Exception) { false }
            }
            if (narrationReady && File(narrationPath).exists()) {
                    val voicedPath = File(cacheDir, "final_voiced.mp4").absolutePath
                    val voiceOk = try { VideoProcessor.mergeAudioVideo(context, currentWorkingVideoPath, narrationPath, voicedPath) } catch (_: Exception) { false }
                    if (voiceOk && VideoProcessor.isValidVideoFile(voicedPath, minSizeBytes = VideoProcessor.MIN_SCENE_SIZE)) {
                        currentWorkingVideoPath = voicedPath
                    }
            }

            val noAmbient = ambientSound.contains("بدون") || ambientSound.isBlank() || ambientSound == "لا يوجد"
            if (!noAmbient) {
                var localAmbientPath = ""
                if (ambientSound.startsWith("/") && File(ambientSound).exists()) {
                    localAmbientPath = ambientSound
                } else if (ambientSound.startsWith("assets/")) {
                    localAmbientPath = copyAssetToCache(context, ambientSound)
                } else {
                    val assetPath = ContentFilterService.getFallbackAudio(ambientSound)
                    localAmbientPath = copyAssetToCache(context, assetPath)
                }
                
                if (localAmbientPath.isNotEmpty()) {
                    val mixedOutputPath = File(cacheDir, "final_mixed.mp4").absolutePath
                    val mixSuccess = VideoProcessor.mergeAmbientAudio(context, concatOutputPath, localAmbientPath, mixedOutputPath)
                    if (mixSuccess) {
                        currentWorkingVideoPath = mixedOutputPath
                    }
                }
            }

            // Apply Developer Watermark / Signature if Developer Account & Enabled
            val accountService = AppServices.getAccountService(context)
            if (accountService.isDeveloperOrAdmin && accountService.isDevWatermarkEnabled) {
                onProgress(0.95f, "جاري طباعة ختم وتوقيع المطور الذهبي على الفيديو... ✦")
                val devWatermarkPath = File(cacheDir, "final_dev_watermark.mp4").absolutePath
                val stampSuccess = VideoProcessor.addDeveloperWatermarkOverlay(
                    context = context,
                    videoPath = currentWorkingVideoPath,
                    watermarkText = accountService.devWatermarkText,
                    outputPath = devWatermarkPath
                )
                if (stampSuccess && File(devWatermarkPath).exists()) {
                    currentWorkingVideoPath = devWatermarkPath
                }
            }

            val sourceFile = File(currentWorkingVideoPath)
            if (!VideoProcessor.isValidVideoFile(sourceFile.absolutePath, minSizeBytes = VideoProcessor.MIN_OUTPUT_SIZE)) {
                SystemLogsManager.addLog(
                    "ERROR",
                    "الملف النهائي غير صالح (لا يحتوي مساراً مرئياً أو مدة كافية) — فشل التصدير 🔴",
                    Color(0xFFEF4444)
                )
                return@withContext null
            }

            sourceFile.copyTo(finalOutputFile, overwrite = true)

            if (!VideoProcessor.isValidVideoFile(finalOutputFile.absolutePath, minSizeBytes = VideoProcessor.MIN_OUTPUT_SIZE)) {
                SystemLogsManager.addLog(
                    "ERROR",
                    "فشل نسخ/التحقق من الملف النهائي في مجلد التصدير 🔴",
                    Color(0xFFEF4444)
                )
                return@withContext null
            }

            val durationHint = try {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(finalOutputFile.absolutePath)
                val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                r.release()
                "${d / 1000}ث"
            } catch (_: Exception) {
                "?"
            }
            val audioHint = if (VideoProcessor.hasAudioTrack(finalOutputFile.absolutePath)) "صوت✓" else "بدون صوت"

            SystemLogsManager.addLog(
                "SUCCESS",
                "تم تصدير الفيديو بنجاح (${finalOutputFile.name}, ${finalOutputFile.length() / 1024}KB, $durationHint, $audioHint) ✅",
                Color(0xFF4CAF50)
            )
            onProgress(1.0f, "تم التصدير بنجاح!")
            
            finalOutputFile
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error processing video", e)
            SystemLogsManager.addLog(
                "ERROR",
                "خطأ في معالجة الفيديو: ${e.localizedMessage ?: e.message} 🔴",
                Color(0xFFEF4444)
            )
            null
        }
    }

    
    private fun copyAssetToCache(context: Context, assetPath: String): String {
        val cleanPath = assetPath.removePrefix("assets/")
        val file = File(context.cacheDir, cleanPath.replace("/", "_"))
        if (!file.exists()) {
            try {
                context.assets.open(cleanPath).use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                return ""
            }
        }
        return file.absolutePath
    }

    /**
     * Persistent B-Roll cache (survives processProject cacheDir wipe).
     * Same Mixkit/Pexels URL is reused offline after first successful download.
     */
    private fun persistentBrollDir(): File {
        val dir = File(context.filesDir, "qabas_broll_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun urlCacheKey(url: String): String {
        val hash = url.trim().hashCode().toString().replace("-", "n")
        return "broll_$hash"
    }

    private suspend fun downloadMedia(urlStr: String, cacheDir: File, index: Int, styleAnalysis: VideoStyleAnalysis? = null): File = withContext(Dispatchers.IO) {
        val cleanUrl = urlStr.trim()
        if (cleanUrl.isBlank() || cleanUrl == "null") {
            return@withContext generateSolidColorVideo(cacheDir, index, 5, styleAnalysis, null)
        }

        val isVideoUrl = cleanUrl.contains(".mp4") || cleanUrl.contains("video") ||
                cleanUrl.contains("mixkit") || cleanUrl.contains("pexels") || cleanUrl.contains("pixabay")
        val targetExt = when {
            cleanUrl.contains(".mp4", ignoreCase = true) -> "mp4"
            cleanUrl.contains(".png", ignoreCase = true) -> "png"
            cleanUrl.contains(".webp", ignoreCase = true) -> "webp"
            cleanUrl.contains(".jpg", ignoreCase = true) || cleanUrl.contains(".jpeg", ignoreCase = true) -> "jpg"
            isVideoUrl -> "mp4"
            else -> "jpg"
        }

        // 1. Persistent cache first (works fully offline after first hit)
        val persistKey = urlCacheKey(cleanUrl)
        val persisted = File(persistentBrollDir(), "$persistKey.$targetExt")
        if (persisted.exists() && persisted.length() > 20_000L) {
            val sessionCopy = File(cacheDir, "dl_media_${index}.$targetExt")
            try {
                persisted.copyTo(sessionCopy, overwrite = true)
                if (sessionCopy.exists() && sessionCopy.length() > 20_000L) {
                    Log.d(TAG, "B-Roll persistent cache hit: ${persisted.name}")
                    return@withContext sessionCopy
                }
            } catch (_: Exception) {}
        }

        // 2. Network download (3 محاولات بتأخير تصاعدي)
        val sessionFile = File(cacheDir, "dl_media_${index}_${System.currentTimeMillis()}.$targetExt")
        var lastException: Exception? = null
        for (attempt in 1..3) {
            try {
                val connection = (java.net.URL(cleanUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 25_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; QabasStudio/1.2)")
                }
                connection.connect()

                if (connection.responseCode in 200..299) {
                    connection.inputStream.use { input ->
                        FileOutputStream(sessionFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (sessionFile.exists() && sessionFile.length() > 30_000L) {
                        try { sessionFile.copyTo(persisted, overwrite = true) } catch (_: Exception) {}
                        return@withContext sessionFile
                    }
                } else {
                    throw java.io.IOException("HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < 3) {
                    val delayMs = attempt * 1500L
                    Log.d(TAG, "B-Roll download attempt $attempt failed, retrying in ${delayMs}ms: ${e.message}")
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        Log.e(TAG, "B-Roll download failed after 3 attempts: $cleanUrl → ${lastException?.message}")
        SystemLogsManager.addLog(
            "WARN",
            "تعذر تحميل الوسائط بعد 3 محاولات — استخدام إطار سينمائي محلي",
            Color(0xFFE8C547)
        )

        // 3. Offline cinematic fallback
        generateSolidColorVideo(cacheDir, index, 5, styleAnalysis, null)
    }

    /**
     * Offline cinematic frame: 1080×1920 gradient + Arabic title (StaticLayout).
     * Looks intentional when network B-Roll is unavailable — not a broken solid block.
     */
    private suspend fun generateSolidColorVideo(
        cacheDir: File,
        index: Int,
        duration: Int,
        styleAnalysis: VideoStyleAnalysis? = null,
        sceneTitle: String? = null
    ): File = withContext(Dispatchers.IO) {
        val safeDuration = duration.coerceIn(3, 30)
        val out = File(cacheDir, "solid_color_$index.mp4")
        try {
            val imgFile = File(cacheDir, "solid_color_img_$index.png")
            val colorsRaw = styleAnalysis?.dominantColors ?: ""
            val keywordsBlob = styleAnalysis?.keywords?.joinToString(" ") ?: ""
            val parseBlob = (colorsRaw + " " + keywordsBlob).lowercase(Locale.ROOT)

            val patternHex = Regex("""(?i)(?:primary|color)\s*[=:]\s*#?([0-9A-Fa-f]{6})""")
            val directivePrimary = patternHex.find(colorsRaw + " " + keywordsBlob)?.groupValues?.getOrNull(1)?.let { "#${it.uppercase(Locale.ROOT)}" }
            val patternBg = Regex("""(?i)(?:bg|background)\s*[=:]\s*#?([0-9A-Fa-f]{6})""")
            val directiveBg = patternBg.find(colorsRaw + " " + keywordsBlob)?.groupValues?.getOrNull(1)?.let { "#${it.uppercase(Locale.ROOT)}" }

            val (bgTop, bgBottom, accent) = when {
                directivePrimary != null && directiveBg != null -> {
                    val top = directiveBg
                    val bottom = try {
                        val c = android.graphics.Color.parseColor(directiveBg)
                        val r = (android.graphics.Color.red(c) * 0.7f).toInt().coerceIn(0, 255)
                        val g = (android.graphics.Color.green(c) * 0.7f).toInt().coerceIn(0, 255)
                        val b = (android.graphics.Color.blue(c) * 0.7f).toInt().coerceIn(0, 255)
                        String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b)
                    } catch (_: Exception) {
                        directiveBg
                    }
                    Triple(top, bottom, directivePrimary)
                }
                parseBlob.contains("cool_emerald") || parseBlob.contains("أخضر") || parseBlob.contains("emerald") ->
                    Triple("#0A1F18", "#0F291E", "#10B981")
                parseBlob.contains("أزرق") || parseBlob.contains("blue") ->
                    Triple("#06101F", "#0A192F", "#38BDF8")
                parseBlob.contains("soft_desert") || parseBlob.contains("برتقالي") || parseBlob.contains("غروب") ->
                    Triple("#1A0C04", "#1E1005", "#F97316")
                parseBlob.contains("نيون") || parseBlob.contains("أصفر") ->
                    Triple("#0B0F19", "#12181F", "#FFE500")
                parseBlob.contains("high_contrast") ->
                    Triple("#050811", "#0B0F19", "#EF4444")
                else ->
                    Triple("#070B14", "#0B0F19", directivePrimary ?: "#8B5CF6")
            }

            try {
                val ok = ProceduralBackdropEngine.render(
                    filePath = imgFile.absolutePath,
                    width = 1080,
                    height = 1920,
                    topHex = bgTop,
                    bottomHex = bgBottom,
                    accentHex = accent,
                    caption = sceneTitle?.trim()?.take(60)?.takeIf { it.isNotBlank() }
                )
                if (!ok || !imgFile.exists() || imgFile.length() == 0L) {
                    Log.e(TAG, "generateSolidColorVideo produced invalid backdrop image for scene $index")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error generating procedural backdrop for scene $index", t)
            }
            var ok = VideoProcessor.generateVideoFromImage(
                context,
                imgFile.absolutePath,
                safeDuration,
                out.absolutePath,
                styleAnalysis
            )
            if (!ok || !VideoProcessor.isValidVideoFile(out.absolutePath, minSizeBytes = VideoProcessor.MIN_LENIENT_SIZE)) {
                Log.e(TAG, "generateSolidColorVideo produced invalid file for scene $index — retrying with minimal guaranteed frame")
                // إعادة محاولة بإطار مضمون: صورة نقطية بسيطة بلا أي اعتماد خارجي
                try {
                    val bmp = android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.parseColor(bgTop))
                    java.io.FileOutputStream(imgFile).use { fos -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fos) }
                    bmp.recycle()
                    ok = VideoProcessor.generateVideoFromImage(
                        context,
                        imgFile.absolutePath,
                        safeDuration,
                        out.absolutePath,
                        null
                    )
                } catch (retryEx: Exception) {
                    Log.e(TAG, "Minimal frame retry failed for scene $index", retryEx)
                }
            }
            // صدق كامل: لا نجاح وهمي — احذف الملف الفاشل حتى لا يدخل سلسلة الدمج
            if (!ok || !VideoProcessor.isValidVideoFile(out.absolutePath, minSizeBytes = VideoProcessor.MIN_LENIENT_SIZE)) {
                SystemLogsManager.addLog(
                    "ERROR",
                    "فشل توليد الإطار المحلي للمشهد $index — لن يدخل الملف الفاشل في الدمج 🔴",
                    Color(0xFFEF4444)
                )
                out.delete()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error generating fallback solid video", t)
            out.delete()
        }
        out
    }
}
