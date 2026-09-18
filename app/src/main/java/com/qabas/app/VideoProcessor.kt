package com.qabas.app

import android.content.Context
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

enum class ExportQualityPreset(
    val label: String,
    val platformTag: String,
    val preset: String,
    val crf: Int,
    val videoBitrate: String,
    val audioBitrate: String,
    val targetFps: Int
) {
    FAST("شورتس وسريع (1080p Fast)", "YouTube Shorts", "fast", 24, "8M", "128k", 30),
    BALANCED("إنستغرام وتيك توك (1080p 60fps)", "Instagram Reels / TikTok", "medium", 20, "15M", "192k", 60),
    HIGH("سينمائي فائق الدقة (4K Master)", "Cinematic 4K HDR", "slow", 17, "25M", "320k", 60)
}

object VideoProcessor {
    private const val TAG = "VideoProcessor"

    /** عتبات الحجم الأدنى الموحدة للملفات — تُستخدم في كل مكان لضمان اتساق الفحص */
    const val MIN_SCENE_SIZE = 8_000L    // مشهد واحد صالح بعد معالجة FFmpeg
    const val MIN_OUTPUT_SIZE = 15_000L  // ملف فيديو نهائي مُصدّر
    const val MIN_LENIENT_SIZE = 5_000L  // فحص متساهل عند الطوارئ/الدمج

    var currentQualityPreset: ExportQualityPreset = ExportQualityPreset.BALANCED

    /**
     * Computes a realistic timeout based on the nature of the FFmpeg operation.
     * Prevents premature kills on mid-range devices while still protecting against freezes.
     */
    fun smartTimeoutMs(operationDescription: String, command: String = ""): Long {
        val desc = operationDescription.lowercase(Locale.ROOT)
        val cmd = command.lowercase(Locale.ROOT)
        return when {
            desc.contains("دمج مشاهد") || desc.contains("concat") || cmd.contains("filter_complex") && cmd.contains("concat") -> 120_000L
            desc.contains("انتقالات") || desc.contains("xfade") || cmd.contains("xfade") -> 150_000L
            desc.contains("توليد مشهد") || desc.contains("من الصورة") || cmd.contains("-loop 1") -> 60_000L
            desc.contains("تدريج") || desc.contains("فلاتر الألوان") || desc.contains("color") -> 75_000L
            desc.contains("نص") || desc.contains("كابشن") || desc.contains("ختم") || desc.contains("overlay") -> 50_000L
            desc.contains("صوت") || desc.contains("دمج التعليق") || desc.contains("محيطي") -> 55_000L
            desc.contains("قص") || desc.contains("trim") -> 50_000L
            currentQualityPreset == ExportQualityPreset.HIGH -> 90_000L
            else -> 60_000L
        }
    }

