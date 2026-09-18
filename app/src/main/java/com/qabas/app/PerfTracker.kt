package com.qabas.app

/**
 * متتبع الأداء المحلي — يقيس أزمنة حقيقية داخل التطبيق بلا أي SDK:
 * - زمن بدء التطبيق (cold start) من Application.onCreate حتى أول إطار.
 * - زمن أول عرض لكل شاشة (time-to-first-frame per screen).
 * - تُقرأ النتائج من قسم التشخيص. البداية فارغة — تمتلئ بالاستخدام الفعلي.
 */
object PerfTracker {
    @Volatile
    private var appStartNs: Long = 0L

    private val firstFrameNs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val renderCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** يُستدعى أول شيء في QabasApplication.onCreate. */
    fun onAppCreate() {
        if (appStartNs == 0L) appStartNs = System.nanoTime()
    }

    /** يُستدعى عند أول تركيب لكل شاشة (مرة واحدة لكل اسم). */
    fun onFirstFrame(screen: String) {
        if (appStartNs == 0L) return
        firstFrameNs.putIfAbsent(screen, System.nanoTime())
        renderCount[screen] = (renderCount[screen] ?: 0) + 1
    }

    data class ScreenTime(val screen: String, val msFromStart: Long, val visits: Int)

    fun coldStartMs(): Long =
        if (appStartNs == 0L) -1 else (System.nanoTime() - appStartNs) / 1_000_000L

    fun screenTimes(): List<ScreenTime> {
        if (appStartNs == 0L) return emptyList()
        return firstFrameNs.map { (screen, ns) ->
            ScreenTime(screen, (ns - appStartNs) / 1_000_000L, renderCount[screen] ?: 0)
        }.sortedBy { it.msFromStart }
    }

    fun reset() {
        appStartNs = 0L
        firstFrameNs.clear()
        renderCount.clear()
    }
}
