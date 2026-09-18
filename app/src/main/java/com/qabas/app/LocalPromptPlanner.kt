package com.qabas.app

/**
 * «المخطط المحلي» — يولّد خطة برمجة عربية متكاملة لطلب تطبيق
 * **بلا إنترنت وبلا أي مفتاح API** — قوالب ذكية حسب نوع المشروع.
 *
 * يُستخدم كبديل تلقائي في [AppServices.generateDeveloperPrompts]
 * عندما لا يتوفر مفتاح Gemini أو فشل الاتصال — لا رسالة خطأ بعد الآن.
 */
object LocalPromptPlanner {

    enum class ProjectKind(
        val label: String,
        val model: String,
        val temperature: String,
        val systemInstructions: String
    ) {
        GAME(
            "ألعاب وتفاعل",
            "Gemini 1.5 Pro", "0.8",
            "أنت خبير تطوير ألعاب ومحركات. ركز على الأداء الجرافيكي والهندسة ثلاثية الأبعاد."
        ),
        CHAT(
            "محادثة وذكاء اصطناعي",
            "Gemini 1.5 Flash", "0.5",
            "أنت خبير تطبيقات المحادثة والذكاء الاصطناعي. ركز على سرعة الاستجابة وربط الواجهات البرمجية."
        ),
        STORE(
            "تجارة ومتاجر",
            "Gemini 1.5 Pro", "0.3",
            "أنت خبير تطبيقات التجارة الإلكترونية. ركز على أمان البيانات وبوابات الدفع وإدارة الحالة."
        ),
        GENERAL(
            "تطبيق عام",
            "Gemini 1.5 Pro", "0.7",
            "أنت مهندس برمجيات محترف. ركز على بناء هيكل متين وتجربة مستخدم ممتازة."
        )
    }

    fun classify(request: AppRequestService.AppRequest): ProjectKind {
        val desc = (request.description + " " + request.title + " " + request.goal).lowercase()
        return when {
            desc.contains("ألعاب") || desc.contains("لعبة") || desc.contains("game") || desc.contains("3d") -> ProjectKind.GAME
            desc.contains("محادثة") || desc.contains("chat") || desc.contains("ذكاء") || desc.contains("مساعد") -> ProjectKind.CHAT
            desc.contains("تجارة") || desc.contains("متجر") || desc.contains("بيع") || desc.contains("e-commerce") || desc.contains("store") -> ProjectKind.STORE
            else -> ProjectKind.GENERAL
        }
    }

    /** خطة كاملة جاهزة للنسخ — تعمل offline تماماً. */
    fun generatePlan(request: AppRequestService.AppRequest): String {
        val kind = classify(request)
        return buildString {
            append("🤖 خطة مولّدة محلياً — تعمل بلا مفاتيح API\n")
            append("━━━━━━━━━━━━━━\n\n")
            append("## 1️⃣ إعدادات AI Studio المقترحة\n")
            append("- النموذج: ${kind.model}\n")
            append("- الحرارة (Temperature): ${kind.temperature}\n")
            append("- تعليمات النظام: ${kind.systemInstructions}\n\n")
            append("## 2️⃣ تحليل المشروع\n")
            append("- الاسم: ${request.title}\n")
            append("- الوصف: ${request.description}\n")
            append("- الهدف: ${request.goal}\n")
            append("- النوع المكتشف: ${kind.label}\n")
            append("- العميل: ${request.userEmail}\n\n")
            append("## 3️⃣ هندسة التطبيق المقترحة\n")
            append("- التقنية: Android + Kotlin + Jetpack Compose + Material3\n")
            append("- إدارة الحالة: ViewModel + StateFlow\n")
            append("- التخزين المحلي: Room (للبيانات) + SharedPreferences (للإعدادات)\n")
            append("- الشبكة (إن لزمت): Retrofit + Moshi\n\n")
            append("## 4️⃣ الشاشات المقترحة\n")
            append("1. شاشة رئيسية تعرض: ${request.goal}\n")
            append("2. شاشة التفاصيل لكل عنصر\n")
            append("3. شاشة الإعدادات\n")
            append("4. شاشة البحث/الفلاتر\n\n")
            append("## 5️⃣ برومبتات جاهزة للنسخ (الصقها واحداً واحداً في AI Studio)\n\n")
            append("### 🧱 برومبت 1 — هيكل البيانات\n")
            append("```\n")
            append("أنشئ data classes بلغة Kotlin لمشروع \"${request.title}\": ${request.description}. ")
            append("اشمل الحقول الأساسية + Room Entity + DAO + TypeConverters إن لزمت.\n")
            append("```\n\n")
            append("### 🎨 برومبت 2 — الواجهة الرئيسية\n")
            append("```\n")
            append("ابنِ شاشة Compose رئيسية لمشروع \"${request.title}\" تحقق الهدف: ${request.goal}. ")
            append("استخدم Material3 + LazyColumn + ViewModel + دعم العربية RTL.\n")
            append("```\n\n")
            append("### ⚙️ برومبت 3 — المنطق والحالة\n")
            append("```\n")
            append("اكتب ViewModel مع StateFlow لإدارة حالة شاشات \"${request.title}\": تحميل/نجاح/خطأ، ")
            append("مع Coroutines و Repository Pattern يفصل مصادر البيانات عن الواجهة.\n")
            append("```\n\n")
            append("## 6️⃣ قائمة الاختبار قبل التسليم\n")
            append("- [ ] يعمل بلا إنترنت للوظائف الأساسية\n")
            append("- [ ] لا انهيار عند تدوير الشاشة\n")
            append("- [ ] النصوص العربية سليمة (RTL)\n")
            append("- [ ] زر الرجوع لا يكسر التنقل\n")
            append("- [ ] الأداء: فتح الشاشة الرئيسية تحت ثانيتين\n")
        }
    }
}
