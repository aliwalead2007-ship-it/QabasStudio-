package com.qabas.app

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

object AppRequestService {
    data class AppRequest(
        val id: String = UUID.randomUUID().toString(),
        val userEmail: String,
        val title: String,
        val description: String,
        val goal: String,
        val status: String = "pending", // pending, in_progress, completed
        val generatedPrompts: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val cost: Int = 0,
        val isPaid: Boolean = false,
        val progress: Int = 0, // 0 to 100
        val priceStatus: String = "none" // none, offered, accepted, rejected
    )

    data class ChatMessage(
        val id: String = UUID.randomUUID().toString(),
        val requestId: String,
        val senderEmail: String,
        val isDeveloper: Boolean,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private const val PREFS_NAME = "qabas_requests_prefs"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()


    data class ModelConfig(
        val modelName: String,
        val temperature: Float,
        val topK: Int,
        val systemInstructions: String
    )

    fun determineBestModelAndSettings(request: AppRequest): ModelConfig {
        val desc = request.description.lowercase() + " " + request.title.lowercase()
        return when {
            desc.contains("ألعاب") || desc.contains("game") || desc.contains("3d") -> ModelConfig(
                modelName = "Gemini 1.5 Pro",
                temperature = 0.8f,
                topK = 40,
                systemInstructions = "أنت خبير في تطوير الألعاب والمحركات. ركز على الأداء الجرافيكي والهندسة ثلاثية الأبعاد."
            )
            desc.contains("محادثة") || desc.contains("chat") || desc.contains("ذكاء") -> ModelConfig(
                modelName = "Gemini 1.5 Flash",
                temperature = 0.5f,
                topK = 30,
                systemInstructions = "أنت خبير في تطوير تطبيقات المحادثة والذكاء الاصطناعي. ركز على سرعة الاستجابة وربط الواجهات البرمجية (APIs)."
            )
            desc.contains("تجارة") || desc.contains("متجر") || desc.contains("e-commerce") -> ModelConfig(
                modelName = "Gemini 1.5 Pro",
                temperature = 0.3f,
                topK = 40,
                systemInstructions = "أنت خبير في تطبيقات التجارة الإلكترونية. ركز على أمان البيانات، وبوابات الدفع، وإدارة الحالة."
            )
            else -> ModelConfig(
                modelName = "Gemini 1.5 Pro",
                temperature = 0.7f,
                topK = 40,
                systemInstructions = "أنت مهندس برمجيات محترف ومهندس معماري. ركز على بناء هيكل متين وتجربة مستخدم ممتازة."
            )
        }
    }

    fun getRequests(context: Context, userEmail: String? = null, isDeveloper: Boolean = false): List<AppRequest> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString("requests", "[]") ?: "[]"
        val type = Types.newParameterizedType(List::class.java, AppRequest::class.java)
        val adapter = moshi.adapter<List<AppRequest>>(type)
        val allRequests: List<AppRequest> = adapter.fromJson(json) ?: emptyList()
        
        return if (isDeveloper) {
            allRequests.sortedByDescending { it.timestamp }
        } else {
            allRequests.filter { it.userEmail == userEmail }.sortedByDescending { it.timestamp }
        }
    }

    fun submitRequest(context: Context, request: AppRequest) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getRequests(context, isDeveloper = true).toMutableList()
        current.add(request)
        val type = Types.newParameterizedType(List::class.java, AppRequest::class.java)
        val adapter = moshi.adapter<List<AppRequest>>(type)
        prefs.edit().putString("requests", adapter.toJson(current)).apply()
    }

    fun getMessages(context: Context, requestId: String): List<ChatMessage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString("messages_$requestId", "[]") ?: "[]"
        val type = Types.newParameterizedType(List::class.java, ChatMessage::class.java)
        val adapter = moshi.adapter<List<ChatMessage>>(type)
        return adapter.fromJson(json) ?: emptyList()
    }

    fun sendMessage(context: Context, message: ChatMessage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getMessages(context, message.requestId).toMutableList()
        current.add(message)
        val type = Types.newParameterizedType(List::class.java, ChatMessage::class.java)
        val adapter = moshi.adapter<List<ChatMessage>>(type)
        prefs.edit().putString("messages_${message.requestId}", adapter.toJson(current)).apply()
    }
    
    suspend fun generatePromptsForDeveloper(request: AppRequest): String = withContext(Dispatchers.IO) {
        return@withContext AppServices.generateDeveloperPrompts(request)
    }

    fun updateRequestProgressAndPayment(context: Context, requestId: String, progress: Int? = null, isPaid: Boolean? = null, cost: Int? = null, status: String? = null, priceStatus: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getRequests(context, isDeveloper = true).toMutableList()
        val index = current.indexOfFirst { it.id == requestId }
        if (index != -1) {
            val req = current[index]
            current[index] = req.copy(
                progress = progress ?: req.progress,
                isPaid = isPaid ?: req.isPaid,
                cost = cost ?: req.cost,
                status = status ?: req.status,
                priceStatus = priceStatus ?: req.priceStatus
            )
            val type = Types.newParameterizedType(List::class.java, AppRequest::class.java)
            val adapter = moshi.adapter<List<AppRequest>>(type)
            prefs.edit().putString("requests", adapter.toJson(current)).apply()
        }
    }

    /** جسر الطلب ← مركز البناء: الطلب النشط قيد التنفيذ حالياً. */
    private const val ACTIVE_BUILD_REQUEST = "active_build_request_id"

    fun setActiveBuildRequest(context: Context, requestId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_BUILD_REQUEST, requestId).apply()
    }

    fun getActiveBuildRequest(context: Context): AppRequest? {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ACTIVE_BUILD_REQUEST, null) ?: return null
        return getRequests(context, isDeveloper = true).find { it.id == id }
    }

    fun clearActiveBuildRequest(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(ACTIVE_BUILD_REQUEST).apply()
    }

    fun updateRequestPrompts(context: Context, requestId: String, prompts: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getRequests(context, isDeveloper = true).toMutableList()
        val index = current.indexOfFirst { it.id == requestId }
        if (index != -1) {
            current[index] = current[index].copy(generatedPrompts = prompts)
            val type = Types.newParameterizedType(List::class.java, AppRequest::class.java)
            val adapter = moshi.adapter<List<AppRequest>>(type)
            prefs.edit().putString("requests", adapter.toJson(current)).apply()
        }
    }
}
