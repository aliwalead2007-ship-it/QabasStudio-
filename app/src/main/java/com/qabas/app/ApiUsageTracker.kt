package com.qabas.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * متتبع حقيقي لاستهلاك وزمن استجابة الخدمات الذكاء الاصطناعي.
 * يخزن عدد الاستدعاءات وإجمالي/آخر زمن استجابة لكل خدمة بشكل دائم في SharedPreferences،
 * بحيث تمتلئ لوحة المطور بقياسات حقيقية وليست نسباً شكلية.
 *
 * يتم استدعاء [recordCall] من نقاط الاستدعاء الفعلية لـ HTTPS في RealServices.kt.
 */
object ApiUsageTracker {
    private const val TAG = "ApiUsageTracker"
    private const val PREFS = "qabas_prefs"
    private const val USAGE_JSON_KEY = "api_usage_tracker_json_v2"

    /**
     * تسمية موحدة للخدمات المستخدمة في لوحة المطور.
     */
    val SUPPORTED: List<String> = listOf(
        "Gemini", "Groq", "OpenAI", "Azure TTS", "ElevenLabs", "HuggingFace", "Pexels", "Pixabay", "Firebase", "Quran Foundation"
    )

    data class ApiStat(
        val totalCalls: Long = 0L,
        val successCalls: Long = 0L,
        val totalLatencyMs: Long = 0L,
        val lastLatencyMs: Long = 0L,
    ) {
        val avgLatencyMs: Long
            get() = if (totalCalls > 0) totalLatencyMs / totalCalls else 0L
        val successRate: Float
            get() = if (totalCalls > 0) successCalls.toFloat() / totalCalls else 0f
    }

    private var _stats: Map<String, ApiStat> = emptyMap()
    private var loaded = false

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(USAGE_JSON_KEY, null)
            if (raw.isNullOrBlank()) {
                _stats = defaultEmptyStats()
            } else {
                val arr = JSONArray(raw)
                val map = LinkedHashMap<String, ApiStat>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name")
                    if (name.isBlank()) continue
                    map[name] = ApiStat(
                        totalCalls = o.optLong("totalCalls"),
                        successCalls = o.optLong("successCalls"),
                        totalLatencyMs = o.optLong("totalLatencyMs"),
                        lastLatencyMs = o.optLong("lastLatencyMs"),
                    )
                }
                _stats = map
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            _stats = defaultEmptyStats()
        }
        loaded = true
    }

    private fun defaultEmptyStats(): Map<String, ApiStat> = LinkedHashMap<String, ApiStat>().apply {
        SUPPORTED.forEach { put(it, ApiStat()) }
    }

    @Synchronized
    private fun persist(context: Context) {
        try {
            val arr = JSONArray()
            _stats.forEach { (name, stat) ->
                arr.put(JSONObject().apply {
                    put("name", name)
                    put("totalCalls", stat.totalCalls)
                    put("successCalls", stat.successCalls)
                    put("totalLatencyMs", stat.totalLatencyMs)
                    put("lastLatencyMs", stat.lastLatencyMs)
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(USAGE_JSON_KEY, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }

    /**
     * تسجيل استدعاء حقيقي لخدمة. يُستدعى داخلياً من [track] ولا يُستخدم خارج نقاط HTTP الفعلية.
     */
    @Synchronized
    fun recordCall(context: Context, apiName: String, latencyMs: Long, success: Boolean) {
        load(context)
        val current = _stats[apiName] ?: ApiStat()
        val updated = ApiStat(
            totalCalls = current.totalCalls + 1,
            successCalls = current.successCalls + if (success) 1 else 0,
            totalLatencyMs = current.totalLatencyMs + latencyMs,
            lastLatencyMs = latencyMs,
        )
        _stats = _stats + (apiName to updated)
        persist(context)
        // فتات خبز: تُرفق تلقائياً بأي انهيار لاحق
        CrashBreadcrumbs.api(apiName, latencyMs, success)
    }

    /**
     * يقيس زمن استجابة استدعاء HTTPS حقيقي ويسجله. يُغلف `client.newCall(...).execute()`.
     * لا يعدّل منطق الاستدعاء، فقط يقيس ويسجل.
     */
    suspend fun <T> track(
        context: Context,
        apiName: String,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val result = block()
            val latencyMs = (System.nanoTime() - start) / 1_000_000L
            recordCall(context, apiName, latencyMs, success = true)
            result
        } catch (e: Exception) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000L
            recordCall(context, apiName, latencyMs, success = false)
            throw e
        }
    }

    /**
     * لقطة القراءة للوحة المطور. تُحمَّل من التخزين الآمن إن لم تكن محمَّلة.
     */
    suspend fun snapshot(context: Context): Map<String, ApiStat> = withContext(Dispatchers.IO) {
        load(context)
        _stats
    }

    /**
     * تصفير القياسات (أداة للمطور).
     */
    suspend fun reset(context: Context) = withContext(Dispatchers.IO) {
        _stats = defaultEmptyStats()
        persist(context)
    }
}
