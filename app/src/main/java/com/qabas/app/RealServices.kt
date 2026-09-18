package com.qabas.app

import kotlinx.coroutines.Dispatchers
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * موحّد مفاتيح الخدمات: أسبقية المفتاح المُدخل من شاشة "مفاتيح API" (qabas_prefs)
 * ثم المفتاح المحقون وقت البناء من .env / GitHub Secrets عبر BuildConfig.
 * القيم الوهمية (your_key / YOUR_...) تُعامل كمفقودة — لا تشغيل وهمي أبداً.
 */
object KeyVault {
    private const val PREFS = "qabas_prefs"

    private fun prefs() =
        AppServices.appContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun isReal(value: String?): Boolean {
        val t = value?.trim() ?: return false
        return t.isNotEmpty() && !t.lowercase().startsWith("your_")
    }

    private fun resolve(prefsKey: String, fromBuildConfig: String?): String {
        val fromPrefs = prefs().getString(prefsKey, "") ?: ""
        if (isReal(fromPrefs)) return fromPrefs.trim()
        if (isReal(fromBuildConfig)) return fromBuildConfig!!.trim()
        return ""
    }

    val gemini: String get() = resolve("gemini_key", BuildConfig.GEMINI_API_KEY)
    val groq: String get() = resolve("groq_key", BuildConfig.GROQ_API_KEY)
    val openai: String get() = resolve("openai_key", BuildConfig.OPENAI_API_KEY)
    val huggingface: String get() = resolve("huggingface_key", BuildConfig.HUGGINGFACE_API_KEY)
    val azureSpeechKey: String get() = resolve("azure_speech_key", BuildConfig.AZURE_SPEECH_KEY)
    val azureSpeechRegion: String get() = resolve("azure_speech_region", BuildConfig.AZURE_SPEECH_REGION)
    val elevenlabs: String get() = resolve("elevenlabs_key", BuildConfig.ELEVENLABS_API_KEY)
    val pexels: String get() = resolve("pexels_key", BuildConfig.PEXELS_API_KEY)
    val pixabay: String get() = resolve("pixabay_key", BuildConfig.PIXABAY_API_KEY)
    val coverr: String get() = (prefs().getString("coverr_key", "") ?: "").trim()

    /** المفتاح الفعّال لأي اسم مفتاح معروف — يستخدمه الطبيب ولوحة الجاهزية لتقرير الحقيقة كاملة */
    fun effective(prefsKey: String): String = when (prefsKey) {
        "gemini_key" -> gemini
        "groq_key" -> groq
        "openai_key" -> openai
        "huggingface_key" -> huggingface
        "azure_speech_key" -> azureSpeechKey
        "azure_speech_region" -> azureSpeechRegion
        "elevenlabs_key" -> elevenlabs
        "pexels_key" -> pexels
        "pixabay_key" -> pixabay
        else -> (prefs().getString(prefsKey, "") ?: "").trim()
    }
}

/** استخراج النص من استجابة Gemini الموحدة — يُعيد null عند الفشل */
private fun extractGeminiText(responseJson: JSONObject): String? {
    val candidates = responseJson.optJSONArray("candidates") ?: return null
    if (candidates.length() == 0) return null
    val parts = candidates.getJSONObject(0)
        .optJSONObject("content")?.optJSONArray("parts") ?: return null
    if (parts.length() == 0) return null
    return parts.getJSONObject(0).optString("text", "").trim()
}

/** عميل HTTP مشترك لجميع خدمات الذكاء الاصطناعي — يُعاد استخدام اتصالات الاتصال ويوفر الذاكرة */
object SharedHttpClient {
    val instance: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

object RealGeminiService {
    private val client = SharedHttpClient.instance
        
    
    suspend fun analyzeIdea(idea: String): IdeaAnalysis = withContext(Dispatchers.IO) {
        val apiKey = KeyVault.gemini
        val fallbackResult = buildDefaultIdeaAnalysis(idea)
        
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY") {
            SystemLogsManager.addLog("WARN", "لم يتم العثور على مفتاح Gemini، سيتم استخدام التحليل المحلي.", androidx.compose.ui.graphics.Color(0xFFE8C547))
            return@withContext fallbackResult
        }
        
