package com.qabas.app

import android.content.Context
import android.util.Log

/**
 * فتات الخبز (Breadcrumbs): آخر 50 حدثاً قبل أي عطل — الشاشات المزارة،
 * استدعاءات API، وبدء الإنتاج. تُرفق تلقائياً بملف الانهيار المحلي
 * وتُرسل إلى Firebase Crashlytics (إن توفرت السحابة).
 */
object CrashBreadcrumbs {
    private const val TAG = "CrashBreadcrumbs"
    private const val MAX = 50

    data class Crumb(val timeMs: Long, val category: String, val message: String)

    private val crumbs = ArrayDeque<Crumb>(MAX)

    @Synchronized
    fun leave(category: String, message: String) {
        if (crumbs.size >= MAX) crumbs.removeFirst()
        crumbs.addLast(Crumb(System.currentTimeMillis(), category, message.take(200)))
        // مرآة حية إلى Crashlytics (صامتة عند غياب السحابة)
        runCatching {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .log("[$category] $message".take(400))
        }
    }

    fun screen(name: String) = leave("screen", name)
    fun api(name: String, ms: Long, ok: Boolean) = leave("api", "$name ${ms}ms ${if (ok) "ok" else "FAIL"}")
    fun action(name: String) = leave("action", name)

    @Synchronized
    fun dump(): String {
        if (crumbs.isEmpty()) return "(لا فتات مسجّل)"
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return crumbs.joinToString("\n") { c ->
            "${fmt.format(java.util.Date(c.timeMs))} [${c.category}] ${c.message}"
        }
    }

    /** إرسال استثناء غير قاتل إلى Crashlytics + سجل الانهيارات المحلي. */
    fun reportNonFatal(context: Context, throwable: Throwable, contextInfo: String = "") {
        runCatching {
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            if (contextInfo.isNotBlank()) crashlytics.setCustomKey("context", contextInfo.take(100))
            crashlytics.recordException(throwable)
        }.onFailure { Log.w(TAG, "crashlytics unavailable: ${it.message}") }
        runCatching {
            QabasCrashGuard.record(context, Thread.currentThread().name, throwable, "nonfatal:$contextInfo")
        }
    }

    /** صحة الإصدار الحالي: انهيارات / إجمالي إقلاعات. */
    fun releaseHealth(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        val launches = prefs.getInt("app_launch_count", 0)
        val dir = java.io.File(context.filesDir, "crash_logs")
        val crashes = dir.listFiles()?.size ?: 0
        return crashes to launches
    }
}
