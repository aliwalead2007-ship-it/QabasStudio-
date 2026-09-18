package com.qabas.app

/**
 * «طبيب مسار الإنتاج» — محرك تشخيص محلي (بلا أي استدعاء شبكة/AI) يقرأ أحداث
 * ProductionPipelineTracker ويحوّل كل فشل/بديل غير طبيعي إلى:
 * مشكلة واضحة + السبب الأرجح + حل عملي، وبعضها قابل للتنفيذ بضغطة واحدة (actionId).
 *
 * القواعد مبنية على قراءة فعلية لمسارات الفشل والـ fallback المكتوبة في
 * VideoEngineManager.kt و VideoProcessor.kt — وليست تخميناً عاماً.
 */

enum class IssueSeverity(val label: String, val colorHex: Long) {
    CRITICAL("حرج — يوقف الإنتاج", 0xFFEF4444),
    WARNING("تحذير — تدهور جودة", 0xFFF59E0B),
    INFO("معلومة", 0xFF60A5FA)
}

data class DiagnosedIssue(
    val id: String,
    val stage: ProductionPipelineTracker.Stage,
    val severity: IssueSeverity,
    val title: String,
    val cause: String,
    val solution: String,
    val occurrences: Int,
    val affectedScenes: List<Int>,
    val actionId: String? = null
) {
    fun toShareText(): String = buildString {
        append("🩺 ${title}\n")
        append("المرحلة: ${stage.label} | الخطورة: ${severity.label} | التكرار: ${occurrences}\n")
        if (affectedScenes.isNotEmpty()) append("المشاهد المتأثرة: ${affectedScenes.joinToString(", ") { (it + 1).toString() }}\n")
        append("\nالسبب المحتمل:\n$cause\n")
        append("\nالحل المقترح:\n$solution")
    }
}

object PipelineDoctor {

    private data class Rule(
        val id: String,
        val stage: ProductionPipelineTracker.Stage,
        val results: Set<ProductionPipelineTracker.Result>,
        val messageContains: List<String> = emptyList(),
        val severity: IssueSeverity,
        val title: String,
        val cause: String,
        val solution: String,
        val actionId: String? = null
    )

