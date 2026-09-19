package com.qabas.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * صندوق أدوات النموذج 🛠️ — دوال حقيقية داخل التطبيق يستدعيها النموذج
 * عبر OpenRouter function-calling بدل التخمين من ذاكرته.
 *
 * الأدوات: حالة الجهاز • تشخيص شامل • توليد سكريبت • بحث قرآني • تفسير آية.
 * كل أداة تُنفّذ فعلياً أو تُرجع رسالة فشل صادقة — لا نتائج وهمية.
 */
object AiTools {

    data class ToolDef(
        val name: String,
        val description: String,
        val properties: JSONObject,
        val required: List<String>,
        val run: suspend (args: JSONObject) -> String
    )

    private fun strProp(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun intProp(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    fun definitions(context: Context): List<ToolDef> = listOf(
        ToolDef(
            name = "device_status",
            description = "تقرير حقيقي عن الجهاز: الطراز والأندرويد والذاكرة والتخزين وحالة المساحة.",
            properties = JSONObject(),
            required = emptyList()
        ) { _ ->
            AiOperatorRouter.execute(context, OperatorIntent(OperatorKind.STATUS, "")) { _ -> }
        },
        ToolDef(
            name = "diagnose",
            description = "فحص شامل حقيقي للتطبيق والخدمات والمفاتيح عبر AppSelfDoctor.",
            properties = JSONObject(),
            required = emptyList()
        ) { _ ->
            AiOperatorRouter.execute(context, OperatorIntent(OperatorKind.DIAGNOSE, "")) { _ -> }
        },
        ToolDef(
            name = "generate_script",
            description = "توليد سكريبت فيديو حقيقي (مشاهد + عناوين + أوصاف) عن موضوع معطى.",
            properties = JSONObject().put("topic", strProp("موضوع الفيديو، مثال: الصبر")),
            required = listOf("topic")
        ) { args ->
            val topic = args.optString("topic").ifBlank { "موضوع عام" }
            AiOperatorRouter.execute(context, OperatorIntent(OperatorKind.SCRIPT, topic, topic)) { _ -> }
        },
        ToolDef(
            name = "search_quran",
            description = "بحث حقيقي في النص العثماني (6236 آية). يُرجع حتى 5 آيات: السورة ورقم الآية والنص.",
            properties = JSONObject().put("query", strProp("كلمة البحث (3 أحرف فأكثر)، مثال: الكرسي")),
            required = listOf("query")
        ) { args ->
            withContext(Dispatchers.IO) {
                val query = args.optString("query")
                QuranDataProvider.loadFromAssets(context)
                val hits = QuranDataProvider.searchVerses(query, 5)
                if (hits.isEmpty()) "لا نتائج لـ «$query» في النص العثماني."
                else buildString {
                    hits.forEach { v ->
                        append("﴿${v.surahName}:${v.verseNumber}﴾ ${v.verseText}\n")
                    }
                }
            }
        },
        ToolDef(
            name = "get_tafsir",
            description = "التفسير الميسر الحقيقي (مجمع الملك فهد) لآية محددة برقم السورة والآية.",
            properties = JSONObject()
                .put("surah", intProp("رقم السورة 1-114"))
                .put("ayah", intProp("رقم الآية")),
            required = listOf("surah", "ayah")
        ) { args ->
            withContext(Dispatchers.IO) {
                val s = args.optInt("surah", -1)
                val a = args.optInt("ayah", -1)
                if (s !in 1..114 || a < 1) return@withContext "أرقام غير صالحة: سورة 1-114 وآية ≥ 1."
                QuranDataProvider.loadTafsirFromAssets(context)
                QuranDataProvider.getTafsirForVerse(s, a)
                    ?.let { "تفسير $s:$a (الميسر): $it" }
                    ?: "لا يتوفر تفسير ميسر لـ $s:$a في النسخة المدمجة."
            }
        }
    )

    /** يحوّل التعريفات لصيغة tools في OpenRouter/OpenAI. */
    fun toApiJson(defs: List<ToolDef>): JSONArray = JSONArray().apply {
        defs.forEach { d ->
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", d.name)
                    put("description", d.description)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", d.properties)
                        put("required", JSONArray(d.required))
                    })
                })
            })
        }
    }
}
