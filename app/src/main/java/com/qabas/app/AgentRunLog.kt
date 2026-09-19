package com.qabas.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل تشغيل واحد للوكيل. */
data class AgentRun(
    val time: Long,
    val title: String,
    val tools: List<String>,
    val summary: String,
    val ok: Boolean,
    val error: String = ""
)

/**
 * «الصندوق الأسود» للوكيل 📦 — كل تشغيل موثّق:
 * نجاح/فشل + الأدوات المستخدمة + نص الخطأ الكامل.
 * الفشل يُكتب أيضاً في crash_logs/agent_*.txt فيلتقطه «سجل الانهيارات»
 * ويظهر في مركز الصحة — لا فشل صامت أبداً.
 */
object AgentRunLog {
    private const val PREFS = "qabas_requests_prefs"
    private const val KEY = "agent_runs"
    private const val MAX = 30

    fun record(
        context: Context,
        title: String,
        tools: List<String>,
        summary: String,
        ok: Boolean,
        error: String = ""
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            arr.put(JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("title", title.take(120))
                put("tools", JSONArray(tools))
                put("summary", summary.take(2000))
                put("ok", ok)
                put("error", error.take(3000))
            })
            while (arr.length() > MAX) arr.remove(0)
            prefs.edit().putString(KEY, arr.toString()).apply()
            AuditLogger.log(
                context, "agent_run",
                "وكيل «$title»: ${if (ok) "نجاح" else "فشل"} — أدوات: ${tools.joinToString(",")}" +
                    (if (!ok && error.isNotBlank()) " — خطأ: ${error.take(200)}" else "")
            )
            if (!ok) writeAgentCrashFile(context, title, tools, summary, error)
        } catch (_: Exception) { }
    }

    private fun writeAgentCrashFile(
        context: Context, title: String, tools: List<String>, summary: String, error: String
    ) {
        try {
            val dir = File(context.filesDir, "crash_logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val body = buildString {
                appendLine("🤖 فشل تشغيل الوكيل @ $stamp")
                appendLine("الطلب: $title")
                appendLine("الأدوات المستخدمة: ${tools.joinToString(", ").ifBlank { "لا شيء (فشل مبكر)" }}")
                appendLine("--- الملخص ---")
                appendLine(summary.ifBlank { "(لا ملخص)" })
                appendLine("--- الخطأ ---")
                appendLine(error.ifBlank { "(غير معروف — راجع سجل التدقيق)" })
                appendLine()
                appendLine("الإصلاح المقترح: افتح «غرفة الوكيل ← المطلوبات» وتأكد من الشروط الخمسة، ثم أعد التشغيل.")
            }
            File(dir, "agent_$stamp.txt").writeText(body)
        } catch (_: Exception) { }
    }

    fun load(context: Context): List<AgentRun> {
        return try {
            val arr = JSONArray(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
            )
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AgentRun(
                    time = o.optLong("time"),
                    title = o.optString("title"),
                    tools = o.optJSONArray("tools")
                        ?.let { arr -> (0 until arr.length()).map { j -> arr.optString(j) } }
                        ?.filter { it.isNotBlank() } ?: emptyList(),
                    summary = o.optString("summary"),
                    ok = o.optBoolean("ok"),
                    error = o.optString("error")
                )
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