    private val RULES = listOf(
        Rule(
            id = "storage_low",
            stage = ProductionPipelineTracker.Stage.VIDEO_ENGINE,
            results = setOf(ProductionPipelineTracker.Result.FAILURE),
            messageContains = listOf("مساحة تخزين"),
            severity = IssueSeverity.CRITICAL,
            title = "مساحة التخزين غير كافية لبدء الإنتاج",
            cause = "المساحة الحرة في كاش الجهاز أقل من 200MB، والمحرك يرفض البدء عمداً لمنع فشل غير متوقع في وسط العملية.",
            solution = "حرّر مساحة تخزين حتى تتوفر 500MB على الأقل (احذف ملفات/تطبيقات غير ضرورية)، ثم أعد المحاولة. تنظيف الكاش الأقدم من ساعة يعمل تلقائياً بالفعل.",
            actionId = "clean_cache"
        ),
        Rule(
            id = "media_fetch_fallback",
            stage = ProductionPipelineTracker.Stage.SCENE_PROCESSING,
            results = setOf(ProductionPipelineTracker.Result.FALLBACK),
            messageContains = listOf("إطار محلي بديل", "تعذر تجهيز الوسائط"),
            severity = IssueSeverity.WARNING,
            title = "تعذّر تجهيز الوسائط الأصلية لمشهد",
            cause = "تنزيل أو قصّ ملف B-Roll/الصورة الأصلية فشل، فاستُخدم إطار سينمائي محلي بديل بدل اللقطة الحقيقية.",
            solution = "تحقق من اتصال الشبكة وصلاحية مفاتيح Pexels/Pixabay في شاشة مفاتيح API، وتأكد أن الروابط المُرجعة من fetchMedia هي روابط فيديو مباشرة فعلاً، لا نص وصفي للمشهد."
        ),
        Rule(
            id = "tts_failed",
            stage = ProductionPipelineTracker.Stage.TTS_GENERATION,
            results = setOf(ProductionPipelineTracker.Result.FAILURE),
            severity = IssueSeverity.WARNING,
            title = "فشل توليد التعليق الصوتي لمشهد",
            cause = "خدمة تحويل النص إلى صوت (السحابية أو محرك Android TTS المحلي) لم تُرجع ملفاً صالحاً خلال 60 ثانية.",
            solution = "المشهد يستمر بلا تعليق تلقائياً. للحل الجذري: تأكد من تثبيت صوت عربي لمحرك TTS على الجهاز، أو من صلاحية مفتاح خدمة الصوت السحابية."
        ),
        Rule(
            id = "text_overlay_failed",
            stage = ProductionPipelineTracker.Stage.TEXT_OVERLAY,
            results = setOf(ProductionPipelineTracker.Result.FAILURE, ProductionPipelineTracker.Result.FALLBACK),
            severity = IssueSeverity.WARNING,
            title = "فشل تركيب طبقة النص/الكابشن على مشهد",
            cause = "أمر FFmpeg لتركيب الكابشن أرجع رمز خطأ عام (ليس نقص ملف ولا ذاكرة) — الشبهة الأولى صورة النص (createArabicTextBitmap) أو تعقيد فلتر overlay الكلمة-بكلمة.",
            solution = "غير قاتل: يستمر المشهد بلا كابشن. إن تكرر مع كل مشهد بلا استثناء، وسّع سجل الخطأ (allLogsAsString كاملاً لا آخر 300 حرف فقط) وتحقق من توفر الخط العربي المستخدم في توليد الصورة."
        ),
        Rule(
            id = "color_grading_failed",
            stage = ProductionPipelineTracker.Stage.COLOR_GRADING,
            results = setOf(ProductionPipelineTracker.Result.FAILURE, ProductionPipelineTracker.Result.FALLBACK),
            severity = IssueSeverity.WARNING,
            title = "فشل تطبيق التلوين السينمائي على مشهد",
            cause = "أمر FFmpeg لتدريج الألوان أرجع رمز خطأ عام، غالباً بسبب فلتر لوني غير متوافق مع صيغة/pix_fmt الفيديو المُدخل.",
            solution = "غير قاتل: يستمر المشهد بلا فلتر لوني. راجع أمر الفلتر لهذا الأسلوب وتأكد من تطابقه مع صيغة الفيديو الناتج من مرحلة تجهيز المشهد."
        ),
        Rule(
            id = "merge_no_valid_scenes",
            stage = ProductionPipelineTracker.Stage.FFmpeg_MERGE,
            results = setOf(ProductionPipelineTracker.Result.FAILURE),
            messageContains = listOf("لا توجد مشاهد صالحة", "لا يوجد أي مشهد صالح"),
            severity = IssueSeverity.CRITICAL,
            title = "لا يوجد أي مشهد صالح للدمج — فشل تام في الإنتاج",
            cause = "كل المشاهد المُعالجة، بما فيها الإطار البديل ثلاثي المستويات، فشلت فحص isValidVideoFile. نادراً ما يكون السبب محتوى مشهد واحد — الأرجح أن FFmpegKit نفسه لا ينفّذ أي أمر بنجاح على هذا الجهاز/البناء.",
            solution = "1) افحص Logcat وقت التشغيل عن UnsatisfiedLinkError أو استثناء من ffmpeg-kit عند أول استدعاء.\n2) استخدم زر «فحص ذاتي لمحرك FFmpeg» بالأسفل للتأكد فوراً هل المحرك يعمل أصلاً على هذا الجهاز.\n3) تأكد أن ffmpeg-kit-full يغطي معمارية الجهاز (arm64-v8a/armeabi-v7a/x86/x86_64) وأن AGP لا يستثني أي ABI.\n4) راجع اعتماديات smart-exception-java (تعليق build.gradle.kts يحذّر من NoClassDefFoundError وقت التشغيل إن نُسيت).",
            actionId = "self_test_ffmpeg"
        ),
        Rule(
            id = "merge_direct_failed",
            stage = ProductionPipelineTracker.Stage.FFmpeg_MERGE,
            results = setOf(ProductionPipelineTracker.Result.FAILURE),
            messageContains = listOf("فشل دمج المشاهد نهائياً"),
            severity = IssueSeverity.CRITICAL,
            title = "فشل الدمج حتى بالطريقة المباشرة بلا انتقالات",
            cause = "فشل الدمج السينمائي بالانتقالات وأيضاً الدمج المباشر البديل (concat demuxer) معاً، مما يستبعد مشكلة انتقال بعينه ويشير لعطل عام في تنفيذ FFmpeg أو تلف بملفات المشاهد المُدخلة.",
            solution = "استخدم زر «فحص ذاتي لمحرك FFmpeg» بالأسفل أولاً لاستبعاد عطل المحرك نفسه، ثم تحقق من صلاحية كل ملفات ready_vid_*.mp4 يدوياً وتطابق ترميز/دقة كل المشاهد قبل concat.",
            actionId = "self_test_ffmpeg"
        ),
        Rule(
            id = "export_failed",
            stage = ProductionPipelineTracker.Stage.EXPORT,
            results = setOf(ProductionPipelineTracker.Result.FAILURE),
            messageContains = listOf("ملف غير صالح أو غير موجود", "فشل التصدير"),
            severity = IssueSeverity.CRITICAL,
            title = "فشل التصدير النهائي",
            cause = "نتيجة مباشرة لفشل مرحلة سابقة (الدمج غالباً) — لا يوجد ملف مُخرَج صالح لتصديره.",
            solution = "عالج تنبيه «دمج FFmpeg» أولاً؛ فشل التصدير يُحل تلقائياً بمجرد أن يُنتج الدمج ملفاً صالحاً واحداً على الأقل."
        )
    )

