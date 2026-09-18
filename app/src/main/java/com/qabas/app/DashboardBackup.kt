package com.qabas.app

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class BackupRestoreCounts(
    val configOk: Boolean,
    val promoCount: Int,
    val giftCount: Int,
    val userCount: Int
)

/**
 * قسم النسخ الاحتياطي والاسترجاع (DashboardBackup):
 * - تصدير JSON للمستخدمين + الأكواد + الإعدادات.
 * - استيراد JSON واستعادة الإعدادات + الأكواد + المستخدمين مع معاينة وتأكيد.
 * - نسخ تلقائي يومي صامت إلى مجلد التطبيق الخاص (AutoBackup).
 */
@Composable
fun DashboardBackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }
    var lastExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Pair<android.net.Uri, BackupRestoreCounts>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            isExporting = true
            lastExportUri = uri
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (json, counts) = readBackupForRestore(context, uri)
            if (json == null || counts == null) {
                Toast.makeText(context, "الملف غير صالح أو ليس نسخة احتياطية قبس", Toast.LENGTH_LONG).show()
                return@launch
            }
            pendingRestore = uri to counts
        }
    }

    LaunchedEffect(isExporting, lastExportUri) {
        if (isExporting && lastExportUri != null) {
            val json = buildExportJson(context)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(lastExportUri!!)?.use { it.write(json.toByteArray()) }
            }
            isExporting = false
            Toast.makeText(context, "تم التصدير بنجاح ✅", Toast.LENGTH_SHORT).show()
            AuditLogger.log(context, "backup_export", "تصدير نسخة احتياطية كاملة JSON")
        }
    }

    // ---- معاينة الاسترجاع وتأكيد المستخدم ----
    pendingRestore?.let { (uri, counts) ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("تأكيد الاسترجاع", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الملف يحتوي على:", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                    if (counts.configOk) Text("✅ إعدادات النظام (${AppRemoteConfig.KEY_MAINTENANCE} …)", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 13.sp)
                    else Text("⚠️ لا توجد إعدادات في الملف", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 13.sp)
                    if (counts.promoCount > 0) Text("✅ $counts.promoCount كود ترقية", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 13.sp)
                    if (counts.giftCount > 0) Text("✅ $counts.giftCount بطاقة هدايا", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 13.sp)
                    if (counts.userCount > 0) Text("✅ $counts.userCount مستخدم (دمج بالسحابة)", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("⚠️ الاسترجاع يدمج البيانات فقط (لا يحذف). الحسابات الحالية لا تتأثر إلا إذا وُجد نفس المعرّف.", color = GoldSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val job = scope.launch {
                            isRestoring = true
                            val result = restoreFromUri(context, uri, counts)
                            isRestoring = false
                            pendingRestore = null
                            val summary = buildString {
                                if (result.configRestored) append("الإعدادات ✅ ")
                                append("الأكواد: ${result.promoRestored + result.giftRestored} ✅ ")
                                append("المستخدمين: ${result.usersRestored}/${counts.userCount} ✅")
                            }
                            Toast.makeText(context, "تم الاسترجاع بنجاح\n$summary", Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !isRestoring,
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("تأكيد الاسترجاع ✓", color = Color.Black, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("إلغاء", color = Color.Gray) }
            },
            containerColor = DeepSlate
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("نسخ احتياطي ومسح", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)

        // ---- النسخ التلقائي اليومي ----
        AutoBackupCard(context = context)

        // ---- بطاقة التصدير ----
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تصدير نسخة احتياطية كاملة (JSON)", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                Text("يشمل: المستخدمين + الأكواد + الإعدادات البعيدة. لا يشمل الملفات أو الصور.", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                Button(
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportLauncher.launch("qabas_backup_$stamp.json")
                    },
                    enabled = !isExporting && !isRestoring,
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    if (isExporting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = DeepSlate, strokeWidth = 2.dp)
                    else Text("تصدير نسخة احتياطية 📦", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---- بطاقة الاسترجاع ----
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("استرجاع نسخة احتياطية (JSON)", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                Text("يُعيد الإعدادات + الأكواد + المستخدمين عبر دمج لا حذف. يظهر معاينة أولاً.", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                Button(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json"))
                    },
                    enabled = !isExporting && !isRestoring,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    if (isRestoring) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("جارٍ الاسترجاع…", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                    } else {
                        Text("استرجاع نسخة احتياطية 📂", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ─── منطق الاسترجاع ───────────────────────────────────────────────

private data class RestoreResult(
    val configRestored: Boolean = false,
    val promoRestored: Int = 0,
    val giftRestored: Int = 0,
    val usersRestored: Int = 0
)

@Composable
private fun AutoBackupCard(context: Context) {
    var enabled by remember { mutableStateOf(AutoBackup.isEnabled(context)) }
    var lastBackup by remember { mutableStateOf(AutoBackup.lastBackupTime(context)) }
    val scope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("النسخ التلقائي اليومي", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                    Text(
                        if (lastBackup > 0) {
                            "آخر نسخة: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastBackup))}"
                        } else "لم تُؤخذ أي نسخة تلقائية بعد",
                        color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp
                    )
                    Text(
                        "تُحفظ في مجلد التطبيق الخاص (آخر 7 نسخ) — بلا تدخل منك.",
                        color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        AutoBackup.setEnabled(context, it)
                        lastBackup = AutoBackup.lastBackupTime(context)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF8B5CF6))
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ok = AutoBackup.runNow(context)
                        lastBackup = AutoBackup.lastBackupTime(context)
                        Toast.makeText(
                            context,
                            if (ok) "أُخذت نسخة تلقائية الآن ✅" else "فشل النسخ التلقائي",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("نسخ الآن", color = Color(0xFF8B5CF6), fontFamily = CairoFont, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── النسخ التلقائي اليومي ─────────────────────────────────────────

object AutoBackup {
    private const val PREFS = "qabas_prefs"
    private const val KEY_ENABLED = "auto_backup_enabled"
    private const val KEY_LAST = "auto_backup_last_ms"
    private const val MAX_KEEP = 7
    private const val REQUEST_CODE = 2002

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun lastBackupTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            scheduleDaily(context)
            AuditLogger.log(context, "auto_backup_enabled", "تفعيل النسخ التلقائي اليومي")
        } else {
            cancelDaily(context)
            AuditLogger.log(context, "auto_backup_disabled", "إيقاف النسخ التلقائي اليومي")
        }
    }

    private fun scheduleDaily(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val intent = android.content.Intent(context, AutoBackupReceiver::class.java)
            val pi = android.app.PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(java.util.Calendar.HOUR_OF_DAY, 3) // 3:00 فجراً
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            alarmManager.setInexactRepeating(
                android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis,
                android.app.AlarmManager.INTERVAL_DAY, pi
            )
        } catch (e: Exception) {
            android.util.Log.w("AutoBackup", "schedule failed: ${e.message}")
        }
    }

    private fun cancelDaily(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val intent = android.content.Intent(context, AutoBackupReceiver::class.java)
            val pi = android.app.PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                android.app.PendingIntent.FLAG_NO_CREATE or
                    (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            )
            if (pi != null) alarmManager.cancel(pi)
        } catch (e: Exception) {
            android.util.Log.w("AutoBackup", "cancel failed: ${e.message}")
        }
    }

    /** تنفيذ نسخة صامتة الآن (من الزر أو من المنبه). */
    suspend fun runNow(context: Context): Boolean {
        return try {
            val json = buildExportJson(context)
            val dir = java.io.File(context.filesDir, "backups").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            java.io.File(dir, "auto_$stamp.json").writeText(json)
            // الاحتفاظ بآخر 7 فقط
            dir.listFiles { f -> f.name.startsWith("auto_") }
                ?.sortedBy { it.name }?.dropLast(MAX_KEEP)
                ?.forEach { runCatching { it.delete() } }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST, System.currentTimeMillis()).apply()
            AuditLogger.log(context, "auto_backup_run", "نسخة تلقائية صامتة")
            true
        } catch (e: Exception) {
            android.util.Log.e("AutoBackup", "runNow failed: ${e.message}", e)
            false
        }
    }
}

/** مستقبل منبه النسخ التلقائي — يعمل حتى لو التطبيق مغلق. */
class AutoBackupReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (!AutoBackup.isEnabled(context)) return
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                AutoBackup.runNow(context)
            } finally {
                pending.finish()
            }
        }
    }
}

private suspend fun readBackupForRestore(context: Context, uri: android.net.Uri): Pair<String?, BackupRestoreCounts?> = withContext(Dispatchers.IO) {
    try {
        val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@withContext null to null
        val root = JSONObject(json)
        if (!root.has("config") && !root.has("promoCodes") && !root.has("users")) return@withContext null to null

        val codesArr = root.optJSONArray("promoCodes") ?: JSONArray()
        var promoCount = 0
        var giftCount = 0
        for (i in 0 until codesArr.length()) {
            val c = codesArr.optJSONObject(i) ?: continue
            if (c.optString("type") == "GIFT") giftCount++ else promoCount++
        }

        val counts = BackupRestoreCounts(
            configOk = root.has("config"),
            promoCount = promoCount,
            giftCount = giftCount,
            userCount = root.optJSONArray("users")?.length() ?: 0
        )
        json to counts
    } catch (e: Exception) {
        null to null
    }
}

private suspend fun restoreFromUri(context: Context, uri: android.net.Uri, counts: BackupRestoreCounts): RestoreResult = withContext(Dispatchers.IO) {
    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@withContext RestoreResult()
    val root = JSONObject(json)
    var result = RestoreResult()

    // 1) استعادة الإعدادات البعيدة
    if (counts.configOk) {
        try {
            val c = root.getJSONObject("config")
            val data = AppRemoteConfig.ConfigData(
                maintenanceMode = c.optBoolean(AppRemoteConfig.KEY_MAINTENANCE, false),
                acceptRequests = c.optBoolean(AppRemoteConfig.KEY_ACCEPT_REQUESTS, true),
                autoAiReply = c.optBoolean(AppRemoteConfig.KEY_AUTO_AI_REPLY, true),
                maintenanceMessage = c.optString(AppRemoteConfig.KEY_MAINTENANCE_MESSAGE, AppRemoteConfig.ConfigData.DEFAULT_MESSAGE)
            )
            AppRemoteConfig.pushToCloud(context, data)
            result = result.copy(configRestored = true)
        } catch (_: Exception) { }
    }

    // 2) استعادة الأكواد
    val gm = GiftManager(context)
    var promoRestored = 0
    var giftRestored = 0
    root.optJSONArray("promoCodes")?.let { arr ->
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val code = obj.optString("code", "") 
            val type = obj.optString("type", "PROMO")
            if (code.isBlank()) continue
            runCatching {
                if (type == "GIFT") {
                    val pts = obj.optInt("points", obj.optLong("points", 0).toInt())
                    gm.addCustomGiftCard(code, pts)
                    giftRestored++
                } else {
                    val daysMs = obj.optLong("days", 0)
                    val days = if (daysMs > 100_000) (daysMs / 86_400_000).coerceAtLeast(1).toInt() else obj.optInt("days", 1).coerceAtLeast(1)
                    gm.addCustomPromoCode(code, days)
                    promoRestored++
                }
            }
        }
    }
    result = result.copy(promoRestored = promoRestored, giftRestored = giftRestored)

    // 3) استعادة المستخدمين (دمج upsert)
    var usersRestored = 0
    root.optJSONArray("users")?.let { arr ->
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            val id = u.optString("id", "")
            val email = u.optString("email", "")
            if (id.isBlank() || email.isBlank()) continue
            val name = u.optString("name", email)
            val type = u.optString("type", "Freemium")
            val projCount = u.optInt("projectCount", 0)
            runCatching {
                CloudServices.Database.saveUserToCloud(id, name, email, type, projCount)
                val isSuspended = u.optBoolean("isSuspended", false)
                if (isSuspended) CloudServices.Database.updateUserSuspension(id, email, true)
                usersRestored++
            }
        }
    }
    result = result.copy(usersRestored = usersRestored)

    // تسجيل تدقيق لعملية الاسترجاع
    AuditLogger.log(
        context,
        "backup_restore",
        "استرجاع: إعدادات=${result.configRestored}, أكواد=${result.promoRestored + result.giftRestored}, مستخدمين=${result.usersRestored}"
    )

    result
}

// ─── منطق التصدير ───────────────────────────────────────────────

private suspend fun buildExportJson(context: Context): String = withContext(Dispatchers.IO) {
    val root = JSONObject()
    root.put("app", "qabas")
    root.put("exportedAt", System.currentTimeMillis())
    root.put("exportedBy", AdminGuard.currentIdentity(context))

    // Config (cloud + local)
    val config = AppRemoteConfig.readLocal(context)
    root.put("config", JSONObject().apply {
        put(AppRemoteConfig.KEY_MAINTENANCE, config.maintenanceMode)
        put(AppRemoteConfig.KEY_ACCEPT_REQUESTS, config.acceptRequests)
        put(AppRemoteConfig.KEY_AUTO_AI_REPLY, config.autoAiReply)
        put(AppRemoteConfig.KEY_MAINTENANCE_MESSAGE, config.maintenanceMessage)
    })

    // Users (best-effort: try Firestore, fallback empty)
    val usersArr = JSONArray()
    runCatching {
        if (CloudServices.isFirebaseInitialized) {
            val docs = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").get().await()
            docs.documents.forEach { d ->
                usersArr.put(JSONObject().apply {
                    put("id", d.id)
                    d.data?.forEach { (k, v) -> put(k, v) }
                })
            }
        }
    }
    root.put("users", usersArr)

    // Promo codes
    val codesArr = JSONArray()
    runCatching {
        val gm = GiftManager(context)
        gm.loadValidCodesFromCloud()
        gm.getCustomPromoCodes().forEach { (code, days) ->
            codesArr.put(JSONObject().apply { put("code", code); put("days", days); put("type", "PROMO") })
        }
        gm.getCustomGiftCards().forEach { (code, pts) ->
            codesArr.put(JSONObject().apply { put("code", code); put("points", pts); put("type", "GIFT") })
        }
    }
    root.put("promoCodes", codesArr)

    root.toString()
}
