package com.qabas.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * حارس الانهيارات: يلتقط:
 *  - استثناءات الخيوط (Thread.UncaughtExceptionHandler) + يعيد تشغيل التطبيق لو main
 *  - استثناءات Coroutines (CoroutineExceptionHandler على scope جذر)
 *  - ANR عبر Watchdog يرسل انهياراً اصطناعياً لو main لم يستجب خلال [ANR_TIMEOUT_MS]
 *
 * كل انهيار يُحفظ في filesDir/crash_logs/crash_<timestamp>.txt مع:
 *  - رأس يحتوي: thread, time, device, version, memory, foreground, hot-path
 *  - الـ stack الكامل
 *
 * حماية loop: لو الانهيار حدث داخل onCreate قبل 6 ثوانٍ من آخر إعادة تشغيل،
 * لا يعيد فتح التطبيق (وإلا يدخل في loop لا نهائي).
 */
object QabasCrashGuard {
    private const val TAG = "QabasCrashGuard"
    private const val MAX_LOG_FILES = 20
    private const val RECOVERY_WINDOW_MS = 15_000L
    private const val RELAUNCH_LOOP_GUARD_MS = 6_000L
    private const val DUPLICATE_MERGE_WINDOW_MS = 100L
    private const val ANR_TIMEOUT_MS = 5_000L
    private const val ANR_CHECK_INTERVAL_MS = 1_500L
    private const val PREF_LAST_TS = "crash_guard_last_ts"
    private const val PREF_COUNT = "crash_guard_count"
    private const val PREF_LAST_RELAUNCH_TS = "crash_guard_last_relaunch_ts"

    @Volatile private var installed = false
    @Volatile private var lastFileWriteMs = 0L
    @Volatile private var lastFileStamp = ""
    @Volatile private var anrWatchdog: Thread? = null