        return@withContext NetworkUtils.safeApiCallWithRetry(
            context = AppServices.appContext,
            maxRetries = 3,
            initialDelayMs = 1000L,
            fallback = { fallbackResult }
        ) {
            val tasteContext = TasteManager.getTasteContext(AppServices.appContext)
            val systemPrompt = """
                أنت محلل محتوى دعوي إخراجي متخصص وكبير المخرجين الفنيين (Art Director).
                يجب عليك استخراج النبرة والمحاور والمشاهد والخطاف من نص المستخدم.
                هام جداً في المشاهد (Visual Metaphors & Pacing): لا تستخدم ترجمة حرفية للمشاهد (مثلاً دعاء = شخص يدعو). استخدم استعارات بصرية طبيعية (رمال الصحراء، قطرة ماء، غيوم، نور). 
                اجعل مدد المشاهد (durationHintSeconds) متغيرة: 2-3 ثوانٍ للمقدمة السريعة والخطاف، 5-7 ثوانٍ للتأمل والرسائل العميقة.
                إن كان النص قصيراً فقلل عدد المشاهد. لا تستخدم قوالب جاهزة.
                $tasteContext
                
                يجب أن تكون النتيجة بتنسيق JSON حصراً:
                {
                    "hook": "جملة افتتاحية مستخرجة أو مصاغة مباشرة من معنى النص",
                    "tone": "النبرة المستخرجة من النص (مثل: خاشع، حماسي، تأملي، وعظي، تعليمي)",
                    "themes": ["محور 1", "محور 2"],
                    "scenes": [
                        { "title": "عنوان المشهد", "description": "وصف المشهد المشتق من النص", "durationHintSeconds": 5 }
                    ]
                }
            """.trimIndent()

            val contentsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "$systemPrompt\n\n--- الفكرة ---\n$idea\n--- نهاية ---") })
                    })
                })
            }
            
            val jsonBody = JSONObject().apply {
                put("contents", contentsArray)
                put("generationConfig", JSONObject().apply {
                    put("response_mime_type", "application/json")
                    put("temperature", 0.2)
                })
            }
            
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                .post(requestBody)
                .build()
                
            val response = ApiUsageTracker.track(AppServices.appContext, "Gemini") { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val responseData = response.body?.string() ?: ""
                val responseJson = JSONObject(responseData)
                val text = extractGeminiText(responseJson)
                if (!text.isNullOrBlank()) {
                        val cleanJson = text.replace("```json", "").replace("```", "").trim()
                        val resultObj = JSONObject(cleanJson)
                        
                        val confidence = resultObj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
                        if (confidence < 0.3) {
                            SystemLogsManager.addLog("WARN", "مستوى الثقة ضعيف (\${(confidence * 100).toInt()}%)، سيتم استخدام التحليل المحلي.", androidx.compose.ui.graphics.Color(0xFFE8C547))
                            return@safeApiCallWithRetry fallbackResult
                        }
                        
                        val themesList = mutableListOf<String>()
                        val themesArr = resultObj.optJSONArray("themes")
                        if (themesArr != null) {
                            for (i in 0 until themesArr.length()) themesList.add(themesArr.getString(i))
                        }
                        
                        val scenesList = mutableListOf<String>()
                        val scenesArr = resultObj.optJSONArray("scenes")
                        if (scenesArr != null) {
                            for (i in 0 until scenesArr.length()) {
                                val sObj = scenesArr.optJSONObject(i)
                                if (sObj != null) {
                                    scenesList.add(sObj.optString("title", "مشهد") + ": " + sObj.optString("description", ""))
                                }
                            }
                        }
                        
                        val hook = resultObj.optString("hook", "")
                        
                        return@safeApiCallWithRetry IdeaAnalysis(
                            summary = idea.take(100),
                            suggestedStyle = "سينمائي وقور",
                            goal = "رسالة إيمانية",
                            targetAudience = "الجمهور العام",
                            tone = resultObj.optString("tone", "روحاني"),
                            keywords = themesList,
                            proposedScenes = scenesList.takeIf { it.isNotEmpty() } ?: listOf("مشهد 1: عرض الفكرة الأساسية"),
                            viralityScore = (confidence * 100).toInt(),
                            hookSuggestions = if (hook.isNotBlank()) listOf(hook) else emptyList(),
                            ctaSuggestions = emptyList(),
                            confidence = confidence,
                            themes = themesList,
                            source = "MODEL"
                        )
                    }
            }
            throw Exception("Failed to analyze idea")
        }
    }

    suspend fun transcribeAudio(audioFile: File): String = withContext(Dispatchers.IO) {
        var apiKey = KeyVault.gemini
        if (apiKey.isBlank()) {
            apiKey = KeyVault.groq
        }
        if (apiKey.isBlank() || !audioFile.exists() || audioFile.length() == 0L) {
            return@withContext ""
        }

        return@withContext NetworkUtils.safeApiCall(
            context = AppServices.appContext,
            fallback = { "" }
        ) {
            val audioBytes = audioFile.readBytes()
            val base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
            val mimeType = when (audioFile.extension.lowercase()) {
                "m4a" -> "audio/mp4"
                "mp3" -> "audio/mp3"
                "wav" -> "audio/wav"
                "aac" -> "audio/aac"
                else -> "audio/mp4"
            }

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", mimeType)
                                put("data", base64Audio)
                            })
                        })
                        put(JSONObject().apply {
                            put("text", "استمع لهذا التسجيل الصوتي بدقة واستخرج النص العربي المنطوق حصراً كفكرة لمشروع فيديو دعوي، بدون أي مقدمات أو شروحات إضافية.")
                        })
                    })
                }))
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = ApiUsageTracker.track(AppServices.appContext, "Gemini") { client.newCall(request).execute() }
            val responseBodyString = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseBodyString)
                val text = extractGeminiText(jsonResponse)
                if (!text.isNullOrBlank()) {
                    return@safeApiCall text
                }
            }
            ""
        }
    }

    suspend fun analyzeVideoStyle(url: String): VideoStyleAnalysis = withContext(Dispatchers.IO) {
        val apiKey = KeyVault.gemini
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY") {
            return@withContext VideoStyleAnalysis(detectedStyle = "تأكد من إعداد مفتاح API.")
        }
        
        return@withContext NetworkUtils.safeApiCall(
            context = AppServices.appContext,
            fallback = { VideoStyleAnalysis(detectedStyle = "لم يتم التعرف على النمط.") }
        ) {
            val prompt = """
                أنت خبير مونتاج سينمائي ومحلل فني للفيديوهات (Art Director & Video Editor).
                المستخدم قدم هذا المدخل (قد يكون رابط فيديو تيك توك/يوتيوب، أو وصفاً كتابياً للأسلوب): 
                "$url"
                
                مهمتك:
                1. إذا كان رابطاً: حاول استنتاج الأسلوب الفني والمونتاج المتوقع لهذا الرابط بناءً على خبرتك، أو تخيل أسلوباً احترافياً عالي الجودة (Premium Cinematic) يناسب صناع المحتوى الإسلامي الهادف.
                2. إذا كان وصفاً نصياً: قم بتحليل الوصف وتحويله إلى عناصر فنية دقيقة للإنتاج.
                3. اجعل النتائج مذهلة، دقيقة، وتصلح كدليل (Prompt) لمحرك مونتاج ذكاء اصطناعي.
                
                أخرج النتيجة بصيغة JSON حصراً بهذا الشكل:
                {
                    "dominantColors": "مثال: تباين عالي مع ذهبي دافئ وأسود عميق",
                    "transitionSpeed": "مثال: سريعة وخاطفة (Glitch/Whip)",
                    "movementPatterns": "مثال: زووم بطيء متصاعد (Slow Zoom-in)",
                    "overallRhythm": "مثال: ملحمي يتصاعد مع الإيقاع",
                    "audioStyle": "مثال: مؤثرات Swoosh عميقة وصدى خفيف",
                    "typographyStyle": "مثال: خط كوفي عريض متوهج",
                    "contentTone": "مثال: مؤثر وملهم",
                    "targetAudience": "مثال: الشباب وصناع التغيير",
                    "keywords": ["cinematic", "epic", "islamic", "premium"]
                }
            """.trimIndent()
            
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", prompt)
                    }))
                }))
                put("generationConfig", JSONObject().apply {
                    put("response_mime_type", "application/json")
                })
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = ApiUsageTracker.track(AppServices.appContext, "Gemini") { client.newCall(request).execute() }
            val responseBodyString = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseBodyString)
                val text = extractGeminiText(jsonResponse)
                if (!text.isNullOrBlank()) {
                        val cleanJson = text.replace("```json", "").replace("```", "").trim()
                        val obj = JSONObject(cleanJson)
                        
                        val analysisResult = VideoStyleAnalysis(
                            dominantColors = obj.optString("dominantColors", Translator.tr("تباين عالي مع ذهبي")),
                            transitionSpeed = obj.optString("transitionSpeed", Translator.tr("سريعة")),
                            movementPatterns = obj.optString("movementPatterns", Translator.tr("متغيرة")),
                            overallRhythm = obj.optString("overallRhythm", Translator.tr("ملحمي")),
                            audioStyle = obj.optString("audioStyle", Translator.tr("مؤثرات محيطية")),
                            typographyStyle = obj.optString("typographyStyle", Translator.tr("عريض")),
                            contentTone = obj.optString("contentTone", Translator.tr("مؤثر")),
                            targetAudience = obj.optString("targetAudience", Translator.tr("الشباب")),
                            keywords = listOf()
                        )
                        
                        // Learn from this reference video
                        try {
                            TasteManager.registerPreference(AppServices.appContext, "STYLE", analysisResult.dominantColors, 2)
                            TasteManager.registerPreference(AppServices.appContext, "PACING", analysisResult.transitionSpeed, 2)
                            TasteManager.registerPreference(AppServices.appContext, "AUDIO", analysisResult.audioStyle, 2)
                        } catch(e: Exception) {}
                        
                        return@safeApiCall analysisResult
                    }
            }
            throw Exception("Failed to parse Gemini response")
        }
    }

    suspend fun generateScript(idea: String, styleDescription: String = "", contentType: String = "", contentTone: String = ""): List<Scene> = withContext(Dispatchers.IO) {
        // Content Filter — only block explicit forbidden content. Never replace Islamic content with error scene.
        val ideaOk = ContentFilterService.filterText(idea)
        val styleOk = styleDescription.isBlank() || ContentFilterService.filterText(styleDescription)
        if (!ideaOk) {
            SystemLogsManager.addLog(
                "WARN",
                "تحذير فلتر المحتوى على الفكرة — سيتم المتابعة بالإنتاج",
                androidx.compose.ui.graphics.Color(0xFFE8C547)
            )
        }
        if (!styleOk) {
            SystemLogsManager.addLog(
                "WARN",
                "تحذير فلتر المحتوى على الوصف الأسلوبي — سيتم المتابعة",
                androidx.compose.ui.graphics.Color(0xFFE8C547)
            )
        }

        val fallbackScenes = buildDefaultFallbackScenes(idea)
        val tasteContext = try { TasteManager.getTasteContext(AppServices.appContext) } catch (_: Exception) { "" }
        val prompt = """
            أنت المخرج الفني وكبير مونتيري منصة «قَبَس» (QABAS AI Studio) - المتخصص الأول في صناعة المحتوى الإسلامي والقرآني القصير فائق الجودة والانتشار (Reels/Shorts/TikTok) بهوية بصرية وروحية فاخرة (Dark Slate & Gold Aesthetic).
            $tasteContext
            الموضوع الأساسي: $idea
            نوع المحتوى المطلوب: $contentType
            النبرة العاطفية والروحية المطلوبة: $contentTone
            التوجيهات الأسلوبية (مهم جداً): $styleDescription

            مهمتك: تحويل الفكرة إلى مشاهد سينمائية مقسمة بدقة تجذب المشاهد منذ الثانية الأولى، وتأخذ بقلبه عبر رحلة إيمانية بصرية متكاملة حتى الخاتمة.

            ثورة قبس الإخراجية (قواعد صارمة جداً):
            1. الاستعارة البصرية (Visual Metaphors): إياك والترجمة الحرفية! إذا كان النص "يجب أن نصبر"، لا تصف رجلاً صابراً. بل صف "قطرة ندى تسقط على أرض قاحلة"، وإذا كان "التوحيد" صف "شعاع نور وحيد يخترق غيوماً داكنة". اعتمد حصرياً على الرموز الطبيعية والكونية العميقة.
            2. إيقاع التقطيع (Pacing & Rhythm): اجعل `durationInSeconds` متغيراً حسب المشهد. الجمل السريعة/الخطافية تحتاج لقطات قصيرة (2-3 ثوانٍ) وإيقاع (سريع - Jump Cuts)، بينما الجمل التأملية تأخذ لقطات طويلة (5-7 ثوانٍ) وإيقاع (بطيء تأملي - Slow Zoom In).
            3. التلوين السينمائي النفسي (Mood Color Grading): اجعل `visualEffect` يعكس الحالة النفسية للمشهد (مثلاً: "Cool dark teal tones for sadness/trials" أو "Warm glowing golden light for hope/mercy").
            4. الدقة البصرية: كتابة وصف كل مشهد (description) باللغة الإنجليزية للبحث المباشر عن B-Roll (مثال: Macro shot of a single water drop hitting dry desert sand, highly detailed, slow motion).

            أخرج النتيجة حصرياً بصيغة JSON كمصفوفة من المشاهد بدون أي نصوص خارج الـ JSON. كل مشهد يحتوي على:
            - title: عنوان المشهد القصير باللغة العربية (عبارة السرد أو الخطاف).
            - description: وصف بصري سينمائي دقيق بالإنجليزية لتوليد الفيديو (مثال: Cinematic slow motion shot of an open holy Quran with warm golden sun rays streaming through an archway, 8k resolution, photorealistic).
            - durationInSeconds: مدة المشهد بالثواني (بين 3 إلى 6 ثوانٍ).
            - transitionType: نوع الانتقال السينمائي (مثال: Smooth Dissolve, Soft Zoom In, Whip Pan, Light Fade).
            - visualEffect: المؤثرات البصرية (مثال: Cinematic Golden Color Grading, Subtle Light Leaks, Film Grain).
            - tempo: إيقاع المشهد (سريع، متوسط، ملحمي، هادئ).
        """.trimIndent()

        // سلسلة المحركات: Gemini ← Groq ← OpenAI ← محلي — أول ناجح يُعتمد ويُعلن
        val geminiKey = KeyVault.gemini
        if (geminiKey.isNotEmpty() && geminiKey != "YOUR_GEMINI_API_KEY") {
            val scenes = tryGeminiScenes(geminiKey, prompt)
            if (scenes.size >= 2) {
                SceneEngineMonitor.report("Gemini")
                SystemLogsManager.addLog("DIRECTOR", "المحرك: Gemini أنتج ${scenes.size} مشاهد", androidx.compose.ui.graphics.Color(0xFF4CAF50))
                return@withContext scenes
            }
        }

        val groqText = try { RealGroqService.chatOrGenerate("$prompt\n\nأخرج JSON فقط بلا أي شرح.") } catch (_: Exception) { null }
        val groqScenes = parseScenesJson(groqText)
        if (groqScenes.size >= 2) {
            SceneEngineMonitor.report("Groq")
            SystemLogsManager.addLog("DIRECTOR", "المحرك: Groq أنتج ${groqScenes.size} مشاهد", androidx.compose.ui.graphics.Color(0xFF4CAF50))
            return@withContext groqScenes
        }

        val openaiText = try { RealOpenAIService.chatOrGenerate("$prompt\n\nأخرج JSON فقط بلا أي شرح.") } catch (_: Exception) { null }
        val openaiScenes = parseScenesJson(openaiText)
        if (openaiScenes.size >= 2) {
            SceneEngineMonitor.report("OpenAI")
            SystemLogsManager.addLog("DIRECTOR", "المحرك: OpenAI أنتج ${openaiScenes.size} مشاهد", androidx.compose.ui.graphics.Color(0xFF4CAF50))
            return@withContext openaiScenes
        }

        SceneEngineMonitor.report("محلي")
        SystemLogsManager.addLog("DIRECTOR", "المحرك: محلي (بلا مفاتيح صالحة) — مشاهد أساسية", androidx.compose.ui.graphics.Color(0xFFE8C547))
        fallbackScenes
    }

    private suspend fun tryGeminiScenes(apiKey: String, prompt: String): List<Scene> {
        return try {
            NetworkUtils.safeApiCall(context = AppServices.appContext, fallback = { emptyList<Scene>() }) {
                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
                    }))
                    put("generationConfig", JSONObject().apply { put("responseMimeType", "application/json") })
                }
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = ApiUsageTracker.track(AppServices.appContext, "Gemini") { client.newCall(request).execute() }
                if (!response.isSuccessful) return@safeApiCall emptyList<Scene>()
                val responseJson = JSONObject(response.body?.string() ?: "")
                val textResponse = extractGeminiText(responseJson) ?: return@safeApiCall emptyList<Scene>()
                parseScenesJson(textResponse)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseScenesJson(text: String?): List<Scene> {
        if (text.isNullOrBlank() || !text.contains("[")) return emptyList()
        return try {
            val cleanJson = text.substring(text.indexOf("["), text.lastIndexOf("]") + 1)
            val jsonArray = JSONArray(cleanJson)
            val scenes = mutableListOf<Scene>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                scenes.add(
                    Scene(
                        title = item.optString("title", Translator.tr("مشهد ${i + 1}")),
                        description = item.optString("description", ""),
                        durationInSeconds = item.optInt("durationInSeconds", 5),
                        transitionType = item.optString("transitionType", "Fade"),
                        visualEffect = item.optString("visualEffect", "cinematic"),
                        tempo = item.optString("tempo", Translator.tr("متوسط"))
                    )
                )
            }
            scenes
        } catch (_: Exception) { emptyList() }
    }

        private fun buildDefaultIdeaAnalysis(idea: String): IdeaAnalysis {
        val cleanIdea = idea.trim().ifBlank { "محتوى إسلامي هادف" }
        val parts = cleanIdea.split(Regex("[.،!؟\\n]")).map { it.trim() }.filter { it.length > 5 }
        val generatedScenes = mutableListOf<String>()
        if (parts.size >= 3) {
            generatedScenes.add("المشهد 1: ${parts[0]}")
            generatedScenes.add("المشهد 2: ${parts[1]}")
            generatedScenes.add("المشهد 3: ${parts[2]}")
        } else {
            val shortText = cleanIdea.take(30) + if (cleanIdea.length > 30) "..." else ""
            generatedScenes.add("المشهد 1: مقدمة حول ($shortText)")
            generatedScenes.add("المشهد 2: عرض الفكرة الأساسية")
            generatedScenes.add("المشهد 3: الخاتمة والدعوة للتفكر")
        }
        return IdeaAnalysis(
            summary = cleanIdea.take(100),
            suggestedStyle = "سينمائي وقور (Gold & Deep Slate)",
            goal = "إيصال رسالة هادفة للمشاهدين",
            targetAudience = "الجمهور العام",
            tone = "خاشع وملهم",
            keywords = cleanIdea.split(" ").take(4).filter { it.length > 3 },
            proposedScenes = generatedScenes,
            viralityScore = 0,
            hookSuggestions = listOf("تعذّر التحليل — أكمل بالفكرة كما هي"),
            ctaSuggestions = listOf("شارك المقطع لتعم الفائدة", "اترك تعليقاً برأيك"),
            source = "FALLBACK"
        )
    }

    private fun buildDefaultFallbackScenes(idea: String): List<Scene> {
        val cleanIdea = idea.trim().ifBlank { "تعظيم النبي ﷺ ونشر أثره" }
        val isProphetTopic = cleanIdea.contains("نبي") || cleanIdea.contains("رسول") || cleanIdea.contains("محمد") || cleanIdea.contains("سيرة") || cleanIdea.contains("الصحابة")
        val isQuranTopic = cleanIdea.contains("قرآن") || cleanIdea.contains("آية") || cleanIdea.contains("سورة") || cleanIdea.contains("تلاوة") || cleanIdea.contains("تدبر")
        val isDuaaTopic = cleanIdea.contains("دعاء") || cleanIdea.contains("توبة") || cleanIdea.contains("استغفار") || cleanIdea.contains("رجاء") || cleanIdea.contains("خشوع")
        val isTafakkurTopic = cleanIdea.contains("تفكر") || cleanIdea.contains("كون") || cleanIdea.contains("سماء") || cleanIdea.contains("خلق") || cleanIdea.contains("عظمة")

        return when {
            isProphetTopic -> listOf(
                Scene(
                    title = "ما سر تعظيم النبي ﷺ في قلوب الصحابة الكرام؟",
                    description = "Cinematic view of the Prophet's Mosque dome and minarets in Medina under warm golden sunset light",
                    durationInSeconds = 5,
                    transitionType = "Dissolve",
                    visualEffect = "Cinematic Golden Color Grading",
                    tempo = "ملحمي وقور",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-view-of-a-mosque-dome-and-minaret-42285-large.mp4"
                ),
                Scene(
                    title = "كان خلقه القرآن، وأرسله ربه رحمة للعالمين وهادياً وبشيراً",
                    description = "Close up shot of an ancient Arabic calligraphy manuscript illuminated by soft candlelight",
                    durationInSeconds = 6,
                    transitionType = "Zoom In",
                    visualEffect = "Light Leaks",
                    tempo = "متوسط",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-turning-pages-of-an-old-book-41558-large.mp4"
                ),
                Scene(
                    title = "صلوا عليه وسلموا تسليماً تطيب بها قلوبكم وترتفع بها درجاتكم",
                    description = "Majestic wide angle shot of the Holy Mosque illuminated at night with warm golden glow",
                    durationInSeconds = 5,
                    transitionType = "Fade",
                    visualEffect = "Film Grain",
                    tempo = "خاشع",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-mosque-illuminated-at-night-42284-large.mp4"
                )
            )
            isQuranTopic -> listOf(
                Scene(
                    title = "تدبر في آيات الله تفتح لك أبواب السكينة والنور",
                    description = "Cinematic slow motion shot of an open holy Quran with warm golden sun rays streaming through an archway",
                    durationInSeconds = 5,
                    transitionType = "Dissolve",
                    visualEffect = "Cinematic Color Grading",
                    tempo = "هادئ",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-hands-holding-an-open-quran-42286-large.mp4"
                ),
                Scene(
                    title = "كتابٌ أحكمت آياته ثم فُصلت من لدن حكيم خبير",
                    description = "Arabic calligraphy pen writing Quranic verses with golden shimmering ink",
                    durationInSeconds = 6,
                    transitionType = "Zoom In",
                    visualEffect = "Light Leaks",
                    tempo = "متوسط",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-artist-drawing-with-calligraphy-pen-43615-large.mp4"
                ),
                Scene(
                    title = "اجعل للقرآن نصيباً من يومك ليزهر قلبك بالبركة",
                    description = "Peaceful rays of sunlight breaking through clouds over serene nature landscape",
                    durationInSeconds = 5,
                    transitionType = "Fade",
                    visualEffect = "Film Grain",
                    tempo = "خاشع",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-clouds-and-fog-over-the-mountain-peaks-42616-large.mp4"
                )
            )
            isDuaaTopic -> listOf(
                Scene(
                    title = "إذا ضاقت بك السبل فارفع يديك إلى من بيده ملكوت كل شيء",
                    description = "Emotional cinematic silhouette of a man praying in a serene mosque mihrab",
                    durationInSeconds = 5,
                    transitionType = "Dissolve",
                    visualEffect = "Cinematic Color Grading",
                    tempo = "خاشع",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-man-praying-in-a-mosque-42288-large.mp4"
                ),
                Scene(
                    title = "وقال ربكم ادعوني أستجب لكم، إنه سميع قريب مجيب الدعاء",
                    description = "Gentle rain drops falling on green leaves symbolizing divine mercy and hope",
                    durationInSeconds = 6,
                    transitionType = "Zoom In",
                    visualEffect = "Light Leaks",
                    tempo = "هادئ",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-rain-drops-falling-on-leaves-42795-large.mp4"
                ),
                Scene(
                    title = "لا تيأس من روح الله، فالفرج أقرب مما تظن",
                    description = "Golden sunrise over desert sand dunes with warm morning light",
                    durationInSeconds = 5,
                    transitionType = "Fade",
                    visualEffect = "Film Grain",
                    tempo = "ملهم",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-desert-sand-dunes-under-a-clear-blue-sky-42526-large.mp4"
                )
            )
            isTafakkurTopic -> listOf(
                Scene(
                    title = "انظر إلى عظمة هذا الكون البديع.. من أتقن صنعه؟",
                    description = "Breathtaking time lapse of starry night sky and milky way galaxy over mountains",
                    durationInSeconds = 5,
                    transitionType = "ZoomIn",
                    visualEffect = "Cinematic Color Grading",
                    tempo = "ملحمي",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-starry-sky-time-lapse-4008-large.mp4"
                ),
                Scene(
                    title = "سنريهم آياتنا في الآفاق وفي أنفسهم حتى يتبين لهم أنه الحق",
                    description = "Sea waves crashing on shore at golden sunset with reflecting sky",
                    durationInSeconds = 6,
                    transitionType = "Dissolve",
                    visualEffect = "Light Leaks",
                    tempo = "متوسط",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-sea-waves-crashing-on-the-shore-at-sunset-42866-large.mp4"
                ),
                Scene(
                    title = "سبحان الخالق العظيم.. تفكر لحظة واحمد الله على نعمه",
                    description = "Majestic mountain peaks covered with flowing clouds and morning sunlight",
                    durationInSeconds = 5,
                    transitionType = "Fade",
                    visualEffect = "Film Grain",
                    tempo = "تأملي",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-clouds-and-fog-over-the-mountain-peaks-42616-large.mp4"
                )
            )
            else -> listOf(
                Scene(
                    title = "« $cleanIdea » - رسالة نور وأثر ملهم",
                    description = "Cinematic dramatic shot of illuminated Islamic architecture with warm golden backlight",
                    durationInSeconds = 5,
                    transitionType = "Dissolve",
                    visualEffect = "Cinematic Color Grading",
                    tempo = "ملحمي",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-mosque-illuminated-at-night-42284-large.mp4"
                ),
                Scene(
                    title = "جوهر المعنى وحكمة اليوم التي تغير طريقة تفكيرك",
                    description = "Close up shot of historical manuscript pages turning under candlelight",
                    durationInSeconds = 6,
                    transitionType = "Zoom In",
                    visualEffect = "Light Leaks",
                    tempo = "متوسط",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-turning-pages-of-an-old-book-41558-large.mp4"
                ),
                Scene(
                    title = "انشر هذا الأثر المبارك واجعل لك بصمة خير تدوم",
                    description = "Peaceful panoramic view of sunrise with warm glowing light",
                    durationInSeconds = 5,
                    transitionType = "Fade",
                    visualEffect = "Film Grain",
                    tempo = "وقور",
                    mediaUrl = "https://assets.mixkit.co/videos/preview/mixkit-desert-sand-dunes-under-a-clear-blue-sky-42526-large.mp4"
                )
            )
        }
    }

    suspend fun generateDeveloperPrompts(request: AppRequestService.AppRequest): String = withContext(Dispatchers.IO) {
        val apiKey = KeyVault.gemini
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY") {
            return@withContext "حدث خطأ أثناء إنشاء الأوامر." 
        }
        
        return@withContext NetworkUtils.safeApiCall(
            context = AppServices.appContext,
            fallback = { "حدث خطأ أثناء إنشاء الأوامر." }
        ) {
            val systemPrompt = """
                أنت خبير في هندسة البرمجيات وتطوير تطبيقات Android باستخدام Jetpack Compose.
                طلب العميل هو كالتالي:
                الاسم: ${request.title}
                الوصف: ${request.description}
                الهدف: ${request.goal}
                
                قم بكتابة خطة عمل متكاملة لمبرمج يستخدم منصة Google AI Studio، تتضمن التالي:
                1. إعدادات منصة Google AI Studio (Configurations): حدد النموذج الأفضل (مثلاً Gemini 1.5 Pro للبرمجة)، ودرجة الحرارة (Temperature) المناسبة، وتعليمات النظام (System Instructions) التي يجب وضعها قبل البدء.
                2. تحليل المشروع الشامل ومتطلباته.
                3. هندسة التطبيق والخطوات البرمجية الدقيقة (هيكلة البيانات، واجهة المستخدم، إدارة الحالة).
                4. الـ Prompts الجاهزة التي يمكن للمبرمج نسخها ولصقها مباشرة في AI Studio لبناء كل جزء من التطبيق.
                
                الرجاء تنسيق الرد بشكل بطاقات أو نقاط واضحة (Markdown) باللغة العربية بأسلوب احترافي جداً.
            """.trimIndent()
            
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", systemPrompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                })
            }
            
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            
            val httpRequest = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                .post(requestBody)
                .build()
                
            val response = ApiUsageTracker.track(AppServices.appContext, "Gemini") { client.newCall(httpRequest).execute() }
            if (!response.isSuccessful) {
                return@safeApiCall "فشل توليد التوجيهات"
            }
            
            val responseData = response.body?.string() ?: ""
            val jsonResponse = JSONObject(responseData)
            val candidates = jsonResponse.optJSONArray("candidates")
            
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.getJSONObject(0)
                val contentObj = candidate.optJSONObject("content")
                val parts = contentObj?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "")
                    return@safeApiCall text
                }
            }
            "فشل توليد التوجيهات"
        }
    }
}

