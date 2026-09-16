package com.qabas.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class DirectorBrief(
    val idea: String = "",
    val contentType: String = "",
    val contentTone: String = "",
    val videoDuration: String = "",
    val selectedRatio: String = "9:16"
) {
    fun missingSlots(): List<String> {
        val m = mutableListOf<String>()
        if (idea.isBlank()) m.add("idea")
        if (contentType.isBlank()) m.add("type")
        if (contentTone.isBlank()) m.add("tone")
        if (videoDuration.isBlank()) m.add("duration")
        return m
    }
}

private data class DirectorMsg(val fromDirector: Boolean, val text: String)

private val SlotOptions = mapOf(
    "type" to listOf("قصة", "تدبر قرآني", "حديث شريف", "موعظة", "سيرة"),
    "tone" to listOf("خاشع", "حماسي", "ملحمي", "هادئ"),
    "duration" to listOf("15 ثانية", "30 ثانية", "60 ثانية"),
    "ratio" to listOf("9:16", "16:9", "1:1")
)

private val SlotQuestion = mapOf(
    "idea" to "احكِ لي الفكرة بكلماتك — عن ماذا الفيديو؟ وما الرسالة التي تريد أن تبقى في قلب المشاهد؟",
    "type" to "فهمت. من تخاطب بهذا؟ وما الشعور الذي تريد أن يخرج به المشاهد؟",
    "tone" to "جميل. أي لحظة هي ذروة التأثير؟ وكيف تريد أن تبدأ أول ثانيتين؟",
    "duration" to "بقي شيء واحد عملي: المدة التقريبية والنسبة (عمودي/أفقي)؟"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectorChatScreen(
    initialText: String = "",
    onComplete: (DirectorBrief) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var brief by remember { mutableStateOf(DirectorBrief(idea = initialText)) }
    var messages by remember {
        mutableStateOf(
            listOf(
                DirectorMsg(
                    true,
                    if (initialText.isBlank()) "أهلاً بك في غرفة المخرج. احكِ لي فكرتك وسأجهز كل شيء."
                    else "ممتاز، استلمت هذه البداية. أخبرني المزيد أو أجب عن أسئلتي لأكمل الموجز."
                )
            )
        )
    }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var treatment by remember { mutableStateOf("") }
    var buildingTreatment by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { AppServices.getAnalyticsService(context).logScreenView("DirectorChat") }

    fun currentChips(): List<String> {
        val missing = brief.missingSlots().firstOrNull() ?: return emptyList()
        return SlotOptions[missing] ?: emptyList()
    }

    fun localExtract(text: String, b: DirectorBrief): DirectorBrief {
        var out = b
        // دمج الفكرة: تراكم بدل استبدال — أول رسالة طويلة تؤسس، واللاحقة تُلحق إن أضافت معنى
        if (text.length > 5) {
            out = if (out.idea.isBlank()) out.copy(idea = text.take(600))
            else if (!out.idea.contains(text.take(20))) out.copy(idea = (out.idea + " " + text).take(600))
            else out
        }
        val t = text
        if (out.contentTone.isBlank()) {
            listOf("خاشع", "حماسي", "ملحمي", "هادئ", "حزين", "مؤثر").firstOrNull { t.contains(it) }
                ?.let { out = out.copy(contentTone = it) }
        }
        if (out.contentType.isBlank()) {
            when {
                t.contains("قرآن") || t.contains("آية") || t.contains("تدبر") -> out = out.copy(contentType = "تدبر قرآني")
                t.contains("حديث") -> out = out.copy(contentType = "حديث شريف")
                t.contains("قصة") || t.contains("سيرة") || t.contains("نبي") -> out = out.copy(contentType = "قصة")
                t.contains("موعظة") || t.contains("خطبة") -> out = out.copy(contentType = "موعظة")
            }
        }
        if (out.videoDuration.isBlank()) {
            when {
                t.contains("15") -> out = out.copy(videoDuration = "15 ثانية")
                t.contains("30") -> out = out.copy(videoDuration = "30 ثانية")
                t.contains("60") || t.contains("دقيقة") -> out = out.copy(videoDuration = "60 ثانية")
            }
        }
        if (t.contains("16:9") || t.contains("يوتيوب") || t.contains("عرضي")) out = out.copy(selectedRatio = "16:9")
        else if (t.contains("1:1") || t.contains("مربع")) out = out.copy(selectedRatio = "1:1")
        return out
    }

    suspend fun aiTurn(userText: String, b: DirectorBrief, history: String): Pair<DirectorBrief, String> = withContext(Dispatchers.IO) {
        val extractPrompt = """
            أنت مخرج سينمائي محترف يحاور صانع محتوى عربي ليفهم فكرته بعمق — لست نموذج تعبئة خانات.
            سجل الحوار حتى الآن:
            $history
            رسالة المستخدم الأخيرة: "$userText".
            الموجز الحالي: فكرة="${b.idea.take(300)}" نوع="${b.contentType}" نبرة="${b.contentTone}" مدة="${b.videoDuration}" نسبة="${b.selectedRatio}".
            مهمتك:
            1. افهم الفكرة الحقيقية من كل الحوار (ادمج رسائل المستخدم في فكرة واحدة غنية 2-3 جمل، لا تنسخ رسالة واحدة فقط).
            2. استنتج النوع والنبرة من السياق إن أمكن بدل سؤالهما حرفياً (مثلاً حديث عن الصبر → موعظة/خاشع).
            3. اسأل سؤالاً واحداً فقط، ذكياً ومحدداً، يعمّق الفهم (الجمهور؟ الشعور المستهدف؟ البداية الخطافة؟ النهاية والدعوة؟) — ممنوع سؤال عام مثل "ما نوع المحتوى؟".
            4. إن كانت الفكرة واضحة (تعرف الرسالة + الجمهور أو الشعور) اكتب reply تأكيداً يعيد صياغة فهمك بجملة ثم يقول إن الموجز جاهز.
            أخرج JSON فقط: {"idea":"الفكرة المدمجة الغنية","contentType":"...","contentTone":"...","videoDuration":"...","selectedRatio":"...","reply":"..."}.
            القيم الفارغة null. النسبة الافتراضية 9:16. reply بالعربية الفصيحة المبسطة، جملتان كحد أقصى.
        """.trimIndent()
        val raw = try { RealGroqService.chatOrGenerate(extractPrompt) } catch (_: Exception) { null }
            ?: try { RealOpenAIService.chatOrGenerate(extractPrompt) } catch (_: Exception) { null }
            ?: try { geminiChat(context, extractPrompt) } catch (_: Exception) { null }
        if (raw.isNullOrBlank() || !raw.contains("{")) return@withContext b to ""
        try {
            val json = JSONObject(raw.substring(raw.indexOf("{"), raw.lastIndexOf("}") + 1))
            fun s(k: String) = json.optString(k, "").takeIf { it.isNotBlank() && it != "null" } ?: ""
            val nb = b.copy(
                idea = s("idea").ifBlank { b.idea },
                contentType = s("contentType").ifBlank { b.contentType },
                contentTone = s("contentTone").ifBlank { b.contentTone },
                videoDuration = s("videoDuration").ifBlank { b.videoDuration },
                selectedRatio = s("selectedRatio").ifBlank { b.selectedRatio }
            )
            nb to json.optString("reply", "").take(200)
        } catch (_: Exception) { b to "" }
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || thinking) return
        input = ""
        messages = messages + DirectorMsg(false, clean)
        thinking = true
        scope.launch {
            var nb = localExtract(clean, brief)
            var reply = ""
            val history = (messages + DirectorMsg(false, clean)).takeLast(8).joinToString("\n") {
                (if (it.fromDirector) "المخرج: " else "المستخدم: ") + it.text
            }
            val ai = aiTurn(clean, nb, history)
            if (ai.first != nb) nb = ai.first
            reply = ai.second
            if (reply.isBlank()) {
                // أسئلة عميقة متدرجة بلا شبكة: رسالة/جمهور/شعور/بداية — لا تسأل "ما النوع؟" حرفياً
                val turns = messages.count { !it.fromDirector }
                reply = when {
                    nb.idea.length < 15 -> SlotQuestion["idea"] ?: "حدثني أكثر."
                    nb.contentType.isBlank() && turns >= 2 -> "لمن توجّه هذا؟ وما الشعور الذي تريد أن يبقى معه بعد المشاهدة؟"
                    nb.contentTone.isBlank() && turns >= 3 -> "كيف تتخيل أول ثانيتين تخطفان العين؟ وماذا تقول الجملة الأخيرة؟"
                    nb.videoDuration.isBlank() && turns >= 4 -> SlotQuestion["duration"] ?: "حدثني أكثر."
                    nb.missingSlots().isEmpty() -> "فهمتك: «${nb.idea.take(90)}». الموجز اكتمل — راجعه بالأعلى واضغط «اعتمد وابدأ»."
                    else -> "حدثني أكثر عن زاويتك الخاصة — ما الذي يجعل فكرتك مختلفة؟"
                }
            }
            brief = nb
            messages = messages + DirectorMsg(true, reply)
            thinking = false
            try { listState.animateScrollToItem(messages.size) } catch (_: Exception) {}
            // معاينة حية: ابنِ العلاج السينمائي أمام عين المستخدم بعد كل دور
            if (nb.idea.length >= 10) {
                buildingTreatment = true
                val tPrompt = """
                    أنت مخرج قبس. من هذا الحوار: $history
                    الفكرة: "${nb.idea.take(400)}" النوع:${nb.contentType} النبرة:${nb.contentTone}
                    اكتب علاجاً سينمائياً حياً بالعربية، 6 أسطر كحد أقصى، بالشكل:
                    الخطاف: جملة أول ثانيتين.
                    المشهد 1: صوت + صورة بالإنجليزية قصيرة.
                    المشهد 2: صوت + صورة.
                    المشهد 3: صوت + صورة.
                    الخاتمة: الدعوة.
                    بلا مقدمات.
                """.trimIndent()
                val t = try { RealGroqService.chatOrGenerate(tPrompt) } catch (_: Exception) { null }
                    ?: try { RealOpenAIService.chatOrGenerate(tPrompt) } catch (_: Exception) { null }
                    ?: try { geminiChat(context, tPrompt) } catch (_: Exception) { null }
                if (!t.isNullOrBlank()) treatment = t.take(900)
                else if (treatment.isBlank()) treatment = "الخطاف: «${nb.idea.take(50)}…»\nالمشهد 1: صوت يطرح السؤال + صورة سماء واسعة\nالمشهد 2: صوت يعمّق المعنى + صورة نور يخترق غيوماً\nالمشهد 3: صوت الذروة + صورة مسجد وقت الغروب\nالخاتمة: دعوة للمشاركة والنشر"
                buildingTreatment = false
            }
        }
    }

    Scaffold(
        containerColor = DeepSlate,
        topBar = {
            TopAppBar(
                title = { Text("غرفة المخرج", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardSurface)
            )
        },
        bottomBar = {
            Column(Modifier.background(CardSurface).padding(12.dp)) {
                val chips = currentChips()
                if (chips.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        items(chips) { c ->
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF8B5CF6).copy(alpha = 0.15f))
                                    .border(1.dp, Color(0xFF8B5CF6), RoundedCornerShape(16.dp))
                                    .clickable { send(c) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(c, color = Color(0xFFC4B5FD), fontFamily = CairoFont, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("احكِ للمخرج...", fontFamily = CairoFont, color = TextSecondary) },
                        shape = RoundedCornerShape(14.dp),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary, unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = GoldPrimary
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { send(input) },
                        modifier = Modifier.size(48.dp).background(
                            Brush.horizontalGradient(listOf(GoldSecondary, GoldPrimary)), CircleShape
                        )
                    ) {
                        if (thinking) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = DeepSlate, strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال", tint = DeepSlate)
                    }
                }
                Spacer(Modifier.height(8.dp))
                ProdPrimaryButton(
                    text = if (brief.idea.isBlank()) "اكتب الفكرة أولاً" else "اعتمد وابدأ",
                    onClick = {
                        if (brief.idea.isNotBlank()) {
                            onComplete(brief.copy(videoDuration = brief.videoDuration.ifBlank { "30 ثانية" }))
                        }
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("موجز المشروع — يُبنى حياً", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        BriefRow("الفكرة", brief.idea.take(80).ifBlank { "—" })
                        BriefRow("النوع", brief.contentType.ifBlank { "—" })
                        BriefRow("النبرة", brief.contentTone.ifBlank { "—" })
                        BriefRow("المدة", brief.videoDuration.ifBlank { "—" })
                        if (buildingTreatment) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = GoldPrimary)
                                Spacer(Modifier.width(6.dp))
                                Text("المخرج يبني المشاهد حياً…", color = TextSecondary, fontFamily = CairoFont, fontSize = 11.sp)
                            }
                        } else if (treatment.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth().background(Color(0xFF0B0F19), RoundedCornerShape(10.dp)).padding(10.dp)) {
                                Text(treatment, color = Color(0xFFE2E8F0), fontFamily = CairoFont, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }
            items(messages) { m ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.fromDirector) Arrangement.Start else Arrangement.End
                ) {
                    if (m.fromDirector) {
                        Box(
                            modifier = Modifier.size(28.dp).background(Color(0xFF8B5CF6).copy(alpha = 0.25f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFFC4B5FD), modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(
                        modifier = Modifier.widthIn(max = 280.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (m.fromDirector) Color(0xFF151B2B) else GoldPrimary.copy(alpha = 0.9f))
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text(
                            m.text, color = if (m.fromDirector) Color.White else DeepSlate,
                            fontFamily = CairoFont, fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

private suspend fun geminiChat(context: android.content.Context, prompt: String): String? =
    withContext(Dispatchers.IO) {
        val key = KeyVault.gemini
        if (key.isBlank()) return@withContext null
        try {
            val body = JSONObject().apply {
                put("contents", org.json.JSONArray().put(JSONObject().apply {
                    put("parts", org.json.JSONArray().put(JSONObject().apply { put("text", prompt) }))
                }))
            }
            val req = okhttp3.Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = ApiUsageTracker.track(context, "Gemini") { SharedHttpClient.instance.newCall(req).execute() }
            if (!resp.isSuccessful) return@withContext null
            val json = JSONObject(resp.body?.string() ?: "")
            json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text")
        } catch (_: Exception) { null }
    }

@Composable
private fun BriefRow(label: String, value: String) {    Row {
        Text("$label: ", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
        Text(value, color = Color.White, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
