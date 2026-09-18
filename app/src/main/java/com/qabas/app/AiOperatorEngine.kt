package com.qabas.app

import android.content.Context
import android.os.Build
import android.os.StatFs
import java.util.Locale

/**
 * محرك «وضع التشغيل» — يحوّل أمراً عربياً حراً إلى فعل حقيقي داخل التطبيق.
 *
 * التصنيف كلمات-مفتاح أولاً (سريع ولا يعتمد على الشبكة)، ثم:
 *  - STATUS   → تقرير جهاز حقيقي (تخزين/ذاكرة/إصدار)
 *  - DIAGNOSE → AppSelfDoctor.runFullDiagnosis (فحص حقيقي متوازٍ)
 *  - SCRIPT   → AppServices.generateScript (توليد مشاهد حقيقي)
 *  - CODE_HINT→ توجيه لوضع الشيفرة (لا تعديل ملفات من هنا)
 *  - CHAT     → AppServices.chatWithAssistant
 * لا يوجد نجاح وهمي: كل نتيجة تأتي من استدعاء فعلي أو رسالة صادقة بالفشل.
 */
enum class OperatorKind { STATUS, DIAGNOSE, SCRIPT, CODE_HINT, CHAT }

data class OperatorIntent(
    val kind: OperatorKind,
    val order: String,
    val topic: String = ""
)

object AiOperatorRouter {

    private val statusWords = listOf(
        "الجهاز", "مساحة", "تخزين", "إصدار", "الاصدار", "سعة", "حالة النظام",
        "معلومات الجهاز", "device", "storage", "status", "version"
    )
    private val diagnoseWords = listOf(
        "افحص", "فحص", "طبيب", "صحة", "تشخيص", "المفاتيح", "مفاتيح",
        "doctor", "diagnose", "health"
    )
    private val scriptWords = listOf(
        "سكريبت", "سيناريو", "مشاهد", "اكتب نص", "نص الفيديو",
        "فكرة فيديو", "فيديو عن", "script"
    )
    private val codeWords = listOf(
        "عدّل", "عدل", "غيّر", "غير", "احفظ", "commit", "الملف", "كود",
        "أضف زر", "اجعل الزر", "استورد",
        "صلح الكود", "أصلح الكود"
    )

    fun classify(order: String): OperatorIntent {
        val o = order.trim()
        val lower = o.lowercase(Locale.ROOT)
        fun hits(words: List<String>) = words.any { lower.contains(it) }
        return when {
            hits(statusWords) -> OperatorIntent(OperatorKind.STATUS, o)
            hits(diagnoseWords) -> OperatorIntent(OperatorKind.DIAGNOSE, o)
            hits(scriptWords) -> OperatorIntent(OperatorKind.SCRIPT, o, topic = extractTopic(o))
            hits(codeWords) -> OperatorIntent(OperatorKind.CODE_HINT, o)
            else -> OperatorIntent(OperatorKind.CHAT, o)
        }
    }

    /** ينزع كلمات التشغيل ليبقى الموضوع الحقيقي للفيديو. */
    private fun extractTopic(order: String): String {
        var t = order
        val triggers = listOf(
            "اكتب لي سكريبت", "اكتب سكريبت", "ولّد سكريبت", "اعمل سكريبت", "أنشئ سكريبت",
            "سكريبت", "سيناريو", "مشاهد", "نص الفيديو", "فكرة فيديو", "فيديو عن",
            "عن", "بخصوص", "حول"
        )
        triggers.forEach { t = t.replace(it, " ") }
        t = t.replace(Regex("\\s+"), " ").trim().trim('،', '.', ':', '؟', '!')
        return t.ifBlank { order }
    }

    suspend fun execute(
        context: Context,
        intent: OperatorIntent,
        onProgress: suspend (String) -> Unit
    ): String = when (intent.kind) {
        OperatorKind.STATUS -> {
            onProgress("أقرأ معلومات الجهاز الحقيقية…")
            deviceReport(context)
        }
        OperatorKind.DIAGNOSE -> {
            onProgress("أشغّل الفحص الشامل (قد يستغرق حتى دقيقة)…")
            diagnoseReport(context)
        }
        OperatorKind.SCRIPT -> {
            onProgress("أولّد سكريبتاً حقيقياً عن: ${intent.topic}…")
            scriptReport(intent.topic)
        }
        OperatorKind.CODE_HINT -> CODE_HINT_TEXT
        OperatorKind.CHAT -> {
            onProgress("أفكر…")
            chatReply(intent.order)
        }
    }