object RealMediaLibraryService {
    private val client = SharedHttpClient.instance

    suspend fun fetchMedia(query: String, type: String = "video"): String = withContext(Dispatchers.IO) {
        val cleanQuery = ContentFilterService.filterImageQuery(query)
        val visualKeywords = BRollEngine.mapArabicToVisualKeywords(cleanQuery)
        
        return@withContext NetworkUtils.safeApiCall(
            context = AppServices.appContext,
            fallback = { 
                if (type == "audio") {
                    ContentFilterService.getFallbackAudio(ContentFilterService.filterAudioQuery(cleanQuery))
                } else {
                    val fallbackBroll = BRollEngine.matchBRoll(cleanQuery)
                    if (type == "video") fallbackBroll.videoUrl else fallbackBroll.thumbnailUrl
                }
            }
        ) {
            val pixabayKey = KeyVault.pixabay
            val pexelsKey = KeyVault.pexels

            if (type == "audio") {
                val audioQuery = ContentFilterService.filterAudioQuery(cleanQuery)
                if (pixabayKey.isNotEmpty() && pixabayKey != "YOUR_PIXABAY_API_KEY") {
                    val url = "https://pixabay.com/api/audio/?key=$pixabayKey&q=$audioQuery"
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val responseJson = org.json.JSONObject(response.body?.string() ?: "")
                        val hits = responseJson.optJSONArray("hits")
                        if (hits != null && hits.length() > 0) {
                            val audioUrl = hits.getJSONObject(0).optString("url")
                            if (audioUrl.isNotBlank()) return@safeApiCall audioUrl
                        }
                    }
                }
                return@safeApiCall ContentFilterService.getFallbackAudio(audioQuery)
            }
            
            // 1. Try Pexels API with translated visual concepts first
            if (pexelsKey.isNotEmpty() && pexelsKey != "YOUR_PEXELS_API_KEY") {
                val searchQueries = listOf(visualKeywords, cleanQuery)
                for (searchQ in searchQueries) {
                    val encodedQ = java.net.URLEncoder.encode(searchQ, "UTF-8")
                    val url = if (type == "video") "https://api.pexels.com/videos/search?query=$encodedQ&per_page=3&orientation=portrait" else "https://api.pexels.com/v1/search?query=$encodedQ&per_page=3&orientation=portrait"
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", pexelsKey)
                        .build()
                    val response = ApiUsageTracker.track(AppServices.appContext, "Pexels") { client.newCall(request).execute() }
                    if (response.isSuccessful) {
                        val responseJson = JSONObject(response.body?.string() ?: "")
                        if (type == "video") {
                            val videos = responseJson.optJSONArray("videos")
                            if (videos != null && videos.length() > 0) {
                                val videoFiles = videos.getJSONObject(0).optJSONArray("video_files")
                                if (videoFiles != null && videoFiles.length() > 0) {
                                    // Choose HD quality file if possible
                                    var chosenLink = videoFiles.getJSONObject(0).optString("link")
                                    for (vIdx in 0 until videoFiles.length()) {
                                        val fObj = videoFiles.getJSONObject(vIdx)
                                        val quality = fObj.optString("quality", "")
                                        if (quality == "hd" || quality == "sd") {
                                            chosenLink = fObj.optString("link")
                                            break
                                        }
                                    }
                                    if (chosenLink.isNotBlank()) return@safeApiCall chosenLink
                                }
                            }
                        } else {
                            val photos = responseJson.optJSONArray("photos")
                            if (photos != null && photos.length() > 0) {
                                val photoSrc = photos.getJSONObject(0).getJSONObject("src")
                                val photoUrl = photoSrc.optString("large", photoSrc.optString("portrait"))
                                if (photoUrl.isNotBlank()) return@safeApiCall photoUrl
                            }
                        }
                    }
                }
            }
    
            // 2. Try Pixabay API
            if (pixabayKey.isNotEmpty() && pixabayKey != "YOUR_PIXABAY_API_KEY") {
                val searchQueries = listOf(visualKeywords, cleanQuery)
                for (searchQ in searchQueries) {
                    val encodedQ = java.net.URLEncoder.encode(searchQ, "UTF-8")
                    val url = if (type == "video") "https://pixabay.com/api/videos/?key=$pixabayKey&q=$encodedQ&video_type=all" else "https://pixabay.com/api/?key=$pixabayKey&q=$encodedQ&image_type=photo"
                    val request = Request.Builder().url(url).build()
                    val response = ApiUsageTracker.track(AppServices.appContext, "Pixabay") { client.newCall(request).execute() }
                    if (response.isSuccessful) {
                        val responseJson = JSONObject(response.body?.string() ?: "")
                        val hits = responseJson.optJSONArray("hits")
                        if (hits != null && hits.length() > 0) {
                            if (type == "video") {
                                val videosObj = hits.getJSONObject(0).optJSONObject("videos")
                                val videoUrl = videosObj?.optJSONObject("medium")?.optString("url")
                                    ?: videosObj?.optJSONObject("small")?.optString("url")
                                if (!videoUrl.isNullOrBlank()) return@safeApiCall videoUrl
                            } else {
                                val imgUrl = hits.getJSONObject(0).optString("largeImageURL")
                                if (imgUrl.isNotBlank()) return@safeApiCall imgUrl
                            }
                        }
                    }
                }
            }

            // 3. Coverr (مفتاح مجاني اختياري) ثم مصادر حرة بلا مفاتيح (Wikimedia/NASA) قبل المكتبة المحلية
            try {
                val ck = KeyVault.coverr
                if (ck.isNotEmpty() && type == "video") {
                    val free = FreeStockSources.searchCoverr(visualKeywords, ck)
                    val u = free.firstOrNull()?.videoUrl ?: ""
                    if (u.startsWith("http")) return@safeApiCall u
                }
            } catch (_: Exception) { }
            try {
                val free = FreeStockSources.searchFree(cleanQuery)
                val pick = free.firstOrNull { it.videoUrl.startsWith("http") || it.thumbnailUrl.startsWith("http") }
                if (pick != null) {
                    val u = if (type == "video") pick.videoUrl.ifBlank { pick.thumbnailUrl } else pick.thumbnailUrl.ifBlank { pick.videoUrl }
                    if (u.isNotBlank() && u.startsWith("http")) return@safeApiCall u
                }
            } catch (_: Exception) { }

            // 4. High-Quality Curated B-Roll Library Fallback (Instant & Free)
            val matchedBRoll = BRollEngine.matchBRoll(cleanQuery)
            val matchedUrl = if (type == "video") matchedBRoll.videoUrl else matchedBRoll.thumbnailUrl
            if (matchedUrl.isNotBlank()) {
                return@safeApiCall matchedUrl
            }

            // 4. Check HuggingFace for AI generated image fallback if photo requested
            if (type == "image") {
                val hfImage = RealHuggingFaceService.generateImage(cleanQuery)
                if (hfImage != null) return@safeApiCall hfImage
            }

            ""
        }
    }

    suspend fun chatWithAssistant(
        messages: List<Pair<Boolean, String>>,
        customSystemInstruction: String? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = KeyVault.gemini
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY") {
            return@withContext "عذراً، مفتاح Gemini غير متوفر. يرجى إضافته من الإعدادات."
        }
        
        return@withContext NetworkUtils.safeApiCallWithRetry(
            context = AppServices.appContext,
            maxRetries = 2,
            initialDelayMs = 1000L,
            fallback = { "حدث خطأ أثناء الاتصال بالذكاء الاصطناعي." }
        ) {
            val systemPrompt = customSystemInstruction ?: TasteManager.getTasteContext(AppServices.appContext)
            val contentsArray = org.json.JSONArray()
            for (msg in messages) {
                contentsArray.put(org.json.JSONObject().apply {
                    put("role", if (msg.first) "user" else "model")
                    put("parts", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply { put("text", msg.second) })
                    })
                })
            }
            
            val jsonBody = org.json.JSONObject().apply {
                put("contents", contentsArray)
                put("systemInstruction", org.json.JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply { put("text", systemPrompt) })
                    })
                })
                put("generationConfig", org.json.JSONObject().apply {
                    put("temperature", 0.7)
                })
            }
            
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                .post(requestBody)
                .build()
                
            val response = ApiUsageTracker.track(AppServices.appContext, "Gemini") { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val responseData = response.body?.string() ?: ""
                val responseJson = org.json.JSONObject(responseData)
                val text = extractGeminiText(responseJson)
                if (!text.isNullOrBlank()) {
                    return@safeApiCallWithRetry text
                }
            }
            "حدث خطأ أثناء معالجة الرد."
        }
    }
}

