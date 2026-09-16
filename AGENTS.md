# AGENTS.md — تسليم المشروع لأي مساعد يكمل العمل على «قبس» (Qabas)

> اقرأ هذا الملف **كاملاً** قبل أي تعديل.  
> هو المرجع الحاكم مع `README.md`.  
> الهدف: تكمل كأنك نفس المساعد السابق — نفس الأولويات، نفس الممنوعات، نفس الخطوة التالية.

---

## 0. قاعدة الملفين الحاكمين

| الملف | الوظيفة |
|-------|---------|
| **`AGENTS.md`** | الحالة التشغيلية، الخطوة التالية، القواعد، التسليم بين الجلسات |
| **`README.md`** | وصف المنتج، سجل الميزات، تحصين المسار، حالة البناء |

**مع كل عمل جوهري:** حدّث الملفين معاً. بدون ذلك العمل غير مكتمل.

---

## 1. من أنت على هذا المشروع

- لست مساعداً عابراً. تعامل مع قبس كمنتج يجب أن **ينتج فيديو حقيقياً** ويستقر على الجهاز.
- قراراتك لصالح استقرار المسار الطويل، لا لإرضاء طلبات تجميلية قبل الأوان.
- بعد كل قرار: **خطوة تالية واحدة** فقط.
- سلّم الملفات المعدّلة كاملة جاهزة للاستبدال على GitHub.
- صراحة تقنية مباشرة بالعربية.

---

## 2. هوية المنتج (مختصر)

- أندرويد / Kotlin / Jetpack Compose
- Dark Slate + Gold
- إنتاج محتوى إسلامي: فكرة → سكربت → B-Roll → صوت → نص على الفيديو → تصدير MP4
- FFmpeg Kit + Gemini/Groq/TTS حقيقي مع fallbacks
- بناء: GitHub Actions → `Qabas-Studio-Debug-APK`

---

## 3. أين وصلنا (آخر تسليم — سبتمبر 2026)

### أ) مسار الإنتاج (محصَّن على مستوى الكود — يحتاج إثبات جهاز)

| ملف | ماذا أُصلح |
|-----|------------|
| `VideoProcessor.kt` | نص عربي `StaticLayout`، timeouts ذكية، `isValidVideoFile` / `hasAudioTrack` |
| `VideoEngineManager.kt` | كاش B-Roll، إطار offline 1080×1920، لوج مدة/صوت |
| `ProcessingScreen.kt` | timeout ديناميكي 180–420ث حسب عدد المشاهد، مؤشر تقدم خطي `LinearProgressIndicator` فاخر، وربط نتائج التصدير بالعقل التراكمي `StyleBrain.recordProductionResult` لتطوير الأداء باستمرار |
| `StyleBrain.kt` | اعتماد وتخزين الأسلوب الأساسي للاستوديو (`primaryStyleId` + `setPrimaryStudioStyle`)، توجيه الإنتاج في `chooseBestStyleForIdea` بالدمج المرجح (70% هوية قبس + 30% أسلوب معتمد)، ودورة التغذية الراجعة التلقائية `recordProductionResult` |
| `StyleBrainSection.kt` | إضافة بادج وزر اعتماد الأسلوب كـ «أساسي للاستوديو 🌟» في بطاقات الأساليب مع التبديل التلقائي والحفظ الدائم |
| `RealServices.kt / AndroidManifest.xml / .env.example` | حل أخطاء التجميع وتصاريح أندرويد الناقصة (VIBRATE)، وتنظيف متغيرات البيئة لبناء التطبيق. |
| `ProcessingScreen.kt` (سبتمبر 2026 — إصلاح تعطل الإنتاج) | رفع مهلة محرك الإنتاج إلى **180–420ث ديناميكياً حسب عدد المشاهد** (كانت 60–180/90–300ث تخنق الترميز السينمائي متعدد المراحل 1080×1920 وتقتل الإنتاج مهما كانت المفاتيح صحيحة). |
| `VideoEngineManager.kt` (سبتمبر 2026 — إصلاح تعطل الإنتاج) | تصفية المشاهد الصالحة فقط (`isValidVideoFile`) قبل الدمج، **دمج بديل مباشر بلا انتقالات عند فشل الدمج السينمائي** بدل الفشل الكامل، وإطار محلي صادق: إعادة محاولة بإطار نقطي مضمون عند فشل التوليد + حذف الملف الفاشل حتى لا يدخل سلسلة الدمج (لا نجاح وهمي). |
| `CloudServices.kt` + `DeveloperDashboardScreen.kt` (سبتمبر 2026 — إصلاح رفع/تنزيل الرتبة) | زر تغيير رتبة المستخدم (⋮ ← مطور/خاص/Freemium) وزر الإيقاف في لوحة المطور كانا يعدّلان نسخة مؤقتة بالذاكرة فقط ولا يحفظان أي شيء في Firestore — التغيير يضيع بمجرد إغلاق الشاشة. أضفنا `CloudServices.Database.updateUserRole` و`updateUserSuspension` (يكتبان فعلياً لمستند المستخدم في Firestore، ويحميان حساب المالك من التخفيض/الإيقاف)، وربطناهما بالواجهة مع رسالة نجاح/فشل صادقة (لا نجاح وهمي).
| `AppSelfDoctor.kt` (سبتمبر 2026 — الطبيب الحقيقي v2) | ترقية الطبيب من فحص نصوص إلى فحص حقيقي: (1) اختبار اتصال فعلي متوازٍ لكل خدمة موصولة بمفتاح (Gemini/Groq/Azure/ElevenLabs/HuggingFace/Pexels/Pixabay/Supabase) بطلب قراءة خفيف بلا تكلفة رموز — ويميز بصدق بين «مفتاح مرفوض» و«شبكة غير متاحة». (2) فحص بيئة الإنتاج: مساحة التخزين لمجلد الأفلام (تحت 0.5GB حرج) ووضع الأداء المضمون. (3) سجل التشخيص يُصرّف على القرص (doctor_history.json) ويبقى بعد إغلاق التطبيق. (4) رسائل محدثة بلا ادعاءات وهمية.
| `RealServices.kt` + `AppSelfDoctor.kt` + `OperationalReadinessManager.kt` (سبتمبر 2026 — موحّد المفاتيح KeyVault) | إصلاح اتصال المفاتيح: الخدمات كانت تقرأ مفاتيح شاشة الإعدادات (prefs) فقط وتتجاهل المفاتيح المحقونة من `.env`/GitHub Secrets عبر BuildConfig (عدا Pexels/Pixabay). الآن `KeyVault` يوحّد الأسبقية: مفتاح المستخدم من الشاشة ← مفتاح BuildConfig ← مفقود (والقيم الوهمية your_key/YOUR_ تعتبر مفقودة — لا تشغيل وهمي). يشمل: Gemini (7 مواضع)، Groq، OpenAI، HuggingFace، Azure (مفتاح+منطقة)، ElevenLabs، Pexels/Pixabay. الطبيب ولوحة الجاهزية يقرآن المفتاح الفعّال نفسه فيقرران بالحقيقة.
| `VideoEngineManager.kt` (سبتمبر 2026 — صوت المسار المضمون) | المسار المضمون (أجهزة ضعيفة/جودة سريعة) كان يتخطى TTS كلياً → فيديو أبكم. الآن يستخدم AndroidTTSService المحلي فقط (بلا شبكة ولا مفاتيح) ويرجع لصمت صادق عند عدم توفره. |
| `CloudServices.kt / LoginScreen.kt / AuthScreens.kt / QabasApplication.kt` (سبتمبر 2026 — رتبة المالك) | بطلب المالك: `OWNER_EMAIL` استثناء وحيد يمنح رتبة «مطور» تلقائياً عند الدخول/التسجيل/تشغيل التطبيق (لو مسجل مسبقاً)، وزر «تنزيل من فايربيس» لا يخفض رتبة المالك أبداً (الحفاظ عليها مهما كان الـ claim). كلمة مرور المالك لم تُخزَّن ولا في الكود ولا في الوثائق. |
| `.github/workflows/android-ci.yml` + `app/build.gradle.kts` (سبتمبر 2026 — توقيع ثابت للتحديث) | إصلاح «الحاجة لحذف النسخة القديمة عند التحديث»: كان debug.keystore يُولَّد من جديد كل بناء على الـ runner → توقيع مختلف كل مرة → أندرويد يرفض التحديث. الآن مفتاح PKCS12 ثابت مخزن في GitHub Secrets (`QABAS_KEYSTORE_B64` + `STORE_PASSWORD`/`KEY_PASSWORD`/`KEY_ALIAS=qabas`) مدمج في المستودع (`signing/qabas-ci.p12` ببيانات debug القياسية المعروفة علناً android/androiddebugkey — هوية تحديث ثابتة وليست مفتاح نشر حساساً) يوقّع كل بناء debug/release بنفس التوقيع — التحديثات تُثبَّت فوق القديمة بلا حذف. ملاحظة: النسخ المثبتة قبل هذا الإصلاح تحتاج حذفاً أخيراً واحداً فقط (لأن توقيعها مختلف). |
| `signing/qabas-ci.p12` (سبتمبر 2026 — إصلاح فشل توقيع CI) | بعد إعادة تسمية الحزمة نجح `compileDebugKotlin` لكن فشل `assembleDebug` عند توقيع الـ APK: الشهادة داخل الـ p12 المُلتزَم كانت بحدود `subject=`/`issuer=` فارغتين (أنشأتها أداة قديمة بـ dname فارغ) فرفضها JDK 17 (`KeytoolException: Failed to read key androiddebugkey … Empty issuer DN not allowed in X509Certificates`). أُعيد توليد `signing/qabas-ci.p12` عبر `keytool` (خرّيج Temurin 17) بمعرّف DN صالح `CN=qabas-ci, OU=Qabas Studio, O=Qabas, C=SA`، صلاحية 10,000 يوم (حتى 2054)، بنفس المواصفات (PKCS12، alias `androiddebugkey`، store/key password `android`) — تُحقق القُراءة بكلٍّ من keytool وopenssl، والهوية الثابتة للتحديث تنتقل سليمة للبناء المقبل. لا APK موزَّعاً من قبل حاملاً التوقيع التالف (لا run أخضر سابقاً)، فإعادة التوليد آمنة. |
| `CloudServices.kt / AccountSettingsSection.kt` (سبتمبر 2026 — إصلاح التجميع) | إعادة قوس إغلاق `object Auth` المحذوف (سبب `Missing '}'` وكسر `Database` في كل الملفات)، استبدال `user.customClaims` غير الموجودة بـ `getIdToken(false).await().claims`، تصحيح استيرادين مكسورين في `AccountSettingsSection` وإخراج `LocalContext`/`rememberCoroutineScope` من داخل onClick (استدعاءات @Composable لا تجوز في lambda النقر). |

