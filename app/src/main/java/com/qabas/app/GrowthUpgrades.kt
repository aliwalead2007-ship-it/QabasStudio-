package com.qabas.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * حزم النمو 2026: ستريك يومي + محرك خطافات + حزمة نشر.
 * مبنية على: RevenueCat 2026 (freemium + سنوي + أسعار منخفضة) ومعمارية Duolingo للستريك،
 * وخوارزمية Shorts 2026 (عتبة 65%، الذهبي 30-45ث، القرار 1.3ث، الحلقة loop، 70% صامت، دعوة تعليق، 3 هاشتاقات).
 */
object StreakManager {
    private const val PREFS = "qabas_prefs"
    private const val KEY_COUNT = "streak_count"
    private const val KEY_LAST = "streak_last_date"
    private const val KEY_FREEZES = "streak_freezes"

    private fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun getStreak(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getString(KEY_LAST, "") ?: ""
        val count = p.getInt(KEY_COUNT, 0)
        if (count == 0) return 0
        // انقطاع يوم واحد = تجميد تلقائي إن وجد، وإلا تصفير
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 86400000L))
        if (last == day() || last == yesterday) return count
        val freezes = p.getInt(KEY_FREEZES, 1)
        return if (freezes > 0) {
            p.edit().putInt(KEY_FREEZES, freezes - 1).putString(KEY_LAST, yesterday).apply()
            count
        } else 0
    }

    fun recordProduction(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getString(KEY_LAST, "") ?: ""
        var count = p.getInt(KEY_COUNT, 0)
        if (last == day()) return count
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 86400000L))
        count = if (last == yesterday) count + 1 else 1
        // مكافأة التجميد كل 7 أيام متتالية — على طريقة Duolingo
        var freezes = p.getInt(KEY_FREEZES, 1)
        if (count % 7 == 0) freezes++
        p.edit().putInt(KEY_COUNT, count).putString(KEY_LAST, day()).putInt(KEY_FREEZES, freezes).apply()
        return count
    }

    fun getFreezes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_FREEZES, 1)
}

object HookEngine {
    data class HookPack(val hooks: List<String>, val loopEnding: String, val commentCta: String)

    fun localPack(idea: String): HookPack {
        val short = idea.take(60)
        return HookPack(
            hooks = listOf(
                "هل تعلم أن $short؟",
                "كل الناس مخطئة في فهم $short — إليك الحقيقة",
                "قف لحظة: $short قد يغير يومك"
            ),
            loopEnding = "والآن أعد المشاهدة: ما أول كلمة خطفتك؟",
            commentCta = "اكتب كلمة واحدة وصفت شعورك 👇"
        )
    }

    suspend fun getPack(idea: String): HookPack = withContext(Dispatchers.IO) {
        val p = """
            الفكرة: "$idea".
            أخرج JSON فقط: {"hooks":["خطاف سؤال","خطاف تناقض","خطاف أمر"],"loopEnding":"جملة ختامية تحيل للبداية وتشجع إعادة المشاهدة","commentCta":"دعوة تعليق بكلمة واحدة"}.
            كلها عربية فصيحة مبسطة، كل جملة أقل من 15 كلمة.
        """.trimIndent()
        val raw = try { RealGroqService.chatOrGenerate(p) } catch (_: Exception) { null }
            ?: try { RealOpenAIService.chatOrGenerate(p) } catch (_: Exception) { null }
        try {
            if (raw != null && raw.contains("{")) {
                val j = org.json.JSONObject(raw.substring(raw.indexOf("{"), raw.lastIndexOf("}") + 1))
                val arr = j.optJSONArray("hooks")
                val hooks = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }.filter { it.isNotBlank() }.take(3)
                if (hooks.isNotEmpty()) return@withContext HookPack(
                    hooks,
                    j.optString("loopEnding", "").ifBlank { localPack(idea).loopEnding },
                    j.optString("commentCta", "").ifBlank { localPack(idea).commentCta }
                )
            }
        } catch (_: Exception) {}
        localPack(idea)
    }
}

object SharePack {
    const val HASHTAGS_3 = "#قبس #ريلز_إسلامي #تدبر"
    const val COMMENT_CTA = "اكتب كلمة واحدة وصفت شعورك 👇"
    const val NO_WATERMARK_NOTE = "صدّر بلا علامة مائية قبل الرفع — المنصات تخفض انتشار الفيديوهات الموسومة."

    /** المدة الذهبية 2026: 30-45ث هي المساحة الآمنة لعتبة 65%. */
    fun goldenDuration(current: String): String = when {
        current.contains("15") -> "30 ثانية"
        current.isBlank() -> "30 ثانية"
        else -> current
    }
}
