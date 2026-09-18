package com.qabas.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenRouter — بوابة نماذج مجانية حقيقية (Llama/Gemma/Qwen بصيغة `:free`).
 * المفتاح يُقرأ من `qabas_prefs` ثم BuildConfig — ويُستخدم في توليد خطط
 * الطلبات قبل السقوط على المخطط المحلي. أي فشل = null (لا نجاح وهمي).
 */
object OpenRouterService {

    private const val TAG = "OpenRouterService"
    const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    /** نماذج مجانية تُجرَّب بالترتيب حتى ينجح أحدها. */
    val FREE_MODELS = listOf(
        "meta-llama/llama-3.3-70b-instruct:free",
        "google/gemma-3-27b-it:free",
        "qwen/qwen3-32b:free"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** يولّد خطة برمجة عربية عبر OpenRouter — يُعيد null عند أي فشل. */
    suspend fun generatePlan(request: AppRequestService.AppRequest, apiKey: String): String? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext null
            val prompt = """
                أنت خبير هندسة برمجيات Android (Kotlin + Jetpack Compose).
                طلب العميل: الاسم: ${request.title} | الوصف: ${request.description} | الهدف: ${request.goal}
                اكتب خطة عمل عربية احترافية: إعدادات مقترحة، تحليل المشروع، الهندسة والشاشات،
                ثم 3 برومبتات جاهزة للنسخ (بيانات/واجهة/منطق)، وقائمة اختبار قبل التسليم.
            """.trimIndent()
            for (model in FREE_MODELS) {
                val text = tryModel(apiKey, model, prompt)
                if (!text.isNullOrBlank()) {
                    Log.d(TAG, "Plan via $model (${text.length} chars)")
                    return@withContext "☁️ خطة OpenRouter ($model)\n━━━━━━━━━━━━━━\n\n$text"
                }
            }
            null
        }