| `IdeaInputSection.kt` | إصلاح كسر `compileDebugKotlin` (state + أقواس + AlertDialog) |
| `DeveloperDashboardScreen.kt` | إزالة تضارب `DevLog` مع `object DevLog`، وتصحيح `LogsSection`، وإزالة تعريف `AccountSettingsSection` المزدوج |
| `StyleBrain.kt` | تغيير سياسة العقل التراكمي ليصبح **فارغاً ومحايداً تماماً** عند التهيئة، ولا يستخدم أساليب كاذبة. إرجاع `null` للمخرج في حالة العقل الفارغ ليُمرر التوليد بحالة محايدة صريحة، والبدء في بناء العقل التراكمي حصرياً من أول فيديو يتم امتصاصه عبر `evolveCoreStyleWithNewAbsorption` |
| `ProcessingScreen.kt / AppNavigation.kt` | استلام الفكرة والنبرة الحقيقية، منع الانتقال الوهمي للمراجعة دون ملف فيديو محلي حقيقي وصالح، وإظهار خيار "إعادة المحاولة/رجوع" الواضح عند الفشل، وإزالة كل صور `picsum` كخلفيات وهمية. |
| `UnderstandingScreen.kt / RealServices.kt` | إزالة الخطافات العشوائية تماماً حال فشل التحليل، واعتماد مشاهد مشتقة ديناميكياً من الفكرة الفعلية حصراً، وتطبيق تحليل JSON دقيق عبر Gemini بنسبة ثقة صارمة. |
| `StyleBrainSection.kt` | إضافة واجهة مقارنة بصرية متكاملة جنباً إلى جنب مع **رسم بياني راداري (Radar Chart)** مخصص للمطور يوضح توزيع القوة بين الأنماط المختارة (التباين، الإيقاع، عمق الألوان، ديناميكية الحركة، الخطوط) مع مؤشرات الاختلاف والتطابق ومعاينة حية للنمط الهجين قبل الدمج. |
| `StyleBrain.kt / StyleBrainSection.kt / ProcessingScreen.kt` | إضافة **محرك التعلم المستمر (Continuous Learning Engine)**: تحليل دوري وتلقائي لفيديوهات الاستوديو السابقة الناجحة لاستخلاص أنماط النجاح البصري، واقتراح تحسينات دقيقة ومدروسة للأسلوب النشط (الألوان، التباين، الإيقاع، الكابشن اللفظي) مع إمكانية تطبيقها فوراً أو تعديلها. |
| `StyleBrain.kt / StyleBrainCrashGuard.kt / StyleBrainSection.kt` | **تحصين مسار امتصاص واستخراج الأنماط الفنية من الفيديو**: حماية الذاكرة وضمان استخراج 6 إطارات مفتاحية بدقة متساوية مع تحجيم آمن لمنع الانهيار و OOM، التحقق الصارم من وجود مفتاح ذكاء اصطناعي (Gemini/Groq) قبل البدء، منع النجاح الوهمي أو السمات الفارغة، وتطبيق خوارزمية تطور القوة الحقيقية المحدودة بـ +0 إلى +3 لكل امتصاص ناجح دون قفز مفاجئ إلى 80 أو 50. |
| `QabasBrainViewModel.kt / StyleBrainSection.kt` | **تتبع دقيق للمراحل ونسبة مئوية في واجهة التحميل مع تجميد زر الاستخراج**: ربط شريط التقدم بـ `QabasBrainViewModel.absorptionState`، عرض نسبة مئوية لحظية لكل مرحلة من المراحل الثلاث (استخراج الإطارات 0-33%، تحليل الألوان 34-66%، استعلام الذكاء الاصطناعي 67-100%)، وتجميد زر الاستخراج تماماً مع إشعار قفل واضح عند عدم توفر مفتاح API. |
| `StyleBrain.kt / MontageDirector.kt / VideoEngineManager.kt / VideoProcessor.kt / ProcessingScreen.kt` | **تطبيق الأسلوب المختار/الممتص (StyleDirective) فعلياً على ملف الفيديو النهائي**: ربط كامل للتوجيه الفني بحيث ينعكس بصرياً في الفيديو الناتج (الألوان المخصصة والتدرج اللوني، فلاتر LUT السينمائية، حركة الكاميرا والزووم والانتقالات متعددة المشاهد xfade، ونمط وموضع الكابشن) مع منع التخطي الصامت وضمان صحة ملف MP4 الناتج. |
| `StyleBrain.kt / ContinuousLearningEngine` | **تحديث محرك التعلم المستمر وحساب القوة**: استبدال قوالب المقترحات الثابتة في `runContinuousLearningAnalysis` ببناء ديناميكي مرن عبر `ContinuousLearningEngine.buildProposals`. منع أي مقترحات مزيفة (Fake Proposals) إذا كان العقل فارغاً من الأساليب الممتصة (`styles.isEmpty()`) أو عدد المشاريع المكتملة صفراً (`completedCount == 0`). وحصر نمو القوة للـ Core في النطاق الصارم `+0 إلى +2` لمدى `0..100` دون أي قفزة فرضية لـ 50. |
| `StyleBrain.kt / extractKeyFrames` | **إصلاح الانهيار التلقائي (Crash) عند استخراج الإطارات**: إزالة الاعتماد على `FFmpegKit` في مهمة الاستخراج الجزئي للصور (والتي كانت تسبب انهياراً للمحرك الأصلي C++ على بعض الأجهزة)، والاعتماد بشكل كامل وموثوق على `MediaMetadataRetriever` لاستخراج الإطارات بأمان، مع تصحيح دورة حياة الكائن `release()` لضمان الاستقرار. |
| `VideoEngineManager.kt / RealServices.kt` | **إصلاح جلب وتحميل موارد B-Roll و Pexels و Pixabay**: تصحيح شرط `preferGuaranteedPath` لمنع التخطي القسري لتحميل الوسائط على مقاطع 3 مشاهد، وتفعيل دعم مفاتيح Pexels و Pixabay تلقائياً من `BuildConfig`، وضمان جلب وتنزيل مقاطع حقيقية تناسب فكرة ونبرة الفيديو. |
| `CinematicExportScreen.kt / AppNavigation.kt` | **تحسين مسار التصدير وإزالة الريندر المزدوج**: إعادة استخدام ملف الفيديو المنتج مسبقاً في `ProcessingScreen` فوراً في `SaveShareScreen` بدون إعادة معالجة غير ضرورية، والانتقال السلس والمباشر من شاشة المراجعة إلى الحفظ والمشاركة. |
| `MontageDirector.kt / RealServices.kt` | **الخطوة 1: ثورة الاستعارة البصرية**: تم إعادة ربط `MontageDirector` بـ `AppServices.generateScript` ليقوم Gemini فعلياً بهندسة مشاهد تعتمد على الاستعارة البصرية (Visual Metaphors) وتغذية المحرك بها، وإلغاء الاعتماد على القوالب الثابتة إلا في حالة فشل الاتصال. |
| `ProcessingScreen.kt` | **إضافة واجهة تحليل الفشل التلقائي (Automated Error Feedback UI)**: بدلاً من الانهيار الصامت أو رسائل الخطأ المبهمة، تم بناء واجهة ذكية تحلل سجل النشاط ونوع الخطأ (مثل فشل FFmpeg، نقص مساحة، رفض اتصال API، مشكلة في Pexels) وتعرض للمستخدم تفسيراً بشرياً واضحاً مع التوجيه السليم للإصلاح مباشرة في نفس شاشة المعالجة. |
| `RealServices.kt / VideoProcessor.kt` | **ثورة الإخراج الفني الشاملة (The Art Director Update)**: تنفيذ 5 قواعد إخراجية احترافية في الكود: 1. استعارات بصرية للمشاهد بدلاً من الترجمة الحرفية (تعديل Prompt). 2. تفعيل دائم للكابشن الحركي (Word-by-word) في `VideoProcessor`. 3. دمج فلتر (Audio Ducking) عبر `sidechaincompress` في FFmpeg لخفض الصوت المحيطي تلقائياً. 4. التلوين السينمائي النفسي عبر تعيين LUTs أو فلاتر `eq` تلقائياً تعكس المزاج (Dark teal vs Golden). 5. إيقاع تقطيع متغير (Pacing) بمدد مشاهد ديناميكية. |
| `StyleBrain.kt` | **العقل الاستباقي النشط (Gen-2 Proactive Brain)**: تحويل `chooseBestStyleForIdea` ليدمج أسلوبين تلقائياً عبر الذكاء الاصطناعي بدلاً من الاختيار السلبي، وتوليد نمط هجين لحظي مخصص لكل فكرة.

### هـ) لوحة المطور — إزالة كل البيانات المزيفة (أرقام حقيقية فقط)

| ملف | ماذا أُنجز |
|-----|------------|
| `ApiUsageTracker.kt` (جديد) | متتبع استهلاك وزمن استجابة حقيقي لكل خدمة (`ApiStat`) يُحفظ في SharedPreferences. **البداية فارغة تماماً** (`emptyMap()`) — لا أقزام مسبقة، وتمتلئ فقط من استدعاءات HTTP الفعلية. |
| `RealServices.kt` | تغليف **12 نقطة HTTP حقيقية** بـ `ApiUsageTracker.track(...)`: Gemini ×6، Groq، Azure TTS، ElevenLabs، HuggingFace، Pexels، Pixabay. لا توجد قياسات وهمية. |
| `CloudServices.kt / SupabaseServices.kt` | `Database.getAllUsers()` (قائمة المستخدمين الحقيقية من Firestore ثم Supabase fallback) و`getDevUserProjectCount(uid)` (عدّ مشاريع حقيقية من `users/{uid}/projects`). |
| `DeveloperDashboardScreen.kt` | **إزالة كل البيانات المختلقة**: قائمة المستخدمين تُحمَّل فعلياً (لا مقالات مزيفة)، إزالة أسماء أعضاء وهميين، تحويل `RevenueSection` لتحميل حقيقي عبر `observeAllTransactions().first()` (الدالتان الميتتان `UsersSection` و`RevenueSection` أُزيلتا لاحقاً واستُبدلتا بـ `EnhancedUsersSection` و`RevenueDashboard`)، إعادة كتابة `ApiConsumptionChart` و`SystemPerformanceHealthKpiGrid` بقياسات حقيقية من `ApiUsageTracker.snapshot`، العناوين/أية خطوات نقل محنكة. |
| `DeveloperDashboardScreen.kt` — `WeeklyEngagementTrendsChart` | **استبدال بيانات أسبوعية مختلقة بأرقام حقيقية من قاعدة البيانات المحلية**: عدّ يومي حقيقي لجدول `projects` (فيديوهات 🎬)، `reel_scripts` (نصوص AI ✍️)، `hadith_cards` (أحاديث 🎴) عبر DAO الجديدة `countXBetween(start,end)`. |
| `DeveloperDashboardScreen.kt` — شاشات الزيارة | استبدال أرقام "الزيارات" المختلقة (84/67/52...) برسالة صادقة "لا تتوفر بيانات زيارة لكل شاشة بعد" مع مؤشرات فارغة في انتظار التتبع الفعلي. |
| `DeveloperDashboardScreen.kt` — `formatRegDate` | معالجة `createdAt` الحقيقي بألوان/صيغ ISO عبر `SimpleDateFormat` (بدون `java.time` لأن `minSdk=24` بلا desugaring) مع fallback رقمية آمن. |

### ب) آية الدخول + السبلاش + أنيميشن دخول الاستوديو (Lottie Entry)

| ملف | الحالة |
|-----|--------|
| `assets/audio/entry_ayah_ruj3a.m4a` | «إن إلى ربك الرجعى» ~7ث |
| `AudioPlayerManager.kt` | تشغيل + **fade in ~0.9ث** + **fade out ~1.1ث** + كتم |
| `SplashScreen.kt` | **أُزيل كلياً (سبتمبر 2026)** — السبلاش لم يعد موجوداً في أي مكان (كود/موارد/ثيم)؛ الأيقونة حُيّدت بمظهر ذهبي محايد لحين استلام اللوجو الجديد |
| `QabasLottieAnimations.kt` | بوابة الاستوديو الذهبية `STUDIO_ENTRY_PORTAL_JSON` وموجة المايك `STUDIO_MIC_WAVE_JSON` |
| `QabasHomeScreen.kt` | تفاعل دخول ناعم (`scale` + `alpha` مع `spring()`)، بوابة استوديو Lottie، وترتيب الأقسام (المشاريع بعد الـ Hero مباشرة مع `defaultExpanded = true` والأقسام المساعدة مطوية افتراضياً) |

### ج) البناء

- تم تصحيح أخطاء التجميع وتضارب الأسماء في `DeveloperDashboardScreen` وبناء التطبيق بنجاح (`compile_applet` Build succeeded).
- APK Debug يُبنى عبر Actions ويُنزَّل من Artifacts.

### ز) بناء محلي مُتحقَّق + حارس الانهيارات (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| **بيئة بناء محلية** | تم تجهيز JDK 17 (Temurin) + Android SDK (platform 36 + build-tools 36.0.0) في `~/tooling` بدون sudo، وضبط `local.properties` — **تشغيل شامل: `:app:assembleDebug` → BUILD SUCCESSFUL** مع APK Debug كامل (131 ملف Kotlin مترابطة فعلياً، لا أخطاء ترجمة/ربط موارد). هذا يُثبت أن «كل وظائف التطبيق موصولة» على مستوى البناء. |
| `QabasCrashGuard.kt` (جديد) | **حارس الانهيارات العالمي**: نظام للقبض على أي استثناء JVM غير متصدّى له في أي Thread. يكتب كل انهيار إلى `filesDir/crash_logs/crash_<stamp>.txt` (مرتبطة بحد أقصى 20 ملفاً)، ويعرضه في `SystemLogsManager` (يرى المطور الانهيار بستاك كامل في لوحة المطور)، وعند انهيار الـ main thread يقوم **بإعادة إطلاق تلقائية محمية ضد التكرار** (نافذة 15 ثانية — تكرار سريع = تسليم للمُعالج الافتراضي ليتوقف التطبيق بدل حلقة لا نهائية). |
| `QabasApplication.kt` | تركيب الحارس أول شيء في `onCreate()` — كل الانهيارات المستقبلية تُلتقط وتُوثَّق وتتعافى منها بدل الموت الصامت بدون أثر. |
| التحقق | `:app:assembleDebug` أخضر بعد الإضافة (3m18s) — لا كسر لأي مسار (FFmpeg/تصدير/UI). |