object RealGroqService {
    private val client = SharedHttpClient.instance

    suspend fun chatOrGenerate(prompt: String): String? = withContext(Dispatchers.IO) {
        val apiKey = KeyVault.groq
        if (apiKey.isBlank()) return@withContext null

        try {
            val isXai = apiKey.startsWith("xai-", ignoreCase = true)
            val apiUrl = if (isXai) "https://api.x.ai/v1/chat/completions" else "https://api.groq.com/openai/v1/chat/completions"
            val modelName = if (isXai) "grok-2-latest" else "llama-3.3-70b-versatile"

            val jsonBody = JSONObject().apply {
                put("model", modelName)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "أنت مساعد ذكي فائق السرعة متخصص في صناعة المحتوى الإسلامي والدعوي الهادف.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.7)
            }

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = ApiUsageTracker.track(AppServices.appContext, "Groq") { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val resObj = JSONObject(response.body?.string() ?: "")
                val choices = resObj.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    return@withContext message?.optString("content")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}

object RealOpenAIService {
    private val client = SharedHttpClient.instance

    suspend fun chatOrGenerate(prompt: String): String? = withContext(Dispatchers.IO) {
        var apiKey = KeyVault.openai
        if (apiKey.isBlank()) return@withContext null

        try {
            val jsonBody = JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "أنت مساعد ذكي متخصص في صناعة المحتوى الإسلامي والدعوي الهادف.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.7)
            }

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = ApiUsageTracker.track(AppServices.appContext, "OpenAI") { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val resObj = JSONObject(response.body?.string() ?: "")
                val choices = resObj.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    return@withContext message?.optString("content")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}

object RealHuggingFaceService {
    private val client = SharedHttpClient.instance

    suspend fun generateImage(prompt: String): String? = withContext(Dispatchers.IO) {
        val apiKey = KeyVault.huggingface
        if (apiKey.isBlank()) return@withContext null

        try {
            val jsonBody = JSONObject().apply {
                put("inputs", "$prompt, high quality islamic background art, cinematic lighting, photorealistic 8k")
            }

            val request = Request.Builder()
                .url("https://api-inference.huggingface.co/models/black-forest-labs/FLUX.1-schnell")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = ApiUsageTracker.track(AppServices.appContext, "HuggingFace") { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val imageBytes = response.body?.bytes()
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    val file = java.io.File(AppServices.appContext.cacheDir, "hf_${System.currentTimeMillis()}.jpg")
                    file.writeBytes(imageBytes)
                    return@withContext file.absolutePath
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}

object RealAzureSpeechService {
    private val client = SharedHttpClient.instance

    suspend fun synthesizeSpeech(
        text: String,
        voiceName: String = "ar-SA-HamedNeural",
        rate: String = "+0%",
        pitch: String = "+0%"
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = KeyVault.azureSpeechKey
        var region = KeyVault.azureSpeechRegion.ifBlank { "eastus" }

        if (apiKey.isBlank()) {
            SystemLogsManager.addLog("WARN", "مفتاح Azure Speech غير مضبوط في الإعدادات.", Color(0xFFE8C547))
            return@withContext null
        }

        try {
            SystemLogsManager.addLog("INFO", "بدء توليد الصوت عبر Azure Neural Speech ($voiceName)...", Color(0xFF38BDF8))
            val escapedText = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

            val ssml = """
                <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ar-SA'>
                    <voice name='$voiceName'>
                        <prosody rate='$rate' pitch='$pitch'>
                            $escapedText
                        </prosody>
                    </voice>
                </speak>
            """.trimIndent()

            val url = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
            val request = Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                .addHeader("Content-Type", "application/ssml+xml")
                .addHeader("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
                .addHeader("User-Agent", "QabasApp")
                .post(ssml.toRequestBody("application/ssml+xml; charset=utf-8".toMediaType()))
                .build()

            val response = ApiUsageTracker.track(AppServices.appContext, "Azure TTS") { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val audioDir = File(AppServices.appContext.filesDir, "audio_projects")
                    if (!audioDir.exists()) audioDir.mkdirs()
                    val outFile = File(audioDir, "azure_tts_${System.currentTimeMillis()}.mp3")
                    outFile.writeBytes(bytes)
                    SystemLogsManager.addLog("SUCCESS", "تم توليد الصوت بنجاح عبر Azure (${outFile.name}) ✅", Color(0xFF4CAF50))
                    return@withContext outFile.absolutePath
                }
            } else {
                val errBody = response.body?.string() ?: ""
                Log.e("AzureSpeech", "Azure TTS failed: ${response.code} $errBody")
                SystemLogsManager.addLog("ERROR", "فشل توليد Azure: كود ${response.code}", Color(0xFFEF4444))
            }
        } catch (e: Exception) {
            Log.e("AzureSpeech", "Error synthesizing with Azure", e)
            SystemLogsManager.addLog("ERROR", "خطأ في اتصال Azure: ${e.message}", Color(0xFFEF4444))
        }
        return@withContext null
    }
}

object RealElevenLabsService {
    private val client = SharedHttpClient.instance

    suspend fun synthesizeSpeech(
        text: String,
        voiceId: String = "21m00Tcm4TlvDq8ikWAM",
        stability: Double = 0.5,
        similarityBoost: Double = 0.75
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = KeyVault.elevenlabs

        if (apiKey.isBlank()) {
            SystemLogsManager.addLog("WARN", "مفتاح ElevenLabs غير مضبوط في الإعدادات.", Color(0xFFE8C547))
            return@withContext null
        }

        try {
            SystemLogsManager.addLog("INFO", "بدء توليد الصوت عبر ElevenLabs Studio ($voiceId)...", Color(0xFF38BDF8))
            val jsonBody = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", JSONObject().apply {
                    put("stability", stability)
                    put("similarity_boost", similarityBoost)
                    put("style", 0.35)
                    put("use_speaker_boost", true)
                })
            }

            val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"
            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "audio/mpeg")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = ApiUsageTracker.track(AppServices.appContext, "ElevenLabs") { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val audioDir = File(AppServices.appContext.filesDir, "audio_projects")
                    if (!audioDir.exists()) audioDir.mkdirs()
                    val outFile = File(audioDir, "elevenlabs_${System.currentTimeMillis()}.mp3")
                    outFile.writeBytes(bytes)
                    SystemLogsManager.addLog("SUCCESS", "تم توليد الصوت بنجاح عبر ElevenLabs (${outFile.name}) ✅", Color(0xFF4CAF50))
                    return@withContext outFile.absolutePath
                }
            } else {
                val errBody = response.body?.string() ?: ""
                Log.e("ElevenLabs", "ElevenLabs TTS failed: ${response.code} $errBody")
                SystemLogsManager.addLog("ERROR", "فشل توليد ElevenLabs: كود ${response.code}", Color(0xFFEF4444))
            }
        } catch (e: Exception) {
            Log.e("ElevenLabs", "Error synthesizing with ElevenLabs", e)
            SystemLogsManager.addLog("ERROR", "خطأ في اتصال ElevenLabs: ${e.message}", Color(0xFFEF4444))
        }
        return@withContext null
    }
}

object RealFFmpegService {
    suspend fun mergeVideo(segments: List<String>, outputPath: String, templateCommand: String): String = withContext(Dispatchers.IO) {
        val validSegments = segments.filter { it.isNotBlank() && File(it).exists() && File(it).length() > 0 }
        if (validSegments.isEmpty()) {
            SystemLogsManager.addLog("ERROR", "لا توجد مقاطع صالحة للدمج — فشل إخراج الفيديو", Color(0xFFEF4444))
            return@withContext ""
        }
        try {
            val context = AppServices.appContext
            val ok = if (validSegments.size == 1) {
                VideoProcessor.concatenateVideos(context, validSegments, outputPath)
            } else {
                VideoProcessor.concatenateVideosWithTransitions(context, validSegments, "dissolve", outputPath)
            }
            if (ok && VideoProcessor.isValidVideoFile(outputPath, minSizeBytes = 8_000L)) {
                SystemLogsManager.addLog("SUCCESS", "تم دمج ${validSegments.size} مقاطع في ملف MP4 صالح ✅", Color(0xFF4CAF50))
                return@withContext outputPath
            }
        } catch (e: Exception) {
            Log.e("RealFFmpegService", "mergeVideo failed: ${e.message}", e)
        }
        SystemLogsManager.addLog("ERROR", "فشل دمج المقاطع في ملف فيديو صالح 🔴", Color(0xFFEF4444))
        return@withContext ""
    }
}

/**
 * Android built-in TextToSpeech — الدائم والمجاني تماماً (لا يحتاج مفتاح API).
 * يُستخدم كـ fallback عندما تغيب مفاتيح Azure/ElevenLabs أو تفشل الشبكة.
 * يحوّل النص إلى ملف صوتي WAV عبر محرك النظام المحلي.
 */
object AndroidTTSService {
    private const val TAG = "AndroidTTSService"

    suspend fun synthesizeSpeech(text: String): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val context = AppServices.appContext
        val outFile = File(context.cacheDir, "android_tts_${System.currentTimeMillis()}.wav")
        val latch = CountDownLatch(1)
        val ready = arrayOf(false)

        var tts: android.speech.tts.TextToSpeech? = null
        tts = android.speech.tts.TextToSpeech(context) { status ->
            try {
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    ready[0] = false
                    latch.countDown()
                    return@TextToSpeech
                }
                val instance = tts ?: return@TextToSpeech
                val langResult = instance.setLanguage(Locale("ar"))
                if (langResult == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                    langResult == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    ready[0] = false
                    latch.countDown()
                    return@TextToSpeech
                }
                instance.setSpeechRate(1.0f)
                val result = instance.synthesizeToFile(text, null, outFile, "qabas_tts")
                ready[0] = result == android.speech.tts.TextToSpeech.SUCCESS
                latch.countDown()
            } catch (e: Exception) {
                Log.e(TAG, "TTS init/synthesize error: ${e.message}")
                ready[0] = false
                latch.countDown()
            }
        }
        val done = latch.await(15, TimeUnit.SECONDS)
        try { tts?.shutdown() } catch (_: Exception) {}

        if (done && ready[0] && outFile.exists() && outFile.length() > 500) {
            SystemLogsManager.addLog("SUCCESS", "تم توليد التعليق الصوتي عبر محرك النظام المجاني ✅", Color(0xFF4CAF50))
            return@withContext outFile.absolutePath
        }
        try { outFile.delete() } catch (_: Exception) {}
        SystemLogsManager.addLog("WARN", "تعذر توليد التعليق الصوتي حتى بمحرك النظام — سيتابع بدون صوت", Color(0xFFE8C547))
        null
    }
}

object QabasPrompts {
    fun hookGenerator(topic: String): String =
        """
        أنت المخرج الإبداعي لمنصة «قَبَس» (QABAS Studio) - الاستوديو الرائد في صناعة المحتوى الدعوي والإسلامي واسع الانتشار (Viral & Dignified Islamic Media).
        الموضوع: $topic
        
        المطلوب: اقترح 3 خطافات افتتاحية بصرية وصوتية قوية جداً (أول 3 ثوانٍ) لفيديو ريلز/شورتس تخطف الانتباه فوراً.
        
        معايير هوية قبس:
        1. الوقار والصدق والأصالة الشرعية التامة (ممنوع التهويل الكاذب أو المبتذل).
        2. زاوية طرح عميقة تلامس القلب والوجدان وتثير الفضول الإيماني بذكاء.
        3. الصياغة بأسلوب عربي فصيح، مشوّق ومباشر.
        
        أخرج النتيجة كـ 3 أسطر فقط، كل خطاف يبدأ بنقطة • بدون مقدمات أو حشو.
        """.trimIndent()

