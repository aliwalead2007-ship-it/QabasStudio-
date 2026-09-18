package com.qabas.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

/**
 * إعدادات التحكم البعيد (Remote Config) — قلب «لوحة التحكم الكاملة».
 *
 * - المصدر الحاسم: مستند Firestore `app_config/config` إن وُجد Firebase.
 *   (قابل للتوسيع لاحقاً إلى جدول Supabase app_config بنفس المفاتيح.)
 * - كاش محلي: `qabas_prefs` (يعمل دون اتصال ولا يكسر البناء بلا سحابة).
 * - أي زر في شاشة المطور يكتب سحابياً ثم يحدّث الكاش حتى تتأثر كل الأجهزة.
 */
object AppRemoteConfig {
    private const val TAG = "AppRemoteConfig"

    const val COLLECTION = "app_config"
    const val DOC_ID = "config"

    const val KEY_MAINTENANCE = "sys_maintenance_mode"
    const val KEY_ACCEPT_REQUESTS = "sys_accept_requests"
    const val KEY_AUTO_AI_REPLY = "sys_auto_ai_reply"
    const val KEY_MAINTENANCE_MESSAGE = "sys_maintenance_message"
    const val KEY_MIN_VERSION = "sys_min_version_code"
    const val KEY_LAST_SYNC = "sys_config_last_sync"

    /** لقطة إعدادات موحّدة تُقرأ من السحابة أو الكاش أو الافتراضيات. */
    data class ConfigData(
        val maintenanceMode: Boolean = false,
        val acceptRequests: Boolean = true,
        val autoAiReply: Boolean = true,
        val maintenanceMessage: String = DEFAULT_MESSAGE,
        val minVersionCode: Int = 0,
        val lastSyncMs: Long = 0L
    ) {
        companion object {
            const val DEFAULT_MESSAGE = "الخدمة متوقفة مؤقتاً للتحديث والصيانة، يرجى المحاولة لاحقاً."
        }
    }

    /** كاش في الذاكرة للقراءة السريعة بعد أول تحديث سحابي. */
    @Volatile
    private var cached: ConfigData? = null

    fun readLocal(context: Context): ConfigData {
        val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        return ConfigData(
            maintenanceMode = prefs.getBoolean(KEY_MAINTENANCE, false),
            acceptRequests = prefs.getBoolean(KEY_ACCEPT_REQUESTS, true),
            autoAiReply = prefs.getBoolean(KEY_AUTO_AI_REPLY, true),
            maintenanceMessage = prefs.getString(KEY_MAINTENANCE_MESSAGE, ConfigData.DEFAULT_MESSAGE)
                ?: ConfigData.DEFAULT_MESSAGE,
            minVersionCode = prefs.getInt(KEY_MIN_VERSION, 0),
            lastSyncMs = prefs.getLong(KEY_LAST_SYNC, 0L)
        )
    }

    fun current(context: Context): ConfigData = cached ?: readLocal(context)