### د) ما لم يُغلق

- إثبات التصدير على **جهاز حقيقي** (تم تفعيل العقل الاستباقي Proactive Brain بطلب مباشر من المستخدم قبل الإثبات).
- أصول B-Roll فيديو داخل `assets` (حالياً كاش + إطار صناعي).
- فرض مسار صوت في الملف النهائي عند نجاح TTS.
- تفعيل Supabase في `Qabas-Studio-Debug-APK` الموزّع عبر Actions: تُضاف `SUPABASE_URL` و`SUPABASE_ANON_KEY` إلى إعدادات GitHub Secrets (الخطوة جاهزة في الـ workflow) — بدونها يعمل الـ APK الموزّع في الوضع المحلي بأمان.

### هـ) تحسينات الطبقة المجانية (سبتمبر 2026)

| ملف | ماذا أُنجز |
|-----|------------|
| `RealServices.kt` / `StyleBrain.kt` / `QabasBrainViewModel.kt` | ترقية نموذج Gemini `gemini-1.5-flash` → `gemini-2.0-flash` في كل نقاط الاتصال (8 مواضع) — أحدث وأفضل ومجاني. |
| `RealServices.kt` — `RealFFmpegService.mergeVideo` | استبدال النموذج الأولي (no-op `delay(2000)`) بدمج حقيقي عبر `VideoProcessor.concatenateVideosWithTransitions` (xfade) مع فحص صلاحية MP4 وسجل واضح — لا عودة كاذبة. |
| `RealServices.kt` — `AndroidTTSService` (جديد) | محرك النطق المدمج في أندرويد `TextToSpeech` (بلا مفتاح API، يعمل دون إنترنت) كـ fallback أخير في `generateVoiceover` — الفيديو لا يُترك بلا صوت عند غياب المفاتيح، دون حقن تلاوة خاطئة. |
| `.env.example` | توثيق الطبقات المجانية لكل خدمة مع الروابط. **تحذير:** قيم placeholder يجب أن تبقى غير فارغة (`"your_key"`) وإلا انكسر `BuildConfig.java` (خطأ `illegal start of expression`). |
| CI | تشغيل كامل أخضر بعد إصلاحين (capture TTS + قيم `.env.example`). |
| `ApiKeysScreen.kt` / `ApiKeyValidator.kt` | إكمال خيارات المفاتيح المجانية: إضافة بطاقتي **Azure Speech (TTS)** (مع حقل المنطقة Region) و**ElevenLabs (TTS)** — وكلاهما يُقرآن فعلياً في `generateVoiceover` وكانا غائبين عن الواجهة — مع فحص اتصال حقيقي لكل منهما في `validateKey`، وإدراج المفتاحين والمنطقة في تصدير/استيراد النسخ الاحتياطي JSON، وإضافة بطاقة افتتاحية «**يعمل مجاناً دون أي مفتاح 🎉**» توضح أن التحليل المحلي + محرك النطق المدمج + FFmpeg تعمل بلا مفاتيح وبدون إنترنت، وأن بقية المفاتيح اختيارية. |

### و) تحسينات UI (سبتمبر 2026)

| ملف | ماذا أُنجز |
|-----|------------|
| `AppNavigation.kt` | انتقال أنيميشن بين الشاشات: لفّ `when (state.appState)` داخل `AnimatedContent` بانتقال `slide (1/4 عرض) + fade` 240ms (Compose BOM 2024.09.00 → stable، بلا OptIn) — نقلة سينمائية بدل التبديل الفاصل. |
| `ApiKeyValidator.kt` / `ApiKeysScreen.kt` | تغطية تحقق مفتاحي Azure TTS وElevenLabs بـ `ApiUsageTracker.track(context, "Azure TTS"|"ElevenLabs")` — أصبحت قياساتهما الحقيقية تظهر في لوحة المطور (كانتا النقطتين الوحيدتين خارج العدّاد). إمضاء `validateKey` الجديد: `(context, serviceType, key, hint = null)`. |
| `QabasStudioSharedComponents.kt` (جديد) | مكوّنا `QabasCard` (حدود ذهبية متدرّجة + توهج + `luxuryCardStyle`) و`QabasSectionHeader` (رأس قسم موحّد مع ترايل اختياري) — إعادة استخدام للمظهر الفاخر. |
| `ProjectsScreen.kt` | اعتماد `QabasCard` لبطاقات المشاريع (يبقى `clickable` على البطاقة) — فض 13 سطراً من التكرار. |

### ز) ربط Supabase — الطبقة السحابية الفعّالة (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| القرار | **Supabase بدل Firebase كطبقة سحابية نشطة الآن** (مشروع جاهز + RLS مفتوح + Postgres حقيقي). Firebase يبقى مساراً اختيارياً — الكود حالياً يقرأ Firestore أولاً ثم Supabase fallback في `CloudServices`/`SupabaseServices`/`StyleBrain`/`DeveloperDashboard`. |
| `SupabaseConfig.kt` | **تحصين `isConfigured` ضد placeholder**: يرفض `https://YOUR_PROJECT_REF.supabase.co`/`your_*`/`invalid` → أي APK بلا قيم حقيقية يتدهور للوضع المحلي بدل الاتصال بعنوان وهمي (لا نجاح وهمي). |
| `.env` (جذر المشروع) | قيم Supabase الحقيقية: `SUPABASE_URL="https://wyevrdnnttckaihhqxbl.supabase.co"` + legacy anon key (ما يتوقعه supabase-kt). مستثنى من git (`.gitignore` سطر 17)؛ بقية المفاتيح placeholders. |
| التحقق الحي | `GET /rest/v1/users` → 200، `INSERT transactions` → 201 (id `6f573d3b-d554-40fe-92e6-9b309f451b2b`)، `DELETE` → 204 — ثم حذف الصف التجريبي. RLS على كل الجداول مفتوح للـ anon. |
| `android-ci.yml` | خطوة `Inject secrets` تكتب `.env` من `secrets.SUPABASE_URL`/`secrets.SUPABASE_ANON_KEY` (إن وُجدت) مع fallback لقيم `.env.example` — CI أخضر بلا أسرار، وسحابي معها. |

### ح) دروس مستفادة (أخطاء حُلت في CI)

1. **`TextToSpeech` capture:** لا تَستدعِ `tts.method()` داخل لامدا `TextToSpeech(context){...}` عبر الثابت الخارجي — استخدم `var tts: TextToSpeech? = null` ثم `val instance = tts ?: return@TextToSpeech` (اللامدا تُنفَّذ أثناء الإنشاء قبل اكتمال التخصيص).
2. **`.env.example`:** القيمة الفارغة (`KEY=""`) تُنتج `BuildConfig` معطوباً (`String KEY = ;`). أبقِ دائماً قيمة placeholder غير فارغة.

### ط) تنظيف جذر المشروع + إعادة التوثيق (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| حذف | سكربتات التصحيح (`fix*.py`, `rewrite*.py`, `insert_chat.py`, `kill_gradle.py`, `patch_style_brain_section.py`, `update_models.py`)، `README.md.patch`, `metadata.json`, `CLAUDE.md`، ومجلدي `jadx_dir/` و`decompiled_apk/` (آثار تفكيك) — جذر نظيف لا يخدم البناء. |
| `README.md` | أعيدت كتابته **كوماتند من الصفر** (توثيق حقيقي: البنية، الشاشات، الطبقة المجانية، Supabase، البناء، الوضع الحالي) بدل سجلّ تغييرات طويل (كان 408 سطراً وأقسام 1–66). |
| `AGENTS.md` | إزالة الإحالة لقسم **64–66** القديم في خطوة التسليم (القسم 6/1) — المرجع الآن `AGENTS.md` ثم `README.md`. |
| تحقق | `git status` يؤكد الحذف (D) مع تعديلات فقط على الملفات المقصودة (workflow، `SupabaseConfig.kt`، الملفان الحاكمان). لا commit/push دون طلب صريح. |

### ي) ثورة شاشة المفاتيح (سبتمبر 2026): حصاد نصف آلي + فحص صحة حي
| عنصر | ماذا أُنجز |
|-----|------------|
| `ApiKeyValidator.kt` | لفّ **نقاط التحقق الست المتبقية** بـ `ApiUsageTracker.track` (Gemini، Groq ×2، HuggingFace، Pexels، Pixabay) — أصبحت كل مكالمات تحقق المفاتيح محسوبة فعلياً في لوحة المطور، لا قياسات وهمية. |
| `ApiKeysScreen.kt` — التقاط من الحافظة | إضافة `KEY_PATTERNS` (أنماط Regex لكل خدمة) + `detectKeyForService` + `detectKeysForAutoFill` + دالة `captureKeyFromClipboard(showErrors)`: تلتقط المفتاح من الحافظة، تملأ الحقل، وتُطلق تحققاً فعلياً فورياً (مع منع التكرار عبر `lastClipboardSeen` وإشعارات تصحيح واضحة بالعربية). |
| `ApiKeysScreen.kt` — السحب عند التركيز | عند التركيز على حقل مفتاح فارغ، لو الحافظة فيها مفتاح جديد → يلتقطه ويحققه تلقائياً (بدون إزعاج بالرسائل؛ الأخطاء تُعرض فقط عند الضغط اليدوي). |
| `ApiKeysScreen.kt` — زرّا الحقل الفارغ | عند غياب المفتاح يظهر صفّ: «التقاط من الحافظة 📋» + «افتح الموقع» (فتح الموقع الفعلي لإنشاء مفتاح جديد عبر `ACTION_VIEW`). |
| `ApiKeysScreen.kt` — شارة البديل المجاني | شارة خضراء `🆓 بدون هذا المفتاح يعمل التطبيق مجاناً تلقائياً بالبديل المحلي...` تظهر على أي بطاقة بلا مفتاح — الصدق أولاً: لا وهم، البدائل موثقة. |
| `ApiKeysScreen.kt` — `LiveHealthCheckPanel` (جديد) | «فحص صحة المفاتيح الحي 🩺»: بطاقة ذاتية الاكتفاء تقرأ `qabas_prefs` مباشرة (نفس التخزين الذي يستهلكه التطبيق)، تفحص 7 خدمات بالتزامن (`launch(Dispatchers.IO)` + `withContext(Dispatchers.Main)`) بأزمنة استجابة حقيقية (`System.nanoTime`)، 🟡/🟢/🔴 لحظية مع تحديث تدريجي، و«خطة الإصلاح المقترحة 🛡️» بأزرار فتح الموقع، وإجمالي مكالمات/نجاح حقيقي من `ApiUsageTracker.snapshot`. البطاقات الفارغة لا تُستدعى وليس لها قياس (لا تحقق على لا شيء). |
| الحفظ | القيم تُقرأ من نفس مفاتيح `qabas_prefs` التي يستخدمها `RealServices` (gemini_key, groq_key, huggingface_key, azure_speech_key + azure_speech_region, elevenlabs_key, pexels_key, pixabay_key) — متطابقة تماماً. |
| النطاق | أُسقط لوح `KeylessAlternativesPanel` المستقل: البطاقة الافتتاحية «يعمل مجاناً دون أي مفتاح 🎉» القائمة تغني — بدل ذلك شارة لكل بطاقة + لوحة الصحة الحي. |
| `ApiKeysScreen.kt` — زرّا الحقل الفارغ (إصلاح شكل «الباركود» وعدم عمل الزر) | **إصلاح الجذر:** كان صفّ «التقاط من الحافظة 📋» + «افتح الموقع» داخل `trailingIcon` الخاص بـ `OutlinedTextField`، فتُسحق الأزرار بعرض كامل داخل فتحة الأيقونة الثابتة (~48dp) وتظهر على شكل باركود/ZXing وتصبح غير قابلة للضغط. أصبح `trailingIcon` يحوي فقط زر إظهار/إخفاء المفتاح + أيقونة التحقق عند عدم الفراغ، ونُقل صفّ الأزرار **أسفل حقل النص** (`Row` بعرض كامل، `OutlinedButton` بارتفاع 38dp) يظهر فقط عند `value.isBlank()`. |
| `ApiKeyValidator.kt` — رسالة Gemini الواضحة | **فحص مبكر للصيغة قبل الشبكة:** قبل `validateKey` الشبكي، إذا لم يطابق المفتاح `AIza[A-Za-z0-9_\-]{35,}` تُرجع نتيجة «صيغة غير صحيحة 🔴» عربية تُوضّح أن المفتاح الصحيح يبدأ بـ `AIzaSy` (39 محرفاً)، وتكشف أن المفاتيح التي تبدأ بـ `AQ.…` هي توكنات OAuth وليست مفاتيح Gemini، مع توجيه لإنشاء مفتاح من `aistudio.google.com/app/apikey` — لا مزيد من أخطاء HTTP 400 المبهمة. |