    private fun tryModel(apiKey: String, model: String, prompt: String): String? {
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.7)
                put("max_tokens", 3000)
            }.toString().toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://qabas.app")
                .header("X-Title", "Qabas Studio")
                .post(body)
                .build()
            val response = client.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "$model HTTP ${response.code}")
                return null
            }
            val json = JSONObject(response.body?.string().orEmpty())
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "$model failed: ${e.message}")
            null
        }
    }

    /** محادثة متعددة الأدوار بتاريخ كامل — تُستخدم في المساعد العام. */
    suspend fun chatWithHistory(
        apiKey: String,
        system: String,
        history: List<Pair<Boolean, String>>,
        model: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || history.isEmpty()) return@withContext null
        val models = if (model.isNullOrBlank()) FREE_MODELS else listOf(model)
        for (m in models) {
            val text = tryHistory(apiKey, m, system, history)
            if (!text.isNullOrBlank()) return@withContext text
        }
        null
    }

    private fun tryHistory(
        apiKey: String,
        model: String,
        system: String,
        history: List<Pair<Boolean, String>>
    ): String? {
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", system)
                    })
                    history.takeLast(10).forEach { (isUser, text) ->
                        put(JSONObject().apply {
                            put("role", if (isUser) "user" else "assistant")
                            put("content", text.take(2000))
                        })
                    }
                })
                put("temperature", 0.7)
                put("max_tokens", 2000)
            }.toString().toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://qabas.app")
                .header("X-Title", "Qabas Studio")
                .post(body)
                .build()
            val response = client.newCall(httpRequest).execute()
            if (!response.isSuccessful) return null
            JSONObject(response.body?.string().orEmpty())
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** محادثة عامة (تُستخدم في المحرر): system + prompt — تُعيد النص أو null. */
    suspend fun chat(apiKey: String, system: String, prompt: String, maxTokens: Int = 6000): String? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext null
            for (model in FREE_MODELS) {
                val text = tryChat(apiKey, model, system, prompt, maxTokens)
                if (!text.isNullOrBlank()) return@withContext text
            }
            null
        }

    private fun tryChat(apiKey: String, model: String, system: String, prompt: String, maxTokens: Int): String? {
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", system)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.7)
                put("max_tokens", maxTokens)
            }.toString().toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://qabas.app")
                .header("X-Title", "Qabas Studio")
                .post(body)
                .build()
            val response = client.newCall(httpRequest).execute()
            if (!response.isSuccessful) return null
            JSONObject(response.body?.string().orEmpty())
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** يولّد هيكل مشروع أندرويد أدنى **قابل للبناء**: gradle + manifest + كود + CI. */
    suspend fun generateStarterFiles(
        request: AppRequestService.AppRequest,
        plan: String,
        apiKey: String
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val prompt = """
            أنت مهندس Android (Kotlin + Jetpack Compose). ابدأ مشروع تطبيق جديد قابل للبناء فعلاً عبر GitHub Actions:
            الاسم: ${request.title} | الوصف: ${request.description} | الهدف: ${request.goal}
            الخطة المعتمدة:
            ${plan.take(2500)}
            ولّد هذه الملفات العشرة بالضبط (package ثابت: com.client.app) — ملف CI يُحقن تلقائياً فلا تولّده:
            1) settings.gradle 2) build.gradle (الجذر) 3) app/build.gradle (applicationId com.client.app, minSdk 24, compose)
            4) app/src/main/AndroidManifest.xml (يصرّح بـ MyApp كـ android:name) 5) app/src/main/java/com/client/app/MainActivity.kt (شاشة ترحيب تحقق الهدف)
            6) app/src/main/java/com/client/app/data/Models.kt 7) app/src/main/java/com/client/app/ui/MainViewModel.kt
            8) README.md 9) app/src/main/res/values/strings.xml
            10) app/src/main/java/com/client/app/MyApp.kt (فئة Application فيها init للمراقبة بمعرف نائب MEASURE_APP_ID — راجع docs/MONITORING.md)
            الصيغة الصارمة لكل ملف (كررها 10 مرات):
            FILE: المسار/الكامل/للملف
            ```
            المحتوى الكامل هنا
            ```
            لا شرح خارج هذه البنية. كل ملف يجب أن يكون صحيحاً وصالحاً للبناء.
        """.trimIndent()
        for (model in FREE_MODELS) {
            val text = tryChat(apiKey, model, "أنت مولد مشاريع أندرويد. تلتزم بصيغة FILE: ثم كتلة كود فقط.", prompt, 8000)
            val files = parseFiles(text.orEmpty())
            if (files.size >= 5) return@withContext files
        }
        emptyList()
    }

    /** امتدادات ملفات المشروع المقبولة — أي شيء خارجها يُرفض بأمان. */
    private val ALLOWED_EXT = setOf(
        "kt", "kts", "java", "xml", "md", "gradle", "properties",
        "yml", "yaml", "json", "gitignore", "pro", "txt"
    )

    /** يفكك رد النموذج إلى (مسار ← كود) حسب صيغة FILE:. */
    fun parseFiles(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val fileMark = Regex("""(?m)^FILE:\s*(.+?)\s*$""")
        val marks = fileMark.findAll(text).toList()
        for ((i, m) in marks.withIndex()) {
            val path = m.groupValues[1].trim().removePrefix("/").take(200)
            val ext = path.substringAfterLast(".", "").lowercase()
            if (path.isBlank() || ".." in path || ext !in ALLOWED_EXT) continue
            val segStart = m.range.last + 1
            val segEnd = marks.getOrNull(i + 1)?.range?.first ?: text.length
            val seg = text.substring(segStart, segEnd)
            val code = extractFence(seg) ?: seg.trim().take(30000)
            if (code.isNotBlank()) out.add(path to code)
            if (out.size >= 12) break
        }
        return out
    }

    private fun extractFence(seg: String): String? {
        val first = seg.indexOf("```")
        if (first == -1) return null
        val start = seg.indexOf("\n", first)
        if (start == -1) return null
        val end = seg.indexOf("```", start)
        if (end == -1) return null
        return seg.substring(start + 1, end).trim().takeIf { it.isNotBlank() }
    }

    /** محادثة بالأدوات: النموذج يستدعي دوال التطبيق الحقيقية حتى 4 جولات. */
    suspend fun chatWithTools(
        apiKey: String,
        system: String,
        history: List<Pair<Boolean, String>>,
        tools: List<AiTools.ToolDef>,
        model: String? = null,
        maxTurns: Int = 4
    ): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || history.isEmpty()) return@withContext Pair("", emptyList())
        val useModel = model?.takeIf { it.isNotBlank() } ?: FREE_MODELS.first()
        val usedTools = mutableListOf<String>()
        val convo = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            history.takeLast(10).forEach { (isUser, text) ->
                put(JSONObject().put("role", if (isUser) "user" else "assistant").put("content", text.take(2000)))
            }
        }
        var finalText = ""
        repeat(maxTurns) {
            val body = JSONObject().apply {
                put("model", useModel)
                put("messages", convo)
                put("tools", AiTools.toApiJson(tools))
                put("temperature", 0.7)
                put("max_tokens", 2000)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://qabas.app")
                .header("X-Title", "Qabas Studio")
                .post(body)
                .build()
            val resp = try {
                client.newCall(req).execute()
            } catch (_: Exception) {
                return@withContext Pair("", usedTools)
            }
            if (!resp.isSuccessful) return@withContext Pair("", usedTools)
            val msg = JSONObject(resp.body?.string().orEmpty())
                .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return@withContext Pair("", usedTools)
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                finalText = msg.optString("content", "")
                return@withContext Pair(finalText, usedTools)
            }
            convo.put(JSONObject().apply {
                put("role", "assistant")
                put("content", msg.optString("content", ""))
                put("tool_calls", calls)
            })
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                val def = tools.find { it.name == name }
                val result = def?.let {
                    usedTools.add(name)
                    runCatching { it.run(args) }.getOrDefault("تعذر تنفيذ الأداة $name.")
                } ?: "أداة غير معروفة: $name."
                convo.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", call.optString("id"))
                    put("content", result.take(3000))
                })
            }
        }
        Pair(finalText.ifBlank { "نفّذت الأدوات ولم يتبقَّ رد نصي." }, usedTools)
    }

    /** فحص سريع لصحة المفتاح — GET /models (خفيف بلا تكلفة). */
    suspend fun validateKey(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val httpRequest = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val response = client.newCall(httpRequest).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }
}