    fun titlesGenerator(topic: String): String =
        """
        أنت خبير التسمية والانتشار الرقمي في منصة «قَبَس» لصناعة المحتوى الإسلامي الهادف.
        الموضوع: $topic
        
        المطلوب: اقترح 5 عناوين رنانة ومحفزة للنقر والمشاهدة لفيديو ريلز أو حلقة بودكاست دعوية.
        
        معايير هوية قبس:
        - الموازنة الدقيقة بين الجاذبية العالية والوقار الإسلامي الفاخر.
        - استخدام كلمات مفتاحية دلالية (أثر، بركة، سكينة، سر، تحول، نور).
        - أخرج 5 عناوين مرقمة فقط مباشرة.
        """.trimIndent()

    fun hashtagsGenerator(topic: String): String =
        """
        أنت خبير خوارزميات النشر والتفاعل في استوديو «قَبَس».
        الموضوع: $topic
        
        المطلوب: اختر 10 وسوم (هاشتاغات) ذكية ومتخصصة تجمع بين الوسوم العامة واسعة الانتشار والوسوم الدعوية الدقيقة.
        تتضمن دائماً: #قبس #أثر_لا_ينقطع #صناعة_المحتوى_الهادف مع الوسوم الخاصة بالموضوع.
        أخرجها في سطر واحد مفصولة بمسافات فقط.
        """.trimIndent()