### ك) هوية الإنتاج AI — الميزات التنافسية لقسم الاستوديو (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| `ProceduralBackdropEngine.kt` (جديد) + `VideoEngineManager.kt` + `RealServices.kt` | **خلفيات برمجية مولّدة (Procedural Backdrops)** بدل الصور الوهمية في مسار الإنتاج: محرك يرسم خلفيات متحركة/ثابتة بالكامل بالكود (رمزياً بالكاميرات، ومادة تحفة للـ B-Roll مع إطار offline 1080×1920)، ويعمل بلا إنترنت وبلا مفاتيح وبلا أصول ثقيلة، مع لحامه في مسار `generateVideo` لضمان فيديو صالح دائماً. CI أخضر (`1c8ba7d`). |
| `Theme.kt` | **نظام ألوان تقني ببنفسجي مهيمن**: الداكنة `primary=AiViolet(#8B5CF6)` + `secondary=AiCyan(#22D3EE)` + `tertiary=AiVioletLight`، والفاتحة `#7C3AED`/`#0891B2`/`#A78BFA`. **الذهبي `GoldPrimary` محفوظ للمقدسات** المستخدمة مباشرة (السبلاش، بطاقة التجويد) والعناوين التبركية. الالتزام `f5bcdec`. |
| `LoginScreen.kt` | **شاشة دخول تجمع الشعار الرسمي + الترحيب**: استبدال الشعار الدائري التجريبي باللوجو الرسمي `R.drawable.qabas_logo` (مقاس `fillMaxWidth(0.72f)`، `ContentScale.Fit`، fallback آمن) مع ترويسة ترحيب («أهلاً بك في قبس» / «استوديو الإنتاج وصناعة الأثر» بالذهبي) وعبارة دخول، وتدرج العلامة `brandGradient` (بنفسجي→أزرق→سيان) على زر الدخول، وألوان حقول/روابط/زر ضيف بنفسجي/سيان. الالتزام `bd00524`. |

### ل) سجل الانهيارات + إزالة آخر البيانات المزيفة + سبلاش «الكتاب المفتوح» (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| `DeveloperDashboardScreen.kt` — `CrashLogsSection` (جديد) | **وثيقة واجهة «سجل الانهيارات 🛡️»**: تقرأ ملفات `crash_logs/crash_*.txt` فعلياً من `filesDir` (الأحدث أولاً)، وتعرض لكل انهيار: الطابع الزمني الحقيقي (`SimpleDateFormat` — بلا `java.time` لـ minSdk24)، الخيط، سطر الاستثناء الأول؛ وعند التوسيع تظهر **ثلاثية التشخيص بالعربية**: «لماذا ‽» (السبب الجذري) و«كيف حدث؟» (السيناريو) و«الحل ✓» (الإصلاح العملي) عبر `diagnoseCrash` + `CrashDiagnosis`. صنّفت 10 فئات معروفة (OOM، FFmpeg/arthenica، NullPointer، مفاتيح Gemini، الشبكة، Pexels/Pixabay، JSON، TTS، Supabase) + حالة عامة صادقة، وزرّا «تحديث» و«مسح الكل» (حذف فعلي للملفات) وحالة فارغة مطمئنة. **قائمة الأقسام** (الـ enum) وعناوين الـ TopAppBar وبطاقة شبكة اللوحة رُبطت بالقسم الجديد. |
| `QabasBrainRepository.kt` | **حذف البذرة المزيفة في عقل القرارات**: `getDecisionHistory()` كانت تُرجع سجلاً مصنّعاً (`createInitialSampleDecisionHistory()` — نحو 109 أسطر من قرارات ملفّقة) عند فراغ الذاكرة. أصبحت تُرجع `emptyList()` وتحمل الواجهة الفارغة بأمان؛ الدالة المزيفة حُذفت كلياً (`sed 249,357d`). |
| `DeveloperDashboardScreen.kt` — عدّاد المستخدمين | **إزالة الرقم الثابت المختلَق «2»**: `QuickMetric "المستخدمين"` أصبح `userCount.toString()` عبر معامل `userCount = devUsers.size` فعلي من `Database.getAllUsers()`. |
| `SplashScreen.kt` — سبلاش «الكتاب المفتوح» (إعادة كتابة كاملة) | **إنتاج Compose نقي بلا Lottie/صور**: كتاب بعمود فقري مركزي يُفتح (الغلافان يلفّان `rotationY` + `cameraDistance` حول `transformOrigin` عند الحواف الداخلية، صفحات ورقية فاتحة بداخلها «۞» و«﴾أَلَا بِذِكْرِ اللهِ تَطْمَئِنُّ الْقُلُوبُ﴿»)، ثم تُكشف شعلة قبس «✦» + «قَـبَـسْ» (Amiri) + «استوديو الإنتاج وصناعة الأثر» بحركة نابضة (spring low-bouncy) وتوهّج ذهبي «إشعاع» بعد اكتمال الفتح، ثم الآية «﴿ادْعُ إِلَىٰ سَبِيلِ رَبِّكَ بِالْحِكْمَةِ وَالْمَوْعِظَةِ الْحَسَنَةِ﴾» أسفل الكتاب. بنفس التزامات الشاشة السابقة: تخطي بلون ذهبي يظهر بعد الاستقرار، لمسة شاشة، مهلة أمان قصوى 4.5ث، ذوبان خروج، بلا صوت. Chroma: ذهبي مقدّس + كحلي عميق على خلفية `#03060C→#060D19`. |
| التحقق | `:app:assembleDebug` أخضر (2m19s) بعد كامل التعديلات — سلامة البناء مؤكدة. |

### م) إعادة هيكلة قسم القرآن — «المصحف الذهبي Hub» (سبتمبر 2026)

| ملف | ماذا أُنجز |
|-----|------------|
| `QuranTajweedScreen.kt` | تحويل قسم القرآن من 4 تبويبات إلى **Hub من 5 أقسام** بهوية المصحف الذهبي: 0 المصحف الشريف 📖 / 1 القراء والروايات 🎙️ / 2 التفسير والمصادر 📚 / 3 الأذكار 🤲 / 4 أكاديمية التجويد 🎓 (المراحل/اختبار التلاوة/الموسوعة كتبويبات داخلية محفوظة عبر `academySubTab`). شريط الأقسام علوي لتجنب التعارض مع شريط التنقل السفلي العام. |
| الحفظ الدائم `qabas_prefs` | آخر سورة مقروءة (`last_read_surah`)، القارئ المختار (`selected_reciter`)، الرواية (`selected_riwaya`)، وعلامة الفاصل في القارئ (`bookmarked_surah_$id`) — تُقرأ عند الدخول وتُكتب عند التغيير؛ بطاقة «متابعة القراءة» تعيد فتح آخر سورة مع زر مسح لا يحذف أي بيانات قرآنية. |
| `GoldenQuranRecitersView` (جديد) | قائمة 12 قارئاً حقيقياً بسيرهم الموجزة + 10 روايات (حفص/ورش/قالون/شعبة/الدوري/السوسي/خلف/خلاد/أبو جعفر/يعقوب) مع اختيار وإبقاء، ورسالة صادقة أن التلاوة الصوتية قيد التجهيز. |
| `GoldenTafsirSourcesView` (جديد) | 9 مصادر تفسيرية معتمدة بأسماء المؤلفين (الميسر، ابن كثير، الطبري، السعدي، الجلالين، القرطبي، الصابوني، الجزائري، طنطاوي) — النصوص الكاملة قيد التجهيز بلا أي تفسير غير موثوق. |
| `GoldenAdhkarView` (جديد) | أذكار صباح/مساء ثابتة صحيحة بنصوصها وعددِها (سيد الاستغفار، الذكر الجامع، آية الكرسي، المعوذات، أدعية الصباح/المساء) مع تحذير ألا تصرف في اللفظ — عداد يدوي صادق. |
| `GoldenMushafReaderView` | صوت التشغيل الآن يعرض إشعاراً صادقاً («قيد التجهيز») بدل التبديل الوهمي، وعلامة الفاصل محفوظة فعلياً. |
| البناء | `:app:assembleDebug` أخضر (2m9s) — الإطار التصميمي المقرر بنظام ألوان قبس التقني (بنفسجي/سيان مع ذهبي مقدّس للأقسام القرآنية) ولم تُضف أي بيانات مزيفة (لا قرآن مُخترع — القارئ بلا أصل نصي يبقى حالة فارغة صادقة). |

### ن) النص القرآني الحقيقي + مقارنة المصحف الذهبي (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| المصدر | نص **حفص برواية عاصم بالرسم العثماني** (نسخة Tanzil المستخدمة رسمياً في مطبوعات مجمع الملك فهد) مُنزَّل برمجياً من `https://cdn.jsdelivr.net/gh/fawazahmed0/quran-api@1/editions/ara-quranuthmanihaf.json` — **لا حرف واحد كُتب يدوياً**. |
| الملف | `app/src/main/assets/quran/uthmani.json` (~1.97MB) — بنية `{"quran": [{"chapter","verse","text"}]}` تنطبق تماماً على فرع `parseQuranJson` (`optJSONArray("quran")`) بلا أي تعديل برمجي. تحقق رقمي: 114 سورة + 6236 آية مطابقة للعدد الرسمي، وآية 1 من الفاتحة تحوي البسملة والبقرة تبدأ بـ «المٓ» (بلا بسملة ملحقة بالنص). |
| القارئ | `GoldenMushafReaderView` كان يعرض حالة فارغة صادقة عند غياب الملف؛ الآن `loadFromAssets` يحمّله و`getVersesForSurah` يعرض الآيات الحقيقية (رأس بسملة إلا التوبة 9، خط Amiri، ألوان التجويد، وضع التسميع، إنشاء ريلز من الآية) — بدون تغيير سطر عرض واحد. |
| مقارنة قسمنا بالمصحف الذهبي (org.goldenquran) | **عندنا الآن:** النص العثماني الحقيقي، أذكار، قائمة 12 قارئاً + 10 روايات، قائمة 9 تفاسير معتمدة، أكاديمية تجويد، **بحث نصي في الآيات مع فتح وتظليل** (القسم «س»)، و**التفسير الميسر المدمج كاملاً** (القسم «ع»). **عندهم ولم نجلب بعد (فجوات موثقة):** تفسير ابن كثير/الطبري وغيرهما، إعراب/بلاغة/صرف/معنى كلمة على مستوى الآية، استماع حقيقي (40+ قارئاً مع تشغيل خلفي)، تتبع الختمة/الأحزاب، أوقات الصلاة والقبلة. **لا إضافات الآن — تُنفَّذ تباعاً بخطوة واحدة.**
| البناء | `:app:assembleDebug` أخضر بعد إضافة الأصل — لا كسر لأي مسار. |