    /**
     * Real validation: file must exist, have meaningful size, and contain a readable video track
     * with positive duration. Optionally requires an audio track.
     * Uses MediaMetadataRetriever (no extra FFmpeg cost).
     */
    fun isValidVideoFile(
        path: String,
        minSizeBytes: Long = MIN_OUTPUT_SIZE,
        requireAudio: Boolean = false
    ): Boolean {
        val file = File(path)
        if (!file.exists() || file.length() < minSizeBytes) return false

        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val videoOk = hasVideo == "yes" || hasVideo == null
            val audioOk = !requireAudio || hasAudio == "yes"
            durationMs >= 800L && (videoOk || durationMs > 0L) && audioOk
        } catch (e: Exception) {
            Log.w(TAG, "isValidVideoFile failed for $path: ${e.message}")
            file.length() >= minSizeBytes * 3
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    /** Returns true if the file has a detectable audio track. */
    fun hasAudioTrack(path: String): Boolean {
        if (!File(path).exists()) return false
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (_: Exception) {
            false
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    /** Cancels any running FFmpeg session (used by UI cancel). */
    fun cancelAll() { try { FFmpegKit.cancel() } catch (_: Throwable) {} }

    /** Concatenates multiple audio files into one (single narration track). */
    suspend fun concatAudios(context: Context, audioPaths: List<String>, outputPath: String): Boolean {
        if (audioPaths.isEmpty()) return false
        if (audioPaths.size == 1) {
            return try { File(audioPaths[0]).copyTo(File(outputPath), overwrite = true); true } catch (_: Exception) { false }
        }
        val listFile = File(context.cacheDir, "qabas_audio_list_${System.currentTimeMillis()}.txt")
        return try {
            listFile.writeText(audioPaths.joinToString("\n") { "file '${File(it).absolutePath.replace("'", "'\\''")}'" })
            executeCommand("-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c:a aac -b:a 128k -ar 44100 -ac 2 \"$outputPath\"", "دمج التعليق الصوتي الموحد")
        } finally { try { listFile.delete() } catch (_: Exception) {} }
    }

    /**
     * Executes an FFmpeg command with intelligent timeout.
     * Timeout scales with operation type and quality preset to avoid false failures on real devices.
     */
    suspend fun executeCommand(
        command: String,
        operationDescription: String = "",
        timeoutMs: Long = -1L
    ): Boolean = withContext(Dispatchers.IO) {
        val effectiveTimeout = if (timeoutMs > 0) timeoutMs else smartTimeoutMs(operationDescription, command)
        Log.d(TAG, "Executing FFmpeg command (timeout=${effectiveTimeout}ms): $command")

        val result = withTimeoutOrNull(effectiveTimeout) {
            FFmpegKit.execute(command)
        }

        if (result == null) {
            Log.e(TAG, "FFmpeg command TIMED OUT after ${effectiveTimeout}ms: $operationDescription")
            SystemLogsManager.addLog(
                "ERROR",
                "انتهت مهلة معالجة الفيديو (${effectiveTimeout / 1000}ث) | $operationDescription 🔴",
                Color(0xFFEF4444)
            )
            try { FFmpegKit.cancel() } catch (_: Throwable) {}
            return@withContext false
        }

        val session = result
        val returnCode = session.returnCode

        if (ReturnCode.isSuccess(returnCode)) {
            Log.d(TAG, "FFmpeg command executed successfully: $operationDescription")
            if (operationDescription.isNotBlank()) {
                SystemLogsManager.addLog("FFMPEG", "نجاح: $operationDescription ✅", Color(0xFF4CAF50))
            }
            true
        } else if (ReturnCode.isCancel(returnCode)) {
            Log.d(TAG, "FFmpeg command cancelled: $operationDescription")
            SystemLogsManager.addLog("WARN", "تم إلغاء عملية المونتاج: $operationDescription ⚠️", Color(0xFFFFB300))
            false
        } else {
            val errorLog = session.allLogsAsString?.takeLast(300) ?: "رمز الخطأ: $returnCode"
            Log.e(TAG, "FFmpeg command failed with return code $returnCode and output: $errorLog")
            val arabicMsg = when {
                errorLog.contains("No such file", ignoreCase = true) -> "تعذر العثور على ملف الوسائط المصدر"
                errorLog.contains("Invalid data", ignoreCase = true) -> "صيغة الفيديو أو الصوت غير متوافقة"
                errorLog.contains("Out of memory", ignoreCase = true) -> "ذاكرة الجهاز ممتلئة أثناء المعالجة"
                else -> "فشل في معالجة الفيديو (${returnCode.value})"
            }
            SystemLogsManager.addLog("ERROR", "$arabicMsg | $operationDescription 🔴", Color(0xFFEF4444))
            false
        }
    }

    /**
     * Merges a video file and an audio file with precise audio sync.
     * Uses `-c:v copy` if possible or fast re-encode, with AAC audio and padding/shortest to ensure clean sync.
     */
    suspend fun mergeAudioVideo(context: Context, videoPath: String, audioPath: String, outputPath: String): Boolean {
        // Robust merge: maps video and audio, resamples audio cleanly to 44.1kHz stereo to avoid desync
        val command = "-y -i \"$videoPath\" -i \"$audioPath\" -c:v copy -c:a aac -b:a ${currentQualityPreset.audioBitrate} -ar 44100 -ac 2 -map 0:v:0 -map 1:a:0 -shortest \"$outputPath\""
        val success = executeCommand(command, "دمج التعليق الصوتي مع مشهد الفيديو")
        if (success && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)) return true

        // Fallback: re-encode video stream if stream-copy fails due to codec mismatch
        val fallbackCmd = "-y -i \"$videoPath\" -i \"$audioPath\" -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -c:a aac -b:a 128k -ar 44100 -ac 2 -map 0:v:0 -map 1:a:0 -shortest \"$outputPath\""
        val fallbackOk = executeCommand(fallbackCmd, "محاولة دمج بديلة للصوت والفيديو")
        return fallbackOk && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)
    }

    suspend fun mergeAmbientAudio(context: Context, videoPath: String, ambientAudioPath: String, outputPath: String): Boolean {
        // ثورة قبس (Audio Ducking): خفض صوت الخلفية تلقائياً عند حديث المعلق الصوتي
        val command = "-y -i \"$videoPath\" -i \"$ambientAudioPath\" -filter_complex \"[1:a]volume=0.4[bg];[bg][0:a]sidechaincompress=threshold=0.05:ratio=4:attack=5:release=500[ducked_bg];[0:a][ducked_bg]amix=inputs=2:duration=first:dropout_transition=2[a]\" -map 0:v -map \"[a]\" -c:v copy -c:a aac -b:a ${currentQualityPreset.audioBitrate} \"$outputPath\""
        val success = executeCommand(command, "خلط الصوت المحيطي مع Audio Ducking")
        if (success && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)) return true

        Log.w(TAG, "mergeAmbientAudio failed — keeping video without ambient mix")
        SystemLogsManager.addLog("WARN", "تعذر خلط الصوت المحيطي — تم الاحتفاظ بالمشهد بدون مزج", Color(0xFFE8C547))
        return try {
            File(videoPath).copyTo(File(outputPath), overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Adds text overlay to a video using a safe Android Canvas Bitmap PNG overlay placed in the 9:16 Safe Zone.
     */
    suspend fun addTextOverlay(context: Context, videoPath: String, text: String, outputPath: String): Boolean {
        val overlayImg = createArabicTextBitmap(context, text, isWatermark = false, styleAnalysis = null)
        if (overlayImg != null && overlayImg.exists()) {
            val command = "-y -i \"$videoPath\" -i \"${overlayImg.absolutePath}\" -filter_complex \"[0:v][1:v]overlay=(main_w-overlay_w)/2:(main_h*0.68)-(overlay_h/2)\" -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -c:a copy \"$outputPath\""
            val success = executeCommand(command, "إضافة النص والكابشن للمشهد داخل المنطقة الآمنة")
            overlayImg.delete()
            if (success && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)) return true
        }
        // لا نكذب: ننسخ الفيديو الأصلي ونسجل الفشل صراحة
        Log.w(TAG, "addTextOverlay failed — returning original video without text overlay")
        SystemLogsManager.addLog("WARN", "تعذر إضافة النص على الفيديو — تم الاحتفاظ بالمشهد بدون كابشن", Color(0xFFE8C547))
        return try {
            File(videoPath).copyTo(File(outputPath), overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Adds text overlay styled dynamically based on Cloned Video Style Analysis placed in the 9:16 Safe Zone.
     * يدعم WordByWord الحقيقي: كشف تدريجي للكلمات عبر طبقات متعددة + enable زمني.
     */
    suspend fun addStyledTextOverlay(
        context: Context,
        videoPath: String,
        text: String,
        styleAnalysis: VideoStyleAnalysis?,
        outputPath: String
    ): Boolean {
        if (styleAnalysis == null) {
            return addTextOverlay(context, videoPath, text, outputPath)
        }

        val animBlob = (
            styleAnalysis.typographyStyle + " " +
                styleAnalysis.keywords.joinToString(" ") + " " +
                styleAnalysis.movementPatterns
            ).lowercase(Locale.ROOT)

        // ثورة قبس (Kinetic Typography): تفعيل دائماً للكابشن الحركي (كلمة بكلمة)
        val ok = addWordByWordOverlay(context, videoPath, text, styleAnalysis ?: VideoStyleAnalysis(), outputPath)
        if (ok) return true
        // سقوط آمن إلى كابشن ثابت

        val overlayImg = createArabicTextBitmap(context, text, isWatermark = false, styleAnalysis = styleAnalysis)
        if (overlayImg != null && overlayImg.exists()) {
            val posBlob = (
                styleAnalysis.typographyStyle + " " + styleAnalysis.keywords.joinToString(" ")
            ).lowercase(Locale.ROOT)
            val yExpr = when {
                posBlob.contains("pos=top") || posBlob.contains("pos:top") || posBlob.contains("أعلى") ->
                    "(main_h*0.12)"
                posBlob.contains("pos=center") || posBlob.contains("pos:center") || posBlob.contains("وسط") || posBlob.contains("منتصف") ->
                    "(main_h-overlay_h)/2"
                else ->
                    "(main_h*0.68)-(overlay_h/2)"
            }
            val command = "-y -i \"$videoPath\" -i \"${overlayImg.absolutePath}\" -filter_complex \"[0:v][1:v]overlay=(main_w-overlay_w)/2:$yExpr\" -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -c:a copy \"$outputPath\""
            val success = executeCommand(command, "إضافة نصوص الأسلوب المخصص داخل المنطقة الآمنة")
            overlayImg.delete()
            if (success && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)) return true
        }
        return addTextOverlay(context, videoPath, text, outputPath)
    }

    /**
     * Word-by-Word حقيقي: يقسم النص إلى كلمات، يولّد PNG لكل مرحلة،
     * ثم يركّب طبقات overlay مع enable='gte(t,start)' لإظهار تدريجي.
     */
    private suspend fun addWordByWordOverlay(
        context: Context,
        videoPath: String,
        text: String,
        styleAnalysis: VideoStyleAnalysis,
        outputPath: String
    ): Boolean {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 1) return false

        val posBlob = (
            styleAnalysis.typographyStyle + " " + styleAnalysis.keywords.joinToString(" ")
        ).lowercase(Locale.ROOT)
        val yExpr = when {
            posBlob.contains("pos=top") || posBlob.contains("pos:top") || posBlob.contains("أعلى") ->
                "(main_h*0.12)"
            posBlob.contains("pos=center") || posBlob.contains("pos:center") || posBlob.contains("وسط") || posBlob.contains("منتصف") ->
                "(main_h-overlay_h)/2"
            else ->
                "(main_h*0.68)-(overlay_h/2)"
        }

        val maxStages = 10
        val stages = if (words.size <= maxStages) {
            (1..words.size).map { n -> words.take(n).joinToString(" ") }
        } else {
            val step = (words.size / maxStages.toDouble()).coerceAtLeast(1.0)
            (1..maxStages).map { i ->
                val n = kotlin.math.min(words.size, (i * step).toInt().coerceAtLeast(i))
                words.take(n).joinToString(" ")
            }.distinct()
        }

        val bitmaps = mutableListOf<File>()
        try {
            for (stageText in stages) {
                val f = createArabicTextBitmap(context, stageText, isWatermark = false, styleAnalysis = styleAnalysis)
                    ?: return false
                bitmaps += f
            }
            if (bitmaps.isEmpty()) return false

            val durationSec = try {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(videoPath)
                val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 6000L
                r.release()
                (ms / 1000.0).coerceIn(2.0, 30.0)
            } catch (_: Exception) {
                6.0
            }

            val leadIn = (durationSec * 0.08).coerceIn(0.15, 0.6)
            val usable = (durationSec - leadIn) * 0.88
            val interval = usable / bitmaps.size

            val inputs = StringBuilder("-y -i \"$videoPath\"")
            bitmaps.forEach { inputs.append(" -i \"${it.absolutePath}\"") }

            val fc = StringBuilder()
            var prev = "[0:v]"
            for (i in bitmaps.indices) {
                val startT = "%.2f".format(leadIn + i * interval)
                val outLabel = if (i == bitmaps.lastIndex) "[vout]" else "[v${i + 1}]"
                val inputIdx = i + 1
                fc.append("$prev[$inputIdx:v]overlay=(main_w-overlay_w)/2:$yExpr:enable='gte(t\\,$startT)'$outLabel")
                if (i < bitmaps.lastIndex) fc.append(";")
                prev = outLabel
            }

            val command = "$inputs -filter_complex \"$fc\" -map \"[vout]\" -map 0:a? -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -c:a aac -b:a ${currentQualityPreset.audioBitrate} -shortest \"$outputPath\""
            Log.d(TAG, "WordByWord stages=${bitmaps.size} interval=${"%.2f".format(interval)}s pos=$yExpr")
            SystemLogsManager.addLog(
                "STYLE",
                "كابشن WordByWord: ${bitmaps.size} مراحل | موضع من التوجيه | مدة≈${"%.1f".format(durationSec)}ث",
                Color(0xFFE8C547)
            )
            val success = executeCommand(command, "إضافة كابشن WordByWord (${bitmaps.size} مراحل)")
            return success && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)
        } catch (e: Exception) {
            Log.e(TAG, "WordByWord overlay failed", e)
            return false
        } finally {
            bitmaps.forEach { runCatching { it.delete() } }
        }
    }

    suspend fun addDeveloperWatermarkOverlay(context: Context, videoPath: String, watermarkText: String, outputPath: String): Boolean {
        val overlayImg = createArabicTextBitmap(context, watermarkText, isWatermark = true, styleAnalysis = null)
        if (overlayImg != null && overlayImg.exists()) {
            val command = "-y -i \"$videoPath\" -i \"${overlayImg.absolutePath}\" -filter_complex \"[0:v][1:v]overlay=(main_w-overlay_w)/2:main_h*0.12\" -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -c:a copy \"$outputPath\""
            val success = executeCommand(command, "طباعة الختم والعلامة المائية في المنطقة الآمنة")
            overlayImg.delete()
            if (success && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)) return true
        }
        Log.w(TAG, "addDeveloperWatermarkOverlay failed — returning original video without watermark")
        SystemLogsManager.addLog("WARN", "تعذر طباعة الختم — تم الاحتفاظ بالمشهد بدون علامة مائية", Color(0xFFE8C547))
        return try {
            File(videoPath).copyTo(File(outputPath), overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Renders Arabic text onto a transparent Bitmap with correct shaping, RTL, and multi-line wrapping.
     * Uses StaticLayout + Typeface that supports Arabic script properly.
     * This is the single source of truth for all on-video text (captions + watermark).
     */
    private fun createArabicTextBitmap(
        context: Context,
        text: String,
        isWatermark: Boolean,
        styleAnalysis: VideoStyleAnalysis?
    ): File? {
        if (text.isBlank()) return null
        var bitmap: android.graphics.Bitmap? = null
        return try {
            val width = if (isWatermark) 920 else 960
            val maxHeight = if (isWatermark) 140 else 220
            val horizontalPadding = 28f
            val verticalPadding = 18f

            val typography = (styleAnalysis?.typographyStyle ?: "").lowercase(Locale.ROOT)
            val colorsRaw = styleAnalysis?.dominantColors ?: ""
            val colors = colorsRaw.lowercase(Locale.ROOT)
            val keywordsBlob = (styleAnalysis?.keywords?.joinToString(" ") ?: "")
            val parseBlob = colorsRaw + " " + keywordsBlob

            // أولوية: hex من StyleDirective (primary / bg) إن وُجد
            val directivePrimary = extractTaggedHex(parseBlob, "primary")
                ?: extractTaggedHex(parseBlob, "color")
            val directiveBg = extractTaggedHex(parseBlob, "bg")
                ?: extractTaggedHex(parseBlob, "background")

            fun withAlpha(hex: String, alpha: String = "E6"): String {
                val h = hex.removePrefix("#")
                return if (h.length == 6) "#$alpha$h" else hex
            }

            val (bgColor, borderColor, textColor) = when {
                isWatermark -> Triple("#E60F172A", "#FFD700", "#FFD700")
                directivePrimary != null -> {
                    val bg = directiveBg?.let { withAlpha(it, "E6") } ?: "#E60B0F19"
                    Triple(bg, directivePrimary, directivePrimary)
                }
                typography.contains("أصفر") || typography.contains("نيون") || typography.contains("neon") || colors.contains("neon") ->
                    Triple("#E60B0F19", "#FFE500", "#FFE500")
                typography.contains("أخضر") || typography.contains("emerald") || colors.contains("أخضر") || colors.contains("emerald") || colors.contains("cool_emerald") ->
                    Triple("#E60F291E", "#10B981", "#10B981")
                typography.contains("برتقالي") || typography.contains("غروب") || colors.contains("برتقالي") || colors.contains("sunset") || colors.contains("soft_desert") ->
                    Triple("#E61E1005", "#F97316", "#FB923C")
                typography.contains("أزرق") || typography.contains("سماوي") || colors.contains("أزرق") ->
                    Triple("#E60A192F", "#38BDF8", "#38BDF8")
                typography.contains("أبيض") || typography.contains("عثماني") || typography.contains("أميري") ->
                    Triple("#D90B0F19", "#FFFFFF", "#FFFFFF")
                typography.contains("كوفي") || typography.contains("ذهبي") || typography.contains("gold") || colors.contains("ذهبي") || colors.contains("warm_gold") ->
                    Triple("#E60B0F19", "#E8C547", "#E8C547")
                colors.contains("high_contrast") || colors.contains("أحمر") ->
                    Triple("#E60B0F19", "#EF4444", "#F8FAFC")
                else ->
                    Triple("#E60B0F19", "#8B5CF6", "#F8FAFC")
            }

            val baseTextSize = when {
                isWatermark -> 28f
                typography.contains("عريض") || typography.contains("كبير") -> 36f
                else -> 32f
            }

            // Typeface that correctly shapes Arabic on Android (sans-serif is reliable for Arabic + Latin)
            val typeface = try {
                Typeface.create("sans-serif", if (isWatermark || typography.contains("عريض")) Typeface.BOLD else Typeface.NORMAL)
            } catch (_: Exception) {
                Typeface.DEFAULT_BOLD
            }

            val textPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor(textColor)
                textSize = baseTextSize
                this.typeface = typeface
                isFakeBoldText = isWatermark
                textAlign = android.graphics.Paint.Align.CENTER
            }

            val availableWidth = (width - horizontalPadding * 2).toInt().coerceAtLeast(200)
            val cleanText = text.trim().replace("\n", " ").take(120)

            @Suppress("DEPRECATION")
            val staticLayout = StaticLayout(
                cleanText,
                textPaint,
                availableWidth,
                Layout.Alignment.ALIGN_CENTER,
                1.15f,
                0f,
                true
            )

            val textBlockHeight = staticLayout.height
            val height = (textBlockHeight + verticalPadding * 2).toInt().coerceIn(90, maxHeight)

            bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)

            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor(bgColor)
                style = android.graphics.Paint.Style.FILL
            }
            val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor(borderColor)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = if (isWatermark) 4f else 5f
            }
            val rect = android.graphics.RectF(10f, 8f, width - 10f, height - 8f)
            canvas.drawRoundRect(rect, 20f, 20f, bgPaint)
            canvas.drawRoundRect(rect, 20f, 20f, borderPaint)

            // Center the StaticLayout vertically and horizontally
            canvas.save()
            val dx = (width - availableWidth) / 2f
            val dy = (height - textBlockHeight) / 2f
            canvas.translate(dx, dy)
            staticLayout.draw(canvas)
            canvas.restore()

            val prefix = if (isWatermark) "watermark" else "caption"
            val file = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Arabic text bitmap", e)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Trims a video from startTime to endTime (in seconds) with 9:16 standard scaling.
     */
    suspend fun trimVideo(context: Context, videoPath: String, startTime: Int, duration: Int, outputPath: String): Boolean {
        val command = "-y -ss $startTime -i \"$videoPath\" -t $duration -vf \"scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setsar=1,format=yuv420p\" -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -r 30 -c:a aac -b:a ${currentQualityPreset.audioBitrate} \"$outputPath\""
        val success = executeCommand(command, "قص وتعديل مدة المشهد وضبط أبعاد 9:16")
        if (success && File(outputPath).exists() && File(outputPath).length() > 0) return true

        val fallbackCmd = "-y -ss $startTime -i \"$videoPath\" -t $duration -vf \"scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p\" -c:v libx264 -preset ultrafast -crf 26 -r 30 -c:a aac \"$outputPath\""
        return executeCommand(fallbackCmd, "قص المشهد بالمعالجة السريعة")
    }

    /**
     * Generates a video clip from a single static image.
     * PRIORITY: Reliability over cinematic effects so the app never freezes.
     * Tries simple stable path first. Heavy zoompan is optional and timed.
     */

    /** يستخرج نوع الحركة من StyleDirective / movementPatterns (Optical Flow → motion=) */
    private fun resolveMotionType(styleAnalysis: VideoStyleAnalysis?): String {
        if (styleAnalysis == null) return "slow_zoom"
        val blob = (
            styleAnalysis.movementPatterns + " " +
            styleAnalysis.keywords.joinToString(" ") + " " +
            styleAnalysis.dominantColors + " " +
            styleAnalysis.overallRhythm
        ).lowercase(Locale.ROOT)
        return when {
            blob.contains("motion=punch_in") || blob.contains("punch_in") || blob.contains("punch") -> "punch_in"
            blob.contains("motion=pan") || (blob.contains("pan") && !blob.contains("expand")) -> "pan"
            blob.contains("motion=static") || blob.contains("static") || blob.contains("ثابت") -> "static"
            blob.contains("motion=slow_zoom") || blob.contains("slow_zoom") || blob.contains("zoom") || blob.contains("زووم") -> "slow_zoom"
            else -> "slow_zoom"
        }
    }

    private fun buildMotionZoompanFilter(motionType: String, durationSec: Int): String? {
        val frames = (durationSec * 24).coerceIn(48, 1440)
        return when (motionType) {
            "static" -> null
            "pan" ->
                "scale=1200:2133:force_original_aspect_ratio=increase,crop=1080:1920," +
                "zoompan=z='1.08':x='if(eq(on,1),0,x+1.2)':y='ih/2-(ih/zoom/2)':d=$frames:s=1080x1920:fps=24,setsar=1,format=yuv420p"
            "punch_in" ->
                "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920," +
                "zoompan=z='min(zoom+0.0025,1.28)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=1080x1920:fps=24,setsar=1,format=yuv420p"
            else -> // slow_zoom
                "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920," +
                "zoompan=z='min(zoom+0.0012,1.15)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=1080x1920:fps=24,setsar=1,format=yuv420p"
        }
    }

    suspend fun generateVideoFromImage(
        context: Context,
        imagePath: String,
        durationInSeconds: Int,
        outputPath: String,
        styleAnalysis: VideoStyleAnalysis? = null
    ): Boolean {
        val safeDuration = durationInSeconds.coerceIn(3, 60)
        val imgFile = File(imagePath)
        if (!imgFile.exists() || imgFile.length() == 0L) {
            Log.e(TAG, "generateVideoFromImage: image file missing or empty → $imagePath")
            return false
        }

        // مسار الحركة من Optical Flow عبر StyleDirective
        val motionType = resolveMotionType(styleAnalysis)
        val motionFilter = buildMotionZoompanFilter(motionType, safeDuration)

        SystemLogsManager.addLog(
            "STYLE",
            "حركة المشهد من التوجيه: $motionType",
            Color(0xFFE8C547)
        )

        // 1) إن طُلبت حركة — جرّب zoompan المناسب
        if (motionFilter != null) {
            val motionCmd = "-y -loop 1 -i \"$imagePath\" -vf \"$motionFilter\" -c:v libx264 -t $safeDuration -preset ultrafast -crf 28 -r 24 -pix_fmt yuv420p \"$outputPath\""
            val motionSuccess = executeCommand(motionCmd, "توليد مشهد بحركة $motionType")
            if (motionSuccess && isValidVideoFile(outputPath)) {
                return true
            }
            Log.w(TAG, "motion path ($motionType) failed — falling back to static")
        }

        // 2) مسار ثابت موثوق (static أو سقوط آمن)
        val simpleCmd = "-y -loop 1 -i \"$imagePath\" -vf \"scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p\" -c:v libx264 -t $safeDuration -preset ultrafast -crf 26 -r 30 -pix_fmt yuv420p \"$outputPath\""
        val simpleSuccess = executeCommand(simpleCmd, "توليد مشهد أساسي موثوق من الصورة")
        if (simpleSuccess && isValidVideoFile(outputPath)) {
            return true
        }

        // 3) طارئ
        val lastResort = "-y -loop 1 -i \"$imagePath\" -c:v libx264 -t $safeDuration -preset ultrafast -crf 28 -r 24 -vf \"scale=720:1280,format=yuv420p\" \"$outputPath\""
        return executeCommand(lastResort, "توليد مشهد طارئ بسيط") && isValidVideoFile(outputPath, minSizeBytes = MIN_LENIENT_SIZE)
    }

    suspend fun concatenateVideos(context: Context, videoPaths: List<String>, outputPath: String): Boolean {
        if (videoPaths.isEmpty()) return false
        if (videoPaths.size == 1) {
            val src = File(videoPaths[0])
            val dst = File(outputPath)
            if (src.exists()) {
                src.copyTo(dst, overwrite = true)
                return true
            }
            return false
        }

        val listFile = createTempFile(context, "concat_list_", ".txt")
        listFile.writeText(videoPaths.joinToString("\n") { "file '${File(it).absolutePath}'" })

        val command = "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy \"$outputPath\""
        val success = executeCommand(command, "دمج مشاهد الفيديو متسلسلة")
        listFile.delete()
        if (success && isValidVideoFile(outputPath)) return true

        // Fallback: re-encode concat filter complex
        val inputs = videoPaths.joinToString(" ") { "-i \"$it\"" }
        val filterStreams = videoPaths.indices.joinToString("") { "[$it:v][$it:a]" }
        val fallbackCmd = "-y $inputs -filter_complex \"${filterStreams}concat=n=${videoPaths.size}:v=1:a=1[v][a]\" -map \"[v]\" -map \"[a]\" -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -c:a aac \"$outputPath\""
        val fallbackOk = executeCommand(fallbackCmd, "دمج المشاهد المعقد بالفلتر المركب")
        return fallbackOk && isValidVideoFile(outputPath)
    }

    /**
     * Concatenates multiple video files with cinematic transitions using FFmpeg filter complex (xfade).
     * Falls back to standard concatenation if xfade fails.
     */
    suspend fun concatenateVideosWithTransitions(
        context: Context,
        videoPaths: List<String>,
        transitionType: String,
        outputPath: String
    ): Boolean {
        if (videoPaths.isEmpty()) return false
        if (videoPaths.size == 1) return concatenateVideos(context, videoPaths, outputPath)

        val normalizedTransition = transitionType.lowercase(Locale.ROOT)
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
        val xfadeFilterName = when {
            normalizedTransition.contains("dissolve") || normalizedTransition.contains("تلاشيسينمائي") -> "dissolve"
            normalizedTransition.contains("slideleft") || normalizedTransition.contains("انزلاقيسار") -> "slideleft"
            normalizedTransition.contains("slideright") || normalizedTransition.contains("انزلاقيمين") -> "slideright"
            normalizedTransition.contains("wipeleft") || normalizedTransition.contains("paperwipe") || normalizedTransition == "مسح" -> "wipeleft"
            normalizedTransition.contains("wiperight") -> "wiperight"
            normalizedTransition.contains("zoomin") || normalizedTransition.contains("glitch") ||
                normalizedTransition.contains("circlecrop") || normalizedTransition.contains("مسحدائري") -> "circleopen"
            normalizedTransition.contains("fadeblack") || normalizedTransition.contains("تلاشياسود") -> "fadeblack"
            normalizedTransition.contains("fade") || normalizedTransition.contains("تلاشي") -> "fade"
            else -> "dissolve"
        }

        val inputsStr = videoPaths.joinToString(" ") { "-i \"$it\"" }
        try {
            val durations = videoPaths.map { path ->
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(path)
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
                    r.release()
                    (ms / 1000.0).coerceAtLeast(1.5)
                } catch (_: Exception) {
                    5.0
                }
            }

            val allHaveAudio = videoPaths.all { hasAudioTrack(it) }
            val transitionDuration = 0.75

            val fc = StringBuilder()
            var accumulatedDuration = 0.0
            var prevV = "[0:v]"
            var prevA = "[0:a]"

            for (i in 1 until videoPaths.size) {
                val curDuration = durations[i - 1]
                accumulatedDuration += curDuration
                val offset = (accumulatedDuration - (i * transitionDuration)).coerceAtLeast(0.5)
                val nextV = if (i == videoPaths.size - 1) "[v]" else "[v$i]"
                fc.append("$prevV[$i:v]xfade=transition=$xfadeFilterName:duration=$transitionDuration:offset=${String.format(Locale.US, "%.2f", offset)}$nextV")
                if (i < videoPaths.size - 1 || allHaveAudio) fc.append(";")
                prevV = nextV

                if (allHaveAudio) {
                    val nextA = if (i == videoPaths.size - 1) "[a]" else "[a$i]"
                    fc.append("$prevA[$i:a]acrossfade=d=$transitionDuration$nextA")
                    if (i < videoPaths.size - 1) fc.append(";")
                    prevA = nextA
                }
            }

            val audioMap = if (allHaveAudio) "-map \"[a]\"" else "-map 0:a?"
            val command = "-y $inputsStr -filter_complex \"$fc\" -map \"[v]\" $audioMap -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -c:a aac -b:a ${currentQualityPreset.audioBitrate} \"$outputPath\""
            val success = executeCommand(command, "دمج المشاهد (${videoPaths.size}) مع انتقالات سنيمائية ($xfadeFilterName)")
            if (success && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)) return true
        } catch (e: Exception) {
            Log.w(TAG, "xfade multi-scene failed: ${e.message}")
        }

        // Fallback to robust concatenation
        return concatenateVideos(context, videoPaths, outputPath)
    }

