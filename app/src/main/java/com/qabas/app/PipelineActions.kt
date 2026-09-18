package com.qabas.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * إجراءات فعلية قابلة للتنفيذ من داخل بطاقات تشخيص «طبيب المسار» —
 * بدل ما يقرأ المطور حلاً نصياً وينفّذه يدوياً، بعض الحلول تُنفَّذ بضغطة واحدة.
 */
object PipelineActions {

    data class ActionResult(val success: Boolean, val message: String)

    /** ينظّف كاش محرك الفيديو (qabas_engine) بالكامل — يحرر مساحة فوراً لحل storage_low. */
    suspend fun cleanEngineCache(context: Context): ActionResult = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "qabas_engine")
            if (!dir.exists()) return@withContext ActionResult(true, "لا يوجد كاش لتنظيفه — المساحة فاضية أصلاً")
            var freedBytes = 0L
            var deletedCount = 0
            dir.listFiles()?.forEach { f ->
                val size = f.length()
                if (f.delete()) {
                    freedBytes += size
                    deletedCount++
                }
            }
            val freedMB = freedBytes / (1024 * 1024)
            ActionResult(true, "تم حذف $deletedCount ملف وتحرير ${freedMB}MB")
        } catch (e: Exception) {
            ActionResult(false, "فشل تنظيف الكاش: ${e.message ?: "خطأ غير معروف"}")
        }
    }

    /**
     * فحص سريع مستقل لمحرك FFmpegKit — ينفّذ أمراً بسيطاً بلا أي وسائط خارجية
     * (مصدر lavfi داخلي) ليؤكد أن المحرك أصلاً ينفّذ أوامر بنجاح على هذا الجهاز،
     * قبل تضييع وقت بتشغيلة إنتاج كاملة فاشلة.
     */
    suspend fun selfTestFFmpeg(context: Context): ActionResult = withContext(Dispatchers.IO) {
        try {
            val testDir = File(context.cacheDir, "qabas_selftest")
            if (!testDir.exists()) testDir.mkdirs()
            val outPath = File(testDir, "selftest_${System.currentTimeMillis()}.mp4").absolutePath
            val command = "-y -f lavfi -i \"color=c=black:s=64x64:d=1\" -c:v libx264 -preset ultrafast -t 1 -pix_fmt yuv420p \"$outPath\""
            val ok = VideoProcessor.executeCommand(command, "فحص ذاتي لمحرك FFmpegKit", timeoutMs = 20_000L)
            val valid = ok && VideoProcessor.isValidVideoFile(outPath, minSizeBytes = VideoProcessor.MIN_LENIENT_SIZE)
            File(outPath).delete()
            if (valid) {
                ActionResult(true, "FFmpegKit يعمل بنجاح على هذا الجهاز ✅ — أي فشل بالإنتاج سببه محتوى/شبكة، لا المحرك نفسه.")
            } else {
                ActionResult(false, "فشل تنفيذ أمر FFmpeg بسيط بلا وسائط خارجية 🔴 — هذا يؤكد أن المشكلة بالمحرك نفسه (native library) لا بمحتوى مشهد معيّن. راجع Logcat عن UnsatisfiedLinkError.")
            }
        } catch (t: Throwable) {
            ActionResult(false, "استثناء أثناء الفحص الذاتي: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}