### س) البحث النصي في القرآن + فتح آية بتظليل (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| `QuranDataProvider.kt` | `searchVerses(query, limit = 50)`: بحث حقيقي في **6236 آية عثمانية** بمسارين (`contains`): النص كما هو، ونسخة مُطبَّعة عبر `normalizeArabicText` (إزالة التشكيل `[\u064B-\u0652]` والتطويل `\u0640` والعلامة `\u0670`، وتوحيد الهمزات أ/إ/آ/ٱ→ا و ى→ي و ة→ه). **فكّ «ال» التعريف:** مفتاح يبدأ بـ «ال» (طول ‏>‏3) يُجرَّب بدونها أولاً — «الكرسي» تمر على «كرسيه» فتُفتح آية الكرسي 2:255. أقل من 3 محارف → `emptyList()`؛ النتائج مرتبة (سورة ثم آية). |
| `GoldenQuranSurahListView` | قسم «نتائج البحث في الآيات ✨» أعلى قائمة السور عند بحث ≥2 محارف: بطاقات آية قابلة للنقر (حد ذهبي، نص `maxLines=2`، «﴿السورة: رقم الآية﴾»)، وفاصل «السور المطابقة» عند وجود الاثنين معاً، والحالة الفارغة تظهر فقط عند خلوّ القائمتين. |
| `GoldenMushafReaderView` | معامل `highlightVerse = -1`: فتح آية من نتائج البحث يُمرّر تلقائياً (`LazyListState` + `LaunchedEffect` على `surah.id/versesList/highlightVerse`) مع احتساب إزاحة البسملة (سورة 9 بلا بسملة)، ويظلّل البطاقة المستهدفة (حد ذهبي `2.dp` + خلفية `#1E293B`). |
| الربط | «فتح الآية 📖» → حفظ `last_read_surah` + `readerTargetVerseNumber` + دخول المصحف. الهدف يُصفَّر (`-1`) عند الخروج وعند فتح سورة عادية — لا تظليل زائف. |
| البناء | `:app:assembleDebug` أخضر (1m28s) — بلا أي نص قرآني مكتوب يدوياً (المصدر الأصلي `uthmani.json` حصراً). |

### ع) التفسير الميسر الحقيقي المدمج (سبتمبر 2026) — سدّ الفجوة الأولى من مقارنة المصحف الذهبي

| عنصر | ماذا أُنجز |
|-----|------------|
| المصدر | **نص التفسير الميسر** (مجمع الملك فهد لطباعة المصحف الشريف) مُنزَّل برمجياً من `https://huggingface.co/datasets/Nasaq-GP/Quran_tafsir/resolve/main/quran_tafsir_dataset.json` (6236 صفاً، 8.98MB) — **لا حرف واحد كُتب يدوياً**. تحقق أصالة: آية الكرسي 2:255 تطابق نص المفسر المطبوع، والعد (سورة/آية) يطابق `uthmani.json` تماماً. |
| الملف | `app/src/main/assets/quran/tafsir_muyassar.json` (~2.68MB) — نسخة مقصوصة `{"tafsir":[{"s":سورة,"a":آية,"t":النص}]}` بلا مفاتيح زائدة عن الحاجة. تحقق رقمي: 6236 مدخلاً، الغلاف التام (كل سورة 1..n يطابق عدد آيات المصحف)، آخرها 114:6. |
| `QuranDataProvider.kt` | `loadTafsirFromAssets(context)` (تحميل كسول مرة واحدة يفحص `quran/tafsir_muyassar.json` ثم `tafsir_muyassar.json`) + `parseTafsirJson` (يقرأ مصفوفة `tafsir` ببنية {s,a,t} مع دعم بدائل {surah,ayah,text} لملفات تفسير أخرى مستقبلاً) + `getTafsirForVerse(surahId, ayah): String?` بمفتاح `"سورة:آية"` — يُرجع `null` عند الغياب (لا تفسير ملفّق). |
| الربط | `getVersesForSurah` و`searchVerses` تملآن `tafseer` فعلياً من الخريطة (`tafsirMap?.get(...) ?: ""`) بدل الثابت الفارغ؛ قارئ المصحف يستدعي `loadTafsirFromAssets` قبل بناء قائمة الآيات. |
| `QuranTajweedScreen.kt` | حوار التفسير يعرض النص الحقيقي للميسر، والقطرة الاحتياطية أصبحت صادقة: «لا يتوفر تفسير ميسر لهذه الآية في النسخة المدمجة حالياً.» (أُزيل النص الترويجي الملفّق الذي كان يظهر لكل آية). |
| `GoldenTafsirSourcesView` | شارة خضراء «مدمج ✓ متاح الآن في المصحف» على بطاقة الميسر، وسطر التعريف يُعلن أن بقية المصادر قيد التجهيز بلا أي تفسير غير موثوق. |
| البناء | `:app:assembleDebug` أخضر (1m22s) — الأصل ~2.68MB يُضغَط في الـ APK كما `uthmani.json`. |

### ف) حلقة شمسية للتنقل (Solar System Ring) — بديل الشريط السفلي (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| القرار | بطلب مباشر من المالك: **تبديل فقط دون أي حذف** — «لا حذف لأي قسم أو ميزة». تفاعل جديد: **نقرة واحدة على الكوكب → حوار تأكيد («الدخول إلى …؟» / «تأكيد ✓» / «إلغاء») → تنقّل**. الشريط السفلي القديم `QabasBottomNavigation` باقٍ في `MainActivity.kt` دون استخدام. |
| `QabasSolarSystemNavigation.kt` (جديد) | `QabasSolarSystemNavigation(currentRoute: AppState, onNavigate: (AppState) -> Unit)`: 5 كواكب تدور حول شعلة ذهبية مركزية (أصلها = «المقعد 270°»). الترتيب مطابق للأصلي: الرئيسية/بطاقة حديث/القرآن/ريلز/الإعدادات بألوانها (GoldPrimary/AiTeal/AiNeonGreen/AiViolet/AiCyan). تثبيت أولي: «أصل الكوكب الحالي يقع عند 270°» ثم اندراف دائري `8°/ث` (~45ث/دورة) عبر `withFrameNanos` مع احترام `ANIMATOR_DURATION_SCALE` (صفر = ساكن). التجميد: عند غياب `RESUMED` أو وجود طلب تنقل معلّق. لا إعادة تركيب — الحركة عبر `graphicsLayer` (إزاحة/تكبير 0.82..1.24/عتامة) والتأكيد = نشاط المقعد. |
| الأبعاد | شريط 108.dp، مدار `R=34.dp`، عرض كوكب 62.dp، قرص 24.dp بتدرج لوني قطري + حد ذهبي 1.6.dp عند الاختيار، أيقونة 13.dp داخل القرص، كابشن CairoFont 8.sp (ذهبي غامق عند الاختيار). الشعلة: توهج نابض 1→1.18 (2400ms عكسي) + مدار ذهبي `alpha 0.08`، قلب أبيض 3.5.dp. الخلفية `0xFF0B1120` بظل ذهبي 16.dp وحد متدرّج 1.dp وزوايا علوية 20.dp. |
| لمس | `detectTapGestures` + اهتزاز `performHapticFeedback(LongPress)` عند كل نقرة وعند التأكيد؛ `onNavigate` يُستدعى مرة واحدة فقط عبر `pendingRoute`. |
| الإصلاحات أثناء البناء | إزالة import غير موجود `androidx.compose.ui.graphics.StrokeWidth`؛ إضافة `androidx.compose.runtime.setValue` لتفويضات `var … by remember`؛ **المشروع فعلياً يحلّ `compose.ui = 1.9.0`** (تجاوز BOM 2024.09.00 بالصراع) فأُعيد توجيه الاهتزاز إلى API الجديد: `androidx.compose.ui.hapticfeedback.HapticFeedbackType` + `androidx.compose.ui.platform.LocalHapticFeedback`. |
| البناء | `:app:assembleDebug` أخضر (1m12s) — تحذير واحد فقط: `Icons.Filled.MenuBook` deprecated (مطابق لاستخدام `MainActivity.kt:195` نفسه). |

### غ) إزالة السبلاش كلياً + حياد أيقونة الـ Launcher — استقبال اللوجو الجديد (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| القرار | بطلب مباشر من المالك («ازله كليا») — شاشة السبلاش أُزيلت كلياً ولم يعد لها أي أثر (كود/موارد/ثيم)، مع التنظيف الكامل للوجوهات القديمة من الأصول لحين استلام اللوجو الجديد. |
| `MainActivity.kt` | حذف `installSplashScreen` (import + استدعاء) وحذف `AppState.SPLASH`؛ الحالة الابتدائية الآن `AppState.DATA_LOADING` مباشرة. |
| `AppNavigation.kt` | حذف فرع `SPLASH` من `when` — الدخول يبدأ من `DATA_LOADING`. |
| `AndroidManifest.xml` | `android:theme="@style/Theme.Qabas.Splash"` ← `@style/Theme.MyApplication` (لا أثر لثيم السبلاش). |
| حذف | `SplashScreen.kt` (إنتاج كامل)، `values/themes_splash.xml`، `values/colors_splash.xml`، `drawable/ic_qabas_splash_icon.xml`، وكل أصول اللوجو القديمة: `qabas_logo.webp`، `qabas_logo_pro.xml`، `qabas_app_icon_*.jpg`، `qabas_logo_*.jpg`، `ic_qabas_foreground.png`، `ic_launcher_foreground.xml`، `ic_launcher_background.xml`، وجميع `ic_launcher*.webp` في مجلدات الكثافة الخمس. |
| `LoginScreen.kt` | حذف كتلة اللوجو الرسمي (كانت تُحمّل `qabas_logo` قبل حذفه) مع استيرادات `Image/ContentScale/painterResource` — الشاشة سليمة. |
| أيقونة محايدة | `drawable/ic_launcher_foreground_neutral.xml` (حلقة + نجمة ٨ رؤوس ذهبي `#D4AF37` على شفاف) استُعمل كـ foreground/monochrome في ملفّي `mipmap-anydpi-v26`؛ وأُنشئ `mipmap/ic_launcher.xml` + `ic_launcher_round.xml` (vector 108dp: خلفية `#030508` + رمز ذهبي) كـ fallback لـ API 24–25 بعد حذف الـ webps. `ic_launcher_background_qabas.xml` مُبقى. |
| التحقق | grep نهائي على `app/src` بلا أي مطابقة لـ: `AppState.SPLASH|SplashScreen|Theme.Qabas.Splash|ic_qabas_splash_icon|qabas_splash_background|ic_qabas_foreground|qabas_logo|qabas_app_icon|qabas_logo_pro|ic_launcher_background"|ic_launcher_foreground"`. |
| متبقٍّ (لا يُلمس الآن) | شاشة التحميل الداخلية `DataLoadingScreen` تحمّل لوجو من `assets/logo/p0..p4.txt` (Base64) — يُستبدل عند استلام اللوجو الجديد. صوت آية الدخول خارج النطاق. |

### ذ) طبقة OpenAI الاختيارية + إغلاق صلاحيات البريد نهائياً — الأعلام فقط (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| `RealOpenAIService.kt` (جديد) | محادثة حقيقية عبر `gpt-4o-mini` (Chat Completions) بقراءة المفاتيح من `qabas_prefs` ثم BuildConfig، مع fallback آمن: **OpenAI → Groq → Gemini** في `chatWithAssistant` و`executeShortTaskWithFallback` — متاح اختيارياً دون إزاحة أي خدمة مجانية. |
| `RealServices.kt` / `ApiKeyValidator.kt` / `ApiKeysScreen.kt` | مفتاح OpenAI `sk-proj-…`: بطاقة في شاشة المفاتيح، تحقق حقيقي، تصدير/استيراد في نسخ الـ JSON الاحتياطية، ولفّ `ApiUsageTracker` — وقياسه يظهر في لوحة المطور. |
| `DeveloperDashboardScreen.kt` | إدخال «مفاتيح API 🔑» (بطاقة `Icons.Default.VpnKey`) في شبكة اللوحة الرئيسية → قسم جديد `DashboardSection.API_KEYS` داخل اللوحة (بلا مغادرة). |
| `.env.example` / `.env` | `OPENAI_API_KEY="your_key"` placeholder غير فارغ؛ `.env` مستثنى من git (السطر 17). **قاعدة أمنية:** لا يُحفظ أي مفتاح حقيقي في الكود/ملف مقتَفَع (يُستخرَج من الـ APK) — المفتاح الحقيقي في `.env` المحلي فقط أو GitHub Secret. |
| `AccountService.kt`/`LoginScreen.kt`/`AuthScreens.kt`/`SettingsScreen.kt`/`RequestChatScreen.kt`/`LeagueService.kt` | **إزالة كل صلاحيات البريد المخزَّنة يدوياً** (`aly750834`/`aliwalead`/`xman88371`/`admin@qabas`/`family@qabas`/`friend@qabas`/`peeesa7`/هكذا): الصلاحيات الآن **أعلام `qabas_prefs` فقط** (`is_admin`/`is_relative`/`is_developer`). `is_developer` يُكتب الآن فقط بقيمة `CloudServices.isOwnerAccount(lowerEmail)` عند التسجيل/الدخول — يحصل عليه المالك حصرا (لا يُمنح لأي مستخدم آخر). |
| التسامح | **(سبتمبر 2026):** لا يُمنح `is_developer` افتراضياً — التسجيل/الدخول يكتبان `true` فقط لحساب المالك (`CloudServices.isOwnerAccount`). الأجهزة القديمة تحافظ على علامتها إن كانت مكتوبة مسبقاً. لا قفل للمطور القديم المخوَّل. |
| التحقق | grep على `app/src/main` بلا مطابقة لـ `aly750834\|peeesa7\|family@qabas\|friend@qabas\|admin@qabas\|isDevEmail\|contains("admin")`؛ `DeveloperDashboardScreen.kt:449` تبقى تسمية عرض فقط («المطور الرئيسي: aliwalead.2007») لا تُوصِل صلاحية. |