    fun quickScript(topic: String, durationSec: Int = 30): String =
        """
        أنت كبير كُتّاب السيناريو في منصة «قَبَس» (QABAS AI Studio).
        اكتب سيناريو ريلز إسلامي متكامل فائق التأثير مدته $durationSec ثانية حول موضوع: $topic.
        
        هيكل النص بأسلوب قبس:
        1. [الخطاف - 3 ثوان]: عبارة تفتح القلب وتثير الانتباه والتدبر.
        2. [صلب المحتوى الدعوي - 22 ثانية]: فكرة عميقة مدعومة بدليل شرعي (آية أو حديث مخرج) أو حكمة جليلة، مع لغة وجدانية سينمائية هادئة.
        3. [الخاتمة والأثر - 5 ثوان]: رسالة عملية ملهمة ودعوة للتأمل والمشاركة (Call to Action: شارك الأثر).
        
        الصياغة: عربية فصيحة، رصينة، بليغة، ومشبعة بالوقار.
        """.trimIndent()

    fun dawahAssistantSystemInstruction(): String =
        """
        أنت «مساعد قَبَس الذكي» (Qabas AI Dawah Co-Director) — الشريك الإبداعي والدعوي لصناع المحتوى الإسلامي والقرآني.
        
        مبادئك وهوية إجاباتك:
        1. الرصانة والأمانة العلمية: تحرّي صحة الأحاديث وعزو الآيات لسورها وتجنب الروايات الضعيفة أو المنكرة.
        2. الإخراج المعاصر: مساعدة المستخدم في صياغة سيناريوهات بصرية، خطافات خاطفة (Hooks)، وتوجيهات إضاءة ومؤثرات صوتية هادئة (بدون معازف).
        3. نبرة قبس: ناصحة، وقورة، ملهمة، واثقة، وداعمة لصانع المحتوى لترك أثر مبارك في الأمة.
        """.trimIndent()

    fun quickIdeas(category: String): String =
        """
        أنت المخطط الاستراتيجي لإنتاج المحتوى في منصة «قَبَس».
        اقترح 5 أفكار ريلز إسلامية مبتكرة وغير تقليدية في مجال: $category.
        
        لكل فكرة وضح:
        - العنوان المقترح
        - زاوية التناول البصرية
        - الرسالة الإيمانية المستفادة
        """.trimIndent()
}

object AppServices {
    val templates: List<Template> = listOf(
        Template(
            id = "tmpl_cinematic",
            name = "القصص الوثائقية السينمائية (Cinematic Teal & Gold)",
            colors = "ذهبي ملكي وأزرق داكن (Teal & Gold)",
            transitions = "ذوبان سينمائي ناعم (Dissolve)",
            textAnimation = "ظهور تدريجي مع تظليل ذهبي"
        ),
        Template(
            id = "tmpl_heritage",
            name = "القصص التراثية والتاريخية (Parchment & Sepia)",
            colors = "ورقي دافئ وخطوط أندلسية (Parchment Sepia)",
            transitions = "مسح ورقي متدرج (Paper Wipe)",
            textAnimation = "خط كوفي أصيل وتكبير ناعم"
        ),
        Template(
            id = "tmpl_quran",
            name = "التدبر القرآني والسكينة (Emerald Quran)",
            colors = "أخضر زمردي وذهبي ناصع (Emerald & Pure Gold)",
            transitions = "تلاشي ضوئي خفيف (Soft Fade)",
            textAnimation = "شفافية متدرجة مع ضبط التشكيل"
        ),
        Template(
            id = "tmpl_viral",
            name = "المقاطع القصيرة الحماسية (Viral Reels)",
            colors = "أسود ورمادي داكن مع إضاءة مشعة (High Contrast)",
            transitions = "انتقالات خاطفة وسريعة (Glitch/Zoom)",
            textAnimation = "نصوص قفز حيوية (Dynamic Pop-In Subtitles)"
        ),
        Template(
            id = "tmpl_podcast",
            name = "البرودكاست والمحاضرات (Royal Discourse)",
            colors = "رمادي داكن وفضي فاخر (Slate & Silver)",
            transitions = "انزلاق جانبي انسيابي (Slide Left)",
            textAnimation = "إظهار الجمل في مستطيل زجاجي"
        )
    )

    private var _appContext: android.content.Context? = null
    var appContext: android.content.Context
        get() = _appContext ?: throw IllegalStateException("AppServices must be initialized by calling AppServices.init(context) before use")
        set(value) {
            _appContext = value.applicationContext
        }

    fun init(context: android.content.Context) {
        _appContext = context.applicationContext
    }