    /**
     * Scope جذر لالتقاط انهيارات coroutines. يُستخدم من قبل بقية التطبيق
     * عبر [rootHandler] عند بناء scope جديد.
     */
    val rootScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + rootHandler)
    }

    /**
     * معالج استثناءات coroutines — يستدعي نفس record() لضمان تسجيل الانهيار
     * حتى لو لم يمر عبر Thread.setDefaultUncaughtExceptionHandler.
     */
    val rootHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            val ctx = appContextRef ?: return@CoroutineExceptionHandler
            record(ctx, Thread.currentThread().name, throwable, "coroutine")
        } catch (_: Throwable) {
        }
    }

    @Volatile private var appContextRef: Context? = null

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        appContextRef = appContext

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            var relaunched = false
            try {
                val message = record(appContext, thread.name, throwable, "thread")
                if (thread.name == "main") {
                    val prefs = appContext.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
                    val now = System.currentTimeMillis()
                    val lastTs = prefs.getLong(PREF_LAST_TS, 0L)
                    val lastRelaunch = prefs.getLong(PREF_LAST_RELAUNCH_TS, 0L)
                    prefs.edit()
                        .putLong(PREF_LAST_TS, now)
                        .putLong(PREF_COUNT, prefs.getLong(PREF_COUNT, 0L) + 1)
                        .apply()

                    // منع loop: لو الانهيار بعد إعادة تشغيل خلال 6 ثوانٍ، لا نعيد فتح.
                    val isInRelaunchLoop = lastRelaunch > 0 && (now - lastRelaunch) < RELAUNCH_LOOP_GUARD_MS
                    if (now - lastTs > RECOVERY_WINDOW_MS && !isInRelaunchLoop) {
                        runCatching {
                            val intent = appContext.packageManager
                                .getLaunchIntentForPackage(appContext.packageName)
                                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            if (intent != null) {
                                appContext.startActivity(intent)
                                prefs.edit().putLong(PREF_LAST_RELAUNCH_TS, now).apply()
                                relaunched = true
                            }
                        }
                    }
                    try {
                        SystemLogsManager.addLog(
                            "خطأ",
                            "انهيار تم التعافي منه: $message",
                            Color(0xFFF44336)
                        )
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            if (!relaunched) {
                // ترك defaultHandler يقتل العملية في حالة loop أو انهيار worker
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        startAnrWatchdog(appContext)
    }

    /**
     * Watchdog بسيط: كل [ANR_CHECK_INTERVAL_MS] يلامس خيط main عبر Handler.
     * لو لم يستقبل main اللمسة خلال [ANR_TIMEOUT_MS] → يسجل انهيار ANR اصطناعي.
     */
    private fun startAnrWatchdog(context: Context) {
        if (anrWatchdog != null) return
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        anrWatchdog = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val touchedAt = System.currentTimeMillis()
                    val received = java.util.concurrent.atomic.AtomicBoolean(false)
                    mainHandler.post { received.set(true) }
                    Thread.sleep(ANR_TIMEOUT_MS)
                    if (!received.get()) {
                        record(
                            context,
                            "main",
                            RuntimeException(
                                "🧊 ANR: main thread did not respond within ${ANR_TIMEOUT_MS}ms " +
                                "(last touch ${System.currentTimeMillis() - touchedAt}ms ago)"
                            ),
                            "anr"
                        )
                        // بعد تسجيل ANR، لا نرسل أكثر من واحد كل دقيقة لتجنب spam
                        Thread.sleep(60_000L)
                    } else {
                        Thread.sleep(ANR_CHECK_INTERVAL_MS)
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                }
            }
        }, "QabasANRWatchdog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * يسجل انهياراً: يستخرج hot-path، يبني رأس معلومات البيئة، يحفظ الملف،
     * ويدمج الانهيارات المتتالية في <100ms في ملف واحد.
     */
    fun record(context: Context, thread: String, throwable: Throwable, source: String = "thread"): String {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(now)
        val display = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
        val env = buildEnvHeader(context)
        val hotPath = findHotPath(throwable)
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { pw ->
                pw.println("════════════════════════════════════════════")
                pw.println("Qabas Crash Log — $display")
                pw.println("Source: $source")
                pw.println("Thread: $thread")
                pw.println("Hot path: $hotPath")
                env.forEach { (k, v) -> pw.println("$k: $v") }
                pw.println("── Breadcrumbs (آخر الأحداث قبل العطل) ──")
                pw.println(CrashBreadcrumbs.dump())
                pw.println("════════════════════════════════════════════")
                throwable.printStackTrace(pw)
            }
        }
        // مرآة سحابية للانهيار القاتل (صامتة عند غياب Firebase)
        runCatching {
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("source", source)
            crashlytics.setCustomKey("thread", thread)
            crashlytics.recordException(throwable)
        }
        try {
            val dir = File(context.filesDir, "crash_logs").apply { mkdirs() }
            // دمج انهيارات متتالية: إذا فُتح ملف قبل 100ms، نُلحق به بدلاً من إنشاء ملف جديد.
            val nowMs = System.currentTimeMillis()
            if (lastFileStamp.isNotEmpty() && (nowMs - lastFileWriteMs) < DUPLICATE_MERGE_WINDOW_MS) {
                val existing = File(dir, lastFileStamp)
                if (existing.exists()) {
                    existing.appendText("\n\n--- merged crash @ $display ---\n" + stack.toString())
                    return throwable.message ?: throwable.javaClass.simpleName
                }
            }
            val fileName = "crash_$stamp.txt"
            File(dir, fileName).writeText(stack.toString())
            lastFileWriteMs = nowMs
            lastFileStamp = fileName
            val existing = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (existing.size > MAX_LOG_FILES) {
                existing.drop(MAX_LOG_FILES).forEach { it.delete() }
            }
        } catch (_: Throwable) {
        }
        notifyDeveloper(context, throwable, source)
        val root = throwable.message ?: throwable.javaClass.simpleName
        return root.take(120)
    }

    /**
     * يبني رأس معلومات البيئة: اسم الجهاز، Android API، versionName/Code،
     * الذاكرة المتاحة، هل التطبيق في المقدمة.
     */
    private fun buildEnvHeader(context: Context): Map<String, String> {
        val app = context.applicationContext as? Application
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = pkg?.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) pkg?.longVersionCode?.toString() ?: "?" else pkg?.versionCode?.toString() ?: "?"
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val freeMb = mi.availMem / (1024 * 1024)
        val totalMb = mi.totalMem / (1024 * 1024)
        val foreground = app?.let {
            val running = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(running)
            running.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } ?: false
        return mapOf(
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "App version" to "$versionName (code $versionCode)",
            "Memory" to "${freeMb}MB free / ${totalMb}MB total",
            "Foreground" to foreground.toString(),
            "Abis" to Build.SUPPORTED_ABIS.joinToString(",")
        )
    }

    /**
     * يجد الإطار الأكثر تكراراً داخل الـ stack — يدل على "hot path"
     * حيث يتكرر نفس الاستدعاء لسبب انهيار (loop/recurse).
     */
    private fun findHotPath(throwable: Throwable): String {
        val frames = throwable.stackTrace
            .map { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        val counts = frames.groupingBy { it }.eachCount()
        val top = counts.maxByOrNull { it.value }
        return if (top != null && top.value >= 3) "${top.key} × ${top.value}" else frames.firstOrNull() ?: "غير معروف"
    }

    /**
     * يحاكي انهياراً اختبارياً. يُستدعى من زر "محاكاة" في لوحة المطور
     * للتحقق أن الحارس يلتقط ويعيد تشغيل التطبيق بشكل سليم.
     */
    fun simulateCrash(): Nothing {
        throw IllegalStateException("🧪 [Dev] simulated crash for QabasCrashGuard self-test")
    }

    /**
     * يُعلم المطور عبر Webhook (Slack/Discord/IFTTT/Webhook.site...).
     * الـ URL يُخزَّن في SharedPreferences تحت developer_webhook_url.
     * يعمل فقط لو: (1) URL موجود، (2) أول انهيار من نوعه في الجلسة (لتجنب spam).
     *
     * يُستدعى من [record] فوراً بعد حفظ الملف — لا يوقف الحارس لو فشل.
     */
    private fun notifyDeveloper(context: Context, throwable: Throwable, source: String) {
        try {
            val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            val url = prefs.getString("developer_webhook_url", null)?.trim().orEmpty()
            if (url.isEmpty() || !url.startsWith("http")) return

            val sessionKey = "webhook_sent_${source}_${throwable.javaClass.simpleName}"
            if (prefs.getBoolean(sessionKey, false)) return
            prefs.edit().putBoolean(sessionKey, true).apply()

            val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            val versionName = pkg?.versionName ?: "?"
            val hotPath = findHotPath(throwable)
            val summary = """
                🚨 *Qabas crash on ${Build.MANUFACTURER} ${Build.MODEL}*
                App: $versionName • Android ${Build.VERSION.RELEASE}
                Source: $source • Thread: ${Thread.currentThread().name}
                Hot path: $hotPath
                Exception: ${throwable.javaClass.simpleName}: ${(throwable.message ?: "").take(200)}
            """.trimIndent()

            // fire-and-forget — لا نمنع الحارس لو الـ webhook بطيء أو معطل
            Thread({
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val payload = "{\"text\":${summary.replace("\"", "\\\"").replace("\n", "\\n")}}"
                    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                } catch (_: Throwable) {
                    // تجاهل — webhook اختياري ولا يجب أن يكسر الحارس
                }
            }, "QabasWebhook").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
        }
    }
}