### ر) توحيد الحزمة على `com.qabas.app` — إزالة `com.example` (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| القديم | حزمة الجذر كانت `com.example` بينما `namespace`/`applicationId` في `app/build.gradle.kts` = `com.qabas.app` (سطر 13/17) → تعارض فئة `R` المولَّدة يهدم البناء. |
| النقل | `git mv` لثلاثة جذور شيفرة: `app/src/main/java/com/example` → `app/src/main/java/com/qabas/app` (132 ملف Kotlin + `ui/theme` + `ui/input`)، و`app/src/test/java/com/example` → `app/src/test/java/com/qabas/app`، و`app/src/androidTest/java/com/example` → `app/src/androidTest/java/com/qabas/app`. |
| الإعلانات/الاستيرادات | sed شامل: `^package com.example` → `package com.qabas.app` و`^import com.example` → `import com.qabas.app` في كل `*.kt` (منها `Type.kt` الذي كان يستورد `com.example.R`). التوزيع النهائي: 123 × `com.qabas.app` + 6 × `ui.input` + 3 × `ui.theme`. |
| `DeveloperDashboardScreen.kt` | فلتر أول إطار للـ crash stack `at com.example.` → `at com.qabas.app.` (سطر 2150 + التعليق 2143) — إصلاح وظيفي لقراءة سجلات الانهيار. |
| `ExampleInstrumentedTest.kt` | تشخيص `packageName` في اختبار الأجهزة: `assertEquals("com.example", …)` → `"com.qabas.app"`. |
| حذف | ملف الشظية الضال `app/src/main/java/com/example/AppNavigation_PROCESSING_snippet.txt` (منتج نصي لمقارنة Diff لا يُجمَّع) — `git rm -qf`. |
| التوثيق | صف الحزمة في `README.md` (§ البنية) صار `com.qabas.app`، ومسار القسم 7 في `AGENTS.md` (الملفات الحساسة) صار `app/src/main/java/com/qabas/app/…`. |
| التحقق | grep شامل بلا أي بقايا `com.example` أو `com/example` خارج git history (الملف الوحيد فيه مذكور تاريخي هو `.beads/issues.jsonl` — تصدير سلبي لا يُعدَّل). بناء محلي **لم يُعمل** في هذه الجلسة لغياب JDK/SDK عنها — يُثبَّت بعد هذا العمل على جهاز فيه أدوات أندرويد. |

### ش) تثبيت إصدار Compose Foundation وإصلاح انهيار FlowRow (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| المشكلة | `java.lang.NoSuchMethodError` على `FlowRow` في `ProfileScreen.kt:424` (وأيضاً `QuranTajweedScreen`، `UnderstandingScreen`، `ViralSeoHashtagEngine`). السبب: عدم تطابق إصدار `foundation-layout` بين التصرّيف والتشغيل — الكود مُصرَّف على `FlowRow` المستقر (1.8+ بتوقيع `FlowRowOverflow`) بينما APK الجهاز فيه `FlowLayoutKt` أقدم بلا هذا التوقيع. |
| الجذر | `composeBom = "2024.09.00"` يحلّ `foundation-layout` ~1.7.x متعدياً، بينما `compose.ui` انحل فعلياً إلى `1.9.0` (تجاوز BOM بالصراع — موثّق سابقاً). لا يوجد `foundation` أو `foundation-layout` في `build.gradle.kts` صريحاً ← الحل التعدي غير الحاسم. |
| الإصلاح | تثبيت `foundation` و`foundation-layout` على `1.9.0` في `gradle/libs.versions.toml` (إصدارات ومكتبات صريحة) + إضافة `implementation(libs.androidx.compose.foundation)` و`implementation(libs.androidx.compose.foundation.layout)` في `app/build.gradle.kts`. |
| الإصدار | `versionCode = 3`، `versionName = "1.2.1"` — APK جديد يثبت فوق 1.2.0. |
| الملفات | `gradle/libs.versions.toml`، `app/build.gradle.kts`، `AGENTS.md`، `README.md`. |

### ص) إصلاح زر «فتح الموقع» في شاشة المفاتيح (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| المشكلة | أزرار فتح الروابط («افتح الموقع»، «الموقع 🔗»، «فتح الموقع» في حوار التعليمات) كانت تعرض «تعذر فتح الرابط» على أجهزة بعض المستخدمين — `startActivity(ACTION_VIEW)` يفشل عند عدم توفّر متصفح افتراضي مرئي أو عند قيود رؤية الحزم. |
| الحل | دالة `openUrl(context, url)` موحّدة: تحاول `ACTION_VIEW` الافتراضي، ثم تفحص قائمة متصفحات شهيرة صريحة (`chrome`/`firefox`/`opera`/`edge`/`brave`/`duckduckgo`/`transsion`/AOSP) واحداً واحداً، وإن لم يوجد متصفح تعرض الرابط كاملاً في رسالة واضحة بدل رسالة مبهمة. |
| Manifest | إضافة `<queries>` لـ `ACTION_VIEW` مع `https` و`http` (إعلان صريح لرؤية المتصفحات). |
| الملفات | `AndroidManifest.xml`، `app/src/main/java/com/qabas/app/ApiKeysScreen.kt`، `AGENTS.md`، `README.md`. |

### ط) تحويل لوحة المطور إلى لوحة تحكم سحابية كاملة (سبتمبر 2026)

| عنصر | ماذا أُنجز |
|-----|------------|
| حارس الوصول (`AdminGuard.kt`) | `is_developer` لم يعد مفعّلاً افتراضياً لأي جهاز. الوصول يتحقق: (1) بريد Firebase Auth == بريد المالك، أو (2) العلم مكتوب صراحةً في prefs. **إغلاق ثغرة (سبتمبر 2026):** التسجيل/الدخول يكتبان `is_developer = CloudServices.isOwnerAccount(lowerEmail)` فقط (المالك حصراً)، و`AccountService.isDeveloperOrAdmin` يقرأ العلم بافتراض `false` — لا يُمنح `true` افتراضياً في أي مسار. شاشة حظر واضحة لأي مستخدم غير مؤهَّل. |
| التحكم البعيد (`AppRemoteConfig.kt`) | أزرار الصيانة/استقبال الطلبات/الرد الآلي/رسالة التوقف تُكتب الآن إلى Firestore (`app_config/config`) وينعكس على كل الأجهزة عبر كاش محلي. لا تحكم محلي فقط بعد الآن. |
| سجل التدقيق (`AuditLogger.kt`) | كل إجراء حساس (رتبة، إيقاف، أكواد، بث إشعار، تصدير) يُسجَّل محلياً + سحابياً مع البصمة والوقت، ويُعرض في قسم «سجل التدقيق 📋» داخل اللوحة. |
| إدارة المستخدمين المتقدمة (`EnhancedUsersSection.kt`) | فلترة حسب الرتبة/الحالة، تفاصيل المستخدم، تأكيد قبل تغيير الرتبة أو الإيقاف، تصدير CSV، إضافة مستخدم سحابياً — مع تسجيل تدقيق. |
|الإيرادات المتقدمة (`RevenueDashboard.kt`) | رسم بياني شهري (Canvas)، تقسيم حسب المنتج، إجمالي معاملات حقيقي (Firestore)، تصدير CSV. |
| الإشعارات البعيد (`RemoteNotificationsManager.kt`) | البث السحابي: المستند يُكتب في Firestore `notifications` ويتلقاه كل الأجهزة عبر listener ويعرض إشعاراً محلياً بدون تكرار. لا إشعار محلي فقط بعد الآن. |
| نسخ احتياطي (`DashboardBackup.kt`) | تصدير JSON كامل (مستخدمون + أكواد + إعدادات) عبر CreateDocument. **استرجاع فعلي (سبتمبر 2026):** قراءة الملف + معاينة عدد العناصر + تأكيد المستخدم + استعادة الإعدادات (`pushToCloud`) + الأكواد (`GiftManager`) + المستخدمين (upsert + حالة الإيقاف) مع تسجيل تدقيق — لا حذف (دمج فقط). |
| بنية الملفات | مكونات جديدة في ملفات مستقلة بدل الاحتكام في `DeveloperDashboardScreen.kt` (3354 سطراً بعد إزالة الدالتين الميتتين `UsersSection`/`RevenueSection`): `AdminGuard.kt`، `AppRemoteConfig.kt`، `AuditLogger.kt`، `EnhancedUsersSection.kt`، `RevenueDashboard.kt`، `RemoteNotificationsManager.kt`، `DashboardBackupSection.kt`، `AuditLogSection.kt`. |
| الملفات | `QabasApplication.kt` (مهام بدء التشغيل)، `AccountService.kt` (قراءة بعيدة)، `DeveloperDashboardScreen.kt` (واجهة حماية + أقسام جديدة)، `AGENTS.md`، `README.md`. |

---

## 4. الخطوة التالية الوحيدة الآن

**بناء أخضر عبر Actions → تثبيت APK → اختبار شاشات FlowRow (الملف الشخصي/التجويد/الفهم/الهاشتاقات) + اختبار تصدير كامل على الجهاز** (بعد إصلاح تعطل الإنتاج ومهلة المحرك والدمج البديل وإصلاح التجميع أعلاه — الإصلاحات تحتاج APK جديداً ليصل الجهاز).

**يعتمد على ما يبلّغ به المستخدم بعد التجربة:**

> **Supabase مفعّل محلياً:** بناؤك من العمل الحالي على جهازك ينتج APK يتصل سحابياً (القيم الحقيقية في `.env`). للتفعيل في الـ APK الموزّع عبر Actions أضف `SUPABASE_URL` و`SUPABASE_ANON_KEY` في GitHub Secrets.

### تحقق قسم القرآن (Hub المصحف الذهبي) — ميزات سبتمبر 2026
→ عند الدخول من الشريط السفلي «القرآن»: تظهر سبعة تبويبات (المصحف الشريف / القراء والروايات / التفسير والمصادر / الأذكار / أكاديمية التجويد / الصفحات الذهبية / المكتبة والدراسة).
تحقق: بطاقة «متابعة القراءة» تظهر وتُعيد آخر سورة، اختيار القارئ/الرواية يُحفظ ويعود بعد إغلاق التطبيق، زر الصوت في القارئ يعرض الإشعار الصادق «قيد التجهيز» (لا وهم)، وعلامة الفاصل 🔖 تُحفظ بين الجلسات.

### تحقق النص القرآني الحقيقي (مُضاف حديثاً)
→ افتح أي سورة من «المصحف الشريف 📖»: يجب أن يظهر **النص العثماني الحقيقي** (خط Amiri) بدل رسالة «النص غير متوفر». تحقق: رأس البسملة في كل سورة إلا التوبة، الأعداد (الفاتحة 7 آيات — سورة 2 تبدأ بـ «المٓ») ، ألوان التجويد 🎨، وضع التسميع 🧠، وزر «إنشاء ريلز من الآية» يمرر نص الآية الحقيقي. أي خلل في ظهور النص يُبلَّغ لضبط المصدر/العرض فقط — لا عودة لمسار التصدير.