    private var _giftManager: GiftManager? = null
    fun getGiftManager(context: android.content.Context): GiftManager {
        return _giftManager ?: synchronized(this) {
            _giftManager ?: GiftManager(context.applicationContext).also { _giftManager = it }
        }
    }

    private var _tierStateManager: TierStateManager? = null
    fun getTierStateManager(context: android.content.Context): TierStateManager {
        return _tierStateManager ?: synchronized(this) {
            _tierStateManager ?: TierStateManager(context.applicationContext).also { _tierStateManager = it }
        }
    }

    private var _analyticsService: AnalyticsService? = null
    fun getAnalyticsService(context: android.content.Context): AnalyticsService {
        return _analyticsService ?: synchronized(this) {
            _analyticsService ?: RealAnalyticsService(context.applicationContext).also { _analyticsService = it }
        }
    }

    private var _paymentService: PaymentService? = null
    fun getPaymentService(context: android.content.Context): PaymentService {
        return _paymentService ?: synchronized(this) {
            _paymentService ?: RealPaymentService(context.applicationContext).also { _paymentService = it }
        }
    }
    
    private var _accountService: AccountService? = null
    fun getAccountService(context: android.content.Context): AccountService {
        return _accountService ?: synchronized(this) {
            _accountService ?: AccountService(context.applicationContext).also { _accountService = it }
        }
    }

    

    suspend fun generateScript(idea: String, styleDescription: String = "", contentType: String = "", contentTone: String = ""): List<Scene> {
        var finalStyleDescription = styleDescription
        if (finalStyleDescription.isBlank()) {
            val bestStyle = StyleBrain.chooseBestStyleForIdea(idea, 60, contentType.ifBlank { "غير محدد" }, contentTone.ifBlank { "غير محدد" })
            if (bestStyle != null) {
                val traits = (bestStyle.visualTraits + bestStyle.motionTraits + bestStyle.textTraits).joinToString("، ")
                finalStyleDescription = "استخدم ميزات هذا الأسلوب المفضل من العقل: $traits. ${bestStyle.analysis}"
            } else {
                finalStyleDescription = "العقل لم يتدرب بعد. استخدم ألواناً داكنة بهوية AI بنفسجية #8B5CF6 وإكسنت سيان #22D3EE (DeepSlate #0B0F19)، ونصوصاً عربية عريضة ديناميكية (كابشنز متفاعلة)."
            }
        }

        // 1) Pollinations — نموذج نصي بلا مفتاح (يُجرَّب أولاً)
        val pollScript = try {
            PollinationsTextService.generateScript(idea, finalStyleDescription, contentType, contentTone)
        } catch (e: Exception) { null }
        if (pollScript != null && pollScript.size >= 2) {
            SystemLogsManager.addLog("INFO", "تم توليد السكربت بـ Pollinations (بلا مفتاح) ✅", Color(0xFF10B981))
            return pollScript
        }

        // 2) llama.cpp محلي (مجاني، offline) للسكربت
        val localScript = try {
            LlamaCppService.generateScriptLocal(idea, finalStyleDescription, contentType, contentTone)
        } catch (e: Exception) {
            Log.w("AppServices", "Local LLM unavailable: ${e.message}")
            null
        }
        if (localScript != null && localScript.isNotEmpty()) {
            SystemLogsManager.addLog("INFO", "تم توليد السكربت بـ llama.cpp محلياً ✅", Color(0xFF10B981))
            return localScript
        }

        // 3) السحابي: Gemini
        return RealGeminiService.generateScript(idea, finalStyleDescription, contentType, contentTone)
    }

    
    suspend fun analyzeIdea(idea: String): IdeaAnalysis {
        return RealGeminiService.analyzeIdea(idea)
    }
    suspend fun chatWithAssistant(
        messages: List<Pair<Boolean, String>>,
        customSystemInstruction: String? = null
    ): String {
        // 1) Pollinations — نموذج نصي بلا مفتاح (يُجرَّب أولاً)
        if (messages.size == 1) {
            val pollResponse = try { PollinationsTextService.chat(messages[0].second) } catch (e: Exception) { null }
            if (!pollResponse.isNullOrBlank()) {
                SystemLogsManager.addLog("INFO", "رد محادثة بـ Pollinations (بلا مفتاح) ✅", Color(0xFF10B981))
                return pollResponse
            }
        }

        // 2) llama.cpp محلي للمحادثات السريعة
        if (messages.size == 1) {
            val localResponse = try {
                LlamaCppService.generate(messages[0].second, maxTokens = 256)
            } catch (e: Exception) {
                null
            }
            if (!localResponse.isNullOrBlank()) {
                SystemLogsManager.addLog("INFO", "رد محلي بـ llama.cpp ✅", Color(0xFF10B981))
                return localResponse!!
            }
        }

        // 2) السحابي: OpenAI → Groq → Gemini
        if (messages.size == 1 && customSystemInstruction.isNullOrBlank()) {
            val openaiResponse = RealOpenAIService.chatOrGenerate(messages[0].second)
            if (!openaiResponse.isNullOrBlank()) return openaiResponse
            val fastResponse = RealGroqService.chatOrGenerate(messages[0].second)
            if (!fastResponse.isNullOrBlank()) return fastResponse
        }
        return RealGeminiService.chatWithAssistant(messages, customSystemInstruction)
    }

    private suspend fun executeShortTaskWithFallback(prompt: String): String {
        // 1) Pollinations — نموذج نصي بلا مفتاح (يُجرَّب أولاً)
        val pollResponse = try { PollinationsTextService.chat(prompt) } catch (e: Exception) { null }
        if (!pollResponse.isNullOrBlank()) {
            SystemLogsManager.addLog("INFO", "مهمة قصيرة بـ Pollinations (بلا مفتاح) ✅", Color(0xFF10B981))
            return pollResponse
        }

        // 2) llama.cpp محلي للمهام القصيرة
        val localResponse = try {
            LlamaCppService.generate(prompt, maxTokens = 256, temperature = 0.5f)
        } catch (e: Exception) { null }
        if (!localResponse.isNullOrBlank()) {
            SystemLogsManager.addLog("INFO", "مهمة قصيرة محلياً بـ llama.cpp ✅", Color(0xFF10B981))
            return localResponse!!
        }

        // 2) السحابي: OpenAI → Groq → Gemini
        val openaiResponse = RealOpenAIService.chatOrGenerate(prompt)
        if (!openaiResponse.isNullOrBlank()) return openaiResponse
        val fastResponse = RealGroqService.chatOrGenerate(prompt)
        if (!fastResponse.isNullOrBlank()) return fastResponse
        return RealGeminiService.chatWithAssistant(listOf(Pair(true, prompt)), null)
    }

    suspend fun generateViralHooks(topic: String): String {
        return executeShortTaskWithFallback(QabasPrompts.hookGenerator(topic))
    }

    suspend fun generateTitles(topic: String): String {
        return executeShortTaskWithFallback(QabasPrompts.titlesGenerator(topic))
    }

    suspend fun generateHashtags(topic: String): String {
        return executeShortTaskWithFallback(QabasPrompts.hashtagsGenerator(topic))
    }

    suspend fun generateQuickScript(topic: String, durationSec: Int = 30): String {
        return executeShortTaskWithFallback(QabasPrompts.quickScript(topic, durationSec))
    }

    suspend fun generateQuickIdeas(category: String): String {
        return executeShortTaskWithFallback(QabasPrompts.quickIdeas(category))
    }

    suspend fun analyzeVideoStyle(url: String): VideoStyleAnalysis {
        return RealGeminiService.analyzeVideoStyle(url)
    }

    suspend fun generateVideo(
        sceneDescription: String,
        durationInSeconds: Int = 5,
        tempo: String = Translator.tr("متوسط"),
        colors: String = Translator.tr("افتراضي"),
        transitions: String = Translator.tr("عادي"),
        textAnim: String = Translator.tr("عادي"),
        visualEffect: String = Translator.tr("لا يوجد")
    ): String = withContext(Dispatchers.IO) {
        try {
            val outVideoFile = File(appContext.cacheDir, "scene_clip_${System.currentTimeMillis()}_${(1000..9999).random()}.mp4")
            
            // 1. Fetch relevant media/image from Pexels / Pixabay
            var downloadedImage: File? = null
            val imageUrl = RealMediaLibraryService.fetchMedia(sceneDescription, "image")
            if (imageUrl.isNotBlank()) {
                downloadedImage = downloadImageToCache(imageUrl)
                if (downloadedImage != null && downloadedImage.exists() && downloadedImage.length() > 0) {
                    SystemLogsManager.addLog("INFO", "تم جلب صورة مناسبة للمشهد من مكتبة الوسائط بنجاح ✅", Color(0xFF4CAF50))
                } else {
                    SystemLogsManager.addLog("WARN", "تعذر تنزيل الصورة من الرابط، جاري الانتقال للمشهد الافتراضي الوقور", Color(0xFFE8C547))
                }
            } else {
                SystemLogsManager.addLog("INFO", "لم يُعثر على صورة في المكتبة، سيتم إنشاء مشهد بتصميم وقور 🎨", Color(0xFFE8C547))
            }

            // 2. Generate video clip from image
            val imageToProcess = downloadedImage ?: createSolidFallbackImage(sceneDescription, colors, visualEffect)
            if (imageToProcess != null && imageToProcess.exists()) {
                val genSuccess = VideoProcessor.generateVideoFromImage(
                    context = appContext,
                    imagePath = imageToProcess.absolutePath,
                    durationInSeconds = durationInSeconds.coerceAtLeast(3),
                    outputPath = outVideoFile.absolutePath
                )
                if (genSuccess && outVideoFile.exists() && outVideoFile.length() > 0) {
                    SystemLogsManager.addLog("SUCCESS", "تم تحويل المشهد إلى كليب محلي بنجاح (${outVideoFile.name}) ✅", Color(0xFF4CAF50))
                    return@withContext outVideoFile.absolutePath
                } else {
                    SystemLogsManager.addLog("WARN", "فشل تحويل الصورة بالمعالج، جاري المحاولة بالقالب الاحتياطي", Color(0xFFEF4444))
                }
            }

            // 3. Fallback: create solid styled canvas and convert to video
            val fallbackImg = createSolidFallbackImage(sceneDescription, colors, visualEffect)
            if (fallbackImg != null && fallbackImg.exists()) {
                val fallbackSuccess = VideoProcessor.generateVideoFromImage(
                    context = appContext,
                    imagePath = fallbackImg.absolutePath,
                    durationInSeconds = durationInSeconds.coerceAtLeast(3),
                    outputPath = outVideoFile.absolutePath
                )
                if (fallbackSuccess && outVideoFile.exists() && outVideoFile.length() > 0) {
                    SystemLogsManager.addLog("SUCCESS", "تم توليد كليب المشهد بالخلفية الوقورة بنجاح ✅", Color(0xFF4CAF50))
                    return@withContext outVideoFile.absolutePath
                }
            }

            SystemLogsManager.addLog("ERROR", "تعذر توليد كليب المشهد المحلي 🔴", Color(0xFFEF4444))
            ""
        } catch (e: Exception) {
            Log.e("AppServices", "Error generating scene video clip", e)
            SystemLogsManager.addLog("ERROR", "فشل توليد كليب المشهد: ${e.localizedMessage ?: e.message} 🔴", Color(0xFFEF4444))
            ""
        }
    }