    /** يحلل مجموعة أحداث (تشغيلة واحدة أو كل التاريخ) ويرجّع مشاكل مرتبة: الحرج أولاً، ثم الأكثر تكراراً. */
    fun analyze(events: List<ProductionPipelineTracker.PipelineEvent>): List<DiagnosedIssue> {
        if (events.isEmpty()) return emptyList()
        val issues = mutableListOf<DiagnosedIssue>()
        val matchedKeys = mutableSetOf<Long>()

        for (rule in RULES) {
            val matches = events.filter { e ->
                e.stage == rule.stage &&
                    e.result in rule.results &&
                    (rule.messageContains.isEmpty() || rule.messageContains.any { kw -> e.message.contains(kw) || e.detail.contains(kw) })
            }
            if (matches.isNotEmpty()) {
                matches.forEach { matchedKeys.add(it.timestamp) }
                issues.add(
                    DiagnosedIssue(
                        id = rule.id,
                        stage = rule.stage,
                        severity = rule.severity,
                        title = rule.title,
                        cause = rule.cause,
                        solution = rule.solution,
                        occurrences = matches.size,
                        affectedScenes = matches.map { it.sceneIndex }.filter { it >= 0 }.distinct().sorted(),
                        actionId = rule.actionId
                    )
                )
            }
        }

        // أي فشل/بديل/مهلة لم تغطّه قاعدة معروفة — لا نتركه مجهولاً، نعرضه بصياغة عامة صادقة
        val abnormalResults = setOf(
            ProductionPipelineTracker.Result.FAILURE,
            ProductionPipelineTracker.Result.FALLBACK,
            ProductionPipelineTracker.Result.TIMEOUT
        )
        val leftovers = events.filter { it.result in abnormalResults && it.timestamp !in matchedKeys }
        leftovers.groupBy { Triple(it.stage, it.result, it.message) }.forEach { (key, evts) ->
            val (stage, result, message) = key
            issues.add(
                DiagnosedIssue(
                    id = "generic_${stage.name}_${message.hashCode()}",
                    stage = stage,
                    severity = if (result == ProductionPipelineTracker.Result.FAILURE) IssueSeverity.WARNING else IssueSeverity.INFO,
                    title = "${stage.label}: $message",
                    cause = "حدث غير مغطى بقاعدة تشخيص معروفة بعد. التفاصيل: ${evts.first().detail.ifBlank { "بلا تفاصيل إضافية" }}",
                    solution = "راجع سجل الحدث كاملاً في «السجل الحي» أدناه لتحديد السبب الدقيق. إن تكرر هذا النوع من الفشل، أضف قاعدة تشخيص جديدة له في PipelineDoctor.kt.",
                    occurrences = evts.size,
                    affectedScenes = evts.map { it.sceneIndex }.filter { it >= 0 }.distinct().sorted()
                )
            )
        }

        return issues.sortedWith(compareBy({ it.severity.ordinal }, { -it.occurrences }))
    }
}