    private fun writeLocal(context: Context, data: ConfigData) {
        val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_MAINTENANCE, data.maintenanceMode)
            .putBoolean(KEY_ACCEPT_REQUESTS, data.acceptRequests)
            .putBoolean(KEY_AUTO_AI_REPLY, data.autoAiReply)
            .putString(KEY_MAINTENANCE_MESSAGE, data.maintenanceMessage)
            .putInt(KEY_MIN_VERSION, data.minVersionCode)
            .putLong(KEY_LAST_SYNC, data.lastSyncMs)
            .apply()
        cached = data
    }

    private fun db() = com.google.firebase.firestore.FirebaseFirestore.getInstance()

    /** جلب الإعدادات من السحابة وتحديث الكاش المحلي. يُرجع true عند النجاح. */
    suspend fun refreshFromCloud(context: Context): Boolean {
        if (!CloudServices.isFirebaseInitialized) return false
        return withContext(Dispatchers.IO) {
            try {
                val snap = db().collection(COLLECTION).document(DOC_ID).get().await()
                if (snap.exists()) {
                    val data = ConfigData(
                        maintenanceMode = snap.getBoolean(KEY_MAINTENANCE) ?: false,
                        acceptRequests = snap.getBoolean(KEY_ACCEPT_REQUESTS) ?: true,
                        autoAiReply = snap.getBoolean(KEY_AUTO_AI_REPLY) ?: true,
                        maintenanceMessage = snap.getString(KEY_MAINTENANCE_MESSAGE)
                            ?: ConfigData.DEFAULT_MESSAGE,
                        minVersionCode = (snap.getLong(KEY_MIN_VERSION) ?: 0L).toInt(),
                        lastSyncMs = snap.getLong(KEY_LAST_SYNC) ?: System.currentTimeMillis()
                    )
                    writeLocal(context, data)
                    Log.d(TAG, "Config loaded from cloud: $data")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshFromCloud failed: ${e.message}")
                false
            }
        }
    }

    /** دفع الإعدادات إلى السحابة (Firestore) بعد تطبيقها محلياً. يُرجع true عند النجاح. */
    suspend fun pushToCloud(context: Context, data: ConfigData): Boolean {
        writeLocal(context, data)
        if (!CloudServices.isFirebaseInitialized) return false
        return withContext(Dispatchers.IO) {
            try {
                val doc = hashMapOf<String, Any>(
                    KEY_MAINTENANCE to data.maintenanceMode,
                    KEY_ACCEPT_REQUESTS to data.acceptRequests,
                    KEY_AUTO_AI_REPLY to data.autoAiReply,
                    KEY_MAINTENANCE_MESSAGE to data.maintenanceMessage,
                    KEY_MIN_VERSION to data.minVersionCode,
                    KEY_LAST_SYNC to System.currentTimeMillis(),
                    "updatedBy" to AdminGuard.currentIdentity(context)
                )
                db().collection(COLLECTION).document(DOC_ID)
                    .set(doc, com.google.firebase.firestore.SetOptions.merge()).await()
                Log.d(TAG, "Config pushed to cloud")
                true
            } catch (e: Exception) {
                Log.e(TAG, "pushToCloud failed: ${e.message}", e)
                false
            }
        }
    }

    // ──────────────── القواعد المشروطة + تجارب A/B ────────────────

    const val OVERRIDES_COLLECTION = "app_config_overrides"

    /**
     * قاعدة تجاوز مشروطة: تغيّر قيمة مفتاح لشريحة فقط
     * (إصدار/نوع مستخدم/نسبة طرح تدريجي) — أساس تجارب A/B.
     */
    data class OverrideRule(
        val id: String = "",
        val key: String = KEY_MAINTENANCE,
        /** القيمة كنص: "true"/"false" للمنطقي، رقم، أو نص الرسالة */
        val value: String = "true",
        val userType: String = "all", // all/developers/premium/free
        val minVersion: Int = 0,
        val maxVersion: Int = Int.MAX_VALUE,
        /** نسبة الطرح 0-100 (100 = الجميع). التوزيع ثابت لكل جهاز. */
        val percent: Int = 100,
        val enabled: Boolean = true,
        val priority: Int = 0
    )

    @Volatile
    private var cachedOverrides: List<OverrideRule>? = null

    /** معرّف ثابت للجهاز لتوزيع نسب A/B بثبات. */
    fun stableBucket(context: Context): Int {
        val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_stable_id", null)
        if (id.isNullOrBlank()) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_stable_id", id).apply()
        }
        return (kotlin.math.abs(id.hashCode()) % 100)
    }

    private fun deviceUserType(context: Context): String {
        val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        return when {
            prefs.getBoolean("is_developer", false) -> "developers"
            prefs.getBoolean("is_premium", false) -> "premium"
            else -> "free"
        }
    }

    private fun ruleMatches(context: Context, rule: OverrideRule, versionCode: Int): Boolean {
        if (!rule.enabled) return false
        if (versionCode < rule.minVersion || versionCode > rule.maxVersion) return false
        val myType = deviceUserType(context)
        if (rule.userType != "all" && rule.userType != myType) {
            // المطور والمميز يريان قواعد الشريحة العامة + شريحتهما فقط
            if (!(rule.userType == "free" && (myType == "premium" || myType == "developers"))) {
                // قاعدة premium تشمل developers أيضاً
                if (!(rule.userType == "premium" && myType == "developers")) return false
            }
        }
        if (rule.percent >= 100) return true
        if (rule.percent <= 0) return false
        return stableBucket(context) < rule.percent
    }

    private fun currentVersionCode(context: Context): Int {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(0)
    }

    /** جلب القواعد من السحابة (مع كاش ذاكرة). */
    suspend fun fetchOverrides(context: Context): List<OverrideRule> {
        cachedOverrides?.let { return it }
        if (!CloudServices.isFirebaseInitialized) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val snap = db().collection(OVERRIDES_COLLECTION).get().await()
                val list = snap.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    OverrideRule(
                        id = doc.id,
                        key = d["key"] as? String ?: return@mapNotNull null,
                        value = d["value"] as? String ?: "true",
                        userType = d["userType"] as? String ?: "all",
                        minVersion = (d["minVersion"] as? Long)?.toInt() ?: 0,
                        maxVersion = (d["maxVersion"] as? Long)?.toInt() ?: Int.MAX_VALUE,
                        percent = (d["percent"] as? Long)?.toInt() ?: 100,
                        enabled = d["enabled"] as? Boolean ?: true,
                        priority = (d["priority"] as? Long)?.toInt() ?: 0
                    )
                }.sortedByDescending { it.priority }
                cachedOverrides = list
                list
            } catch (e: Exception) {
                Log.w(TAG, "fetchOverrides failed: ${e.message}")
                emptyList()
            }
        }
    }

    fun invalidateOverrides() {
        cachedOverrides = null
    }

    private suspend fun matchingRule(context: Context, key: String): OverrideRule? {
        val version = currentVersionCode(context)
        return fetchOverrides(context).firstOrNull { it.key == key && ruleMatches(context, it, version) }
    }

    /** قراءة فعالة: القاعدة المشروطة أولاً، ثم القيمة العامة. */
    suspend fun effectiveBoolean(context: Context, key: String, default: Boolean): Boolean {
        val rule = matchingRule(context, key) ?: return current(context).let {
            when (key) {
                KEY_MAINTENANCE -> it.maintenanceMode
                KEY_ACCEPT_REQUESTS -> it.acceptRequests
                KEY_AUTO_AI_REPLY -> it.autoAiReply
                else -> default
            }
        }
        return rule.value.equals("true", ignoreCase = true)
    }

    suspend fun effectiveString(context: Context, key: String, default: String): String {
        val rule = matchingRule(context, key) ?: return when (key) {
            KEY_MAINTENANCE_MESSAGE -> current(context).maintenanceMessage
            else -> default
        }
        return rule.value
    }

    /** إضافة/تحديث قاعدة من لوحة المطور. */
    suspend fun saveOverride(context: Context, rule: OverrideRule): Boolean {
        if (!CloudServices.isFirebaseInitialized) return false
        return withContext(Dispatchers.IO) {
            try {
                val doc = hashMapOf<String, Any>(
                    "key" to rule.key,
                    "value" to rule.value,
                    "userType" to rule.userType,
                    "minVersion" to rule.minVersion,
                    "maxVersion" to if (rule.maxVersion == Int.MAX_VALUE) Long.MAX_VALUE else rule.maxVersion.toLong(),
                    "percent" to rule.percent.toLong(),
                    "enabled" to rule.enabled,
                    "priority" to rule.priority.toLong(),
                    "updatedBy" to AdminGuard.currentIdentity(context),
                    "updatedAt" to System.currentTimeMillis()
                )
                if (rule.id.isBlank()) {
                    db().collection(OVERRIDES_COLLECTION).add(doc).await()
                } else {
                    db().collection(OVERRIDES_COLLECTION).document(rule.id)
                        .set(doc, com.google.firebase.firestore.SetOptions.merge()).await()
                }
                invalidateOverrides()
                AuditLogger.log(context, "config_override_save", "${rule.key}=${rule.value} (${rule.userType} ${rule.percent}٪)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "saveOverride failed: ${e.message}", e)
                false
            }
        }
    }

    /** حذف قاعدة. */
    suspend fun deleteOverride(ruleId: String): Boolean {
        if (!CloudServices.isFirebaseInitialized) return false
        return withContext(Dispatchers.IO) {
            try {
                db().collection(OVERRIDES_COLLECTION).document(ruleId).delete().await()
                invalidateOverrides()
                true
            } catch (e: Exception) {
                Log.e(TAG, "deleteOverride failed: ${e.message}", e)
                false
            }
        }
    }
}
