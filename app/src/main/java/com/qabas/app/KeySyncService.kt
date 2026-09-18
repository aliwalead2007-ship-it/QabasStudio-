package com.qabas.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object KeySyncService {

    private const val TAG = "KeySyncService"
    private const val PREFS_NAME = "qabas_prefs"
    private const val SYNC_TABLE = "user_api_keys"
    private const val ENCRYPTION_KEY_PREF = "key_sync_master"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable
    data class SyncedKey(
        val id: String = "",
        val user_id: String = "",
        val service: String = "",
        val encrypted_value: String = "",
        val updated_at: String = ""
    )

    private val KEY_FIELDS = listOf(
        "gemini_key", "groq_key", "openai_key", "openrouter_key", "huggingface_key",
        "azure_speech_key", "azure_speech_region", "elevenlabs_key",
        "pexels_key", "pixabay_key"
    )

    private fun getOrCreateMasterKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var key = prefs.getString(ENCRYPTION_KEY_PREF, null)
        if (key == null) {
            key = buildString {
                val random = java.security.SecureRandom()
                val bytes = ByteArray(16)
                random.nextBytes(bytes)
                bytes.forEach { append("%02x".format(it)) }
            }
            prefs.edit().putString(ENCRYPTION_KEY_PREF, key).apply()
        }
        return key
    }

    private fun encrypt(value: String, masterKey: String): String {
        return try {
            val keyBytes = masterKey.toByteArray(Charsets.UTF_8).copyOf(16)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Encrypt failed, using plain base64", e)
            Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    private fun decrypt(encrypted: String, masterKey: String): String {
        return try {
            val keyBytes = masterKey.toByteArray(Charsets.UTF_8).copyOf(16)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decrypted = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Decrypt failed, trying plain base64", e)
            try {
                String(Base64.decode(encrypted, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) { "" }
        }
    }

    private fun getUserId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("user_id", null)
            ?: prefs.getString("user_email", "anonymous")?.let { email ->
                val digest = MessageDigest.getInstance("SHA-256").digest(email.toByteArray())
                digest.take(16).joinToString("") { "%02x".format(it) }
            }
            ?: "anonymous"
    }

    fun pushToCloud(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        if (!SupabaseConfig.isConfigured) {
            onResult?.invoke(false, "Supabase غير مُعد")
            return
        }

        scope.launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val masterKey = getOrCreateMasterKey(context)
                val userId = getUserId(context)
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())

                val keysToSync = KEY_FIELDS.mapNotNull { field ->
                    val value = prefs.getString(field, "") ?: ""
                    if (value.isNotBlank() && !value.startsWith("your_")) {
                        mapOf(
                            "user_id" to userId,
                            "service" to field,
                            "encrypted_value" to encrypt(value, masterKey),
                            "updated_at" to timestamp
                        )
                    } else null
                }

                if (keysToSync.isEmpty()) {
                    withContext(Dispatchers.Main) { onResult?.invoke(false, "لا توجد مفاتيح لل-sync") }
                    return@launch
                }

                // Upsert each key
                keysToSync.forEach { keyData ->
                    SupabaseConfig.client.from(SYNC_TABLE).upsert(
                        json.parseToJsonElement(json.encodeToString(
                            kotlinx.serialization.serializer(),
                            keyData
                        )) as? kotlinx.serialization.json.JsonObject ?: return@forEach
                    ) {
                        onConflict = "user_id,service"
                    }
                }

                withContext(Dispatchers.Main) {
                    onResult?.invoke(true, "تم رفع ${keysToSync.size} مفتاح للسحابة ☁️✅")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Push failed", e)
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, "فشل الرفع: ${e.message ?: "خطأ غير معروف"}")
                }
            }
        }
    }

    fun pullFromCloud(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        if (!SupabaseConfig.isConfigured) {
            onResult?.invoke(false, "Supabase غير مُعد")
            return
        }

        scope.launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val masterKey = getOrCreateMasterKey(context)
                val userId = getUserId(context)

                val result = SupabaseConfig.client.from(SYNC_TABLE).select {
                    filter { eq("user_id", userId) }
                    order("updated_at", Order.DESCENDING)
                }.decodeList<SyncedKey>()

                if (result.isEmpty()) {
                    withContext(Dispatchers.Main) { onResult?.invoke(false, "لا توجد مفاتيح محفوظة في السحابة") }
                    return@launch
                }

                var imported = 0
                val editor = prefs.edit()
                result.forEach { synced ->
                    val decrypted = decrypt(synced.encrypted_value, masterKey)
                    if (decrypted.isNotBlank() && synced.service in KEY_FIELDS) {
                        editor.putString(synced.service, decrypted)
                        imported++
                    }
                }
                editor.apply()

                withContext(Dispatchers.Main) {
                    onResult?.invoke(true, "تم سحب $imported مفتاح من السحابة ☁️📥")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pull failed", e)
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, "فشل السحب: ${e.message ?: "خطأ غير معروف"}")
                }
            }
        }
    }

    fun syncBidirectional(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        if (!SupabaseConfig.isConfigured) {
            onResult?.invoke(false, "Supabase غير مُعد")
            return
        }

        scope.launch {
            try {
                // First pull, then push (merge strategy: newest wins)
                pullFromCloud(context) { pullSuccess, pullMsg ->
                    if (pullSuccess) {
                        pushToCloud(context) { pushSuccess, pushMsg ->
                            onResult?.invoke(pushSuccess, "$pullMsg\n$pushMsg")
                        }
                    } else {
                        // Pull failed, just push local keys
                        pushToCloud(context) { pushSuccess, pushMsg ->
                            onResult?.invoke(pushSuccess, "سحب فاشل: $pullMsg\n$pushMsg")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, "خطأ في المزامنة: ${e.message}")
                }
            }
        }
    }
}
