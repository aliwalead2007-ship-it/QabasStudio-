package com.qabas.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سجل التدقيق (Audit Log): يوثّق كل إجراء حساس في لوحة المطور.
 * - سحابياً: Firestore collection `audit_log` (auto-id مستند).
 * - محلياً: كاش JSON في qabas_prefs (يعمل دون اتصال ويُقرأ في قسم جديد باللوحة).
 */
object AuditLogger {
    private const val TAG = "AuditLogger"
    private const val PREFS_KEY = "audit_log_entries"
    private const val MAX_LOCAL_ENTRIES = 100

    data class AuditEntry(
        val id: String,
        val action: String,
        val detail: String,
        val actor: String,
        val timeMs: Long,
        /** بصمة السجل السابق — سلسلة ممانعة للعبث (أي تعديل يكسر السلسلة). */
        val prevHash: String = "",
        val hash: String = ""
    )

    private fun sha256Hex(input: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "${input.hashCode()}"
        }
    }

    private fun entryHash(prevHash: String, action: String, detail: String, actor: String, timeMs: Long): String {
        return sha256Hex("$prevHash|$action|$detail|$actor|$timeMs")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun log(context: Context, action: String, detail: String) {
        val timeMs = System.currentTimeMillis()
        val prevHash = lastHash(context)
        val actor = AdminGuard.currentIdentity(context)
        val entry = AuditEntry(
            id = java.util.UUID.randomUUID().toString(),
            action = action,
            detail = detail,
            actor = actor,
            timeMs = timeMs,
            prevHash = prevHash,
            hash = entryHash(prevHash, action, detail, actor, timeMs)
        )
        appendLocal(context, entry)
        try {
            if (CloudServices.isFirebaseInitialized) {
                scope.launch {
                    runCatching {
                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                            .collection("audit_log")
                            .add(
                                mapOf(
                                    "action" to entry.action,
                                    "detail" to entry.detail,
                                    "actor" to entry.actor,
                                    "timestamp" to entry.timeMs,
                                    "app" to "qabas_dev_dashboard"
                                )
                            ).await()
                    }.onFailure { Log.w(TAG, "cloud audit failed: ${it.message}") }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audit log failed: ${e.message}")
        }
        Log.d(TAG, "[audit] $action :: $detail :: by ${entry.actor}")
    }

    private fun appendLocal(context: Context, entry: AuditEntry) {
        runCatching {
            val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(PREFS_KEY, "[]") ?: "[]")
            arr.put(
                org.json.JSONObject()
                    .put("id", entry.id)
                    .put("action", entry.action)
                    .put("detail", entry.detail)
                    .put("actor", entry.actor)
                    .put("timeMs", entry.timeMs)
                    .put("prevHash", entry.prevHash)
                    .put("hash", entry.hash)
            )
            while (arr.length() > MAX_LOCAL_ENTRIES) arr.remove(0)
            prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
        }
    }

    private fun lastHash(context: Context): String {
        return runCatching {
            val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(PREFS_KEY, "[]") ?: "[]")
            if (arr.length() == 0) "" else arr.getJSONObject(arr.length() - 1).optString("hash", "")
        }.getOrDefault("")
    }

    /** التحقق من سلامة السلسلة: يُرجع index أول مدخل مكسور، أو -1 إن سليمة. */
    fun verifyChain(context: Context): Int {
        val entries = readLocal(context).sortedBy { it.timeMs }
        var prev = ""
        entries.forEachIndexed { i, e ->
            // مدخلات ما قبل السلسلة (بلا بصمة) تُتخطى دون كسر
            if (e.hash.isBlank()) {
                prev = ""
                return@forEachIndexed
            }
            if (e.prevHash != prev) return i
            if (e.hash != entryHash(e.prevHash, e.action, e.detail, e.actor, e.timeMs)) return i
            prev = e.hash
        }
        return -1
    }

    fun readLocal(context: Context): List<AuditEntry> {
        return runCatching {
            val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(PREFS_KEY, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AuditEntry(
                    id = o.optString("id", ""),
                    action = o.optString("action", ""),
                    detail = o.optString("detail", ""),
                    actor = o.optString("actor", ""),
                    timeMs = o.optLong("timeMs", 0L),
                    prevHash = o.optString("prevHash", ""),
                    hash = o.optString("hash", "")
                )
            }.sortedByDescending { it.timeMs }
        }.getOrDefault(emptyList())
    }

    fun buildCsv(entries: List<AuditEntry>): String {
        val sb = StringBuilder()
        sb.append("الوقت,الإجراء,التفاصيل,الفاعل,البصمة\n")
        entries.forEach { e ->
            sb.append("\"${formatTime(e.timeMs)}\",\"${e.action}\",\"${e.detail.replace("\"", "'")}\",\"${e.actor}\",\"${e.hash.take(12)}\"\n")
        }
        return sb.toString()
    }

    fun formatTime(timeMs: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
}
