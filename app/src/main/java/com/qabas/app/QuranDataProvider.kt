package com.qabas.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * RawAyah
 * نموذج بيانات يمثل الآية القرآنية المستخرجة من ملفات البيانات
 */
data class RawAyah(
    val surah: Int,
    val ayah: Int,
    val text: String
)

/**
 * QuranDataProvider.kt
 * مزود بيانات القرآن الكريم وأكاديمية التجويد الشاملة في تطبيق قبس (Qabas).
 * يحتوي على القائمة الكاملة لكافة سور القرآن الكريم الـ 114 مع معلوماتها التوثيقية،
 * ونظام استرجاع الآيات الأصيلة والأحكام التجويدية.
 */

object QuranDataProvider {

    private const val TAG = "QuranDataProvider"

    // ذاكرة التخزين المؤقت للآيات المحملة مقسمة حسب رقم السورة
    @Volatile
    private var versesMap: Map<Int, List<RawAyah>>? = null

    // ذاكرة التخزين المؤقت للتفسير الميسر بمفتاح "سورة:آية"
    @Volatile
    private var tafsirMap: Map<String, String>? = null

    /**
     * تحميل آمن من assets للتفسير الميسر (مجمع الملك فهد) المدمج محلياً
     * من ملف "quran/tafsir_muyassar.json". يُعاد التحميل مرة واحدة فقط.
     */
    fun loadTafsirFromAssets(context: Context): Boolean {
        if (tafsirMap != null && tafsirMap!!.isNotEmpty()) {
            return true
        }
        val assetPaths = listOf("quran/tafsir_muyassar.json", "tafsir_muyassar.json")
        for (path in assetPaths) {
            try {
                val jsonString = context.assets.open(path).use { inputStream ->
                    inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                if (jsonString.isNotBlank()) {
                    val parsed = parseTafsirJson(jsonString)
                    if (parsed.isNotEmpty()) {
                        tafsirMap = parsed
                        Log.i(TAG, "Successfully loaded ${parsed.size} tafsir entries from asset: $path")
                        return true
                    }
                }
            } catch (_: java.io.FileNotFoundException) {
                // الملف غير موجود في هذا المسار، يتم فحص المسار التالي
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading $path from assets: ${e.message}")
            }
        }
        return false
    }

    /**
     * تحليل مرن للـ JSON للتفسير الميسر: يقرأ مصفوفة "tafsir" ببنية
     * {"s","a","t"} مع دعم بدائل {"surah","ayah","text"} في ملفات أخرى مستقبلاً.
     */
    private fun parseTafsirJson(jsonStr: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        try {
            val trimmed = jsonStr.trim()
            if (!trimmed.startsWith("{")) return result
            val root = JSONObject(trimmed)
            val array = root.optJSONArray("tafsir")
                ?: root.optJSONArray("verses")
                ?: root.optJSONArray("ayahs")
                ?: root.optJSONArray("data")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val surah = obj.optInt("surah", obj.optInt("surah_number", obj.optInt("chapter", obj.optInt("s", 0))))
                    val ayah = obj.optInt("ayah", obj.optInt("verse", obj.optInt("ayah_number", obj.optInt("a", 0))))
                    val text = obj.optString("text", obj.optString("arabic_tafsir", obj.optString("tafsir", obj.optString("t", ""))))
                    if (surah > 0 && ayah > 0 && text.isNotBlank()) {
                        result["$surah:$ayah"] = text
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing tafsir JSON: ${e.message}")
        }
        return result
    }

    /**
     * استرجاع نص التفسير الميسر لآية محددة. يُرجع null عند غياب التحميل
     * أو عدم وجود الآية في النسخة المدمجة — لا تفسير ملفّق.
     */
    fun getTafsirForVerse(surahId: Int, ayah: Int): String? =
        tafsirMap?.get("$surahId:$ayah")

    private val remoteTafsirCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** ملاذ شبكي صادق: تفسير الميسر عربي من spa5k/tafsir_api عبر CDN (بلا مفتاح). null عند الفشل */
    suspend fun fetchRemoteTafsir(surahId: Int, ayah: Int): String? {
        val key = "$surahId:$ayah"
        tafsirMap?.get(key)?.let { return it }
        remoteTafsirCache[key]?.let { return it }
        return try {
            val url = "https://cdn.jsdelivr.net/gh/spa5k/tafsir_api@main/tafsir/ar-muyassar/$surahId/$ayah.json"
            val req = okhttp3.Request.Builder().url(url).build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()
            val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(req).execute().use { it.body?.string() ?: "" }
            }
            if (body.isBlank()) return null
            val root = JSONObject(body)
            val text = root.optString("text", root.optString("tafsir", ""))
            if (text.isBlank()) null else {
                remoteTafsirCache[key] = text
                val mutable = (tafsirMap ?: emptyMap()).toMutableMap()
                mutable[key] = text
                tafsirMap = mutable
                text
            }
        } catch (_: Exception) { null }
    }

    /**
     * دالة تحميل آمنة من assets للقرآن الكريم كاملاً بالرسم العثماني.
     * تفحص المسارات المحتملة: "quran/uthmani.json" ثم "quran/quran.json"
     */
    fun loadFromAssets(context: Context): Boolean {
        if (versesMap != null && versesMap!!.isNotEmpty()) {
            return true
        }

        val assetPaths = listOf("quran/uthmani.json", "quran/quran.json", "uthmani.json", "quran.json")
        for (path in assetPaths) {
            try {
                val jsonString = context.assets.open(path).use { inputStream ->
                    inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                if (jsonString.isNotBlank()) {
                    val parsed = parseQuranJson(jsonString)
                    if (parsed.isNotEmpty()) {
                        versesMap = parsed.groupBy { it.surah }
                        Log.i(TAG, "Successfully loaded ${parsed.size} ayahs from asset: $path")
                        return true
                    }
                }
            } catch (_: java.io.FileNotFoundException) {
                // الملف غير موجود في هذا المسار، يتم فحص المسار التالي
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading $path from assets: ${e.message}")
            }
        }
        return false
    }

    /**
     * تحليل مرن لمحتوى الـ JSON لدعم الهياكل المختلفة (مصفوفة آيات أو كائن مجمع)
     */
    private fun parseQuranJson(jsonStr: String): List<RawAyah> {
        val result = mutableListOf<RawAyah>()
        try {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val surah = obj.optInt("surah", obj.optInt("surah_number", obj.optInt("chapter", obj.optInt("s", 0))))
                    val ayah = obj.optInt("ayah", obj.optInt("verse", obj.optInt("ayah_number", obj.optInt("a", 0))))
                    val text = obj.optString("text", obj.optString("verse_text", obj.optString("uthmani", obj.optString("ar", ""))))
                    if (surah > 0 && ayah > 0 && text.isNotBlank()) {
                        result.add(RawAyah(surah, ayah, text))
                    }
                }
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val array = root.optJSONArray("verses")
                    ?: root.optJSONArray("ayahs")
                    ?: root.optJSONArray("quran")
                    ?: root.optJSONArray("data")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val surah = obj.optInt("surah", obj.optInt("surah_number", obj.optInt("chapter", obj.optInt("s", 0))))
                        val ayah = obj.optInt("ayah", obj.optInt("verse", obj.optInt("ayah_number", obj.optInt("a", 0))))
                        val text = obj.optString("text", obj.optString("verse_text", obj.optString("uthmani", obj.optString("ar", ""))))
                        if (surah > 0 && ayah > 0 && text.isNotBlank()) {
                            result.add(RawAyah(surah, ayah, text))
                        }
                    }
                } else if (root.has("surahs")) {
                    val surahsArray = root.optJSONArray("surahs")
                    if (surahsArray != null) {
                        for (i in 0 until surahsArray.length()) {
                            val sObj = surahsArray.optJSONObject(i) ?: continue
                            val sId = sObj.optInt("id", sObj.optInt("number", i + 1))
                            val vArray = sObj.optJSONArray("verses") ?: sObj.optJSONArray("ayahs")
                            if (vArray != null) {
                                for (j in 0 until vArray.length()) {
                                    val vObj = vArray.optJSONObject(j)
                                    if (vObj != null) {
                                        val ayahNum = vObj.optInt("id", vObj.optInt("number", j + 1))
                                        val text = vObj.optString("text", vObj.optString("uthmani", ""))
                                        if (text.isNotBlank()) {
                                            result.add(RawAyah(sId, ayahNum, text))
                                        }
                                    } else {
                                        val text = vArray.optString(j, "")
                                        if (text.isNotBlank()) {
                                            result.add(RawAyah(sId, j + 1, text))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Quran JSON: ${e.message}")
        }
        return result
    }

    /**
     * نص آية محددة من الذاكرة المحملة (يُستخدم في قارئ الصفحات).
     * يُرجع null عند غياب التحميل — لا نص ملفّق.
     */
    fun getVerseText(surahId: Int, ayah: Int): String? =
        versesMap?.get(surahId)?.firstOrNull { it.ayah == ayah }?.text

    /** اسم السورة علناً (يُستخدم في قارئ الصفحات). */
    fun surahNameOf(surahId: Int): String = getSurahName(surahId)

    /**
     * قائمة الـ 114 سورة كاملة موثقة بالترتيب والتفاصيل
     */
    val surahs: List<QuranSurahItem> by lazy {
        listOf(
            QuranSurahItem(1, "الفاتحة", "Al-Fatiha", "مكية", 7, 1, isLocked = false, isMastered = true, score = 98, "أحكام المد الطبيعي والنون الساكنة", listOf("الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ ﴿٢﴾", "إِيَّاكَ نَعْبُدُ وَإِيَّاكَ نَسْتَعِينُ ﴿٥﴾", "اهْدِنَا الصِّرَاطَ الْمُسْتَقِيمَ ﴿٦﴾")),
            QuranSurahItem(2, "البقرة", "Al-Baqarah", "مدنية", 286, 1, isLocked = false, isMastered = false, score = 92, "أحكام الميم الساكنة والإدغام بغنة", listOf("اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ ﴿٢٥٥﴾", "آمَنَ الرَّسُولُ بِمَا أُنزِلَ إِلَيْهِ مِن رَّبِّهِ ﴿٢٨٥﴾", "لَا يُكَلِّفُ اللَّهُ نَفْسًا إِلَّا وُسْعَهَا ﴿٢٨٦﴾")),
            QuranSurahItem(3, "آل عمران", "Ali 'Imran", "مدنية", 200, 3, isLocked = false, isMastered = false, score = 0, "المد المتصل والمنفصل", listOf("رَبَّنَا لَا تُزِغْ قُلُوبَنَا بَعْدَ إِذْ هَدَيْتَنَا ﴿٨﴾", "قُلْ إِن كُنتُمْ تُحِبُّونَ اللَّهَ فَاتَّبِعُونِي ﴿٣١﴾")),
            QuranSurahItem(4, "النساء", "An-Nisa", "مدنية", 176, 4, isLocked = false, isMastered = false, score = 0, "أحكام الوصل والوقف والمدود", listOf("يَا أَيُّهَا النَّاسُ اتَّقُوا رَبَّكُمُ ﴿١﴾", "وَاعْبُدُوا اللَّهَ وَلَا تُشْرِكُوا بِهِ شَيْئًا ﴿٣٦﴾")),
            QuranSurahItem(5, "المائدة", "Al-Ma'idah", "مدنية", 120, 6, isLocked = false, isMastered = false, score = 0, "أحكام الإظهار والترقيق", listOf("يَا أَيُّهَا الَّذِينَ آمَنُوا أَوْفُوا بِالْعُقُودِ ﴿١﴾", "الْيَوْمَ أَكْمَلْتُ لَكُمْ دِينَكُمْ ﴿٣﴾")),
            QuranSurahItem(6, "الأنعام", "Al-An'am", "مكية", 165, 7, isLocked = false, isMastered = false, score = 0, "أحكام القلقلة وتفخيم اللام", listOf("الْحَمْدُ لِلَّهِ الَّذِي خَلَقَ السَّمَاوَاتِ وَالْأَرْضَ ﴿١﴾", "قُلْ إِنَّ صَلَاتِي وَنُسُكِي وَمَحْيَايَ وَمَمَاتِي لِلَّهِ ﴿١٦٢﴾")),
            QuranSurahItem(7, "الأعراف", "Al-A'raf", "مكية", 206, 8, isLocked = false, isMastered = false, score = 0, "أحكام السجدات والمد اللازم", listOf("المص ﴿١﴾ كِتَابٌ أُنزِلَ إِلَيْكَ ﴿٢﴾", "إِنَّ رَبَّكُمُ اللَّهُ الَّذِي خَلَقَ السَّمَاوَاتِ وَالْأَرْضَ ﴿٥٤﴾")),
            QuranSurahItem(8, "الأنفال", "Al-Anfal", "مدنية", 75, 9, isLocked = false, isMastered = false, score = 0, "أحكام التقاء الساكنين والغنة", listOf("إِنَّمَا الْمُؤْمِنُونَ الَّذِينَ إِذَا ذُكِرَ اللَّهُ وَجِلَتْ قُلُوبُهُمْ ﴿٢﴾")),
            QuranSurahItem(9, "التوبة", "At-Tawbah", "مدنية", 129, 10, isLocked = false, isMastered = false, score = 0, "أحكام ترك البسملة والاستعاذة", listOf("بَرَاءَةٌ مِّنَ اللَّهِ وَرَسُولِهِ ﴿١﴾", "لَقَدْ جَاءَكُمْ رَسُولٌ مِّنْ أَنفُسِكُمْ عَزِيزٌ عَلَيْهِ مَا عَنِتُّمْ ﴿١٢٨﴾")),
            QuranSurahItem(10, "يونس", "Yunus", "مكية", 109, 11, isLocked = false, isMastered = false, score = 0, "الحروف المقطعة وأحكام الراء", listOf("الر ۚ تِلْكَ آيَاتُ الْكِتَابِ الْحَكِيمِ ﴿١﴾", "أَلَا إِنَّ أَوْلِيَاءَ اللَّهِ لَا خَوْفٌ عَلَيْهِمْ وَلَا هُمْ يَحْزَنُونَ ﴿٦٢﴾")),
            QuranSurahItem(11, "هود", "Hud", "مكية", 123, 11, isLocked = false, isMastered = false, score = 0, "أحكام الإمالة في (مَجْرَاهَا)", listOf("وَقَالَ ارْكَبُوا فِيهَا بِسْمِ اللَّهِ مَجْرَاهَا وَمُرْسَاهَا ﴿٤١﴾", "فَاسْتَقِمْ كَمَا أُمِرْتَ ﴿١١٢﴾")),
            QuranSurahItem(12, "يوسف", "Yusuf", "مكية", 111, 12, isLocked = false, isMastered = false, score = 0, "أحكام الإشمام والروم في (لَا تَأْمَنَّا)", listOf("إِذْ قَالَ يُوسُفُ لِأَبِيهِ يَا أَبَتِ إِنِّي رَأَيْتُ أَحَدَ عَشَرَ كَوْكَبًا ﴿٤﴾", "قَالُوا يَا أَبَانَا مَا لَكَ لَا تَأْمَنَّا عَلَىٰ يُوسُفَ ﴿١١﴾")),
            QuranSurahItem(13, "الرعد", "Ar-Ra'd", "مدنية", 43, 13, isLocked = false, isMastered = false, score = 0, "أحكام تفخيم الراء وقلقلة الدال", listOf("الَّذِينَ آمَنُوا وَتَطْمَئِنُّ قُلُوبُهُم بِذِكْرِ اللَّهِ ۗ أَلَا بِذِكْرِ اللَّهِ تَطْمَئِنُّ الْقُلُوبُ ﴿٢٨﴾")),
            QuranSurahItem(14, "إبراهيم", "Ibrahim", "مكية", 52, 13, isLocked = false, isMastered = false, score = 0, "المدود والوقف التام", listOf("وَإِذْ تَأَذَّنَ رَبُّكُمْ لَئِن شَكَرْتُمْ لَأَزِيدَنَّكُمْ ﴿٧﴾")),
            QuranSurahItem(15, "الحجر", "Al-Hijr", "مكية", 99, 14, isLocked = false, isMastered = false, score = 0, "الإظهار المطلق والغنة", listOf("إِنَّا نَحْنُ نَزَّلْنَا الذِّكْرَ وَإِنَّا لَهُ لَحَافِظُونَ ﴿٩﴾")),
            QuranSurahItem(16, "النحل", "An-Nahl", "مكية", 128, 14, isLocked = false, isMastered = false, score = 0, "أحكام الاستعاذة والنبر", listOf("ادْعُ إِلَىٰ سَبِيلِ رَبِّكَ بِالْحِكْمَةِ وَالْمَوْعِظَةِ الْحَسَنَةِ ﴿١٢٥﴾")),
            QuranSurahItem(17, "الإسراء", "Al-Isra", "مكية", 111, 15, isLocked = false, isMastered = false, score = 0, "أحكام الوقف الكافي والتفخيم", listOf("سُبْحَانَ الَّذِي أَسْرَىٰ بِعَبْدِهِ لَيْلًا مِّنَ الْمَسْجِدِ الْحَرَامِ ﴿١﴾", "وَقَضَىٰ رَبُّكَ أَلَّا تَعْبُدُوا إِلَّا إِيَّاهُ وَبِالْوَالِدَيْنِ إِحْسَانًا ﴿٢٣﴾")),
            QuranSurahItem(18, "الكهف", "Al-Kahf", "مكية", 110, 15, isLocked = false, isMastered = false, score = 90, "السكتات اللطيفة والإخفاء الشفوي", listOf("الْحَمْدُ لِلَّهِ الَّذِي أَنزَلَ عَلَىٰ عَبْدِهِ الْكِتَابَ وَلَمْ يَجْعَل لَّهُ عِوَجًا ﴿١﴾", "إِنَّ الَّذِينَ آمَنُوا وَعَمِلُوا الصَّالِحَاتِ كَانَتْ لَهُمْ جَنَّاتُ الْفِرْدَوْسِ نُزُلًا ﴿١٠٧﴾", "قُل لَّوْ كَانَ الْبَحْرُ مِدَادًا لِّكَلِمَاتِ رَبِّي لَنَفِدَ الْبَحْرُ ﴿١٠٩﴾")),
            QuranSurahItem(19, "مريم", "Maryam", "مكية", 98, 16, isLocked = false, isMastered = false, score = 0, "المد اللازم الحرفي في (كهيعص)", listOf("كهيعص ﴿١﴾ ذِكْرُ رَحْمَتِ رَبِّكَ عَبْدَهُ زَكَرِيَّا ﴿٢﴾")),
            QuranSurahItem(20, "طه", "Ta-Ha", "مكية", 135, 16, isLocked = false, isMastered = false, score = 0, "أحكام الفواتح والإمالة الصغرى", listOf("طه ﴿١﴾ مَا أَنزَلْنَا عَلَيْكَ الْقُرْآنَ لِتَشْقَىٰ ﴿٢﴾", "قَالَ رَبِّ اشْرَحْ لِي صَدْرِي ﴿٢٥﴾")),
            QuranSurahItem(21, "الأنبياء", "Al-Anbiya", "مكية", 112, 17, isLocked = false, isMastered = false, score = 0, "أحكام القلقلة والإظهار", listOf("لَّا إِلَٰهَ إِلَّا أَنتَ سُبْحَانَكَ إِنِّي كُنتُ مِنَ الظَّالِمِينَ ﴿٨٧﴾", "وَمَا أَرْسَلْنَاكَ إِلَّا رَحْمَةً لِّلْعَالَمِينَ ﴿١٠٧﴾")),
            QuranSurahItem(22, "الحج", "Al-Hajj", "مدنية", 78, 17, isLocked = false, isMastered = false, score = 0, "سجدات التلاوة وأحكام المد", listOf("يَا أَيُّهَا النَّاسُ اتَّقُوا رَبَّكُمْ ۚ إِنَّ زَلْزَلَةَ السَّاعَةِ شَيْءٌ عَظِيمٌ ﴿١﴾")),
            QuranSurahItem(23, "المؤمنون", "Al-Mu'minun", "مكية", 118, 18, isLocked = false, isMastered = false, score = 0, "ترقيق الميم والغنة", listOf("قَدْ أَفْلَحَ الْمُؤْمِنُونَ ﴿١﴾ الَّذِينَ هُمْ فِي صَلَاتِهِمْ خَاشِعُونَ ﴿٢﴾")),
            QuranSurahItem(24, "النور", "An-Nur", "مدنية", 64, 18, isLocked = false, isMastered = false, score = 0, "آية النور وأحكام المد المتصل", listOf("اللَّهُ نُورُ السَّمَاوَاتِ وَالْأَرْضِ ۚ مَثَلُ نُورِهِ كَمِشْكَاةٍ فِيهَا مِصْبَاحٌ ﴿٣٥﴾")),
            QuranSurahItem(25, "الفرقان", "Al-Furqan", "مكية", 77, 18, isLocked = false, isMastered = false, score = 0, "صفات عباد الرحمن والتفخيم", listOf("تَبَارَكَ الَّذِي نَزَّلَ الْفُرْقَانَ عَلَىٰ عَبْدِهِ لِيَكُونَ لِلْعَالَمِينَ نَذِيرًا ﴿١﴾", "وَعِبَادُ الرَّحْمَٰنِ الَّذِينَ يَمْشُونَ عَلَى الْأَرْضِ هَوْنًا ﴿٦٣﴾")),
            QuranSurahItem(26, "الشعراء", "Ash-Shu'ara", "مكية", 227, 19, isLocked = false, isMastered = false, score = 0, "الفواتح الحرفية والمدود", listOf("طسم ﴿١﴾ تِلْكَ آيَاتُ الْكِتَابِ الْمُبِينِ ﴿٢﴾", "وَإِذَا مَرِضْتُ فَهُوَ يَشْفِينِ ﴿٨٠﴾")),
            QuranSurahItem(27, "النمل", "An-Naml", "مكية", 93, 19, isLocked = false, isMastered = false, score = 0, "البسملة وسط السورة", listOf("إِنَّهُ مِن سُلَيْمَانَ وَإِنَّهُ بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ ﴿٣٠﴾")),
            QuranSurahItem(28, "القصص", "Al-Qasas", "مكية", 88, 20, isLocked = false, isMastered = false, score = 0, "أحكام التنوين والوقف", listOf("رَبِّ إِنِّي لِمَا أَنزَلْتَ إِلَيَّ مِنْ خَيْرٍ فَقِيرٌ ﴿٢٤﴾")),
            QuranSurahItem(29, "العنكبوت", "Al-'Ankabut", "مكية", 69, 20, isLocked = false, isMastered = false, score = 0, "أحكام الهمزات والتفخيم", listOf("الم ﴿١﴾ أَحَسِبَ النَّاسُ أَن يُتْرَكُوا أَن يَقُولُوا آمَنَّا وَهُمْ لَا يُفْتَنُونَ ﴿٢﴾")),
            QuranSurahItem(30, "الروم", "Ar-Rum", "مكية", 60, 21, isLocked = false, isMastered = false, score = 0, "تفخيم الراء وترقيقها", listOf("وَمِنْ آيَاتِهِ أَنْ خَلَقَ لَكُم مِّنْ أَنفُسِكُمْ أَزْوَاجًا لِّتَسْكُنُوا إِلَيْهَا ﴿٢١﴾")),
            QuranSurahItem(31, "لقمان", "Luqman", "مكية", 34, 21, isLocked = false, isMastered = false, score = 0, "وصايا لقمان والمد العارض", listOf("يَا بُنَيَّ أَقِمِ الصَّلَاةَ وَأْمُرْ بِالْمَعْرُوفِ وَانْهَ عَنِ الْمُنكَرِ ﴿١٧﴾")),
            QuranSurahItem(32, "السجدة", "As-Sajdah", "مكية", 30, 21, isLocked = false, isMastered = false, score = 0, "سجدة التلاوة والمد اللازم", listOf("تَتَجَافَىٰ جُنُوبُهُمْ عَنِ الْمَضَاجِعِ يَدْعُونَ رَبَّهُمْ خَوْفًا وَطَمَعًا ﴿١٦﴾")),
            QuranSurahItem(33, "الأحزاب", "Al-Ahzab", "مدنية", 73, 21, isLocked = false, isMastered = false, score = 0, "أحكام الصلاة على النبي ﷺ والمد", listOf("إِنَّ اللَّهَ وَمَلَائِكَتَهُ يُصَلُّونَ عَلَى النَّبِيِّ ۚ يَا أَيُّهَا الَّذِينَ آمَنُوا صَلُّوا عَلَيْهِ وَسَلِّمُوا تَسْلِيمًا ﴿٥٦﴾")),
            QuranSurahItem(34, "سبأ", "Saba", "مكية", 54, 22, isLocked = false, isMastered = false, score = 0, "أحكام الإظهار والترقيق", listOf("الْحَمْدُ لِلَّهِ الَّذِي لَهُ مَا فِي السَّمَاوَاتِ وَمَا فِي الْأَرْضِ ﴿١﴾")),
            QuranSurahItem(35, "فاطر", "Fatir", "مكية", 45, 22, isLocked = false, isMastered = false, score = 0, "صفات الحروف والمدود", listOf("يَا أَيُّهَا النَّاسُ أَنتُمُ الْفُقَرَاءُ إِلَى اللَّهِ ۖ وَاللَّهُ هُوَ الْغَنِيُّ الْحَمِيدُ ﴿١٥﴾")),
            QuranSurahItem(36, "يس", "Ya-Sin", "مكية", 83, 22, isLocked = false, isMastered = false, score = 88, "أحكام الإظهار المطلق والغنة", listOf("يس ﴿١﴾ وَالْقُرْآنِ الْحَكِيمِ ﴿٢﴾ إِنَّكَ لَمِنَ الْمُرْسَلِينَ ﴿٣﴾", "إِنَّمَا أَمْرُهُ إِذَا أَرَادَ شَيْئًا أَن يَقُولَ لَهُ كُن فَيَكُونُ ﴿٨٢﴾")),
            QuranSurahItem(37, "الصافات", "As-Saffat", "مكية", 182, 23, isLocked = false, isMastered = false, score = 0, "المد اللازم الكلمي المثقل", listOf("وَالصَّافَّاتِ صَفًّا ﴿١﴾ فَالزَّاجِرَاتِ زَجْرًا ﴿٢﴾")),
            QuranSurahItem(38, "ص", "Sad", "مكية", 88, 23, isLocked = false, isMastered = false, score = 0, "حرف الصاد وتفخيمه والاستعلاء", listOf("ص ۚ وَالْقُرْآنِ ذِي الذِّكْرِ ﴿١﴾", "كِتَابٌ أَنزَلْنَاهُ إِلَيْكَ مُبَارَكٌ لِّيَدَّبَّرُوا آيَاتِهِ ﴿٢٩﴾")),
            QuranSurahItem(39, "الزمر", "Az-Zumar", "مكية", 75, 23, isLocked = false, isMastered = false, score = 0, "أحكام الرجاء والاستغفار", listOf("قُلْ يَا عِبَادِيَ الَّذِينَ أَسْرَفُوا عَلَىٰ أَنفُسِهِمْ لَا تَقْنَطُوا مِن رَّحْمَةِ اللَّهِ ﴿٥٣﴾")),
            QuranSurahItem(40, "غافر", "Ghafir", "مكية", 85, 24, isLocked = false, isMastered = false, score = 0, "الحواميم والمد الحرفي (حم)", listOf("حم ﴿١﴾ تَنزِيلُ الْكِتَابِ مِنَ اللَّهِ الْعَزِيزِ الْعَلِيمِ ﴿٢﴾", "وَقَالَ رَبُّكُمُ ادْعُونِي أَسْتَجِبْ لَكُمْ ﴿٦٠﴾")),
            QuranSurahItem(41, "فصلت", "Fussilat", "مكية", 54, 24, isLocked = false, isMastered = false, score = 0, "تسهيل الهمزة في (أَأَعْجَمِيٌّ)", listOf("إِنَّ الَّذِينَ قَالُوا رَبُّنَا اللَّهُ ثُمَّ اسْتَقَامُوا تَتَنَزَّلُ عَلَيْهِمُ الْمَلَائِكَةُ ﴿٣٠﴾", "وَلَوْ جَعَلْنَاهُ قُرْآنًا أَعْجَمِيًّا لَّقَالُوا لَوْلَا فُصِّلَتْ آيَاتُهُ ۖ أَأَعْجَمِيٌّ وَعَرَبِيٌّ ﴿٤٤﴾")),
            QuranSurahItem(42, "الشورى", "Ash-Shura", "مكية", 53, 25, isLocked = false, isMastered = false, score = 0, "مد حرف (عسق) 4 أو 6 حركات", listOf("حم ﴿١﴾ عسق ﴿٢﴾ كَذَٰلِكَ يُوحِي إِلَيْكَ وَإِلَى الَّذِينَ مِن قَبْلِكَ اللَّهُ الْعَزِيزُ الْحَكِيمُ ﴿٣﴾")),
            QuranSurahItem(43, "الزخرف", "Az-Zukhruf", "مكية", 89, 25, isLocked = false, isMastered = false, score = 0, "أحكام الوصل والفصل", listOf("حم ﴿١﴾ وَالْكِتَابِ الْمُبِينِ ﴿٢﴾ إِنَّا جَعَلْنَاهُ قُرْآنًا عَرَبِيًّا لَّعَلَّكُمْ تَعْقِلُونَ ﴿٣﴾")),
            QuranSurahItem(44, "الدخان", "Ad-Dukhan", "مكية", 59, 25, isLocked = false, isMastered = false, score = 0, "ليلة القدر والغنة", listOf("حم ﴿١﴾ وَالْكِتَابِ الْمُبِينِ ﴿٢﴾ إِنَّا أَنزَلْنَاهُ فِي لَيْلَةٍ مُّبَارَكَةٍ ﴿٣﴾")),
            QuranSurahItem(45, "الجاثية", "Al-Jathiyah", "مكية", 37, 25, isLocked = false, isMastered = false, score = 0, "أحكام التنوين والوقف", listOf("حم ﴿١﴾ تَنزِيلُ الْكِتَابِ مِنَ اللَّهِ الْعَزِيزِ الْحَكِيمِ ﴿٢﴾")),
            QuranSurahItem(46, "الأحقاف", "Al-Ahqaf", "مكية", 35, 26, isLocked = false, isMastered = false, score = 0, "بر الوالدين وأحكام الإخفاء", listOf("وَوَصَّيْنَا الْإِنسَانَ بِوَالِدَيْهِ إِحْسَانًا ﴿١٥﴾")),
            QuranSurahItem(47, "محمد", "Muhammad", "مدنية", 38, 26, isLocked = false, isMastered = false, score = 0, "أحكام الغنة والهمس", listOf("الَّذِينَ كَفَرُوا وَصَدُّوا عَن سَبِيلِ اللَّهِ أَضَلَّ أَعْمَالَهُمْ ﴿١﴾")),
            QuranSurahItem(48, "الفتح", "Al-Fath", "مدنية", 29, 26, isLocked = false, isMastered = false, score = 0, "ضم الهاء في (عَلَيْهُ اللَّهَ)", listOf("إِنَّا فَتَحْنَا لَكَ فَتْحًا مُّبِينًا ﴿١﴾", "مُّحَمَّدٌ رَّسُولُ اللَّهِ ۚ وَالَّذِينَ مَعَهُ أَشِدَّاءُ عَلَى الْكُفَّارِ رُحَمَاءُ بَيْنَهُمْ ﴿٢٩﴾")),
            QuranSurahItem(49, "الحجرات", "Al-Hujurat", "مدنية", 18, 26, isLocked = false, isMastered = false, score = 0, "أخلاق المؤمنين وأحكام النداء", listOf("يَا أَيُّهَا الَّذِينَ آمَنُوا إِن جَاءَكُمْ فَاسِقٌ بِنَبَإٍ فَتَبَيَّنُوا ﴿٦﴾", "إِنَّمَا الْمُؤْمِنُونَ إِخْوَةٌ ﴿١٠﴾")),
            QuranSurahItem(50, "ق", "Qaf", "مكية", 45, 26, isLocked = false, isMastered = false, score = 0, "حرف القاف وصفة القلقلة والاستعلاء", listOf("ق ۚ وَالْقُرْآنِ الْمَجِيدِ ﴿١﴾", "وَلَقَدْ خَلَقْنَا الْإِنسَانَ وَنَعْلَمُ مَا تُوَسْوِسُ بِهِ نَفْسُهُ ﴿١٦﴾")),
            QuranSurahItem(51, "الذاريات", "Adh-Dhariyat", "مكية", 60, 26, isLocked = false, isMastered = false, score = 0, "القسم والمد العارض", listOf("وَمَا خَلَقْتُ الْجِنَّ وَالْإِنسَ إِلَّا لِيَعْبُدُونِ ﴿٥٦﴾")),
            QuranSurahItem(52, "الطور", "At-Tur", "مكية", 49, 27, isLocked = false, isMastered = false, score = 0, "الهمس في التاء والطاء", listOf("وَالطُّورِ ﴿١﴾ وَكِتَابٍ مَّسْطُورٍ ﴿٢﴾")),
            QuranSurahItem(53, "النجم", "An-Najm", "مكية", 62, 27, isLocked = false, isMastered = false, score = 0, "سجدة النجم والمدود", listOf("وَالنَّجْمِ إِذَا هَوَىٰ ﴿١﴾ مَا ضَلَّ صَاحِبُكُمْ وَمَا غَوَىٰ ﴿٢﴾", "وَأَن لَّيْسَ لِلْإِنسَانِ إِلَّا مَا سَعَىٰ ﴿٣٩﴾")),
            QuranSurahItem(54, "القمر", "Al-Qamar", "مكية", 55, 27, isLocked = false, isMastered = false, score = 0, "ترقيق الراء في (مُسْتَمِرّ)", listOf("اقْتَرَبَتِ السَّاعَةُ وَانشَقَّ الْقَمَرُ ﴿١﴾", "وَلَقَدْ يَسَّرْنَا الْقُرْآنَ لِلذِّكْرِ فَهَلْ مِن مُّدَّكِرٍ ﴿١٧﴾")),
            QuranSurahItem(55, "الرحمن", "Ar-Rahman", "مدنية", 78, 27, isLocked = false, isMastered = false, score = 94, "نبر الآيات والمد العارض للسكون", listOf("الرَّحْمَٰنُ ﴿١﴾ عَلَّمَ الْقُرْآنَ ﴿٢﴾ خَلَقَ الْإِنسَانَ ﴿٣﴾", "فَبِأَيِّ آلَاءِ رَبِّكُمَا تُكَذِّبَانِ ﴿١٣﴾", "كُلُّ مَنْ عَلَيْهَا فَانٍ ﴿٢٦﴾")),
            QuranSurahItem(56, "الواقعة", "Al-Waqi'ah", "مكية", 96, 27, isLocked = false, isMastered = false, score = 0, "أحكام القلقلة والتفخيم والترقيق", listOf("إِذَا وَقعَتِ الْوَاقِعَةُ ﴿١﴾", "وَالسَّابِقُونَ السَّابِقُونَ ﴿١٠﴾ أُولَٰئِكَ الْمُقَرَّبُونَ ﴿١١﴾")),
            QuranSurahItem(57, "الحديد", "Al-Hadid", "مدنية", 29, 27, isLocked = false, isMastered = false, score = 0, "المسبحات وأحكام النون", listOf("سَبَّحَ لِلَّهِ مَا فِي السَّمَاوَاتِ وَالْأَرْضِ ۖ وَهُوَ الْعَزِيزُ الْحَكِيمُ ﴿١﴾", "أَلَمْ يَأْنِ لِلَّذِينَ آمَنُوا أَن تَخْشَعَ قُلُوبُهُمْ لِذِكْرِ اللَّهِ ﴿١٦﴾")),
            QuranSurahItem(58, "المجادلة", "Al-Mujadila", "مدنية", 22, 28, isLocked = false, isMastered = false, score = 0, "لفظ الجلالة في كل آية", listOf("قَدْ سَمِعَ اللَّهُ قَوْلَ الَّتِي تُجَادِلُكَ فِي زَوْجِهَا ﴿١﴾")),
            QuranSurahItem(59, "الحشر", "Al-Hashr", "مدنية", 24, 28, isLocked = false, isMastered = false, score = 0, "أواخر الحشر وأسماء الله الحسنى", listOf("لَوْ أَنزَلْنَا هَٰذَا الْقُرْآنَ عَلَىٰ جَبَلٍ لَّرَأَيْتَهُ خَاشِعًا مُّتَصَدِّعًا مِّنْ خَشْيَةِ اللَّهِ ﴿٢١﴾", "هُوَ اللَّهُ الَّذِي لَا إِلَٰهَ إِلَّا هُوَ الْمَلِكُ الْقُدُّوسُ ﴿٢٣﴾")),
            QuranSurahItem(60, "الممتحنة", "Al-Mumtahanah", "مدنية", 13, 28, isLocked = false, isMastered = false, score = 0, "الإدغام والإخفاء", listOf("يَا أَيُّهَا الَّذِينَ آمَنُوا لَا تَتَّخِذُوا عَدُوِّي وَعَدُوَّكُمْ أَوْلِيَاءَ ﴿١﴾")),
            QuranSurahItem(61, "الصف", "As-Saff", "مدنية", 14, 28, isLocked = false, isMastered = false, score = 0, "المد اللازم ونصر الله", listOf("سَبَّحَ لِلَّهِ مَا فِي السَّمَاوَاتِ وَمَا فِي الْأَرْضِ ﴿١﴾", "نَصْرٌ مِّنَ اللَّهِ وَفَتْحٌ قَرِيبٌ ﴿١٣﴾")),
            QuranSurahItem(62, "الجمعة", "Al-Jumu'ah", "مدنية", 11, 28, isLocked = false, isMastered = false, score = 0, "أحكام نداء الجمعة والترتيل", listOf("يَا أَيُّهَا الَّذِينَ آمَنُوا إِذَا نُودِيَ لِلصَّلَاةِ مِن يَوْمِ الْجُمُعَةِ فَاسْعَوْا إِلَىٰ ذِكْرِ اللَّهِ ﴿٩﴾")),
            QuranSurahItem(63, "المنافقون", "Al-Munafiqun", "مدنية", 11, 28, isLocked = false, isMastered = false, score = 0, "الإنفاق قبل الموت والهمس", listOf("وَأَنفِقُوا مِن مَّا رَزَقْنَاكُم مِّن قَبْلِ أَن يَأْتِيَ أَحَدَكُمُ الْمَوْتُ ﴿١٠﴾")),
            QuranSurahItem(64, "التغابن", "At-Taghabun", "مدنية", 18, 28, isLocked = false, isMastered = false, score = 0, "التوكل على الله والمد", listOf("اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ ۚ وَعَلَى اللَّهِ فَلْيَتَوَكَّلِ الْمُؤْمِنُونَ ﴿١٣﴾")),
            QuranSurahItem(65, "الطلاق", "At-Talaq", "مدنية", 12, 28, isLocked = false, isMastered = false, score = 0, "التقوى وتيسير الأمور", listOf("وَمَن يَتَّقِ اللَّهَ يَجْعَل لَّهُ مَخْرَجًا ﴿٢﴾ وَيَرْزُقْهُ مِنْ حَيْثُ لَا يَحْتَسِبُ ﴿٣﴾")),
            QuranSurahItem(66, "التحريم", "At-Tahrim", "مدنية", 12, 28, isLocked = false, isMastered = false, score = 0, "التوبة النصوح والإخفاء", listOf("يَا أَيُّهَا الَّذِينَ آمَنُوا تُوبُوا إِلَى اللَّهِ تَوْبَةً نَّصُوحًا ﴿٨﴾")),
            QuranSurahItem(67, "الملك", "Al-Mulk", "مكية", 30, 29, isLocked = false, isMastered = true, score = 96, "أحكام الميم الساكنة والمد اللازم", listOf("تَبَارَكَ الَّذِي بِيَدِهِ الْمُلْكُ وَهُوَ عَلَىٰ كُلِّ شَيْءٍ قَدِيرٌ ﴿١﴾", "الَّذِي خَلَقَ الْمَوْتَ وَالْحَيَاةَ لِيَبْلُوَكُمْ أَيُّكُمْ أَحْسَنُ عَمَلًا ﴿٢﴾")),
            QuranSurahItem(68, "القلم", "Al-Qalam", "مكية", 52, 29, isLocked = false, isMastered = false, score = 0, "إظهار النون في (ن ۚ وَالْقَلَمِ)", listOf("ن ۚ وَالْقَلَمِ وَمَا يَسْطُرُونَ ﴿١﴾", "وَإِنَّكَ لَعَلَىٰ خُلُقٍ عَظِيمٍ ﴿٤﴾")),
            QuranSurahItem(69, "الحاقة", "Al-Haqqah", "مكية", 52, 29, isLocked = false, isMastered = false, score = 0, "المد اللازم الكلمي المثقل 6 حركات", listOf("الْحَاقَّةُ ﴿١﴾ مَا الْحَاقَّةُ ﴿٢﴾", "فَأَمَّا مَنْ أُوتِيَ كِتَابَهُ بِيَمِينِهِ فَيَقُولُ هَاؤُمُ اقْرَءُوا كِتَابِيَهْ ﴿١٩﴾")),
            QuranSurahItem(70, "المعارج", "Al-Ma'arij", "مكية", 44, 29, isLocked = false, isMastered = false, score = 0, "أحكام الصبر الجميل", listOf("فَاصْبِرْ صَبْرًا جَمِيلًا ﴿٥﴾")),
            QuranSurahItem(71, "نوح", "Nuh", "مكية", 28, 29, isLocked = false, isMastered = false, score = 0, "الاستغفار وثمراته", listOf("فَقُلْتُ اسْتَغْفِرُوا رَبَّكُمْ إِنَّهُ كَانَ غَفَّارًا ﴿١٠﴾ يُرْسِلِ السَّمَاءَ عَلَيْكُم مِّدْرَارًا ﴿١١﴾")),
            QuranSurahItem(72, "الجن", "Al-Jinn", "مكية", 28, 29, isLocked = false, isMastered = false, score = 0, "سماع القرآن والتوحيد", listOf("قُلْ أُوحِيَ إِلَيَّ أَنَّهُ اسْتَمَعَ نَفَرٌ مِّنَ الْجِنِّ فَقَالُوا إِنَّا سَمِعْنَا قُرْآنًا عَجَبًا ﴿١﴾")),
            QuranSurahItem(73, "المزمل", "Al-Muzzammil", "مكية", 20, 29, isLocked = false, isMastered = false, score = 0, "أمر الترتيل (وَرَتِّلِ الْقُرْآنَ تَرْتِيلًا)", listOf("يَا أَيُّهَا الْمُزَّمِّلُ ﴿١﴾ قُمِ اللَّيْلَ إِلَّا قَلِيلًا ﴿٢﴾", "وَرَتِّلِ الْقُرْآنَ تَرْتِيلًا ﴿٤﴾")),
            QuranSurahItem(74, "المدثر", "Al-Muddathir", "مكية", 56, 29, isLocked = false, isMastered = false, score = 0, "الدعوة والتكبير", listOf("يَا أَيُّهَا الْمُدَّثِّرُ ﴿١﴾ قُمْ فَأَنذِرْ ﴿٢﴾ وَرَبَّكَ فَكَبِّرْ ﴿٣﴾")),
            QuranSurahItem(75, "القيامة", "Al-Qiyamah", "مكية", 40, 29, isLocked = false, isMastered = false, score = 0, "السكت اللطيف في (مَنْ ۜ رَاقٍ)", listOf("لَا أُقْسِمُ بِيَوْمِ الْقِيَامَةِ ﴿١﴾", "كَلَّا إِذَا بَلَغَتِ التَّرَاقِيَ ﴿٢٦﴾ وَقِيلَ مَنْ ۜ رَاقٍ ﴿٢٧﴾")),
            QuranSurahItem(76, "الإنسان", "Al-Insan", "مدنية", 31, 29, isLocked = false, isMastered = false, score = 0, "نعيم الجنة والألفات السبعة", listOf("إِنَّا هَدَيْنَاهُ السَّبِيلَ إِمَّا شَاكِرًا وَإِمَّا كَفُورًا ﴿٣﴾", "وَيُطْعِمُونَ الطَّعَامَ عَلَىٰ حُبِّهِ مِسْكِينًا وَيَتِيمًا وَأَسِيرًا ﴿٨﴾")),
            QuranSurahItem(77, "المرسلات", "Al-Mursalat", "مكية", 50, 29, isLocked = false, isMastered = false, score = 0, "إدغام القاف في الكاف في (أَلَمْ نَخْلُقكُّم)", listOf("وَالْمُرْسَلَاتِ عُرْفًا ﴿١﴾", "أَلَمْ نَخْلُقكُّم مِّن مَّاءٍ مَّهِينٍ ﴿٢٠﴾")),
            QuranSurahItem(78, "النبأ", "An-Naba", "مكية", 40, 30, isLocked = false, isMastered = false, score = 85, "المد العارض للسكون والقلقلة", listOf("عَمَّ يَتَسَاءَلُونَ ﴿١﴾ عَنِ النَّبَإِ الْعَظِيمِ ﴿٢﴾", "إِنَّ لِلْمُتَّقِينَ مَفَازًا ﴿٣١﴾ حَدَائِقَ وَأَعْنَابًا ﴿٣٢﴾")),
            QuranSurahItem(79, "النازعات", "An-Nazi'at", "مكية", 46, 30, isLocked = false, isMastered = false, score = 0, "الهمس والغنة", listOf("وَالنَّازِعَاتِ غَرْقًا ﴿١﴾ وَالنَّاشِطَاتِ نَشْطًا ﴿٢﴾", "وَأَمَّا مَنْ خَافَ مَقَامَ رَبِّهِ وَنَهَى النَّفْسَ عَنِ الْهَوَىٰ ﴿٤٠﴾")),
            QuranSurahItem(80, "عبس", "'Abasa", "مكية", 42, 30, isLocked = false, isMastered = false, score = 0, "تذكرة القرآن والتفخيم", listOf("عَبَسَ وَتَوَلَّىٰ ﴿١﴾ أَن جَاءَهُ الْأَعْمَىٰ ﴿٢﴾", "كَلَّا إِنَّهَا تَذْكِرَةٌ ﴿١١﴾ فَمَن شَاءَ ذَكَرَهُ ﴿١٢﴾")),
            QuranSurahItem(81, "التكوير", "At-Takwir", "مكية", 29, 30, isLocked = false, isMastered = false, score = 0, "أحكام القسم وإذا الشمس كورت", listOf("إِذَا الشَّمْسُ كُوِّرَتْ ﴿١﴾ وَإِذَا النُّجُومُ انكَدَرَتْ ﴿٢﴾", "فَأَيْنَ تَذْهَبُونَ ﴿٢٦﴾ إِنْ هُوَ إِلَّا ذِكْرٌ لِّلْعَالَمِينَ ﴿٢٧﴾")),
            QuranSurahItem(82, "الانفطار", "Al-Infitar", "مكية", 19, 30, isLocked = false, isMastered = false, score = 0, "الكرام الكاتبين والإخفاء", listOf("يَا أَيُّهَا الْإِنسَانُ مَا غَرَّكَ بِرَبِّكَ الْكَرِيمِ ﴿٦﴾", "وَإِنَّ عَلَيْكُمْ لَحَافِظِينَ ﴿١٠﴾ كِرَامًا كَاتِبِينَ ﴿١١﴾")),
            QuranSurahItem(83, "المطففين", "Al-Mutaffifin", "مكية", 36, 30, isLocked = false, isMastered = false, score = 0, "السكت اللطيف في (كَلَّا ۖ بَلْ ۜ رَانَ)", listOf("وَيْلٌ لِّلْمُطَفِّفِينَ ﴿١﴾", "كَلَّا ۖ بَلْ ۜ رَانَ عَلَىٰ قُلُوبِهِم مَّا كَانُوا يَكْسِبُونَ ﴿١٤﴾")),
            QuranSurahItem(84, "الانشقاق", "Al-Inshiqaq", "مكية", 25, 30, isLocked = false, isMastered = false, score = 0, "سجدة الانشقاق والحساب اليسير", listOf("فَأَمَّا مَنْ أُوتِيَ كِتَابَهُ بِيَمِينِهِ ﴿٧﴾ فَسَوْفَ يُحَاسَبُ حِسَابًا يَسِيرًا ﴿٨﴾")),
            QuranSurahItem(85, "البروج", "Al-Buruj", "مكية", 22, 30, isLocked = false, isMastered = false, score = 0, "القلقلة الكبرى في الوقف على (الْبُرُوجِ)", listOf("وَالسَّمَاءِ ذَاتِ الْبُرُوجِ ﴿١﴾", "إِنَّ بَطْشَ رَبِّكَ لَشَدِيدٌ ﴿١٢﴾ إِنَّهُ هُوَ يُبْدِئُ وَيُعِيدُ ﴿١٣﴾")),
            QuranSurahItem(86, "الطارق", "At-Tariq", "مكية", 17, 30, isLocked = false, isMastered = false, score = 0, "أحكام القلقلة وتفخيم الطاء والراء", listOf("وَالسَّمَاءِ وَالطَّارِقِ ﴿١﴾ وَمَا أَدْرَاكَ مَا الطَّارِقُ ﴿٢﴾ النَّجْمُ الثَّاقِبُ ﴿٣﴾")),
            QuranSurahItem(87, "الأعلى", "Al-A'la", "مكية", 19, 30, isLocked = false, isMastered = false, score = 0, "الإمالة والتسهيل والتفخيم", listOf("سَبِّحِ اسْمَ رَبِّكَ الْأَعْلَى ﴿١﴾ الَّذِي خَلَقَ فَسَوَّىٰ ﴿٢﴾", "قَدْ أَفْلَحَ مَن تَزَكَّىٰ ﴿١٤﴾ وَذَكَرَ اسْمَ رَبِّهِ فَصَلَّىٰ ﴿١٥﴾")),
            QuranSurahItem(88, "الغاشية", "Al-Ghashiyah", "مكية", 26, 30, isLocked = false, isMastered = false, score = 0, "نغم الآيات والتفكر في خلق الله", listOf("هَلْ أَتَاكَ حَدِيثُ الْغَاشِيَةِ ﴿١﴾", "أَفَلَا يَنظُرُونَ إِلَى الْإِبِلِ كَيْفَ خُلِقَتْ ﴿١٧﴾")),
            QuranSurahItem(89, "الفجر", "Al-Fajr", "مكية", 30, 30, isLocked = false, isMastered = false, score = 0, "تفخيم الراء وقلقلة الجيم", listOf("وَالْفَجْرِ ﴿١﴾ وَلَيَالٍ عَشْرٍ ﴿٢﴾", "يَا أَيَّتُهَا النَّفْسُ الْمُطْمَئِنَّةُ ﴿٢٧﴾ ارْجِعِي إِلَىٰ رَبِّكِ رَاضِيَةً مَّرْضِيَّةً ﴿٢٨﴾")),
            QuranSurahItem(90, "البلد", "Al-Balad", "مكية", 20, 30, isLocked = false, isMastered = false, score = 0, "القلقلة في (الْبَلَدِ) والتواصي بالمرحمة", listOf("لَا أُقْسِمُ بِهَٰذَا الْبَلَدِ ﴿١﴾", "وَتَوَاصَوْا بِالصَّبْرِ وَتَوَاصَوْا بِالْمَرْحَمَةِ ﴿١٧﴾")),
            QuranSurahItem(91, "الشمس", "Ash-Shams", "مكية", 15, 30, isLocked = false, isMastered = false, score = 0, "تزكية النفس وتفخيم الشين والصاد", listOf("وَالشَّمْسِ وَضُحَاهَا ﴿١﴾ وَالْقَمَرِ إِذَا تَلَاهَا ﴿٢﴾", "قَدْ أَفْلَحَ مَن زَكَّاهَا ﴿٩﴾ وَقَدْ خَابَ مَن دَسَّاهَا ﴿١٠﴾")),
            QuranSurahItem(92, "الليل", "Al-Layl", "مكية", 21, 30, isLocked = false, isMastered = false, score = 0, "أحكام التيسير والإنفاق", listOf("وَاللَّيْلِ إِذَا يَغْشَىٰ ﴿١﴾ وَالنَّهَارِ إِذَا تَجَلَّىٰ ﴿٢﴾", "فَأَمَّا مَنْ أَعْطَىٰ وَاتَّقَىٰ ﴿٥﴾ وَصَدَّقَ بِالْحُسْنَىٰ ﴿٦﴾")),
            QuranSurahItem(93, "الضحى", "Ad-Duha", "مكية", 11, 30, isLocked = false, isMastered = false, score = 0, "أحكام المد والوقف ورعاية اليتيم", listOf("وَالضُّحَىٰ ﴿١﴾ وَاللَّيْلِ إِذَا سَجَىٰ ﴿٢﴾ مَا وَدَّعَكَ رَبُّكَ وَمَا قَلَىٰ ﴿٣﴾", "وَلَسَوْفَ يُعْطِيكَ رَبُّكَ فَتَرْضَىٰ ﴿٥﴾")),
            QuranSurahItem(94, "الشرح", "Ash-Sharh", "مكية", 8, 30, isLocked = false, isMastered = false, score = 0, "أحكام الإخفاء وانشراح الصدر", listOf("أَلَمْ نَشْرَحْ لَكَ صَدْرَكَ ﴿١﴾ وَوَضَعْنَا عَنكَ وِزْرَكَ ﴿٢﴾", "فَإِنَّ مَعَ الْعُسْرِ يُسْرًا ﴿٥﴾ إِنَّ مَعَ الْعُسْرِ يُسْرًا ﴿٦﴾")),
            QuranSurahItem(95, "التين", "At-Tin", "مكية", 8, 30, isLocked = false, isMastered = false, score = 0, "قلقلة الدال وخلق الإنسان", listOf("وَالتِّينِ وَالزَّيْتُونِ ﴿١﴾", "لَقَدْ خَلَقْنَا الْإِنسَانَ فِي أَحْسَنِ تَقْوِيمٍ ﴿٤﴾")),
            QuranSurahItem(96, "العلق", "Al-'Alaq", "مكية", 19, 30, isLocked = false, isMastered = false, score = 0, "أول ما نزل وسجدة التلاوة", listOf("اقْرَأْ بِاسْمِ رَبِّكَ الَّذِي خَلَقَ ﴿١﴾ خَلَقَ الْإِنسَانَ مِنْ عَلَقٍ ﴿٢﴾", "كَلَّا لَا تُطِعْهُ وَاسْجُدْ وَاقْتَرِب ۩ ﴿١٩﴾")),
            QuranSurahItem(97, "القدر", "Al-Qadr", "مكية", 5, 30, isLocked = false, isMastered = false, score = 95, "أحكام التفخيم والترقيق في الراء", listOf("إِنَّا أَنزَلْنَاهُ فِي لَيْلَةِ الْقَدْرِ ﴿١﴾ وَمَا أَدْرَاكَ مَا لَيْلَةُ الْقَدْرِ ﴿٢﴾", "لَيْلَةُ الْقَدْرِ خَيْرٌ مِّنْ أَلْفِ شَهْرٍ ﴿٣﴾")),
            QuranSurahItem(98, "البينة", "Al-Bayyinah", "مدنية", 8, 30, isLocked = false, isMastered = false, score = 0, "إخلاص الدين وأحكام النون", listOf("وَمَا أُمِرُوا إِلَّا لِيَعْبُدُوا اللَّهَ مُخْلِصِينَ لَهُ الدِّينَ حُنَفَاءَ ﴿٥﴾")),
            QuranSurahItem(99, "الزلزلة", "Az-Zalzalah", "مدنية", 8, 30, isLocked = false, isMastered = false, score = 0, "الإدغام بغنة في (فَمَن يَعْمَلْ)", listOf("إِذَا زُلْزِلَتِ الْأَرْضُ زِلْزَالَهَا ﴿١﴾", "فَمَن يَعْمَلْ مِثْقَالَ ذَرَّةٍ خَيْرًا يَرَهُ ﴿٧﴾ وَمَن يَعْمَلْ مِثْقَالَ ذَرَّةٍ شَرًّا يَرَهُ ﴿٨﴾")),
            QuranSurahItem(100, "العاديات", "Al-'Adiyat", "مكية", 11, 30, isLocked = false, isMastered = false, score = 0, "القلقلة الكبرى في أواخر الآيات", listOf("وَالْعَادِيَاتِ ضَبْحًا ﴿١﴾ فَالْمُورِيَاتِ قَدْحًا ﴿٢﴾", "إِنَّ الْإِنسَانَ لِرَبِّهِ لَكَنُودٌ ﴿٦﴾")),
            QuranSurahItem(101, "القارعة", "Al-Qari'ah", "مكية", 11, 30, isLocked = false, isMastered = false, score = 0, "نبر القارعة وثقل الموازين", listOf("الْقَارِعَةُ ﴿١﴾ مَا الْقَارِعَةُ ﴿٢﴾", "فَأَمَّا مَن ثَقُلَتْ مَوَازِينُهُ ﴿٦﴾ فَهُوَ فِي عِيشَةٍ رَّاضِيَةٍ ﴿٧﴾")),
            QuranSurahItem(102, "التكاثر", "At-Takathur", "مكية", 8, 30, isLocked = false, isMastered = false, score = 0, "عين اليقين والسؤال عن النعيم", listOf("أَلْهَاكُمُ التَّكَاثُرُ ﴿١﴾ حَتَّىٰ زُرْتُمُ الْمَقَابِرَ ﴿٢﴾", "ثُمَّ لَتُسْأَلُنَّ يَوْمَئِذٍ عَنِ النَّعِيمِ ﴿٨﴾")),
            QuranSurahItem(103, "العصر", "Al-'Asr", "مكية", 3, 30, isLocked = false, isMastered = false, score = 0, "ترقيق وتفخيم الراء وقيمة الوقت", listOf("وَالْعَصْرِ ﴿١﴾ إِنَّ الْإِنسَانَ لَفِي خُسْرٍ ﴿٢﴾ إِلَّا الَّذِينَ آمَنُوا وَعَمِلُوا الصَّالِحَاتِ ﴿٣﴾")),
            QuranSurahItem(104, "الهمزة", "Al-Humazah", "مكية", 9, 30, isLocked = false, isMastered = false, score = 0, "الحذر من الغيبة والهمز", listOf("وَيْلٌ لِّكُلِّ هُمَزَةٍ لُّمَزَةٍ ﴿١﴾ الَّذِي جَمَعَ مَالًا وَعَدَّدَهُ ﴿٢﴾", "نَارُ اللَّهِ الْمُوقَدَةُ ﴿٦﴾")),
            QuranSurahItem(105, "الفيل", "Al-Fil", "مكية", 5, 30, isLocked = false, isMastered = false, score = 0, "الإظهار الشفوي في (أَلَمْ تَرَ)", listOf("أَلَمْ تَرَ كَيْفَ فَعَلَ رَبُّكَ بِأَصْحَابِ الْفِيلِ ﴿١﴾", "فَجَعَلَهُمْ كَعَصْفٍ مَّأْكُولٍ ﴿٥﴾")),
            QuranSurahItem(106, "قريش", "Quraysh", "مكية", 4, 30, isLocked = false, isMastered = false, score = 0, "مد اللين في (قُرَيْشٍ - خَوْفٍ)", listOf("لِإِيلَافِ قُرَيْشٍ ﴿١﴾ إِيلَافِهِمْ رِحْلَةَ الشِّتَاءِ وَالصَّيْفِ ﴿٢﴾", "الَّذِي أَطْعَمَهُم مِّن جُوعٍ وَآمَنَهُم مِّنْ خَوْفٍ ﴿٤﴾")),
            QuranSurahItem(107, "الماعون", "Al-Ma'un", "مكية", 7, 30, isLocked = false, isMastered = false, score = 0, "إخلاص العبادة وإعانة المحتاج", listOf("أَرَأَيْتَ الَّذِي يُكَذِّبُ بِالدِّينِ ﴿١﴾", "الَّذِينَ هُمْ عَن صَلَاتِهِمْ سَاهُونَ ﴿٥﴾ الَّذِينَ هُمْ يُرَاءُونَ ﴿٦﴾")),
            QuranSurahItem(108, "الكوثر", "Al-Kawthar", "مكية", 3, 30, isLocked = false, isMastered = true, score = 99, "أحكام قلقلة الدال والتفخيم", listOf("إِنَّا أَعْطَيْنَاكَ الْكَوْثَرَ ﴿١﴾ فَصَلِّ لِرَبِّكَ وَانْحَرْ ﴿٢﴾ إِنَّ شَانِئَكَ هُوَ الْأَبْتَرُ ﴿٣﴾")),
            QuranSurahItem(109, "الكافرون", "Al-Kafirun", "مكية", 6, 30, isLocked = false, isMastered = false, score = 0, "المد المنفصل والبراءة من الشرك", listOf("قُلْ يَا أَيُّهَا الْكَافِرُونَ ﴿١﴾ لَا أَعْبُدُ مَا تَعْبُدُونَ ﴿٢﴾", "لَكُمْ دِينُكُمْ وَلِيَ دِينِ ﴿٦﴾")),
            QuranSurahItem(110, "النصر", "An-Nasr", "مدنية", 3, 30, isLocked = false, isMastered = false, score = 0, "المد المتصل في (جَاءَ) والاستغفار", listOf("إِذَا جَاءَ نَصْرُ اللَّهِ وَالْفَتْحُ ﴿١﴾ وَرَأَيْتَ النَّاسَ يَدْخُلُونَ فِي دِينِ اللَّهِ أَفْوَاجًا ﴿٢﴾ فَسَبِّحْ بِحَمْدِ رَبِّكَ وَاسْتَغْفِرْهُ ﴿٣﴾")),
            QuranSurahItem(111, "المسد", "Al-Masad", "مكية", 5, 30, isLocked = false, isMastered = false, score = 0, "القلقلة الكبرى في (وَتَبَّ)", listOf("تَبَّتْ يَدَا أَبِي لَهَبٍ وَتَبَّ ﴿١﴾ مَا أَغْنَىٰ عَنْهُ مَالُهُ وَمَا كَسَبَ ﴿٢﴾")),
            QuranSurahItem(112, "الإخلاص", "Al-Ikhlas", "مكية", 4, 30, isLocked = false, isMastered = true, score = 100, "القلقلة الكبرى والصغرى", listOf("قُلْ هُوَ اللَّهُ أَحَدٌ ﴿١﴾ اللَّهُ الصَّمَدُ ﴿٢﴾ لَمْ يَلِدْ وَلَمْ يُولَدْ ﴿٣﴾ وَلَمْ يَكُن لَّهُ كُفُوًا أَحَدٌ ﴿٤﴾")),
            QuranSurahItem(113, "الفلق", "Al-Falaq", "مكية", 5, 30, isLocked = false, isMastered = true, score = 98, "أحكام النون والميم المشددتين والقلقلة", listOf("قُلْ أَعُوذُ بِرَبِّ الْفَلَقِ ﴿١﴾ مِن شَرِّ مَا خَلَقَ ﴿٢﴾ وَمِن شَرِّ غَاسِقٍ إِذَا وَقَبَ ﴿٣﴾")),
            QuranSurahItem(114, "الناس", "An-Nas", "مكية", 6, 30, isLocked = false, isMastered = true, score = 97, "الغنة في النون المشددة والهمس", listOf("قُلْ أَعُوذُ بِرَبِّ النَّاسِ ﴿١﴾ مَلِكِ النَّاسِ ﴿٢﴾ إِلَٰهِ النَّاسِ ﴿٣﴾"))
        )
    }

    /**
     * استرجاع الآيات الأصيلة مع التفسير وأحكام التجويد للسور.
     * تحول الآيات المحملة من ملف الـ JSON إلى QuranVerseDetail مرتبة حسب رقم الآية.
     * إذا لم تكن السورة محملة ترجع قائمة فارغة للتعامل معها برمجياً في الواجهة.
     */
    fun getVersesForSurah(surahId: Int, surahName: String): List<QuranVerseDetail> {
        val loaded = versesMap?.get(surahId) ?: return emptyList()
        return loaded.sortedBy { it.ayah }.map { ayah ->
            QuranVerseDetail(
                surahNumber = surahId,
                surahName = surahName,
                verseNumber = ayah.ayah,
                verseText = ayah.text,
                tafseer = tafsirMap?.get("$surahId:${ayah.ayah}") ?: "",
                tajweedNotes = ""
            )
        }
    }

    private fun getSurahName(surahId: Int): String =
        surahs.firstOrNull { it.id == surahId }?.name ?: "سورة $surahId"

    /**
     * تطبيع النص العربي للبحث: حذف التشكيل والتطويل وألف الخنجرية،
     * ثم توحيد الهمزات والألف المقصورة والتاء المربوطة لتزداد دقة المطابقة.
     */
    private fun normalizeArabicText(text: String): String =
        text.replace(Regex("[\\u064B-\\u0652\\u0640\\u0670]"), "")
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ٱ", "ا")
            .replace("ى", "ي").replace("ة", "ه")

    /**
     * بحث نصي حقيقي في الآيات الـ 6236 المحملة من النص العثماني.
     * يشترط 3 أحرف على الأقل، ويمرر مطابقة ثانية بفكّ «ال» التعريف
     * (مثل «الكرسي» → «كرسيه» في آية الكرسي 2:255). تُرتب النتائج
     * حسب السورة ثم رقم الآية. تحميل النسخة العثمانية شرط مسبق
     * (loadFromAssets) وإلا تُرجع قائمة فارغة — لا بحث على لا شيء.
     */
    fun searchVerses(query: String, limit: Int = 50): List<QuranVerseDetail> {
        val trimmed = query.trim()
        if (trimmed.length < 3) return emptyList()
        val map = versesMap ?: return emptyList()
        val needle = normalizeArabicText(trimmed)
        val strippedNeedle =
            if (needle.length > 3 && needle.startsWith("ال")) needle.substring(2) else null
        val results = ArrayList<QuranVerseDetail>()
        for ((surahId, ayahs) in map) {
            val surahName = getSurahName(surahId)
            for (ayah in ayahs.sortedBy { it.ayah }) {
                val hay = normalizeArabicText(ayah.text)
                if (hay.contains(needle) || (strippedNeedle != null && hay.contains(strippedNeedle))) {
                    results.add(
                        QuranVerseDetail(
                            surahNumber = surahId,
                            surahName = surahName,
                            verseNumber = ayah.ayah,
                            verseText = ayah.text,
                            tafseer = tafsirMap?.get("$surahId:${ayah.ayah}") ?: "",
                            tajweedNotes = ""
                        )
                    )
                    if (results.size >= limit) break
                }
            }
            if (results.size >= limit) break
        }
        return results.sortedWith(compareBy({ it.surahNumber }, { it.verseNumber }))
    }
}
