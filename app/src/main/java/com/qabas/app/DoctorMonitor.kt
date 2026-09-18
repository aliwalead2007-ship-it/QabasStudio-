package com.qabas.app

import android.content.Context
import kotlinx.coroutines.*
import java.io.File

// الطبيب الحي: فحص خفيف دوري (بلا شبكة) + علاج صامت + نبض يُقرأ من الواجهة.
// القاعدة: لا حذف لبيانات المستخدم أبداً — فقط كاش المحرك والمشاهد المنتهية.
object DoctorMonitor {

    const val PULSE_KEY = "doctor_pulse"           // green | yellow | red
    const val PULSE_TIME_KEY = "doctor_pulse_time"
    const val PULSE_MSG_KEY = "doctor_pulse_msg"
    const val LAST_RUN_KEY = "doctor_last_run"
    private const val INTERVAL_MS = 6 * 3_600_000L

    fun readPulse(context: Context): Triple<String, Long, String> {
        val p = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        return Triple(
            p.getString(PULSE_KEY, "green") ?: "green",
            p.getLong(PULSE_TIME_KEY, 0L),
            p.getString(PULSE_MSG_KEY, "") ?: ""
        )
    }

    fun maybeRun(context: Context) {
        val p = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        val last = p.getLong(LAST_RUN_KEY, 0L)
        if (System.currentTimeMillis() - last < INTERVAL_MS) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { runLightCheck(context.applicationContext) } catch (_: Exception) {}
        }
    }

    private fun runLightCheck(context: Context) {
        val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        val healed = mutableListOf<String>()

        // 1) مساحة التخزين
        val cacheDir = File(context.cacheDir, "qabas_engine")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val usableMB = try { cacheDir.usableSpace / (1024 * 1024) } catch (_: Exception) { 1000L }

        // 2) علاج صامت: كاش المحرك الأقدم من ساعة
        var freedFiles = 0
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("scene_") && System.currentTimeMillis() - f.lastModified() > 3_600_000L) {
                    if (f.delete()) freedFiles++
                }
            }
            if (freedFiles > 0) healed.add("كاش مشاهد قديم ($freedFiles)")
        } catch (_: Exception) {}

        // 3) علاج صامت: مشاهد معلقة منتهية (>24ساعة)
        try {
            val cachedTime = prefs.getLong("cached_scenes_time", 0L)
            if (prefs.contains("cached_scenes") && System.currentTimeMillis() - cachedTime > 24 * 3_600_000L) {
                prefs.edit().remove("cached_scenes").remove("cached_scenes_time").apply()
                healed.add("مشاهد معلقة منتهية")
            }
        } catch (_: Exception) {}

        // 4) رصد الانهيارات الأخيرة (انفجار ≥3 خلال 24ساعة = أحمر)
        var recentCrashes = 0
        try {
            val dir = File(context.filesDir, "crash_logs")
            val dayAgo = System.currentTimeMillis() - 24 * 3_600_000L
            recentCrashes = dir.listFiles()?.count { it.lastModified() > dayAgo } ?: 0
        } catch (_: Exception) {}

        val (pulse, msg) = when {
            usableMB < 200L -> "red" to "مساحة حرجة (${usableMB}MB) — حرر مساحة"
            recentCrashes >= 3 -> "red" to "انهيارات متكررة ($recentCrashes/24ساعة) — راجع سجل الانهيارات"
            usableMB < 500L -> "yellow" to "مساحة محدودة (${usableMB}MB)"
            recentCrashes > 0 -> "yellow" to "انهيار أخير مسجل — مستقر الآن"
            healed.isNotEmpty() -> "green" to "عالج ذاتياً: ${healed.joinToString("، ")}"
            else -> "green" to "سليم — آخر فحص دوري"
        }

        prefs.edit()
            .putString(PULSE_KEY, pulse)
            .putLong(PULSE_TIME_KEY, System.currentTimeMillis())
            .putString(PULSE_MSG_KEY, msg)
            .putLong(LAST_RUN_KEY, System.currentTimeMillis())
            .apply()

        try {
            SystemLogsManager.addLog(
                if (pulse == "green") "INFO" else "WARN",
                "الطبيب الحي: $msg",
                androidx.compose.ui.graphics.Color(if (pulse == "red") 0xFFEF4444 else if (pulse == "yellow") 0xFFE8C547 else 0xFF4CAF50)
            )
        } catch (_: Exception) {}
    }
}