### تحقق قارئ الصفحات الجديد (مُضاف حديثاً — خيار أ)
→ من قسم «المصحف الشريف 📖»: بطاقة «📖 المصحف بالصفحات» تفتح القارئ على آخر صفحة محفوظة؛ السحب يتنقل بين 604 صفحات؛ الشريط العلوي يعرض السورة والجزء؛ السفلي يعرض رقم الصفحة + 🔖؛ نافذة الاختيار (سورة/جزء/صفحة/علاماتي) تقفز للموضع الصحيح (تحقق: الفاتحة=1، البقرة=2، الجزء 30، صفحة 604 = خواتيم الناس). أي خلل يُبلَّغ لضبط العرض فقط.
### تحقق البحث النصي وفتح الآية (مُضاف حديثاً)
→ في «المصحف الشريف 📖»: اكتب «الكرسي» أو كلمة من نص آية أو رقم آية → يظهر قسم «نتائج البحث في الآيات ✨»؛ انقر آية → يُفتح المصحف مركّزاً على تلك الآية بتظليل ذهبي (الإزاحة الصحيحة للبسملة في غير التوبة). تحقق: «الكرسي» تجد 2:255، أقل من 3 محارف لا يبحث، ودخول سورة عادية أو رجوع يزيل التظليل.

### تحقق التفسير الميسر المدمج (مُضاف حديثاً)
→ في قارئ أي سورة ومن أي آية: انقر أيقونة/زر التفسير → يظهر **نص التفسير الميسر الحقيقي** (مجمع الملك فهد) لهذه الآية بدل القطرة الترويجية السابقة. تحقق: تفسير آية الكرسي 2:255، وأول وآخر سورة (1:1 و114:6)، وحالة الآيات النادرة تسقط نصاً صادقاً «لا يتوفر تفسير ميسر لهذه الآية في النسخة المدمجة حالياً.» وفي «التفسير والمصادر 📚» شارة «مدمج ✓ متاح الآن في المصحف» على بطاقة الميسر. أي خلل في النص يُبلَّغ لضبط المصدر/العرض فقط.

### إن لم يُختبر مسار التصدير بعد
→ ثبّت APK → فكرة عربية قصيرة → معالجة → تصدير → تشغيل الملف.  
تحقق: كابشن عربي، مدة، تشغيل بدون نت / بدون TTS إن أمكن.  
**تذكير للاختبار:** أي انهيار يحدث الآن يُسجَّل تلقائياً (ملف `crash_logs/` + سطر في لوحة المطور بستاك كامل) — انسخ رسالة الانهيار من لوحة المطور عند البلاغ إن أمكن.

### تحقق التطويرات المتقدمة (سبتمبر 2026)
→ 5 تطويرات في مسار الإنتاج (`ProcessingScreen.kt` + `VideoEngineManager.kt`):
1. **معالجة توازية ذكية:** semaphore = 2 (≤4 أنوية) / 3 (5-6) / 4 (7+) — مُفعّلة افتراضياً
2. **حفظ المشاهد:** JSON في `cached_scenes` prefs بعد التوليد + مسح بعد النجاح
3. **إعادة محاولة B-Roll:** 3 محاولات بتأخير تصاعدي (1.5ث → 3ث) قبل الإطار المحلي
4. **كاش ذكي:** حذف الأقدم من ساعة فقط (بدلاً من كل شيء)
5. **فحص مساحة التخزين:** `< 200MB` = فشل صريح، `< 500MB` = تحذير
تحقق: فيديو 5 مشاهد أسرع من السابق، إعادة المحاولة بعد فشل FFmpeg لا تعيد توليد Gemini.

### تسليم اللوجو الجديد (يحل محل «تحقق المعالجة البصرية للسبلاش» — السبلاش أُزيل كلياً)
→ بانتظار ملف اللوجو الجديد من المالك (PNG ≥1024 بخلفية شفافة أو SVG). عند استلامه:
1) أيقونة الـ launcher: استبدال `drawable/ic_launcher_foreground_neutral.xml` (يُستعمل في adaptive `mipmap-anydpi-v26`) وفي `mipmap/ic_launcher.xml` / `ic_launcher_round.xml` (vector لـ API<26)، أو وضع PNG بديل يحل محل الحلقة/النجمة المحايدة.
2) لوجو شاشة التحميل `assets/logo/p0..p4.txt` (Base64 — يُعاد توليده من الملف الجديد) المستخدم في `DataLoadingScreen`.

### تحقق حلقة التنقل الشمسية (مُضاف حديثاً — بديل الشريط السفلي)
→ الشريط السفلي استُبدل بنظام «الكواكب حول الشمس»: 5 كواكب تدور ببطء (8°/ث) حول شعلة ذهبية. تحقق: 1) نقرة كوكب → يظهر حوار تأكيد «الدخول إلى …؟» و«تأكيد ✓»/«إلغاء» — لا تنقّل قبل التأكيد. 2) تأكيد → يتحول الحِجْر ويُفتح القسم الصحيح مع اهتزاز خفيف. 3) الكوكب النشط يقع عند المقعد السفلي (270°) ويكبر ويلمع بحد ذهبي وكابشن ذهبي داكن. 4) عند إغلاق التطبيق أو فتح حوار يَتوقف الدوران (لا يتحرك في الخلفية). 5) كل الأقسام الخمسة تعمل كما في السابق — **لا حذف لأي قسم أو ميزة**. أي عيب بصري/حركي يُبلَّغ لضبط الحركات فقط، لا عودة لمسار التصدير.

### إن نجح التصدير
→ لا ميزات عشوائية. إصلاح أي عيب لاحظه المستخدم، أو تقوية offline، ثم حدّث الملفين.

### إن فشل شيء
→ أصلح **ذلك الفشل فقط** (سبلاش / FFmpeg / كابشن / صوت). لا تفتح جبهات جديدة.

### طلبات هوية (آية، أنيميشن)
→ مقبولة إذا قصيرة وغير حاجبة؛ لا تؤخّر إصلاح المسار.

---

## 5. ممنوعات صارمة

1. لا هدم مسار FFmpeg أو التصدير من أجل UI.
2. لا `return true` بعد فشل صامت.
3. لا حقن محتوى ديني خاطئ كـ fallback للصوت/النص.
4. لا ميزة جديدة فوق مسار تصدير غير مُثبت على الجهاز.
5. لا تترك `AGENTS.md` / `README.md` بخطوة تالية قديمة بعد إنجاز عمل.

---

## 6. كيف تكمل «كأنك نفس المساعد»

1. اقرأ `AGENTS.md` بالكامل ثم `README.md` (الملفان الحاكمان وفق القسم 0 — وليس مرجعاً مرقّماً قديماً).
2. اسأل المستخدم (إن لزم): هل نجح التصدير على الجهاز؟ ماذا رأيت بالضبط؟
3. نفّذ **خطوة واحدة** حسب القسم 4.
4. عدّل الملفات الكاملة → سلّمها للتنزيل.
5. حدّث `AGENTS.md` (أين وصلنا + الخطوة التالية) و`README.md` (سجل مختصر).

---

## 7. ملفات حساسة للمسار (لا تلمسها دون قراءة)

```
app/src/main/java/com/qabas/app/VideoProcessor.kt
app/src/main/java/com/qabas/app/VideoEngineManager.kt
app/src/main/java/com/qabas/app/ProcessingScreen.kt
app/src/main/java/com/qabas/app/RealServices.kt
app/src/main/java/com/qabas/app/ui/input/IdeaInputSection.kt
app/src/main/java/com/qabas/app/AudioPlayerManager.kt
app/src/main/assets/audio/entry_ayah_ruj3a.m4a
```

---

## 8. ملخص سطر واحد للجلسة القادمة

