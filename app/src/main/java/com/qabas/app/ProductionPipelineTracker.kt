package com.qabas.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object ProductionPipelineTracker {

    private const val PREFS_NAME = "qabas_pipeline_events"
    private const val KEY_EVENTS = "pipeline_events"
    private const val MAX_EVENTS = 200

    enum class Stage(val label: String) {
        PIPELINE_START("بدء المسار"),
        IDEA_INPUT("إدخال الفكرة"),
        CONTENT_GUARD("فحص المحتوى"),
        STYLE_SELECTION("اختيار الأسلوب"),
        SCRIPT_GENERATION("توليد السكربت"),
        BROLL_FETCH("جلب B-Roll"),
        SCENE_PROCESSING("تجهيز المشاهد"),
        TTS_GENERATION("توليد الصوت"),
        TEXT_OVERLAY("طبقة النص"),
        COLOR_GRADING("التلوين السينمائي"),
        FFmpeg_MERGE("دمج FFmpeg"),
        VIDEO_ENGINE("محرك الفيديو"),
        EXPORT("التصدير النهائي"),
        STYLE_FEEDBACK("تغذية الأسلوب"),
        PIPELINE_END("اكتمال المسار")
    }

    /** ترتيب المراحل القياسي — يُستخدم لرسم شريط تدفق المسار في لوحة المطور. */
    val CANONICAL_ORDER = listOf(
        Stage.PIPELINE_START,
        Stage.IDEA_INPUT,
        Stage.CONTENT_GUARD,
        Stage.STYLE_SELECTION,
        Stage.SCRIPT_GENERATION,
        Stage.BROLL_FETCH,
        Stage.SCENE_PROCESSING,
        Stage.TTS_GENERATION,
        Stage.TEXT_OVERLAY,
        Stage.COLOR_GRADING,
        Stage.FFmpeg_MERGE,
        Stage.VIDEO_ENGINE,
        Stage.EXPORT,
        Stage.STYLE_FEEDBACK,
        Stage.PIPELINE_END
    )

    enum class Result { SUCCESS, FAILURE, FALLBACK, SKIPPED, TIMEOUT }

    data class PipelineEvent(
        val timestamp: Long,
        val stage: Stage,
        val result: Result,
        val message: String,
        val detail: String = "",
        val durationMs: Long = 0,
        val runId: String = "",
        val sceneIndex: Int = -1
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ts", timestamp)
            put("stage", stage.name)
            put("result", result.name)
            put("msg", message)
            put("detail", detail)
            put("dur", durationMs)
            put("runId", runId)
            put("sceneIndex", sceneIndex)
        }

        companion object {
            fun fromJson(j: JSONObject) = PipelineEvent(
                timestamp = j.optLong("ts", 0),
                stage = try { Stage.valueOf(j.optString("stage", "")) } catch (_: Exception) { Stage.VIDEO_ENGINE },
                result = try { Result.valueOf(j.optString("result", "")) } catch (_: Exception) { Result.FAILURE },
                message = j.optString("msg", ""),
                detail = j.optString("detail", ""),
                durationMs = j.optLong("dur", 0),
                runId = j.optString("runId", ""),
                sceneIndex = j.optInt("sceneIndex", -1)
            )
        }
    }

    fun record(
        context: Context,
        stage: Stage,
        result: Result,
        message: String,
        detail: String = "",
        durationMs: Long = 0,
        runId: String = "",
        sceneIndex: Int = -1
    ) {
        val event = PipelineEvent(System.currentTimeMillis(), stage, result, message, detail, durationMs, runId, sceneIndex)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]")
        } catch (_: Exception) { JSONArray() }

        arr.put(event.toJson())

        while (arr.length() > MAX_EVENTS) {
            arr.remove(0)
        }

        prefs.edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    fun getEvents(context: Context): List<PipelineEvent> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]")
        } catch (_: Exception) { JSONArray() }

        return (0 until arr.length()).mapNotNull { i ->
            try { PipelineEvent.fromJson(arr.getJSONObject(i)) } catch (_: Exception) { null }
        }.sortedByDescending { it.timestamp }
    }

    fun getStageStats(context: Context): Map<Stage, Map<Result, Int>> {
        val events = getEvents(context)
        val stats = mutableMapOf<Stage, MutableMap<Result, Int>>()
        for (e in events) {
            stats.getOrPut(e.stage) { mutableMapOf() }[e.result] =
                (stats[e.stage]?.get(e.result) ?: 0) + 1
        }
        return stats
    }

    fun clearEvents(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_EVENTS).apply()
    }

    /** ملخص تشغيلة إنتاج كاملة (runId واحد = فيديو واحد). */
    data class RunSummary(
        val runId: String,
        val startTime: Long,
        val endTime: Long,
        val eventCount: Int,
        val successCount: Int,
        val failureCount: Int,
        val fallbackCount: Int,
        val hasFailure: Boolean,
        val completed: Boolean
    )

    /** كل التشغيلات مرتبة من الأحدث للأقدم. */
    fun getRuns(context: Context): List<RunSummary> {
        val events = getEvents(context).sortedBy { it.timestamp }
        val byRun = events.filter { it.runId.isNotBlank() }.groupBy { it.runId }
        return byRun.map { (runId, evts) ->
            RunSummary(
                runId = runId,
                startTime = evts.first().timestamp,
                endTime = evts.last().timestamp,
                eventCount = evts.size,
                successCount = evts.count { it.result == Result.SUCCESS },
                failureCount = evts.count { it.result == Result.FAILURE },
                fallbackCount = evts.count { it.result == Result.FALLBACK },
                hasFailure = evts.any { it.result == Result.FAILURE },
                completed = evts.any { it.stage == Stage.PIPELINE_END }
            )
        }.sortedByDescending { it.startTime }
    }

    /** أحداث آخر تشغيلة (الأحدث زمنياً) بترتيبها الزمني التصاعدي. */
    fun getLatestRunEvents(context: Context): List<PipelineEvent> {
        val runs = getRuns(context)
        if (runs.isEmpty()) return emptyList()
        return getEvents(context).filter { it.runId == runs.first().runId }.sortedBy { it.timestamp }
    }

    /** متوسط مدة كل مرحلة (من الأحداث الناجحة ذات مدة مسجلة). */
    fun getAvgDurationPerStage(context: Context): Map<Stage, Long> {
        val events = getEvents(context)
        val durations = mutableMapOf<Stage, MutableList<Long>>()
        for (e in events) {
            if (e.durationMs > 0) {
                durations.getOrPut(e.stage) { mutableListOf() }.add(e.durationMs)
            }
        }
        return durations.mapValues { (_, list) -> list.average().toLong() }
    }

    fun formatTimestamp(ts: Long): String {
        val sdf = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(ts))
    }

    fun resultEmoji(r: Result): String = when (r) {
        Result.SUCCESS -> "✅"
        Result.FAILURE -> "❌"
        Result.FALLBACK -> "⚠️"
        Result.SKIPPED -> "⏭️"
        Result.TIMEOUT -> "⏱️"
    }

    fun resultColor(r: Result): Long = when (r) {
        Result.SUCCESS -> 0xFF10B981
        Result.FAILURE -> 0xFFEF4444
        Result.FALLBACK -> 0xFFF59E0B
        Result.SKIPPED -> 0xFF6B7280
        Result.TIMEOUT -> 0xFF8B5CF6
    }
}
