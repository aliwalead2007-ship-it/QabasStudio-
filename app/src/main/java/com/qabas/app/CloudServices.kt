package com.qabas.app

import java.util.Locale

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

import com.qabas.app.ProjectService.Project

/**
 * مدير الخدمات السحابية (Firebase)
 * تم تجهيز هذا الملف ليعمل فور إضافة ملف google-services.json
 */
object CloudServices {
    /** بريد مالك التطبيق — الاستثناء الوحيد لمنح رتبة «مطور» تلقائياً (بطلب من المالك نفسه) */
    const val OWNER_EMAIL = "aliwalead.2007@gmail.com"

    /** هل هذا حساب المالك؟ (مقارنة غير حساسة لحالة الأحرف وبالمسافات) */
    fun isOwnerAccount(email: String?): Boolean =
        email?.trim()?.lowercase(java.util.Locale.ROOT) == OWNER_EMAIL
    private const val TAG = "CloudServices"
    
    // حالة توفر الفايربيز لتجنب انهيار التطبيق قبل إضافة الملف
    val isFirebaseInitialized: Boolean
        get() = try {
            com.google.firebase.FirebaseApp.getApps(AppServices.appContext).isNotEmpty()
        } catch (e: Exception) {
            false
        }

    fun initializeWithJson(context: Context, jsonText: String): Pair<Boolean, String> {
        return try {
            val jsonObj = org.json.JSONObject(jsonText)
            val projectInfo = jsonObj.optJSONObject("project_info")
            val projectId = projectInfo?.optString("project_id") ?: ""
            val storageBucket = projectInfo?.optString("storage_bucket") ?: ""
            
            val clientArr = jsonObj.optJSONArray("client")
            var apiKey = ""
            var appId = ""
            if (clientArr != null && clientArr.length() > 0) {
                val firstClient = clientArr.getJSONObject(0)
                val clientInfo = firstClient.optJSONObject("client_info")
                appId = clientInfo?.optString("mobilesdk_app_id") ?: ""
                
                val apiKeyArr = firstClient.optJSONArray("api_key")
                if (apiKeyArr != null && apiKeyArr.length() > 0) {
                    apiKey = apiKeyArr.getJSONObject(0).optString("current_key")
                }
            }
            
            if (apiKey.isBlank() || appId.isBlank() || projectId.isBlank()) {
                return Pair(false, "ملف غير مكتمل: تعذر العثور على API Key أو App ID أو Project ID")
            }
            
            val optionsBuilder = com.google.firebase.FirebaseOptions.Builder()
                .setApiKey(apiKey)
                .setApplicationId(appId)
                .setProjectId(projectId)
                
            if (storageBucket.isNotBlank()) {
                optionsBuilder.setStorageBucket(storageBucket)
            }
            
            val options = optionsBuilder.build()
            
            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
                com.google.firebase.FirebaseApp.initializeApp(context, options)
            }
            
            val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("firebase_json", jsonText)
                .putString("firebase_key", apiKey)
                .putString("firebase_project_id", projectId)
                .putString("firebase_app_id", appId)
                .apply()
                
            Pair(true, "تم تفعيل Firebase بنجاح للمشروع: $projectId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse/initialize Firebase JSON: ${e.message}", e)
            Pair(false, "خطأ في قراءة ملف JSON: ${e.localizedMessage}")
        }
    }

    fun tryInitFromSavedConfig(context: Context) {
        try {
            if (com.google.firebase.FirebaseApp.getApps(context).isNotEmpty()) return
            val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            val jsonText = prefs.getString("firebase_json", "") ?: ""
            if (jsonText.isNotBlank()) {
                initializeWithJson(context, jsonText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in tryInitFromSavedConfig: ${e.message}")
        }
    }

    // --- خدمة المصادقة (Authentication) ---
    object Auth {
        val auth by lazy { FirebaseAuth.getInstance() }
        
        private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
        val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

        init {
            if (isFirebaseInitialized) {
                _currentUser.value = auth.currentUser
                auth.addAuthStateListener { firebaseAuth ->
                    _currentUser.value = firebaseAuth.currentUser
                }
            }
        }

        fun getCurrentUserId(): String? = if (isFirebaseInitialized) auth.currentUser?.uid else "local_user_123"
        
        suspend fun login(email: String, password: String): Boolean {
            if (!isFirebaseInitialized) return false
            return try {
                auth.signInWithEmailAndPassword(email, password).await()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Login failed: ${e.message}")
                false
            }
        }

        suspend fun register(email: String, password: String): Boolean {
            if (!isFirebaseInitialized) return false
            return try {
                auth.createUserWithEmailAndPassword(email, password).await()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Registration failed: ${e.message}")
                false
            }
        }

        suspend fun resetPassword(email: String): Boolean {
            if (!isFirebaseInitialized) return false
            return try {
                auth.sendPasswordResetEmail(email).await()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Password reset failed: ${e.message}")
                false
            }
        }

        fun logout() {
            if (isFirebaseInitialized) {
                auth.signOut()
            }
        }

    }

        /** synchronizes is_admin flag with Firebase Auth custom claim */
        suspend fun syncAdminClaimFromFirebase(context: Context) {
            if (!isFirebaseInitialized) return
            try {
                val user = Auth.auth.currentUser
                if (user == null) return
                val claims = user.getIdToken(false).await().claims
                val isAdminFromFirebase = claims["admin"] as? Boolean ?: false
                // رتبة المالك لا تُخفض أبداً — الحفاظ على is_admin عند حساب المالك ولو كان الـ claim السحابي فارغاً
                val newAdminState = isAdminFromFirebase || isOwnerAccount(user.email)
                val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("is_admin", newAdminState).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync admin claim: ${e.message}")
            }
        }

    // --- خدمة قاعدة البيانات (Firestore) ---
    object Database {
        private val db by lazy { FirebaseFirestore.getInstance() }

        suspend fun saveUser(userId: String, name: String, email: String, type: String = "مجاني") {
            if (!isFirebaseInitialized) return
            try {
                val userMap = hashMapOf(
                    "id" to userId,
                    "name" to name,
                    "email" to email,
                    "type" to type,
                    "createdAt" to System.currentTimeMillis(),
                    "status" to "نشط الآن",
                    "strikes" to 0
                )
                db.collection("users").document(userId).set(userMap, com.google.firebase.firestore.SetOptions.merge()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving user: ${e.message}")
            }
        }

        /**
         * حفظ/دمج سجل مستخدم في مجموعة users من لوحة إدارة المطور.
         * يكتب createdAt كـ epoch millis ليسهل تنسيقه عبر DevDashboardFormatters.formatRegDate.
         * يُرجع true عند النجاح، false عند الفشل (لتُمكِّن الـ UI من عرض Snackbar للخطأ).
         */
        suspend fun saveUserToCloud(
            userId: String,
            name: String,
            email: String,
            type: String,
            projectCount: Int = 0
        ): Boolean {
            if (!isFirebaseInitialized) {
                Log.w(TAG, "Firebase is not initialized. Cannot save user to cloud.")
                return false
            }
            return try {
                val userMap = hashMapOf(
                    "id" to userId,
                    "name" to name,
                    "email" to email,
                    "type" to type,
                    "projectCount" to projectCount,
                    "createdAt" to System.currentTimeMillis(),
                    "status" to "نشط الآن",
                    "isSuspended" to false,
                    "strikes" to 0,
                    "source" to "dev_dashboard"
                )
                db.collection("users").document(userId)
                    .set(userMap, com.google.firebase.firestore.SetOptions.merge()).await()
                Log.d(TAG, "User ($userId, $email) saved to Firestore successfully.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error saving user to cloud: ${e.message}", e)
                false
            }
        }

        /**
         * تحديث رتبة مستخدم فعلياً في Firestore من لوحة المطور.
         * حماية صارمة: رتبة المالك (OWNER_EMAIL) لا تُخفض أبداً من هذه الشاشة — فقط رفعها مسموح.
         * يُرجع true عند النجاح الحقيقي فقط — لا نجاح وهمي أبداً.
         */
        suspend fun updateUserRole(userId: String, userEmail: String, newType: String): Boolean {
            if (!isFirebaseInitialized) {
                Log.w(TAG, "Firebase is not initialized. Cannot update user role.")
                return false
            }
            if (isOwnerAccount(userEmail) && newType != "مطور") {
                Log.w(TAG, "Blocked attempt to downgrade owner account role.")
                return false
            }
            return try {
                db.collection("users").document(userId)
                    .update("type", newType).await()
                Log.d(TAG, "User ($userId) role updated to $newType successfully.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error updating user role: ${e.message}", e)
                false
            }
        }

        /**
         * تحديث حالة إيقاف مستخدم فعلياً في Firestore من لوحة المطور.
         * حماية صارمة: حساب المالك لا يمكن إيقافه من هذه الشاشة.
         * يُرجع true عند النجاح الحقيقي فقط.
         */
        suspend fun updateUserSuspension(userId: String, userEmail: String, isSuspended: Boolean): Boolean {
            if (!isFirebaseInitialized) {
                Log.w(TAG, "Firebase is not initialized. Cannot update suspension status.")
                return false
            }
            if (isOwnerAccount(userEmail) && isSuspended) {
                Log.w(TAG, "Blocked attempt to suspend owner account.")
                return false
            }
            return try {
                db.collection("users").document(userId)
                    .update("isSuspended", isSuspended).await()
                Log.d(TAG, "User ($userId) suspension set to $isSuspended successfully.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error updating suspension status: ${e.message}", e)
                false
            }
        }

        suspend fun savePromoCodeToCloud(code: String, type: String, value: Long): Boolean {
            if (!isFirebaseInitialized) {
                Log.w(TAG, "Firebase is not initialized. Cannot save promo code to cloud.")
                return false
            }
            return try {
                val normalized = code.uppercase(java.util.Locale.ROOT)
                val codeMap = hashMapOf(
                    "code" to normalized,
                    "type" to type, // "PROMO" or "GIFT"
                    "value" to value,
                    "createdAt" to System.currentTimeMillis()
                )
                db.collection("promo_codes").document(normalized).set(codeMap).await()
                Log.d(TAG, "Promo code ($normalized) saved to Firestore successfully.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error saving promo code: ${e.message}", e)
                false
            }
        }

        /**
         * جلب الأكواد الصالحة من Firestore وتحويلها إلى شكل قابل للاستخدام.
         * الكاش المحلي يُملأ مرة عند الإقلاع ثم يُحدَّث بعد كل كتابة جديدة.
         * يُرجع Pair(promos, giftCards) حيث promos=Map<code, durationMs> و giftCards=Map<code, points>.
         */
        suspend fun fetchValidCodesCache(): Pair<Map<String, Long>, Map<String, Int>> {
            if (!isFirebaseInitialized) return Pair(emptyMap(), emptyMap())
            return try {
                val snapshot = db.collection("promo_codes").get().await()
                val promos = mutableMapOf<String, Long>()
                val gifts = mutableMapOf<String, Int>()
                for (doc in snapshot.documents) {
                    val data = doc.data ?: continue
                    val code = (data["code"] as? String) ?: continue
                    val type = (data["type"] as? String) ?: continue
                    val raw = data["value"]
                    when (type) {
                        "PROMO" -> {
                            val days = (raw as? Number)?.toLong() ?: continue
                            promos[code] = days * 24L * 60L * 60L * 1000L
                        }
                        "GIFT" -> {
                            val points = (raw as? Number)?.toInt() ?: continue
                            gifts[code] = points
                        }
                    }
                }
                Pair(promos, gifts)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching valid codes from Firestore: ${e.message}", e)
                Pair(emptyMap(), emptyMap())
            }
        }

        suspend fun getCloudPromoCodes(): List<Map<String, Any>> {
            if (!isFirebaseInitialized) return emptyList()
            return try {
                val snapshot = db.collection("promo_codes").get().await()
                snapshot.documents.mapNotNull { it.data }
            } catch (e: Exception) {
                emptyList()
            }
        }
        
        suspend fun getAllUsers(): List<Map<String, Any>> {
            if (!isFirebaseInitialized) return emptyList()
            return try {
                val snapshot = db.collection("users").get().await()
                snapshot.documents.mapNotNull { it.data }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching users: ${e.message}")
                emptyList()
            }
        }

        /**
         * عدد مشاريع المستخدم الحقيقي من المجموعة الفرعية `users/{uid}/projects`.
         * يُستخدم في لوحة المطور لعرض أرقام حقيقية بدلاً من القيم المزيفة.
         */
        suspend fun getDevUserProjectCount(uid: String): Int {
            if (!isFirebaseInitialized || uid.isBlank()) return 0
            return try {
                val snapshot = db.collection("users").document(uid)
                    .collection("projects").get().await()
                snapshot.size()
            } catch (e: Exception) {
                Log.e(TAG, "Error counting projects for $uid: ${e.message}")
                0
            }
        }

        /**
         * منح رتبة مؤقتة: تُكتب الرتبة + تاريخ انتهاء (roleExpiresAt).
         * عند انتهاء المدة تُسترجع الرتبة تلقائياً عبر sweepExpiredRoles.
         */
        suspend fun grantTemporaryRole(userId: String, userEmail: String, newType: String, daysValid: Int): Boolean {
            if (!isFirebaseInitialized) return false
            if (isOwnerAccount(userEmail)) return false
            return try {
                val expiresAt = System.currentTimeMillis() + daysValid * 24L * 60 * 60 * 1000
                db.collection("users").document(userId)
                    .update(mapOf("type" to newType, "roleExpiresAt" to expiresAt)).await()
                Log.d(TAG, "Temporary role $newType granted to $userId for $daysValid days.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error granting temporary role: ${e.message}", e)
                false
            }
        }

        /** إرجاع الرتب المنتهية إلى Freemium (يُستدعى عند فتح لوحة المستخدمين). */
        suspend fun sweepExpiredRoles(): Int {
            if (!isFirebaseInitialized) return 0
            return try {
                val now = System.currentTimeMillis()
                val snapshot = db.collection("users")
                    .whereLessThan("roleExpiresAt", now).get().await()
                var count = 0
                snapshot.documents.forEach { doc ->
                    runCatching {
                        doc.reference.update(mapOf("type" to "Freemium", "roleExpiresAt" to com.google.firebase.firestore.FieldValue.delete())).await()
                        count++
                    }
                }
                if (count > 0) Log.d(TAG, "Swept $count expired roles.")
                count
            } catch (e: Exception) {
                Log.e(TAG, "sweepExpiredRoles failed: ${e.message}", e)
                0
            }
        }

        /** الخط الزمني للمستخدم: مشاريعه + مشترياته مرتبة زمنياً (الأحدث أولاً). */
        suspend fun getUserTimeline(uid: String): List<Map<String, Any>> {
            if (!isFirebaseInitialized || uid.isBlank()) return emptyList()
            return try {
                val events = mutableListOf<Map<String, Any>>()
                db.collection("users").document(uid).collection("projects")
                    .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(15).get().await().documents.forEach { doc ->
                        val d = doc.data ?: return@forEach
                        events.add(mapOf(
                            "kind" to "project",
                            "title" to (d["title"] as? String ?: d["idea"] as? String ?: "مشروع"),
                            "ts" to ((d["createdAt"] as? Long) ?: (d["timestamp"] as? Long) ?: 0L)
                        ))
                    }
                db.collection("users").document(uid).collection("purchases")
                    .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(15).get().await().documents.forEach { doc ->
                        val d = doc.data ?: return@forEach
                        events.add(mapOf(
                            "kind" to "purchase",
                            "title" to (d["productId"] as? String ?: "شراء"),
                            "ts" to ((d["timestamp"] as? Long) ?: 0L)
                        ))
                    }
                // تاريخ انتهاء الرتبة المؤقتة كحدث قادم
                db.collection("users").document(uid).get().await().data?.let { d ->
                    (d["roleExpiresAt"] as? Long)?.takeIf { it > 0 }?.let { exp ->
                        events.add(mapOf("kind" to "role_expiry", "title" to "انتهاء الرتبة المؤقتة", "ts" to exp))
                    }
                }
                events.sortedByDescending { (it["ts"] as? Long) ?: 0L }
            } catch (e: Exception) {
                Log.e(TAG, "getUserTimeline failed: ${e.message}", e)
                emptyList()
            }
        }

        // حفظ مشروع في السحابة
        suspend fun saveProjectToCloud(project: Project) {
            if (!isFirebaseInitialized) {
                Log.w(TAG, "Firebase is not initialized. Cannot save to cloud.")
                return
            }
            try {
                val userId = Auth.getCurrentUserId() ?: return
                db.collection("users").document(userId)
                  .collection("projects").document(project.id)
                  .set(project).await()
                Log.d(TAG, "Project saved to cloud successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving project to cloud: ${e.message}")
            }
        }
        
        // حذف مشروع من السحابة
        suspend fun deleteProjectFromCloud(projectId: String) {
            if (!isFirebaseInitialized) return
            try {
                val userId = Auth.getCurrentUserId() ?: return
                db.collection("users").document(userId)
                  .collection("projects").document(projectId)
                  .delete().await()
                Log.d(TAG, "Project deleted from cloud successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting project from cloud: ${e.message}")
            }
        }

        // جلب المشاريع من السحابة
        suspend fun getUserProjects(): List<Project> {
            if (!isFirebaseInitialized) return emptyList()
            
            return try {
                val userId = Auth.getCurrentUserId() ?: return emptyList()
                val snapshot = db.collection("users").document(userId)
                                 .collection("projects").get().await()
                snapshot.toObjects(Project::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching projects: ${e.message}")
                emptyList()
            }
        }

        // جلب المشاريع المنشورة في مجتمع الإلهام (Reels Feed)
        suspend fun getFeedProjects(): List<Project> {
            if (!isFirebaseInitialized) return emptyList()
            
            return try {
                val snapshot = db.collection("public_feed")
                                 .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                                 .limit(20)
                                 .get().await()
                snapshot.toObjects(Project::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching feed: ${e.message}")
                emptyList()
            }
        }

        // جلب لوحة المتصدرين (Leaderboard)
        suspend fun getLeaderboard(): List<CreatorStats> {
            if (!isFirebaseInitialized) return emptyList()
            
            return try {
                val snapshot = db.collection("leaderboard")
                                 .orderBy("points", com.google.firebase.firestore.Query.Direction.DESCENDING)
                                 .limit(50)
                                 .get().await()
                snapshot.documents.mapNotNull { doc ->
                    val isEligible = doc.getBoolean("is_eligible") ?: true
                    if (!isEligible) return@mapNotNull null // تجاهل المستخدمين الذين نالوا المكافأة مؤخراً
                    
                    CreatorStats(
                        name = doc.getString("name") ?: "صانع محتوى",
                        rank = doc.getLong("rank")?.toInt() ?: 0,
                        points = doc.getLong("points")?.toInt() ?: 0,
                        views = doc.getString("views") ?: "0K",
                        badge = doc.getString("badge") ?: ""
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching leaderboard: ${e.message}")
                emptyList()
            }
        }

        // تسجيل عملية شراء
        suspend fun recordPurchase(productId: String, purchaseToken: String) {
            if (!isFirebaseInitialized) return
            try {
                val userId = Auth.getCurrentUserId() ?: return
                val price = when(productId) {
                   "coin_500" -> 1.99
                   "coin_1200" -> 4.99
                   "coin_3000" -> 9.99
                   "coin_10000" -> 29.99
                   "pro_1m" -> 9.99
                   "pro_1y" -> 89.99
                   else -> 0.0
                }
                val record = hashMapOf(
                    "id" to java.util.UUID.randomUUID().toString(),
                    "userId" to userId,
                    "productId" to productId,
                    "purchaseToken" to purchaseToken,
                    "timestamp" to System.currentTimeMillis(),
                    "priceAmount" to price,
                    "currency" to "USD"
                )
                db.collection("transactions").document(record["id"] as String).set(record).await()
                db.collection("users").document(userId).collection("purchases").document(record["id"] as String).set(record).await()
                Log.d(TAG, "Purchase recorded successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error recording purchase: ${e.message}")
            }
        }

        // تسجيل عمليات فحص حارس المحتوى في السحابة
        suspend fun recordContentGuardCheck(text: String, verdict: String, score: Int, reason: String) {
            if (!isFirebaseInitialized) return
            try {
                val userId = Auth.getCurrentUserId() ?: "anonymous"
                val logId = "guard_${System.currentTimeMillis()}"
                val logData = hashMapOf(
                    "id" to logId,
                    "userId" to userId,
                    "textSnippet" to text.take(200),
                    "verdict" to verdict,
                    "score" to score,
                    "reason" to reason,
                    "timestamp" to System.currentTimeMillis()
                )
                db.collection("content_guard_logs").document(logId).set(logData).await()
                db.collection("users").document(userId).collection("guard_checks").document(logId).set(logData).await()
                Log.d(TAG, "Content guard log recorded successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error recording guard check: ${e.message}")
            }
        }

        // جلب سجلات حارس المحتوى بشكل مباشر (ريال تايم)
        fun observeGuardLogs(): Flow<List<Map<String, Any>>> = callbackFlow {
            if (!isFirebaseInitialized) {
                trySend(emptyList())
                close()
                return@callbackFlow
            }
            val listener = db.collection("content_guard_logs")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(40)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Guard listen failed: ${error.message}")
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { it.data }
                        trySend(list)
                    }
                }
            awaitClose { listener.remove() }
        }

        // تسجيل تفاصيل إحصائيات المشروع وتحديث مؤشرات صانع المحتوى
        suspend fun recordProjectAnalytics(projectId: String, title: String, status: String, cost: Double = 0.0) {
            if (!isFirebaseInitialized) return
            try {
                val userId = Auth.getCurrentUserId() ?: return
                val eventMap = hashMapOf(
                    "projectId" to projectId,
                    "userId" to userId,
                    "title" to title,
                    "status" to status,
                    "cost" to cost,
                    "timestamp" to System.currentTimeMillis()
                )
                db.collection("project_events").document("pe_${System.currentTimeMillis()}").set(eventMap).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error recording project analytics: ${e.message}")
            }
        }

        // جلب كل المشتريات للوحة المطور بشكل مباشر (ريال تايم)
        fun observeAllTransactions(): Flow<List<Map<String, Any>>> = callbackFlow {
            if (!isFirebaseInitialized) {
                trySend(emptyList())
                close()
                return@callbackFlow
            }
            val listener = db.collection("transactions")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed: ${error.message}")
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { it.data }
                        trySend(list)
                    }
                }
            awaitClose { listener.remove() }
        }

        // =====================================================================
        // إدارة كائنات الأنماط الفنية في Firestore (Style Objects in Cloud)
        // =====================================================================

        suspend fun saveStyleObjectToCloud(styleObject: StyleObject): Boolean {
            if (!isFirebaseInitialized) {
                Log.w(TAG, "Firebase is not initialized. Cannot save style object to Firestore.")
                return false
            }
            return try {
                val userId = Auth.getCurrentUserId() ?: "creator_local"
                val styleMap = styleObject.toMap().toMutableMap()
                styleMap["userId"] = userId
                styleMap["updatedAt"] = System.currentTimeMillis()

                // حفظ في المجموعة العامة للأفكار والأنماط
                db.collection("style_objects").document(styleObject.id).set(styleMap).await()

                // حفظ في مسار المستخدم الخاص
                db.collection("users").document(userId)
                    .collection("style_objects").document(styleObject.id)
                    .set(styleMap).await()

                Log.d(TAG, "StyleObject (${styleObject.id}) successfully saved to Firestore.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error saving style object to Firestore: ${e.message}", e)
                false
            }
        }

        suspend fun getCloudStyleObjects(userId: String? = null): List<StyleObject> {
            if (!isFirebaseInitialized) return emptyList()
            return try {
                val targetUserId = userId ?: Auth.getCurrentUserId()
                val snapshot = if (!targetUserId.isNullOrBlank()) {
                    val userStyles = db.collection("users").document(targetUserId)
                        .collection("style_objects").get().await()
                    if (!userStyles.isEmpty) userStyles else db.collection("style_objects").get().await()
                } else {
                    db.collection("style_objects").get().await()
                }

                snapshot.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    try {
                        StyleObject.fromMap(data)
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching style objects from Firestore: ${e.message}", e)
                emptyList()
            }
        }

        suspend fun deleteStyleObjectFromCloud(styleId: String): Boolean {
            if (!isFirebaseInitialized) return false
            return try {
                val userId = Auth.getCurrentUserId()
                db.collection("style_objects").document(styleId).delete().await()
                if (!userId.isNullOrBlank()) {
                    db.collection("users").document(userId)
                        .collection("style_objects").document(styleId).delete().await()
                }
                Log.d(TAG, "StyleObject ($styleId) deleted from Firestore.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting style object from Firestore: ${e.message}", e)
                false
            }
        }

        fun observeCloudStyleObjects(): Flow<List<StyleObject>> = callbackFlow {
            if (!isFirebaseInitialized) {
                trySend(emptyList())
                close()
                return@callbackFlow
            }
            val listener = db.collection("style_objects")
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen style_objects failed: ${error.message}")
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            val data = doc.data ?: return@mapNotNull null
                            try {
                                StyleObject.fromMap(data)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        trySend(list)
                    }
                }
            awaitClose { listener.remove() }
        }

        // =====================================================================
        // إدارة أنماط الماستر المركبة في Firestore (Master Styles in Cloud)
        // =====================================================================

        suspend fun saveMasterStyleToCloud(masterStyle: MasterStyle): Boolean {
            if (!isFirebaseInitialized) {
                Log.w(TAG, "Firebase is not initialized. Cannot save master style to Firestore.")
                return false
            }
            return try {
                val userId = Auth.getCurrentUserId() ?: "creator_local"
                val styleMap = masterStyle.toMap().toMutableMap()
                styleMap["userId"] = userId
                styleMap["updatedAt"] = System.currentTimeMillis()

                db.collection("master_styles").document(masterStyle.id).set(styleMap).await()

                db.collection("users").document(userId)
                    .collection("master_styles").document(masterStyle.id)
                    .set(styleMap).await()

                Log.d(TAG, "MasterStyle (${masterStyle.id}) successfully saved to Firestore.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error saving master style to Firestore: ${e.message}", e)
                false
            }
        }

        suspend fun getCloudMasterStyles(userId: String? = null): List<MasterStyle> {
            if (!isFirebaseInitialized) return emptyList()
            return try {
                val targetUserId = userId ?: Auth.getCurrentUserId()
                val snapshot = if (!targetUserId.isNullOrBlank()) {
                    val userMasters = db.collection("users").document(targetUserId)
                        .collection("master_styles").get().await()
                    if (!userMasters.isEmpty) userMasters else db.collection("master_styles").get().await()
                } else {
                    db.collection("master_styles").get().await()
                }

                snapshot.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    try {
                        MasterStyle.fromMap(data)
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching master styles from Firestore: ${e.message}", e)
                emptyList()
            }
        }

        suspend fun deleteMasterStyleFromCloud(masterStyleId: String): Boolean {
            if (!isFirebaseInitialized) return false
            return try {
                val userId = Auth.getCurrentUserId()
                db.collection("master_styles").document(masterStyleId).delete().await()
                if (!userId.isNullOrBlank()) {
                    db.collection("users").document(userId)
                        .collection("master_styles").document(masterStyleId).delete().await()
                }
                Log.d(TAG, "MasterStyle ($masterStyleId) deleted from Firestore.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting master style from Firestore: ${e.message}", e)
                false
            }
        }
    }
}