> **قارئ المصحف بالصفحات (سبتمبر 2026 — خيار أ):** بُني قارئ مستقل أصلي (`MushafReaderScreen` + `MushafPageData` + `madani_pages.json` المولّد من quran-api/Unlicense) — **صفر كود من quran_android (GPL-3)** ومدفوعات الفيديو untouched. الدخول من بطاقة «📖 المصحف بالصفحات» في قسم المصحف. **البناء لم يُتحقق هنا (لا JDK/SDK) — يُبنى عبر Actions قبل الإغلاق.**
> المسار محصَّن في الكود؛ آية الدخول والسبلاش جاهزان؛ لوحة المطور خالية من البيانات المزيفة (مستخدمون/إيرادات/استهلاك API/عدّ أسبوعي حقيقي).  
> **الأولوية:** نتيجة اختبار التصدير على الجهاز → ثم إصلاح أو تقوية offline فقط.  
> لوحة المطور: 14 نقطة HTTP ملفوفة بـ `ApiUsageTracker` (انضمّت Azure TTS وElevenLabs من شاشة المفاتيح)، وتحميل فعلي من Firestore/Supabase/Room.  
> **سبتمبر 2026:** Gemini ↑ 2.0-flash، دمج FFmpeg حقيقي، تعليق صوتي مجاني دائماً (TTS النظام)، توثيق مجانيّ في `.env.example`، CI أخضر.
> **سبتمبر 2026 (إصلاح FlowRow):** تثبيت `foundation`/`foundation-layout` 1.9.0 صريحاً لإزالة `NoSuchMethodError` على `FlowRow` في شاشات الملف الشخصي والتجويد والفهم والهاشتاقات. `versionCode = 3`، `versionName = 1.2.1`.  
> **سبتمبر 2026 (زر «فتح الموقع»):** `openUrl` موحّدة ببدائل متصفحات صريحة + `<queries>` في Manifest — لا «تعذر فتح الرابط» مبهم بعد الآن.
> **سبتمبر 2026 (لوحة تحكم سحابية كاملة):** إغلاق أمني للوحة (`AdminGuard`)، تحكم بعيد (`AppRemoteConfig` → Firestore)، سجل تدقيق، إدارة مستخدمين متقدمة (فلترة/تأكيد/CSV)، إشعارات سحابية (`RemoteNotificationsManager`)، نسخ احتياطي واسترجاع فعلي (معاينة + تأكيد + دمج)، رسم إيرادات شهري.
> **سبتمبر 2026 (عدة التصميم الفاخرة — استوديو الصور + بطاقات الحديث):** 1) مكتبة خطوط عربية حقيقية مضمّنة (6 خطوط: Amiri، شهرزاد، عارف رقعة، ريم كوفي، القاهرة، تجوّل). 2) كتل نص متعددة (حتى 3 كتل) قابلة للسحب في المعاينة وموضعها يُحفظ في التصدير (DesignTextBlock). 3) أنماط نص بـ6 ألوان + 5 خلفيات (صورة/لون/تدرج/AI/ضبابية) + 6 فلاتر لونية + 8 زخارف رسمية مشتركة (Canvas موحّدة screen/تصدير). 4) تصدير JPEG/PNG بدقة HD/2K/4K. 5) في استوديو الحديث: اختيار خط ومحاذاة ودوران + زر نسخ الحديث + زر هاشتاقات SEO. ملف مشترك جديد: `DesignDesignKit.kt`. **تحقق البناء مُعلَّق** (لا JDK/SDK في هذه البيئة — يُختبر عبر Actions أو على جهاز مؤهَّل).
> شاشة المفاتيح اكتملت لكل الطبقة المجانية (Azure + ElevenLabs وبطاقة «يعمل بلا مفاتيح»).  
> **UI سبتمبر 2026:** انتقال أنيميشن بين الشاشات (`AnimatedContent` slide+fade)، مكوّنا `QabasCard`/`QabasSectionHeader` مع اعتمادها في المشاريع، وتغطية تحقق المفاتيح بالعداد — CI أخضر للدفعة (`7542c0e`).  
> **طبقة سحابية (سبتمبر 2026):** ربط Supabase الفعلي — `.env` بقيم حقيقية محلياً، تحقق قراءة/كتابة/حذف حي، `isConfigured` محصّن ضد placeholder، وCI جاهز لحقن السرّين (يعمل محلياً حتى بدونهما).  
> **تنظيف + إعادة توثيق (سبتمبر 2026):** جذر المشروع نظيف من سكربتات التصحيح وآثار التفكيك، `README.md` كوماتند حقيقي (نحو 180 سطراً بدل 408)، وإزالة مرجع الأقسام القديم من `AGENTS.md`.  
> **شاشة المفاتيح (سبتمبر 2026 — «ثورة شاشة المفاتيح»):** حصاد شبه آلي (التقاط/تحقق تلقائي من الحافظة عند اللصق أو التركيز + زرّا «التقاط من الحافظة 📋»/«افتح الموقع»)، شارة «🆓 بديل مجاني» على كل بطاقة بلا مفتاح، ولوح «فحص صحة المفاتيح الحي 🩺» لـ 7 خدمات بأزمنة استجابة حقيقية و«خطة إصلاح مقترحة» — 8 نقاط تحقق ملفوفة بـ `ApiUsageTracker` (انضمّت الست الباقية). **إصلاحات لاحقة:** أزرار «التقاط/افتح الموقع» خرجت من `trailingIcon` (كانت تُسحَق وتظهر كباركود غير قابل للضغط) إلى صفّ أسفل الحقل، ورسالة تحقق Gemini تكشف توكنات `AQ.…` (OAuth) بأنها ليست مفاتيح API قبل أي طلب شبكة.  
> **هوية الإنتاج AI (سبتمبر 2026):** ميزات تنافسية لمسار الاستوديو — خلفيات برمجية مولّدة `ProceduralBackdropEngine` (بلا إنترنت/مفاتيح/أصول، CI أخضر `1c8ba7d`)، نظام ألوان تقني **بنفسجي مهيمن** + إكسنت سيان (الذهبي محفوظ للمقدسات، `f5bcdec`)، وشاشة دخول بدمج اللوجو الرسمي `qabas_logo` مع الترحيب وتدرج العلامة بنفسجي/سيان (`bd00524`).  
> **بناء محلي مُتحقَّق + حارس الانهيارات (سبتمبر 2026):** `:app:assembleDebug` أخضر محلياً (Temurin 17 + SDK 36 في `~/tooling`) يثبت ربط كل الوظائف، و`QabasCrashGuard.kt` الجديد يلتقط أي انهيار JVM في أي Thread (ملف `crash_logs/` + لوحة المطور + إعادة إطلاق تلقائية محمية بمهلة 15ث للـ main thread).  
> **سجل الانهيارات + آخر البيانات المزيفة + سبلاش «الكتاب المفتوح» (سبتمبر 2026):** لوحة المطور تعرض `CrashLogsSection` حقيقية تقرأ `crash_logs/*.txt` مع تشخيص عربي ثلاثي (لماذا/كيف/الحل ← 10 فئات) وتحديث/مسح فعلي؛ حُذفت بذرة قرارات `StyleBrain` المزيفة من `QabasBrainRepository` (أصبحت `emptyList()`)، وعدّاد «المستخدمين» صار `devUsers.size` فعلياً من `Database.getAllUsers()`؛ والسبلاش أُعيدت كتابته كلياً بإنتاج Compose نقي: كتاب يفتح (rotationY + cameraDistance) بصفحات «۞» وآية الطمأنينة، ثم شعلة «✦ قَـبَـسْ» (Amiri) بحركة نابضة وتوهّج ذهبي وآية الدعوة، مع تخطي/لمسة/مهلة 4.5ث/ذوبان خروج بلا صوت — البناء أخضر (`2m19s`).  
> **قسم القرآن «المصحف الذهبي Hub» (سبتمبر 2026):** أعيدت هيكلة `QuranTajweedScreen.kt` من 4 تبويبات إلى **Hub من 5 أقسام** (المصحف الشريف 📖 / القراء والروايات 🎙️ / التفسير والمصادر 📚 / الأذكار 🤲 / أكاديمية التجويد 🎓 بشريط علوي)، مع حفظ دائم في `qabas_prefs` (آخر سورة مقروءة + بطاقة «متابعة القراءة» تعيد فتحها، القارئ/الرواية المختاران، علامة الفاصل 🔖)، و3 أقسام جديدة: 12 قارئاً حقيقياً بسيرهم + 10 روايات، و9 مصادر تفسيرية معتمدة بأسماء المؤلفين، وأذكار صباح/مساء بنصوص ثابتة وعدّاد يدوي — كلها بحالات صادقة «قيد التجهيز» بلا أي بيانات أو تلاوات مزيفة (زر صوت القارئ يعرض الإشعار الصادق بدل التبديل الوهمي). البناء أخضر (`2m9s`).  
> **النص القرآني الحقيقي + مقارنة المصحف الذهبي (سبتمبر 2026):** دُمج **نص حفص العثماني الكامل** (Tanzil — مطبوعات مجمع الملك فهد) مُنزَّلاً برمجياً من `fawazahmed0/quran-api` في `assets/quran/uthmani.json` (~1.97MB) — 114 سورة و6236 آية مُتحقق منها، بنية `{"quran":[...]}` تُطابق `parseQuranJson` بلا أي تعديل، و`GoldenMushafReaderView` صار يعرض الآيات الحقيقية (بسملة، خط Amiri، تجويد، تسميع، ريلز من الآية) بلا تغيير سطر عرض. **المقارنة:** عندنا النص+الأذكار+قوائم القراء/التفاسير+الأكاديمية؛ الفجوات الموثقة أمام المصحف الذهبي: تفسير/إعراب/بلاغة/معنى كلمة، استماع 40+ قارئاً، تتبع الختمة، أوقات الصلاة والقبلة. البناء أخضر.  
> **البحث النصي في القرآن + فتح الآية (سبتمبر 2026):** `searchVerses` على **6236 آية عثمانية** بمسارين (`contains`) بعد `normalizeArabicText` (تطبيع الهمزات أ/إ/آ/ٱ→ا و ى→ي و ة→ه وتجريد التشكيل/التطويل) مع **فكّ «ال»** («الكرسي» → آية الكرسي 2:255 عبر «كرسيه»)، وقسم «نتائج البحث في الآيات ✨» أعلى قائمة السور (بطاقات قابلة للنقر، فاصل «السور المطابقة»، حالة فارغة عند خلوّ الاثنين)، وفتح الآية في المصحف بتمرير تلقائي `LazyListState` + `animateScrollToItem` مع إزاحة البسملة وتظليل ذهبي `2.dp` + خلفية `#1E293B`، وحفظ `last_read_surah` وتصفير الهدف عند الخروج/فتح سورة عادية — البناء أخضر (1m28s).  
> **التفسير الميسر الحقيقي المدمج (سبتمبر 2026):** دُمج **نص التفسير الميسر** (مجمع الملك فهد) مُنزَّلاً برمجياً من `Nasaq-GP/Quran_tafsir` (6236 صفاً، لا حرف مكتوب يدوياً، 2:255 مطابق للمطبوع) في `assets/quran/tafsir_muyassar.json` (~2.68MB، غلاف 1:1..114:6 تام) — `loadTafsirFromAssets` كسول بفحص مسارين + `parseTafsirJson` يقرأ بنية `{s,a,t}` بدعم بدائل {surah,ayah,text} + `getTafsirForVerse` بمفتاح «سورة:آية» يُرجع null عند الغياب؛ `getVersesForSurah` و`searchVerses` تملآن `tafseer` الحقيقي بدل الثابت الفارغ؛ حوار التفسير يعرض النص الحقيقي والقطرة أصبحت صادقة «لا يتوفر تفسير ميسر لهذه الآية في النسخة المدمجة حالياً.»؛ وبطاقة الميسر في «التفسير والمصادر 📚» حملت شارة «مدمج ✓ متاح الآن في المصحف» (بقية المصادر قيد التجهيز بلا تفسير غير موثوق). البناء أخضر (1m22s).  
> **الأولوية القادمة:** اختبار التصدير على جهاز حقيقي — أي انهيار يظهر الآن يُسجَّل تلقائياً للتوثيق والإصلاح؛ والسبلاش الجديد يُتحقق بصرياً عند أول تشغيل؛ وقسم القرآن (Hub والنص العثماني والبحث بتظليلهم والتفسير الميسر) يُتحققون كما في القسم 4.  
> **ما بعد التحقق (مجدول في beads):** ختمة/تتبع الأحزاب ← أوقات الصلاة + القبلة ← تلاوة حقيقية 40+ قارئاً مع تشغيل خلفي (يتطلب foreground service + `ACCESS_FINE_LOCATION`) ← إعراب/معنى كلمة.
> **حلقة شمسية للتنقل (سبتمبر 2026):** استُبدل الشريط السفلي بلا أي حذف — `QabasSolarSystemNavigation.kt` جديد: 5 كواكب (الألوان/الترتيب الأصليان) تدور 8°/ث حول شعلة ذهبية بتثبيت الكوكب النشط عند 270° (الحركة عبر `graphicsLayer` فقط، تجميد عند غير RESUMED أو عند فتح الحوار، احترام `ANIMATOR_DURATION_SCALE`)، ونقرة كوكب → حوار تأكيد («الدخول إلى …؟»/«تأكيد ✓»/«إلغاء») → تنقّل، واهتزاز خفيف — `QabasBottomNavigation` باقٍ دون استخدام. أُصلح في البناء: compose.ui المحلول فعلياً 1.9.0 (تجاوز BOM بالصراع) فأُعيد توجيه الاهتزاز لـ `androidx.compose.ui.hapticfeedback.HapticFeedbackType` + `androidx.compose.ui.platform.LocalHapticFeedback`. البناء أخضر (1m12s).  
> **إزالة السبلاش + حياد الأيقونة (سبتمبر 2026):** بطلب المالك («ازله كليا») أُزيلت شاشة السبلاش كلياً — لا أثر لها في كود/موارد/ثيم (`MainActivity` يبدأ من `DATA_LOADING` بلا `installSplashScreen`، `AndroidManifest` theme ← `Theme.MyApplication`)، وحُذفت كل أصول اللوجو القديمة (`qabas_logo*.webp/xml/jpg`، `ic_qabas_*`، webps كثافات الـ launcher)، وحُيّدت أيقونة الـ launcher بمظهر ذهبي محايد (foreground neutral + fallback vector لـ API<26) وحدفت كتلة اللوجو من `LoginScreen.kt` — التحقق grep صفر. **الخطوة التالية:** استلام اللوجو الجديد (PNG ≥1024 بخلفية شفافة أو SVG) وتطبيقه في الأيقونة + لوجو شاشة التحميل `assets/logo/p*.txt`.
> **طبقة OpenAI + الأعلام فقط (سبتمبر 2026):** خيار محادثة حقيقي عبر `gpt-4o-mini` بأولوية **OpenAI → Groq → Gemini** (بطاقة مفتاح `sk-proj-…` في شاشة المفاتيح + تحقق + نسخ احتياطي + قياس `ApiUsageTracker` + إدخال «مفاتيح API 🔑» بلوحة المطور)، و`.env.example` بـ `your_key` — القاعدة الأمنية: لا مفتاح حقيقي في كود مقتَفَع (المفتاح في `.env` المحلي أو GitHub Secret). **صلاحيات البريد أُغلقت نهائياً:** admin/قريب/مطوّر = أعلام `qabas_prefs` فقط بعد إزالة كل الأنماط (`aly750834`/`aliwalead`/`xman88371`/`admin@…`/`family@…`/`friend@…`/`peeesa7`) من AccountService/LoginScreen/AuthScreens/SettingsScreen/RequestChatScreen/LeagueService؛ `is_developer` يُكتب الآن فقط لمالك الحساب عند التسجيل/الدخول (`CloudServices.isOwnerAccount`) — لا يُمنح وضع المطور للمستخدمين العاديين، والأجهزة القديمة المخوَّلة تحتفظ بعلامتها المكتوبة. التحقق grep صفر لبقايا الأنماط.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:46cd31e7 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
## Beads Issue Tracker

Use Beads (`bd`) for durable task tracking in repositories that include it. Use the `beads` skill at `.agents/skills/beads/SKILL.md` (project install) or `~/.agents/skills/beads/SKILL.md` (global install) for Beads workflow guidance, then use the `bd` CLI for issue operations.

### Quick Reference

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work
bd close <id>           # Complete work
bd prime                # Refresh Beads context
```

### Rules

- Use `bd` for all task tracking; do not create markdown TODO lists.
- Run `bd prime` when Beads context is missing or stale. Codex 0.129.0+ can load Beads context automatically through native hooks; use `/hooks` to inspect or toggle them.
- Keep persistent project memory in Beads via `bd remember`; do not create ad hoc memory files.

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->