    /**
     * Applies FFmpeg filters synthesized directly from a Cloned Video Style Analysis.
     */

    // ─── StyleDirective parsers (hex + filterHint from VideoStyleAnalysis strings) ───

    private fun extractTaggedHex(source: String, tag: String): String? {
        // matches primary:#E8C547 or primary=#E8C547 or primary: #E8C547
        val pattern = Regex("""(?i)${tag}\s*[=:]\s*#?([0-9A-Fa-f]{6})""")
        val m = pattern.find(source) ?: return null
        return "#${m.groupValues[1].uppercase(Locale.ROOT)}"
    }

    private fun extractFilterHint(styleAnalysis: VideoStyleAnalysis): String {
        val blob = buildString {
            append(styleAnalysis.dominantColors)
            append(' ')
            append(styleAnalysis.keywords.joinToString(" "))
            append(' ')
            append(styleAnalysis.movementPatterns)
            append(' ')
            append(styleAnalysis.typographyStyle)
        }.lowercase(Locale.ROOT)
        return when {
            blob.contains("soft_desert") || blob.contains("صحراء") || blob.contains("desert") -> "soft_desert"
            blob.contains("cool_emerald") || blob.contains("emerald") || blob.contains("زمرد") -> "cool_emerald"
            blob.contains("high_contrast_dark") || (blob.contains("contrast") && blob.contains("dark")) -> "high_contrast_dark"
            blob.contains("ai_violet") || blob.contains("بنفسج") || blob.contains("violet") || blob.contains("cyan") || blob.contains("سيان") -> "ai_violet"
            blob.contains("warm_gold") || blob.contains("ذهبي") || blob.contains("gold") -> "warm_gold"
            blob.contains("filter=") -> {
                Regex("""filter\s*[=:]\s*([a-z_]+)""", RegexOption.IGNORE_CASE)
                    .find(blob)?.groupValues?.getOrNull(1) ?: "ai_violet"
            }
            else -> "ai_violet"
        }
    }

