package com.qabas.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * PollinationsTextService — نموذج نصي بلا مفتاح (بدون API).
 * يستخدم endpoint https://text.pollinations.ai/public المجاني المفتوح.
 * لا يحتاج مفتاح، يعمل عبر الإنترنت فقط.
 * يُجرَّب أولًا في سلسلة التوليد (قبل OpenAI/Groq/Gemini).
 */
object PollinationsTextService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** محادثة نصية واحدة — ترجع النص أو null عند الفشل */
    suspend fun chat(prompt: String): String? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(prompt, "UTF-8")
        val url = "https://text.pollinations.ai/public/$encoded"
        try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Qabas/1.0")
                .build()
            val resp = client.newCall(request).execute()
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string()?.trim()?.take(2000)
        } catch (_: Exception) { null }
    }

    /** توليد سكربت مشاهد — ترجع List<Scene> أو null عند الفشل */
    suspend fun generateScript(
        idea: String,
        style: String,
        contentType: String,
        tone: String
    ): List<Scene>? {
        val prompt = buildString {
            append("أنت مخرج سينمائي عربي متخصص في المحتوى الإسلامي القصير. ")
            append("أخرج JSON فقط بمصفوفة \"scenes\" من 3-6 مشاهد. ")
            append("كل مشهد: title (عربي), description (إنجليزي دقيق للبحث عن B-Roll), durationInSeconds (3-6), transitionType, visualEffect, tempo (سريع/متوسط/بطيء). ")
            append("لا تخرج نصاً خارج JSON. ")
            append("الفكرة: $idea | الأسلوب: ${style.ifBlank { "داكن بنفسجي #8B5CF6 مع إكسنت سيان #22D3EE" }} | النوع: ${contentType.ifBlank { "وثائقي"} } | النبرة: ${tone.ifBlank { "متزن مؤثر"} }")
        }
        val text = chat(prompt) ?: return null
        return parseScenesJson(text)
    }

    private fun parseScenesJson(text: String): List<Scene> {
        if (!text.contains("[")) return emptyList()
        return try {
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']') + 1
            val json = text.substring(start, end)
            val arr = org.json.JSONArray(json)
            val scenes = mutableListOf<Scene>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                scenes.add(
                    Scene(
                        title = o.optString("title", "مشهد ${i + 1}"),
                        description = o.optString("description", ""),
                        durationInSeconds = o.optInt("durationInSeconds", 5),
                        transitionType = o.optString("transitionType", "Fade"),
                        visualEffect = o.optString("visualEffect", "cinematic"),
                        tempo = o.optString("tempo", "متوسط")
                    )
                )
            }
            scenes
        } catch (_: Exception) { emptyList() }
    }
}