    // ── تقرير الجهاز: أرقام حقيقية من StatFs/Runtime/BuildConfig ──
    private fun deviceReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("📱 تقرير الجهاز الحقيقي")
        sb.appendLine("• الطراز: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("• أندرويد: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("• التطبيق: ${BuildConfig.VERSION_NAME} (بناء ${BuildConfig.VERSION_CODE})")

        val rt = Runtime.getRuntime()
        val maxMb = rt.maxMemory() / (1024L * 1024L)
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        sb.appendLine("• ذاكرة JVM: $usedMb / $maxMb ميجابايت")

        val freeMb = freeMegabytes(context.filesDir.absolutePath)
        val totalMb = totalMegabytes(context.filesDir.absolutePath)
        sb.appendLine("• التخزين الداخلي: $freeMb ميجابايت متاحة من $totalMb")
        context.getExternalFilesDir(null)?.let { ext ->
            sb.appendLine("• التخزين الخارجي: ${freeMegabytes(ext.absolutePath)} ميجابايت متاحة")
        }
        sb.appendLine("• حالة المساحة: " + when {
            freeMb < 200 -> "🔴 حرج — فرّغ مساحة فوراً قبل التصدير"
            freeMb < 500 -> "🟠 منخفض — قد يعطّل التصدير"
            else -> "🟢 جيد"
        })
        return sb.toString().trim()
    }

    private fun freeMegabytes(path: String): Long {
        val stat = StatFs(path)
        return stat.availableBlocksLong * stat.blockSizeLong / (1024L * 1024L)
    }

    private fun totalMegabytes(path: String): Long {
        val stat = StatFs(path)
        return stat.blockCountLong * stat.blockSizeLong / (1024L * 1024L)
    }

    // ── الفحص الشامل: نتائج DoctorReport الحقيقية ──
    private suspend fun diagnoseReport(context: Context): String {
        val reports = runCatching { AppSelfDoctor.runFullDiagnosis(context) }.getOrDefault(emptyList())
        if (reports.isEmpty()) return "لم يُرجع الطبيب أي نتيجة — أعد المحاولة أو تحقق من الإنترنت."
        val critical = reports.count { it.severity == "حرج" }
        val important = reports.count { it.severity == "مهم" }
        val sb = StringBuilder()
        sb.appendLine("🩺 نتيجة الفحص الحقيقي: ${reports.size} بند (حرج: $critical • مهم: $important)")
        reports.sortedBy { severityRank(it.severity) }.take(12).forEach { r ->
            sb.appendLine("${severityIcon(r.severity)} ${r.title} — ${r.message}")
            if (r.suggestedAction.isNotBlank()) sb.appendLine("   ↳ ${r.suggestedAction}")
        }
        if (reports.size > 12) sb.appendLine("… و${reports.size - 12} بنداً آخر في لوحة الطبيب.")
        return sb.toString().trim()
    }

    private fun severityRank(s: String): Int = when (s) {
        "حرج" -> 0
        "مهم" -> 1
        "تحسين" -> 2
        else -> 3
    }

    private fun severityIcon(s: String): String = when (s) {
        "حرج" -> "🔴"
        "مهم" -> "🟠"
        "تحسين" -> "🟡"
        else -> "🟢"
    }

    // ── السكريبت: مشاهد حقيقية من AppServices.generateScript ──
    private suspend fun scriptReport(topic: String): String {
        val scenes = runCatching { AppServices.generateScript(topic) }.getOrDefault(emptyList())
        if (scenes.isEmpty()) {
            return "لم يرجع المحرك أي مشهد عن «$topic». أضف مفتاح Gemini/Groq من شاشة المفاتيح أو ثبّت النموذج المحلي ثم أعد المحاولة."
        }
        val sb = StringBuilder()
        sb.appendLine("🎬 سكريبت حقيقي (${scenes.size} مشهد) عن: $topic")
        scenes.forEachIndexed { i, s ->
            sb.appendLine("${i + 1}. ${s.title} — ${s.durationInSeconds}ث")
            if (s.description.isNotBlank()) sb.appendLine("   ${s.description.take(180)}")
        }
        sb.appendLine("\nلاستخدامه في فيديو: انتقل إلى الاستوديو → الفكرة → معالجة.")
        return sb.toString().trim()
    }

    // ── المحادثة الحرة: تبقى كاحتياط صادق ──
    private suspend fun chatReply(order: String): String {
        val reply = runCatching {
            AppServices.chatWithAssistant(listOf(Pair(true, order)))
        }.getOrDefault("")
        return reply.ifBlank {
            "لا يوجد رد. صياغة أوضح، أو أضف مفتاح Gemini/Groq من شاشة المفاتيح."
        }
    }

    private const val CODE_HINT_TEXT =
        "هذا يبدو تعديل شيفرة. بدّل إلى «تحرير الشيفرة» بالأعلى، حمّل الملف، ثم اطلب التعديل ليُحفظ كـ commit.\n\n" +
            "في «وضع التشغيل» أنفّذ أفعالاً حقيقية داخل التطبيق مباشرة:\n" +
            "• «افحص» — فحص شامل حقيقي للمفاتيح والخدمات\n" +
            "• «سكريبت عن …» — توليد مشاهد فعلية\n" +
            "• «معلومات الجهاز» — تخزين وذاكرة وإصدار بأرقام حقيقية\n" +
            "• أو اسألني أي سؤال حر."
}