    private suspend fun downloadImageToCache(urlStr: String): File? = withContext(Dispatchers.IO) {
        if (urlStr.isBlank()) return@withContext null
        try {
            val cacheFile = File(appContext.cacheDir, "scene_img_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg")
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(urlStr).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    return@withContext cacheFile
                }
            }
        } catch (e: Exception) {
            Log.e("AppServices", "Error downloading image to cache: $urlStr", e)
        }
        null
    }

    private fun createSolidFallbackImage(text: String, colors: String = "", visualEffect: String = ""): File? {
        val combined = "$colors $visualEffect".lowercase()
        val primaryColorHex = when {
            combined.contains("أخضر") || combined.contains("emerald") || combined.contains("green") -> "#10B981"
            combined.contains("نيون") || combined.contains("neon") || combined.contains("أصفر") || combined.contains("yellow") -> "#FFE500"
            combined.contains("برتقالي") || combined.contains("غروب") || combined.contains("orange") -> "#F97316"
            combined.contains("أزرق") || combined.contains("blue") || combined.contains("سماوي") -> "#38BDF8"
            combined.contains("أبيض") || combined.contains("white") -> "#F8FAFC"
            else -> "#8B5CF6"
        }
        val bgColorHex = when {
            combined.contains("أخضر") || combined.contains("emerald") -> "#0F291E"
            combined.contains("أزرق") || combined.contains("blue") -> "#0A192F"
            combined.contains("برتقالي") || combined.contains("sunset") -> "#1E1005"
            else -> "#0B0F19"
        }
        val cardColorHex = when {
            combined.contains("أخضر") -> "#16382B"
            combined.contains("أزرق") -> "#13233E"
            combined.contains("برتقالي") -> "#2D170A"
            else -> "#151B2B"
        }
        return try {
            val file = File(appContext.cacheDir, "solid_fallback_${System.currentTimeMillis()}_${(1000..9999).random()}.png")
            val ok = ProceduralBackdropEngine.render(
                filePath = file.absolutePath,
                width = 1080,
                height = 1920,
                topHex = cardColorHex,
                bottomHex = bgColorHex,
                accentHex = primaryColorHex,
                caption = text.trim(),
                brandLine = "قَبَس | QABAS AI STUDIO",
                footerText = "محتوى دعوي هادف موثوق ✦"
            )
            if (ok && file.exists() && file.length() > 0L) file else null
        } catch (e: Exception) {
            Log.e("AppServices", "Error creating solid fallback image", e)
            null
        }
    }

    suspend fun getTrendingIdeas(): List<TrendingIdea> = withContext(Dispatchers.IO) {
        val apiKey = KeyVault.gemini

        val defaultIdeas = listOf(
            TrendingIdea(title = "قصص الأنبياء - العبرة الخالدة", description = "فيديوهات قصيرة سينمائية تسرد مواقف الصبر واليقين", tags = listOf("قصص", "إيمان", "عبرة"), source = "FALLBACK"),
            TrendingIdea(title = "تدبر آية - سكينة القلب", description = "وقفات قرآنية وجدانية مع تصوير طبيعي وألوان ذهبية", tags = listOf("قرآن", "تدبر", "سكينة"), source = "FALLBACK"),
            TrendingIdea(title = "أسرار الفجر والبركة", description = "خطاف قوي عن ثمرات الاستيقاظ المبكر وصلاة الفجر", tags = listOf("الفجر", "بركة", "عادات"), source = "FALLBACK"),
            TrendingIdea(title = "أدعية نبوية مأثورة", description = "سلسلة أدعية من السنة الصحيحة بأداء صوتي هادئ وخاشع", tags = listOf("دعاء", "سنة", "ذكر"), source = "FALLBACK")
        )

        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY") {
            return@withContext defaultIdeas
        }

        return@withContext NetworkUtils.safeApiCallWithRetry(
            context = AppServices.appContext,
            maxRetries = 2,
            fallback = { defaultIdeas }
        ) {
            val prompt = """
                أنت خبير محتوى وتريندات إسلامية هادفة على منصات Reels و TikTok.
                اقترح 4 أفكار تريند حصرية تناسب الساعة وتجذب المشاهدين بالوقار والأثر الإيماني.
                أخرج النتيجة كمصفوفة JSON فقط بدون markdown:
                [
                    {
                        "title": "عنوان الفكرة الجذاب",
                        "description": "وصف المشهد والإخراج في سطرين",
                        "tags": ["وسم1", "وسم2"]
                    }
                ]
            """.trimIndent()

            val response = executeShortTaskWithFallback(prompt)
            val cleanJson = if (response.contains("[")) response.substring(response.indexOf("["), response.lastIndexOf("]") + 1) else response
            val arr = JSONArray(cleanJson)
            val result = mutableListOf<TrendingIdea>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val tagsList = mutableListOf<String>()
                val tagsArr = obj.optJSONArray("tags")
                if (tagsArr != null) {
                    for (t in 0 until tagsArr.length()) tagsList.add(tagsArr.getString(t))
                }
                result.add(
                    TrendingIdea(
                        title = obj.optString("title", "فكرة إسلامية ملهمة"),
                        description = obj.optString("description", "محتوى دعوي احترافي"),
                        viralityScore = obj.optInt("viralityScore", 0).coerceIn(0, 100),
                        tags = tagsList.takeIf { it.isNotEmpty() } ?: listOf("إسلامي", "قبس"),
                        source = "MODEL"
                    )
                )
            }
            if (result.isNotEmpty()) result else defaultIdeas
        }
    }

    suspend fun generateDeveloperPrompts(request: AppRequestService.AppRequest): String {
        return RealGeminiService.generateDeveloperPrompts(request)
    }

    /**
     * Real AI Voiceover synthesis — أولوية: Kokoro محلي (مجاني، بلا مفتاح، بلا نت)
     * ثم Azure Neural Speech → ElevenLabs → Android TTS المدمج.
     * لا محتوى مزيف أبداً — فشل صريح = null.
     */
    suspend fun generateVoiceover(
        text: String,
        tone: String = "وثائقي",
        voiceId: String = "ar-SA-HamedNeural",
        engine: String = "AZURE",
        customVoiceName: String? = null,
        rate: String = "+0%",
        pitch: String = "+0%"
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null

        // 1) Kokoro TTS المحلي (مجاني، 82M، CPU، عربي)
        val kokoroPath = try {
            KokoroTtsService.synthesizeSpeech(AppServices.appContext, text, "ar")
        } catch (e: Exception) {
            Log.w("AppServices", "Kokoro TTS unavailable: ${e.message}")
            null
        }
        if (!kokoroPath.isNullOrBlank() && File(kokoroPath).exists() && File(kokoroPath).length() > 500) {
            SystemLogsManager.addLog("INFO", "تم توليد الصوت بـ Kokoro محلياً ✅", Color(0xFF10B981))
            return@withContext kokoroPath
        }

        // 2) Azure / ElevenLabs السحابية
        var generatedPath: String? = null
        if (engine.equals("ELEVENLABS", ignoreCase = true)) {
            val elevenVoiceId = if (voiceId.contains("Neural") || voiceId.isBlank()) "21m00Tcm4TlvDq8ikWAM" else voiceId
            generatedPath = RealElevenLabsService.synthesizeSpeech(text, elevenVoiceId)
        } else {
            val azureVoice = if (!voiceId.contains("Neural") && voiceId.length < 15) "ar-SA-HamedNeural" else voiceId
            generatedPath = RealAzureSpeechService.synthesizeSpeech(text, azureVoice, rate, pitch)
        }

        if (!generatedPath.isNullOrBlank() && File(generatedPath).exists() && File(generatedPath).length() > 500) {
            return@withContext generatedPath
        }

        // 3) Android TTS المدمج (fallback أخير، بلا مفتاح، بلا نت)
        try {
            val systemTts = AndroidTTSService.synthesizeSpeech(text)
            if (!systemTts.isNullOrBlank() && File(systemTts).exists() && File(systemTts).length() > 500) {
                return@withContext systemTts
            }
        } catch (e: Exception) {
            Log.w("AppServices", "Android TTS fallback failed: ${e.message}")
        }

        SystemLogsManager.addLog(
            "WARN",
            "تعذر توليد التعليق الصوتي (لا مفاتيح سحابية، ولا Kokoro، ولا محرك نظام) — المشهد بدون صوت",
            Color(0xFFE8C547)
        )
        Log.w("AppServices", "generateVoiceover failed for text length=${text.length} — returning null (no fake narration)")
        return@withContext null
    }

    suspend fun generateAiImage(prompt: String): String? {
        val hfImage = RealHuggingFaceService.generateImage(prompt)
        if (hfImage != null) return hfImage
        return fetchMedia(prompt, "image")
    }

    suspend fun fetchMedia(query: String, type: String = "video"): String {
        return RealMediaLibraryService.fetchMedia(query, type)
    }
}