    private fun hexToRgb01(hex: String): Triple<Float, Float, Float>? {
        val h = hex.removePrefix("#").trim()
        if (h.length != 6) return null
        return try {
            val r = h.substring(0, 2).toInt(16) / 255f
            val g = h.substring(2, 4).toInt(16) / 255f
            val b = h.substring(4, 6).toInt(16) / 255f
            Triple(r, g, b)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * يبني فلتر FFmpeg شبه LUT من filterHint + ألوان hex.
     * أقوى من التخمين بالكلمات — تدرجات احترافية لكل أسلوب.
     */
    private fun buildDirectiveColorFilter(
        filterHint: String,
        primaryHex: String?,
        bgHex: String?
    ): String {
        // فلاتر شبه LUT: eq + colorchannelmixer + (اختياري) curves تقريبي عبر gamma
        val base = when (filterHint) {
            "soft_desert" ->
                "eq=contrast=1.14:brightness=0.035:saturation=1.28:gamma=1.05," +
                "colorchannelmixer=1.14:0.06:0.01:0:0.04:0.96:0.03:0:0.02:0.03:0.80:0"
            "cool_emerald" ->
                "eq=contrast=1.22:brightness=-0.01:saturation=1.30:gamma=0.98," +
                "colorchannelmixer=0.86:0.01:0.03:0:0.02:1.14:0.04:0:0.01:0.06:0.96:0"
            "high_contrast_dark" ->
                "eq=contrast=1.42:brightness=-0.055:saturation=1.18:gamma=0.94," +
                "colorchannelmixer=1.08:0:0:0:0:0.92:0:0:0:0:0.88:0"
            "warm_gold" ->
                "eq=contrast=1.20:brightness=0.015:saturation=1.24:gamma=1.03," +
                "colorchannelmixer=1.10:0.05:0.01:0:0.04:1.02:0.01:0:0.01:0.03:0.86:0"
            "ai_violet" ->
                "eq=contrast=1.24:brightness=-0.005:saturation=1.26:gamma=0.985," +
                "colorchannelmixer=0.94:0.07:0.12:0:0.06:0.88:0.12:0:0.12:0.10:1.05:0"
            else ->
                "eq=contrast=1.16:saturation=1.16:gamma=1.02"
        }

        val rgb = primaryHex?.let { hexToRgb01(it) }
        if (rgb != null) {
            val (r, g, b) = rgb
            val rr = (0.90f + r * 0.22f).coerceIn(0.82f, 1.18f)
            val gg = (0.90f + g * 0.22f).coerceIn(0.82f, 1.18f)
            val bb = (0.90f + b * 0.22f).coerceIn(0.82f, 1.18f)
            val mixer =
                "colorchannelmixer=${"%.2f".format(rr)}:0:0:0:0:${"%.2f".format(gg)}:0:0:0:0:${"%.2f".format(bb)}:0"
            return if (base.contains("colorchannelmixer")) {
                base.replace(Regex("colorchannelmixer=[^,]*"), mixer)
            } else {
                "$base,$mixer"
            }
        }
        return base
    }

    private fun buildDirectiveSpeedFilter(styleAnalysis: VideoStyleAnalysis): String? {
        val tempo = (styleAnalysis.transitionSpeed + " " + styleAnalysis.overallRhythm + " " +
            styleAnalysis.movementPatterns + " " + styleAnalysis.keywords.joinToString(" ")).lowercase(Locale.ROOT)
        return when {
            tempo.contains("punch_in") || tempo.contains("سريع") || tempo.contains("خاطف") || tempo.contains("fast") || tempo.contains("ملحمي") ->
                "setpts=0.88*PTS"
            tempo.contains("static") || tempo.contains("خاشع") || tempo.contains("بطيء") || tempo.contains("سكينة") || tempo.contains("slow") || tempo.contains("تأملي") ->
                "setpts=1.08*PTS"
            tempo.contains("pan") ->
                "setpts=1.02*PTS"
            else -> null
        }
    }

    suspend fun applyStyleClonedFilters(
        context: Context,
        videoPath: String,
        styleAnalysis: VideoStyleAnalysis?,
        outputPath: String
    ): Boolean {
        if (styleAnalysis == null) {
            return applyCinematicColorGrading(context, videoPath, "Cinematic", outputPath)
        }

        val blob = styleAnalysis.dominantColors + " " + styleAnalysis.keywords.joinToString(" ")
        val primaryHex = extractTaggedHex(blob, "primary")
            ?: extractTaggedHex(blob, "color")
        val bgHex = extractTaggedHex(blob, "bg")
            ?: extractTaggedHex(blob, "background")
        val filterHint = extractFilterHint(styleAnalysis)

        // 1) فلتر لوني من التوجيه الملموس (filterHint + hex)
        val colorFilter = buildDirectiveColorFilter(filterHint, primaryHex, bgHex)

        // 2) إيقاع زمني من tempo
        val speedFilter = buildDirectiveSpeedFilter(styleAnalysis)

        val combinedVf = if (speedFilter != null) "$colorFilter,$speedFilter" else colorFilter

        val command = "-y -i \"$videoPath\" -vf \"$combinedVf\" -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -c:a copy \"$outputPath\""
        Log.d(TAG, "Cloned Style FFmpeg [$filterHint primary=$primaryHex]: $command")
        SystemLogsManager.addLog(
            "STYLE",
            "تطبيق توجيه ملموس (LUT): $filterHint | primary=${primaryHex ?: "-"} | bg=${bgHex ?: "-"}",
            Color(0xFFE8C547)
        )
        val success = executeCommand(command, "تطبيق فلاتر StyleDirective ($filterHint)")
        if (success && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)) return true
        Log.w(TAG, "applyStyleClonedFilters failed — keeping original without color grade")
        SystemLogsManager.addLog("WARN", "تعذر تطبيق فلاتر الأسلوب — تم الاحتفاظ بالمشهد بدون تدريج لوني", Color(0xFFE8C547))
        return try {
            File(videoPath).copyTo(File(outputPath), overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Applies template color grading filters (Teal & Gold, Sepia, Emerald, High Contrast).
     */
    suspend fun applyCinematicColorGrading(
        context: Context,
        videoPath: String,
        templateName: String,
        outputPath: String
    ): Boolean {
        val lower = templateName.lowercase()
        val eqFilter = when {
            // ثورة قبس (Mood Color Grading) - Dynamic mappings from Gemini
            lower.contains("warm") || lower.contains("golden") || lower.contains("hope") || lower.contains("mercy") ->
                "eq=contrast=1.15:brightness=0.03:saturation=1.25,colorchannelmixer=1.1:0:0:0:0:0.95:0:0:0:0:0.8:0" // Warm, golden, hopeful
            lower.contains("cool") || lower.contains("teal") || lower.contains("sad") || lower.contains("trial") || lower.contains("dark") ->
                "eq=contrast=1.2:brightness=-0.04:saturation=0.85,colorchannelmixer=0.8:0:0:0:0:1.0:0:0:0:0:1.15:0" // Cool, moody, dramatic
            lower.contains("nature") || lower.contains("green") || lower.contains("peace") || lower.contains("serene") ->
                "eq=contrast=1.1:brightness=0.01:saturation=1.3,colorchannelmixer=0.9:0:0:0:0:1.15:0:0:0:0:0.95:0" // Vibrant greens, peaceful
            lower.contains("dramatic") || lower.contains("epic") || lower.contains("high contrast") ->
                "eq=contrast=1.35:brightness=-0.02:saturation=1.1" // High contrast, epic
            // فلاتر الاستوديو المتقدم القديمة كاحتياط
            lower.contains("سينمائي") || lower.contains("سينمائية") ->
                "eq=contrast=1.2:brightness=-0.02:saturation=1.15,colorchannelmixer=0.95:0:0:0:0:1.05:0:0:0:0:1.1:0"
            lower.contains("روحاني") || lower.contains("قرآني") ->
                "eq=contrast=1.1:brightness=0.01:saturation=1.2,colorchannelmixer=0.9:0:0:0:0:1.15:0:0:0:0:0.95:0"
            lower.contains("عتيق") || lower.contains("تراثية") || lower.contains("sepia") ->
                "colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"
            lower.contains("ساطع") || lower.contains("حماسي") || lower.contains("reels") ->
                "eq=contrast=1.3:brightness=0.03:saturation=1.35"
            else -> "eq=contrast=1.08:saturation=1.1"
        }

        val command = "-y -i \"$videoPath\" -vf \"$eqFilter\" -c:v libx264 -preset ${currentQualityPreset.preset} -crf ${currentQualityPreset.crf} -c:a copy \"$outputPath\""
        val success = executeCommand(command, "تطبيق التدريج اللوني (Color Grading: $templateName)")
        if (success && isValidVideoFile(outputPath, minSizeBytes = MIN_SCENE_SIZE)) return true
        Log.w(TAG, "applyCinematicColorGrading failed — keeping original without grade")
        SystemLogsManager.addLog("WARN", "تعذر تطبيق التدريج اللوني — تم الاحتفاظ بالمشهد بدون فلتر", Color(0xFFE8C547))
        return try {
            File(videoPath).copyTo(File(outputPath), overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates a temporary file for processing
     */
    fun createTempFile(context: Context, prefix: String, extension: String): File {
        val outputDir = File(context.cacheDir, "video_processing")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        return File.createTempFile(prefix, extension, outputDir)
    }
}
