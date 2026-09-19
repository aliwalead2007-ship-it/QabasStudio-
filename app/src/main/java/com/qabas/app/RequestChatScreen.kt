package com.qabas.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val WaBackground = Color(0xFF0B141A)
private val WaMine = Color(0xFF005C4B)
private val WaTheirs = Color(0xFF1F2C34)
private val WaTicks = Color(0xFF53BDEB)
private val WaDateChip = Color(0xFF182229)

/**
 * محادثة الطلب بروح واتساب: فقاعات خضراء/داكنة + طوابع وقت + فواصل أيام +
 * علامات قراءة + رسائل جاهزة + تحديث حي + أدوات المطور قابلة للطي.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestChatScreen(
    requestId: String,
    onBack: () -> Unit,
    onOpenDashboardSection: (String) -> Unit = {},
    onOpenDetails: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
    val isAdmin = prefs.getBoolean("is_admin", false)
    val currentUserEmail = prefs.getString("user_email", "user@example.com") ?: "user@example.com"

    val request = remember { mutableStateOf(AppRequestService.getRequests(context, isDeveloper = true).find { it.id == requestId }) }
    val messages = remember { mutableStateOf(AppRequestService.getMessages(context, requestId)) }
    var newMessage by remember { mutableStateOf("") }
    var showTemplates by remember { mutableStateOf(false) }
    var showDevTools by remember { mutableStateOf(false) }

    // AI Prompt loading for developer
    var isLoadingPrompts by remember { mutableStateOf(false) }

    // تحديث حي كل 3 ثوانٍ — رسائل الطرف الآخر تظهر دون خروج
    LaunchedEffect(requestId) {
        while (isActive) {
            delay(3000)
            messages.value = AppRequestService.getMessages(context, requestId)
            request.value = AppRequestService.getRequests(context, isDeveloper = true).find { it.id == requestId }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.value.size) {
        if (messages.value.isNotEmpty()) {
            listState.animateScrollToItem(messages.value.size - 1)
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val otherName = if (isAdmin) (request.value?.userEmail ?: "العميل") else "فريق قبس 🛠️"
    val otherInitial = otherName.trim().firstOrNull()?.toString() ?: "؟"

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val msg = AppRequestService.ChatMessage(
            requestId = requestId,
            senderEmail = currentUserEmail,
            isDeveloper = isAdmin,
            message = clean
        )
        AppRequestService.sendMessage(context, msg)
        messages.value = AppRequestService.getMessages(context, requestId)
        newMessage = ""
    }

    /** قفزة للوحة المطور على قسم محدد (عبر pref يستهلكه الدخول). */
    fun jumpTo(section: String) {
        prefs.edit().putString("dev_dash_target", section).apply()
        onOpenDashboardSection(section)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(GoldPrimary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                otherInitial, color = GoldPrimary,
                                fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                if (isAdmin) (request.value?.title ?: "محادثة التطبيق") else "فريق قبس 🛠️",
                                fontFamily = CairoFont, fontWeight = FontWeight.Bold,
                                color = Color.White, fontSize = 15.sp, maxLines = 1
                            )
                            Text(
                                request.value?.let {
                                    when (it.status) {
                                        "completed" -> "مكتمل ✅ • ${it.progress}٪"
                                        "in_progress" -> "قيد التنفيذ ⚙️ • ${it.progress}٪"
                                        else -> "قيد المراجعة 🆕"
                                    }
                                } ?: "متصل",
                                fontFamily = NotoSansFont, color = Color(0xFF8696A0), fontSize = 11.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1F2C34))
            )
        },
        bottomBar = {
            Column {
                // رسائل جاهزة للمطور
                if (showTemplates && isAdmin) {
                    Surface(color = Color(0xFF1F2C34), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "مرحباً! استلمنا طلبك وسنراجعه قريباً ✅",
                                "أرسلنا عرض السعر — راجعه من تفاصيل الطلب 📋",
                                "نحتاج دفعة مقدمة 50٪ لبدء التنفيذ 💰",
                                "نسختك جاهزة للاختبار 🎉 أخبرنا بملاحظاتك"
                            ).forEach { tpl ->
                                Surface(
                                    color = WaBackground,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        send(tpl)
                                        showTemplates = false
                                    }
                                ) {
                                    Text(
                                        tpl, color = Color.White, fontFamily = NotoSansFont,
                                        fontSize = 13.sp, modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(WaBackground)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isAdmin) {
                        IconButton(onClick = { showTemplates = !showTemplates }) {
                            Icon(Icons.Default.Add, contentDescription = "قوالب", tint = Color(0xFF8696A0))
                        }
                        IconButton(
                            onClick = {
                                newMessage = "مرحباً عميلنا العزيز، العمل على مشروعك يسير بشكل ممتاز بفضل الله. أنجزنا ${request.value?.progress ?: 0}% حتى الآن، ونحن ملتزمون بأعلى جودة. لأي استفسار نحن هنا لخدمتك!"
                            }
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "تقرير", tint = Color(0xFF8696A0))
                        }
                    }
                    OutlinedTextField(
                        value = newMessage,
                        onValueChange = { newMessage = it },
                        placeholder = { Text("اكتب رسالة…", fontFamily = NotoSansFont, color = Color(0xFF8696A0)) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = WaTheirs,
                            unfocusedContainerColor = WaTheirs
                        ),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = if (newMessage.isNotBlank()) Color(0xFF00A884) else Color(0xFF374045),
                        shape = CircleShape,
                        modifier = Modifier.size(46.dp)
                    ) {
                        IconButton(onClick = { send(newMessage) }, enabled = newMessage.isNotBlank()) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        containerColor = WaBackground
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(padding)
                .background(WaBackground)
                .padding(horizontal = 8.dp)
        ) {
            // أدوات المطور — قابلة للطي حتى لا تزحم المحادثة
            val currentReq = request.value
            if (isAdmin && currentReq != null) {
                Surface(
                    color = Color(0xFF1F2C34),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .clickable { showDevTools = !showDevTools }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "🛠️ أدوات المطور (الخطة والبناء)",
                            color = GoldPrimary, fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(if (showDevTools) "▲" else "▼", color = GoldPrimary, fontSize = 12.sp)
                    }
                }
                if (showDevTools) {
                    DevToolsPanel(
                        context = context,
                        scope = scope,
                        prefs = prefs,
                        requestId = requestId,
                        currentReq = currentReq,
                        isLoadingPrompts = isLoadingPrompts,
                        onLoadingChange = { isLoadingPrompts = it },
                        onRequestChange = {
                            request.value = AppRequestService.getRequests(context, isDeveloper = true).find { it.id == requestId }
                        }
                    )
                }
            }

            // ── شريط الإجراءات الذكي: ينقلك للمكان الصحيح حسب حالة الطلب ──
            request.value?.let { rq ->
                SmartActionBar(
                    isAdmin = isAdmin,
                    req = rq,
                    hasRepo = context.getSharedPreferences("qabas_requests_prefs", Context.MODE_PRIVATE)
                        .getString("repo_${rq.id}", null) != null,
                    onGeneratePlan = { showDevTools = true },
                    onJumpBuild = { jumpTo("BUILD_CENTER") },
                    onOpenDetails = onOpenDetails
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {                itemsIndexed(messages.value) { index, msg ->
                    val prev = messages.value.getOrNull(index - 1)
                    if (shouldShowDayHeader(msg.timestamp, prev?.timestamp)) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Surface(color = WaDateChip, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    dayLabel(msg.timestamp), color = Color(0xFF8696A0),
                                    fontFamily = NotoSansFont, fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    val isMine = msg.isDeveloper == isAdmin
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.78f)
                                .background(
                                    color = if (isMine) WaMine else WaTheirs,
                                    shape = RoundedCornerShape(
                                        topStart = 12.dp, topEnd = 12.dp,
                                        bottomStart = if (isMine) 12.dp else 2.dp,
                                        bottomEnd = if (isMine) 2.dp else 12.dp
                                    )
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Column {
                                Text(
                                    text = msg.message,
                                    color = Color.White,
                                    fontFamily = NotoSansFont,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        timeFmt.format(Date(msg.timestamp)),
                                        color = Color(0xFF8696A0),
                                        fontFamily = NotoSansFont,
                                        fontSize = 10.sp
                                    )
                                    if (isMine) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("✓✓", color = WaTicks, fontSize = 11.sp, fontFamily = NotoSansFont)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * شريط الإجراءات الذكي: يقرأ حالة الطلب ويعرض الخطوة التالية المطلوبة
 * مع زر ينقلك لمكانها (التسعير/الخطة/المستودع/البناء/التفاصيل) — للمطور والعميل.
 */
@Composable
private fun SmartActionBar(
    isAdmin: Boolean,
    req: AppRequestService.AppRequest,
    hasRepo: Boolean,
    onGeneratePlan: () -> Unit,
    onJumpBuild: () -> Unit,
    onOpenDetails: () -> Unit
) {
    data class Action(val emoji: String, val title: String, val hint: String, val go: (() -> Unit)?)

    val action: Action = if (isAdmin) {
        when {
            req.status == "completed" -> Action("📁", "الطلب مغلق ✅", "سُلّم وأُغلق — لا خطوة مطلوبة", null)
            req.priceStatus != "accepted" -> Action(
                "💰", "إكمال التسعير",
                if (req.priceStatus == "rejected") "العميل رفض — اعرض سعراً جديداً" else "السعر غير محسوم — التوليد مقفل",
                onJumpBuild
            )
            req.generatedPrompts.isNullOrBlank() -> Action(
                "🤖", "توليد الخطة", "الخطة وقود كل ما بعدها", onGeneratePlan
            )
            !hasRepo -> Action("📦", "إنشاء المستودع", "طلب مقبول بخطة بلا بيت", onJumpBuild)
            req.progress < 100 -> Action(
                "🏗️", "متابعة البناء (${req.progress}٪)",
                "الهيكل/السحب/الدمج بانتظارك", onJumpBuild
            )
            else -> Action("📁", "جاهز للتسليم", "سلّم آخر إصدار وأغلق", onJumpBuild)
        }
    } else {
        when {
            req.priceStatus == "offered" -> Action(
                "📋", "عرض سعر بانتظارك (${req.cost}$)",
                "اقبل أو ارفض من تفاصيل الطلب", onOpenDetails
            )
            req.status == "in_progress" -> Action(
                "📊", "مشروعك قيد التنفيذ (${req.progress}٪)",
                "تابع التقدم لحظة بلحظة", onOpenDetails
            )
            req.status == "completed" -> Action("🎉", "تطبيقك جاهز!", "شكراً لثقتك بقبس", null)
            else -> Action("⏳", "طلبك قيد المراجعة", "سنرد عليك قريباً", null)
        }
    }

    if (action.go == null && action.hint.isBlank()) return
    Surface(
        color = GoldPrimary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .clickable(enabled = action.go != null) { action.go?.invoke() }
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(action.emoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    action.title, color = GoldPrimary, fontFamily = CairoFont,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                )
                Text(
                    action.hint, color = Color(0xFF8696A0), fontFamily = NotoSansFont, fontSize = 11.sp
                )
            }
            if (action.go != null) {
                Text("←", color = GoldPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun dayStart(ts: Long): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ts
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun shouldShowDayHeader(ts: Long, prevTs: Long?): Boolean {
    if (prevTs == null) return true
    return dayStart(ts) != dayStart(prevTs)
}

private fun dayLabel(ts: Long): String {    val today = dayStart(System.currentTimeMillis())
    val day = dayStart(ts)
    val diffDays = ((today - day) / 86_400_000).toInt()
    return when (diffDays) {
        0 -> "اليوم"
        1 -> "أمس"
        else -> SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(ts))
    }
}

/** أدوات المطور داخل المحادثة: مفتاح OpenRouter + توليد الخطة + نسخ + ربط البناء. */
@Composable
private fun DevToolsPanel(    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    prefs: android.content.SharedPreferences,
    requestId: String,
    currentReq: AppRequestService.AppRequest,
    isLoadingPrompts: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    onRequestChange: () -> Unit
) {
    val promptsText = currentReq.generatedPrompts
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── وكيل فهم الطلب: يقرأ المحادثة ويلخص المطلوب + يقترح سعراً ──
            var agentBusy by remember { mutableStateOf(false) }
            var agentBrief by remember { mutableStateOf<String?>(null) }
            var suggestedPrice by remember { mutableStateOf<Int?>(null) }
            Button(
                onClick = {
                    val orKey = KeyVault.openrouter
                    if (orKey.isBlank()) {
                        Toast.makeText(context, "أدخل مفتاح OpenRouter أولاً", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    agentBusy = true
                    scope.launch {
                        val history = AppRequestService.getMessages(context, requestId)
                            .takeLast(15).joinToString("\n") {
                                "${if (it.isDeveloper) "المطور" else "العميل"}: ${it.message.take(300)}"
                            }
                        val prompt = """
                            أنت محلل طلبات تطبيقات. اقرأ طلب العميل ومحادثته ثم أخرج:
                            1) سطرين: ماذا يريد فعلاً (احتياجه الحقيقي لا كلماته).
                            2) نطاق العمل: بسيط/متوسط/معقد + أهم 3 ميزات.
                            3) مخاطر أو أسئلة ناقصة (إن وجدت).
                            4) في آخر سطر وحده تماماً: PRICE: <رقم بالدولار من 30 إلى 2000 حسب الحجم>
                            الطلب: ${currentReq.title} — ${currentReq.description} (الهدف: ${currentReq.goal})
                            المحادثة:
                            $history
                        """.trimIndent()
                        val brief = OpenRouterService.chat(
                            orKey,
                            "أنت محلل طلبات دقيق وموجز. تجيب بالعربية وتنهي بسطر PRICE: الرقم.",
                            prompt, 1500
                        )
                        val price = brief?.let { Regex("""PRICE:\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                        agentBrief = brief?.replace(Regex("""PRICE:\s*\d+"""), "")?.trim()
                        suggestedPrice = price
                        agentBusy = false
                    }
                },
                enabled = !agentBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (agentBusy) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("الوكيل يقرأ الطلب…", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                } else {
                    Text("🤖 افهم الطلب معي", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            agentBrief?.let { brief ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(color = DeepSlate, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(brief.take(900), color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(10.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // اعتمد السعر المقترح كعرض رسمي
                    if (suggestedPrice != null && suggestedPrice!! > 0) {
                        Button(
                            onClick = {
                                AppRequestService.updateRequestProgressAndPayment(
                                    context, requestId, cost = suggestedPrice, priceStatus = "offered"
                                )
                                AppRequestService.sendMessage(
                                    context,
                                    AppRequestService.ChatMessage(
                                        requestId = requestId, senderEmail = "dev", isDeveloper = true,
                                        message = "عرض سعر 📋: تكلفة تطبيقك $${suggestedPrice}. افتح تفاصيل الطلب للقبول أو الرفض — لن نبدأ قبل موافقتك."
                                    )
                                )
                                onRequestChange()
                                Toast.makeText(context, "أُرسل العرض ${suggestedPrice}$ للعميل 📋", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("اعتمد ${suggestedPrice}$ عرضاً 📋", color = Color.Black, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    // ابدأ بصمت: خطة + ربط + تنفيذ — بلا أي رسالة للعميل
                    Button(
                        onClick = {
                            scope.launch {
                                onLoadingChange(true)
                                val fresh = AppRequestService.getRequests(context, isDeveloper = true).find { it.id == requestId } ?: currentReq
                                if (fresh.generatedPrompts.isNullOrBlank()) {
                                    val prompts = AppRequestService.generatePromptsForDeveloper(fresh)
                                    AppRequestService.updateRequestPrompts(context, requestId, prompts)
                                }
                                AppRequestService.updateRequestProgressAndPayment(context, requestId, status = "in_progress", progress = 10)
                                AppRequestService.setActiveBuildRequest(context, requestId)
                                context.getSharedPreferences("qabas_requests_prefs", Context.MODE_PRIVATE)
                                    .edit().putBoolean("silent_$requestId", true).apply()
                                onRequestChange()
                                onLoadingChange(false)
                                Toast.makeText(context, "🔇 بدأ الوكيل بصمت — يعمل دون مراسلة العميل", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("🔇 ابدأ بصمت", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (promptsText.isNullOrBlank()) {
                val hasGemini = KeyVault.gemini.isNotBlank()
                val savedOrKey = remember { mutableStateOf(prefs.getString("openrouter_key", "") ?: "") }
                if (!hasGemini && savedOrKey.value.isBlank()) {
                    var draft by remember { mutableStateOf("") }
                    var saved by remember { mutableStateOf(false) }
                    Text(
                        "🔑 مفتاح OpenRouter المجاني — أو تُستخدم الخطة المحلية تلقائياً",
                        color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it; saved = false },
                            placeholder = { Text("sk-or-…", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GoldPrimary,
                                unfocusedBorderColor = Color(0xFF1E293B),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val v = draft.trim()
                                if (v.startsWith("sk-or-")) {
                                    prefs.edit().putString("openrouter_key", v).apply()
                                    savedOrKey.value = v
                                    saved = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(if (saved) "تم ✓" else "حفظ", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Button(
                    onClick = {
                        scope.launch {
                            onLoadingChange(true)
                            val prompts = AppRequestService.generatePromptsForDeveloper(currentReq)
                            AppRequestService.updateRequestPrompts(context, requestId, prompts)
                            onRequestChange()
                            onLoadingChange(false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    if (isLoadingPrompts) {
                        CircularProgressIndicator(color = DeepSlate, modifier = Modifier.size(20.dp))
                    } else {
                        Text("توليد خطة البرمجة بالذكاء الاصطناعي", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text("خطة البرمجة المقترحة:", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(promptsText.take(400) + if (promptsText.length > 400) "…" else "", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("خطة", promptsText))
                            Toast.makeText(context, "نُسخت الخطة 📋", Toast.LENGTH_SHORT).show()
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("📋 نسخ", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            AppRequestService.updateRequestProgressAndPayment(context, requestId, status = "in_progress")
                            AppRequestService.setActiveBuildRequest(context, requestId)
                            onRequestChange()
                            Toast.makeText(context, "🛠️ رُبط بمركز البناء — ارجع للوحة ← مركز البناء", Toast.LENGTH_LONG).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("🛠️ ابدأ البناء", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
