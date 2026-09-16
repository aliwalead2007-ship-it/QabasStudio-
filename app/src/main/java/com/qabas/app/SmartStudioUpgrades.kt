package com.qabas.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * رادار الترند: 5 أفكار يومية متوقعة للمحتوى الإسلامي القصير.
 * يحاول الذكاء أولاً (Groq → OpenAI)، وعند الفشل يرجع قائمة دائمة الصلاحية.
 */
object TrendRadar {
    val EvergreenFallback = listOf(
        "آية الكرسي: لماذا هي أعظم آية؟",
        "قصة أصحاب الكهف بلسان عصرنا",
        "دعاء تفريج الهم من السنة",
        "ماذا يحدث في أول ليلة في القبر؟",
        "فضل الصلاة على النبي ﷺ بالأدلة"
    )

    suspend fun getDailyTopics(): List<String> = withContext(Dispatchers.IO) {
        val p = "اقترح 5 أفكار فيديوهات إسلامية قصيرة رائجة الآن (ريلز 30 ثانية). أخرج JSON فقط: [\"فكرة1\",...]. بلا شرح."
        val raw = try { RealGroqService.chatOrGenerate(p) } catch (_: Exception) { null }
            ?: try { RealOpenAIService.chatOrGenerate(p) } catch (_: Exception) { null }
        try {
            if (raw != null && raw.contains("[")) {
                val arr = JSONArray(raw.substring(raw.indexOf("["), raw.lastIndexOf("]") + 1))
                val out = (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.length > 5 }.take(5)
                if (out.size >= 3) return@withContext out
            }
        } catch (_: Exception) {}
        EvergreenFallback
    }
}

/** أنماط الكابشن الفيروسية 2026: تُلحق بالوصف الأسلوبي تلقائياً. */
object CaptionStyles {
    const val VIRAL_DIRECTIVE =
        " Word-by-word kinetic captions, 3-5 words per screen, gold bold Cairo font with black stroke, " +
        "punch-in animation on keywords, emoji-free, always on screen."
}

/** إزالة كلمات الحشو من النص — على طريقة Descript. */
object SmartCut {
    private val Fillers = listOf("يعني", "أمم", "امم", "آه", "اه ", "همم", "زي ما قلت", "بصراحة يعني", "تمام؟")
    fun clean(text: String): String {
        var t = " $text "
        Fillers.forEach { t = t.replace(it, " ") }
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
