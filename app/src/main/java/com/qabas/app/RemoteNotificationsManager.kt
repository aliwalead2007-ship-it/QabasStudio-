package com.qabas.app

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * إشعارات عامة حقيقية عبر السحابة (يتم استلامها من أي جهاز):
 * - لوحة المطور تكتب مستنداً في Firestore collection `notifications`.
 * - كل جهاز يستمع للـ collection عبر listener ويعرض إشعاراً محلياً
 *   (عبر AppNotificationService) — بدون تكرار (set للمعرفات المرئية).
 *
 * ملاحظة: هذه الطبقة لا تتطلب خادماً خارجياً؛ استقبال فوري للعملاء المتصلين.
 * (الدفع عبر FCM لقوائم الأجهزة خارج نطاق تطبيق الجوال ويحتاج خادماً.)
 */
object RemoteNotificationsManager {
    private const val TAG = "RemoteNotificationsManager"
    private const val SEEN_PREFS = "remote_notif_seen_ids"

    data class Broadcast(
        val id: String,
        val title: String,
        val message: String,
        val target: String,
        val createdAt: Long,
        val sendAt: Long = 0L
    ) {
        /** موعد العرض الفعلي — فوري إن غاب الحقل (توافق مع المستندات القديمة). */
        val dueAt: Long get() = if (sendAt > 0) sendAt else createdAt
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _lastSendResult = MutableStateFlow<String?>(null)
    val lastSendResult: StateFlow<String?> = _lastSendResult.asStateFlow()

    private fun db() = FirebaseFirestore.getInstance()

    /** كتابة بث جديد إلى السحابة — يُرجع معرف المستند عند النجاح. */
    suspend fun sendBroadcast(
        context: Context,
        title: String,
        message: String,
        target: String = "all",
        sendAt: Long = 0L
    ): String? {
        return try {
            if (!CloudServices.isFirebaseInitialized) {
                _lastSendResult.value = "التطبيق في الوضع المحلي — لا توجد سحابة لاستقبال البث"
                return null
            }
            val docRef = db().collection("notifications").add(
                mapOf(
                    "title" to title,
                    "message" to message,
                    "target" to target,
                    "createdAt" to System.currentTimeMillis(),
                    "sendAt" to if (sendAt > 0) sendAt else System.currentTimeMillis(),
                    "sender" to AdminGuard.currentIdentity(context)
                )
            ).await()
            AuditLogger.log(context, "notification_broadcast", "«$title» إلى $target")
            _lastSendResult.value = "تم إرسال البث إلى السحابة ✅"
            docRef.id
        } catch (e: Exception) {
            Log.e(TAG, "sendBroadcast failed: ${e.message}", e)
            _lastSendResult.value = "فشل الإرسال: ${e.message}"
            null
        }
    }

    /** تدفق حي للبثّات من السحابة. */
    fun observeBroadcasts(): Flow<List<Broadcast>> = callbackFlow {
        if (!CloudServices.isFirebaseInitialized) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        try {
            val listener = db().collection("notifications")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "broadcast listen failed: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            val d = doc.data ?: return@mapNotNull null
                            Broadcast(
                                id = doc.id,
                                title = d["title"] as? String ?: "",
                                message = d["message"] as? String ?: "",
                                target = d["target"] as? String ?: "all",
                                createdAt = (d["createdAt"] as? Long) ?: 0L,
                                sendAt = (d["sendAt"] as? Long) ?: 0L
                            )
                        }
                        trySend(list)
                    }
                }
            awaitClose { listener.remove() }
        } catch (e: Exception) {
            Log.e(TAG, "observeBroadcasts init failed: ${e.message}")
            trySend(emptyList())
            close()
        }
    }

    private val inboxFlow: Flow<List<Broadcast>> by lazy {
        observeBroadcasts().distinctUntilChanged()
    }

    /** هل ينتمي هذا الجهاز للشريحة المستهدفة؟ (all/developers/premium) */
    fun deviceMatchesTarget(context: Context, target: String): Boolean {
        if (target == "all") return true
        val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        return when (target) {
            "developers" -> prefs.getBoolean("is_developer", false)
            "premium" -> prefs.getBoolean("is_premium", false) ||
                prefs.getBoolean("is_developer", false)
            else -> true
        }
    }

    /** حذف بث مجدول من السحابة (إلغاء قبل موعده). */
    suspend fun cancelBroadcast(docId: String): Boolean {
        return try {
            if (!CloudServices.isFirebaseInitialized) return false
            db().collection("notifications").document(docId).delete().await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "cancelBroadcast failed: ${e.message}", e)
            false
        }
    }

    /** يُشغَّل مرة من QabasApplication: يستمع للبثّات ويعرض إشعاراً محلياً لم يره الجهاز. */
    fun startInbox(context: Context) = scope.launch {
        // إعادة فحص دورية للبثّات المجدولة (الـ listener لا يُطلق عند حلول الموعد وحده)
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(15 * 60 * 1000L)
                checkDue(context)
            }
        }
        inboxFlow.collect { broadcasts ->
            deliverDue(context, broadcasts)
        }
    }

    private suspend fun checkDue(context: Context) {
        try {
            if (!CloudServices.isFirebaseInitialized) return
            val snapshot = db().collection("notifications")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()
            val list = snapshot.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                Broadcast(
                    id = doc.id,
                    title = d["title"] as? String ?: "",
                    message = d["message"] as? String ?: "",
                    target = d["target"] as? String ?: "all",
                    createdAt = (d["createdAt"] as? Long) ?: 0L,
                    sendAt = (d["sendAt"] as? Long) ?: 0L
                )
            }
            deliverDue(context, list)
        } catch (e: Exception) {
            Log.e(TAG, "checkDue failed: ${e.message}", e)
        }
    }

    private fun deliverDue(context: Context, broadcasts: List<Broadcast>) {
        if (broadcasts.isEmpty()) return
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        var changed = false
        broadcasts.forEach { b ->
            // مجدول لم يحن موعده بعد — يُترك لفحص لاحق
            if (b.id !in seen && b.dueAt <= now && deviceMatchesTarget(context, b.target)) {
                seen.add(b.id)
                changed = true
                runCatching {
                    AppNotificationService.sendNotification(context, b.title, b.message)
                }
            }
        }
        if (changed) {
            prefs.edit().putStringSet("ids", seen).apply()
        }
    }
}
