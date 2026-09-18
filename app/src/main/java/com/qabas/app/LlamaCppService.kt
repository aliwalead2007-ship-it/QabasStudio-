package com.qabas.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * LlamaCppService — LLM محلي (Gemma3 1B / Qwen3 0.5B / SmolLM2 135M) عبر llama.cpp JNI.
 * موديل GGUF يوضع في assets/models/llama/ ثم ينسخ لأول مرة إلى filesDir.
 * لا يحتاج مفتاح، يعمل offline تماماً.
 * الرخصة: MIT (llama.cpp) + رخصة الموديل (غالباً Apache-2.0 أو MIT).
 */
object LlamaCppService {
    private const val TAG = "LlamaCppService"
    private const val MODEL_DIR = "models/llama/"
    private const val DEFAULT_MODEL = "gemma-3-1b-it-q4_k_m.gguf"

    // JNI declarations — تتوفر عند إضافة مكتبة llama-android
    external fun llamaInit(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun llamaGenerate(ctxPtr: Long, prompt: String, nPredict: Int, temp: Float, topP: Float, stopSequences: Array<String>): String
    external fun llamaFree(ctxPtr: Long)

    @Volatile
    private var ctxPtr: Long = 0
    @Volatile
    private var isInitialized = false
    @Volatile
    private var currentModelPath: String? = null

    init {
        try {
            System.loadLibrary("llama_jni")
            Log.i(TAG, "llama_jni library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "llama_jni not available (expected if not built): ${e.message}")
        }
    }

    /** ينسخ الموديل من assets إلى filesDir لأول مرة */
    private suspend fun ensureModelFile(context: Context, modelName: String): File? = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val modelDir = File(filesDir, MODEL_DIR)
        if (!modelDir.exists()) modelDir.mkdirs()
        val modelFile = File(modelDir, modelName)
        if (modelFile.exists() && modelFile.length() > 1000000) return@withContext modelFile
        try {
            val assets = context.assets
            val input = assets.open(MODEL_DIR + modelName)
            FileOutputStream(modelFile).use { out -> input.copyTo(out) }
            Log.i(TAG, "Copied model to ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            modelFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model from assets: ${e.message}")
            null
        }
    }

    /** تهيئة llama.cpp مع الموديل المحدد */
    suspend fun initialize(context: Context, modelName: String = DEFAULT_MODEL): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && ctxPtr != 0L && currentModelPath == modelName) return@withContext true
        val modelFile = ensureModelFile(context, modelName) ?: return@withContext false
        try {
            // تحرير السياق السابق إن وجد
            if (ctxPtr != 0L) {
                llamaFree(ctxPtr)
                ctxPtr = 0L
            }
            val nCtx = 2048 // سياق كافٍ للسكربت القصير
            val nThreads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1)
            ctxPtr = llamaInit(modelFile.absolutePath, nCtx, nThreads)
            if (ctxPtr == 0L) {
                Log.e(TAG, "llamaInit returned 0")
                return@withContext false
            }
            currentModelPath = modelName
            isInitialized = true
            Log.i(TAG, "llama.cpp initialized with $modelName (ctx=$nCtx, threads=$nThreads)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize llama.cpp: ${e.message}")
            false
        }
    }

    /** توليد نص (سكربت، حوار، أي مهمة نصية) */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        stopSequences: Array<String> = arrayOf("</s>", "<|endoftext|>")
    ): String? = withContext(Dispatchers.IO) {
        if (!isInitialized || ctxPtr == 0L) return@withContext null
        if (prompt.isBlank()) return@withContext null
        try {
            val result = llamaGenerate(ctxPtr, prompt, maxTokens, temperature, topP, stopSequences)
            if (result.isNotBlank()) {
                Log.d(TAG, "Generated ${result.length} chars")
                return@withContext result.trim()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "llamaGenerate failed: ${e.message}")
            null
        }
    }

    /** بناء برومبت مخصص للسكربت/المشاهد بالعربية */
    fun buildScriptPrompt(idea: String, styleDescription: String, contentType: String, contentTone: String): String {
        return """
            <|startoftext|>أنت مخرج سينمائي محترف للغة العربية، متخصص في صناعة محتوى إسلامي مؤثّر.
            اكتب سكربت فيديو مقسم لمشاهد واضحة، مع وصف بصري دقيق لكل مشهد، ونص التعليق الصوتي، ومدة تقريبية.
            الفكرة: $idea
            الأسلوب: ${styleDescription.ifBlank { "سينمائي هادئ، ألوان داكنة مع ذهبي" }}
            النوع: ${contentType.ifBlank { "وثائقي قصير" }}
            النبرة: ${contentTone.ifBlank { "متزنة، مؤثرة" }}

            أخرج JSON فقط بهذا التنسيق:
            {
              "scenes": [
                {
                  "title": "عنوان المشهد",
                  "description": "وصف بصري مفصل",
                  "voiceover": "نص التعليق الصوتي",
                  "duration": 8.0,
                  "keywords": ["كلمة1", "كلمة2"]
                }
              ]
            }
            <|endoftext|>
        """.trimIndent()
    }

    fun release() {
        if (ctxPtr != 0L) {
            llamaFree(ctxPtr)
            ctxPtr = 0L
            isInitialized = false
            currentModelPath = null
        }
    }

    /** توليد سكربت محلي عبر llama.cpp وإرجاع List<Scene> */
    suspend fun generateScriptLocal(
        idea: String,
        styleDescription: String,
        contentType: String,
        contentTone: String
    ): List<Scene>? = withContext(Dispatchers.IO) {
        if (!initialize(AppServices.appContext)) return@withContext null
        val prompt = buildScriptPrompt(idea, styleDescription, contentType, contentTone)
        val response = generate(prompt, maxTokens = 1024, temperature = 0.6f)
        if (response.isNullOrBlank()) return@withContext null
        // محاولة تحليل JSON
        try {
            val json = extractJson(response)
            val scenesArray = json.optJSONArray("scenes")
            if (scenesArray == null || scenesArray.length() == 0) return@withContext null
            val scenes = mutableListOf<Scene>()
            for (i in 0 until scenesArray.length()) {
                val obj = scenesArray.getJSONObject(i)
                val scene = Scene(
                    title = obj.optString("title", "مشهد ${i + 1}"),
                    description = obj.optString("description", ""),
                    durationInSeconds = obj.optDouble("duration", 8.0).toInt()
                )
                scenes.add(scene)
            }
            if (scenes.isNotEmpty()) return@withContext scenes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse local script JSON: ${e.message}")
        }
        null
    }

    private fun extractJson(text: String): org.json.JSONObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return org.json.JSONObject(text.substring(start, end + 1))
        }
        return org.json.JSONObject(text)
    }
}