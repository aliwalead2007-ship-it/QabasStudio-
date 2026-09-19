package com.qabas.app

/**
 * AIAssistantScreen.kt
 * تم تحسين المساعد الدعوي الذكي ليدعم 4 أوضاع تخصصية (مخرج، باحث شرعي، خبير فيرال، توجيه صوتي)
 * مع اقتراحات مبوبة (أفكار، تصحيح، سكريبت، دليل شرعي) وإمكانية نسخ وتحويل أي مخرج
 * إلى ريلز ومشروع مونتاج مباشرة بضغطة زر.
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.launch

data class AssistantModePreset(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val systemInstruction: String,
    val initialMessage: String
)

data class AssistantSuggestionCategory(
    val id: String,
    val name: String,
    val icon: String,
    val suggestions: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIAssistantScreen(
    onBack: () -> Unit,
    onCreateReelFromScript: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showSystemInstructionDialog by remember { mutableStateOf(false) }

    val presets = remember {
        listOf(
            AssistantModePreset(
                id = "director",
                title = "🎬 مخرج سكريبتات",
                icon = Icons.Default.MovieFilter,
                systemInstruction = "أنت مخرج سكريبتات قبس السينمائي. ركز على تقسيم السكريبت لمشاهد مع الوصف البصري (B-Roll) والخطاف البصري (Hook) في أول 3 ثوانٍ والمؤثرات الصوتية.",
                initialMessage = "أهلاً بك في استوديو قبس! أنا مخرج السكريبتات الذكي.\n• صياغة سكريبت سينمائي 9:16 مقسم لمشاهد.\n• تحديد B-Roll ومؤثرات الصوت.\n• كتابة خطاف (Hook) فيروسي جاذب."
            ),
            AssistantModePreset(
                id = "scholar",
                title = "📖 باحث شرعي",
                icon = Icons.Default.MenuBook,
                systemInstruction = "أنت باحث شرعي وموثق نصوص إسلامية. ركز على توثيق الآيات القرآنية بالتشكيل ورقم السورة والآية، والأحاديث الصحيحة من البخاري ومسلم مع تخريجها الموثق وتجنب الشبهات.",
                initialMessage = "مرحباً بك! أنا الباحث الشرعي الموثق في قبس.\n• استخراج الآيات القرآنية بالتشكيل الكامل.\n• التثبت وتخريج الأحاديث الصحيحة.\n• تبسيط المعاني والشروح للفيديوهات القصيرة."
            ),
            AssistantModePreset(
                id = "viral",
                title = "🔥 خبير الفيرال",
                icon = Icons.Default.LocalFireDepartment,
                systemInstruction = "أنت خبير الانتشار الفيرالي والخطافات (Hooks). صغ خطافات بصرية وسمعية قوية في أول 3 ثوانٍ، واقترح العناوين والوسوم (Hashtags) الأكثر انتشاراً دون المساس بوقار المحتوى.",
                initialMessage = "أهلاً بك! أنا مستشارك لصناعة ريلز فيروسية متصدرة.\n• ابتكار 3 خطافات لافتة في أول 3 ثوان.\n• اقتراح عناوين بصرية عالية النقرات.\n• هاشتاقات مستهدفة لصناع المحتوى الهادف."
            ),
            AssistantModePreset(
                id = "voice",
                title = "🎙️ توجيه صوتي",
                icon = Icons.Default.Mic,
                systemInstruction = "أنت مستشار الأداء والتوجيه الصوتي. صغ النصوص مع تحديد النبرة (وثائقي، خشوع، حماسي) وعلامات التوقف [وقف] والكلمات المفتاحية للتركيز.",
                initialMessage = "مرحباً بك! أنا موجه الإلقاء والأداء الصوتي.\n• تحديد نبرات الإلقاء (خشوع، وثائقي، حماسي).\n• إضافة علامات الوقوف للتسجيل والمُلقن.\n• تحسين أداء التعليق الصوتي AI."
            )
        )
    }

    // 4 Quick Suggestion Categories (أفكار – تصحيح – سكريبت – دليل شرعي)
    val suggestionCategories = remember {
        listOf(
            AssistantSuggestionCategory(
                id = "ideas",
                name = "أفكار 💡",
                icon = "💡",
                suggestions = listOf(
                    "فكرة ريلز دعوي مؤثر عن قيام الليل وعظمة السحر",
                    "3 خطافات فيروسية عن فضل بر الوالدين وأثره",
                    "فكرة فيديو قصير عن الاستغفار وتفريج الكروب",
                    "سلسلة قصص التابعين وأثرها في دقيقة واحدة"
                )
            ),
            AssistantSuggestionCategory(
                id = "correction",
                name = "تصحيح ✍️",
                icon = "✍️",
                suggestions = listOf(
                    "راجع هذا النص لغوياً وأضف التشكيل الدقيق للآيات",
                    "دقق صحة الحديث الشريف وتخريجه المعتمد",
                    "اختصر هذا النص ليكون ريلز مركزاً مدته 30 ثانية",
                    "حسّن أسلوب النص ليكون بنبرة وثائقية مهيبة ومؤثرة"
                )
            ),
            AssistantSuggestionCategory(
                id = "script",
                name = "سكريبت 🎬",
                icon = "🎬",
                suggestions = listOf(
                    "صيغ سكريبت ريلز 45 ثانية مع B-Roll عن الصبر",
                    "سكريبت مشهد سينمائي عن قصة موسى والخضر مع العبر",
                    "اكتب سكريبت شورتس سريع مع خطاف في أول 3 ثوان",
                    "سكريبت دعوي بنبرة خاشعة مع فواصل موسيقية هادئة"
                )
            ),
            AssistantSuggestionCategory(
                id = "sharia",
                name = "دليل شرعي 📖",
                icon = "📖",
                suggestions = listOf(
                    "ابحث عن أحاديث صحيحة في فضل الصدقة مع التخريج",
                    "آيات قرآنية عن طمأنينة القلب والسكينة بالتشكيل",
                    "شرح موثق ومختصر لحديث (إنما الأعمال بالنيات)",
                    "أقوال أئمة السلف في الإخلاص وحقيقة التوكل"
                )
            )
        )
    }

    var selectedSuggestionCategory by remember { mutableStateOf(suggestionCategories[0].id) }
    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    val currentPreset = presets[selectedPresetIndex]
    val prefs = remember { context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE) }
    // نموذج OpenRouter المختار (null = تلقائي) — يظهر فقط عند وجود المفتاح
    var selectedOrModel by remember { mutableStateOf<String?>(null) }
    val hasOrKey = remember {
        val k = prefs.getString("openrouter_key", "") ?: ""
        k.isNotBlank()
    }
    // وضع الأدوات 🛠️ — النموذج ينفذ دوال حقيقية بدل التخمين (مفعّل افتراضياً مع المفتاح)
    var toolsMode by remember(hasOrKey) { mutableStateOf(hasOrKey) }
    val orModelLabels = remember {
        mapOf(
            "meta-llama/llama-3.3-70b-instruct:free" to "🦙 Llama 3.3",
            "google/gemma-3-27b-it:free" to "💎 Gemma 3",
            "qwen/qwen3-32b:free" to "🌊 Qwen 3"
        )
    }
    var customInstructionText by remember { mutableStateOf("") }

    val messages = remember {
        mutableStateListOf(
            Pair(true, currentPreset.initialMessage)
        )
    }

    var isTyping by remember { mutableStateOf(false) }
    var lastFailedPrompt by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var hasGeminiKey by remember {
        val key = prefs.getString("gemini_key", "") ?: ""
        mutableStateOf(key.isNotBlank() && key != "YOUR_GEMINI_API_KEY")
    }

    fun sendMessage(textToSend: String) {
        if (textToSend.isBlank() || isTyping) return
        messages.add(Pair(false, textToSend))
        query = ""
        isTyping = true
        lastFailedPrompt = null

        coroutineScope.launch {
            listState.animateScrollToItem(0)
        }

        coroutineScope.launch {
            try {
                val tasteContext = TasteManager.getTasteContext(context)
                val fullSystemInstruction = buildString {
                    append(currentPreset.systemInstruction)
                    append("\n\n[إرشادات عامة للمحتوى الإسلامي]:")
                    append("\n- استشهد بالآيات القرآنية بالرسم والتشكيل السليم.")
                    append("\n- وثق الأحاديث النبوية بذكر المخرج (البخاري، مسلم، إلخ) وتجنب الأحاديث الضعيفة أو المنكرة.")
                    append("\n- عند كتابة سكريبت، قسّمه إلى مشاهد 9:16 مع B-Roll وخطاف جذاب في أول 3 ثوان.")
                    if (tasteContext.isNotBlank()) {
                        append("\n\n[سياق أسلوب وهوية صانع المحتوى (Taste Profile)]:\n")
                        append(tasteContext)
                    }
                    if (customInstructionText.isNotBlank()) {
                        append("\n\n[تعليمات إضافية خاصة بالمستخدم]:\n")
                        append(customInstructionText)
                    }
                }

                val contextHistory = messages.takeLast(10).map { Pair(it.first, it.second) }
                val orKey = prefs.getString("openrouter_key", "") ?: ""
                val response = if (toolsMode && orKey.isNotBlank()) {
                    // وضع الأدوات: النموذج يستدعي دوال التطبيق الحقيقية
                    val (text, used) = OpenRouterService.chatWithTools(
                        orKey, fullSystemInstruction, contextHistory,
                        AiTools.definitions(context), selectedOrModel
                    )
                    val finalText = text.ifBlank { "تعذر التنفيذ بالأدوات — أُعيد التوجيه للمسار العادي." }
                    if (text.isBlank()) {
                        AppServices.chatWithAssistant(contextHistory, fullSystemInstruction, selectedOrModel)
                    } else if (used.isNotEmpty()) {
                        "🛠️ استخدمت: ${used.joinToString("، ")}\n\n$finalText"
                    } else finalText
                } else {
                    AppServices.chatWithAssistant(contextHistory, fullSystemInstruction, selectedOrModel)
                }
                isTyping = false
                messages.add(Pair(true, response))
            } catch (e: Exception) {
                isTyping = false
                lastFailedPrompt = textToSend
                val errorMsg = "⚠️ حدث خطأ أثناء الاتصال بخدمة الذكاء الاصطناعي: ${e.localizedMessage ?: "يرجى التأكد من الاتصال بالإنترنت ومفتاح Gemini API في الإعدادات."}"
                messages.add(Pair(true, errorMsg))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = GoldPrimary.copy(alpha = 0.2f),
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "المساعد الدعوي الذكي ✦",
                                color = GoldPrimary,
                                fontWeight = FontWeight.Bold,
                                fontFamily = CairoFont,
                                fontSize = 16.sp
                            )
                            Text(
                                "مدعوم بنماذج Gemini لصناعة السكريبتات والتوثيق",
                                color = TextSecondary,
                                fontFamily = CairoFont,
                                fontSize = 10.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "العودة", tint = GoldPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showSystemInstructionDialog = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "System Instruction", tint = GoldPrimary)
                    }
                    IconButton(onClick = {
                        messages.clear()
                        messages.add(Pair(true, currentPreset.initialMessage))
                    }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Chat", tint = Color.Gray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepSlate)
            )
        },
        containerColor = DeepSlate
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Connection status pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (hasGeminiKey) Color(0xFF0D2818) else Color(0xFF2D1808),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        0.5.dp,
                        if (hasGeminiKey) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (hasGeminiKey) Color(0xFF10B981) else Color(0xFFF59E0B))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (hasGeminiKey) "محرك Gemini 1.5/2.5 متصل فعلياً ✦" else "وضع الاستجابة السريعة (أضف مفتاحك من الإعدادات)",
                            color = if (hasGeminiKey) Color(0xFF6EE7B7) else Color(0xFFFCD34D),
                            fontSize = 11.sp,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Text(
                    text = "${messages.size} رسائل",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = CairoFont
                )
            }

            // 1. Assistant Mode Selector
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                items(
                    count = presets.size,
                    key = { index -> presets[index].id }
                ) { index ->
                    val preset = presets[index]
                    val isSelected = index == selectedPresetIndex
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (selectedPresetIndex != index) {
                                selectedPresetIndex = index
                                messages.clear()
                                messages.add(Pair(true, preset.initialMessage))
                            }
                        },
                        label = {
                            Text(
                                preset.title,
                                fontFamily = CairoFont,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Icon(preset.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GoldPrimary,
                            selectedLabelColor = DeepSlate,
                            selectedLeadingIconColor = DeepSlate,
                            containerColor = Color(0xFF151B2B),
                            labelColor = TextSecondary,
                            iconColor = GoldPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = Color(0xFF1E293B),
                            selectedBorderColor = GoldPrimary
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }

            // 1.5 OpenRouter model picker — يظهر فقط عند وجود مفتاح openrouter_key
            if (hasOrKey) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = toolsMode,
                            onClick = { toolsMode = !toolsMode },
                            label = { Text("🛠️ أدوات حقيقية", fontFamily = CairoFont, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GoldPrimary,
                                selectedLabelColor = DeepSlate,
                                containerColor = Color(0xFF151B2B),
                                labelColor = TextSecondary
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedOrModel == null,
                            onClick = { selectedOrModel = null },
                            label = { Text("⚡ تلقائي", fontFamily = CairoFont, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF10B981),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF151B2B),
                                labelColor = TextSecondary
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                    items(
                        count = orModelLabels.size,
                        key = { i -> orModelLabels.keys.elementAt(i) }
                    ) { i ->
                        val modelId = orModelLabels.keys.elementAt(i)
                        FilterChip(
                            selected = selectedOrModel == modelId,
                            onClick = { selectedOrModel = if (selectedOrModel == modelId) null else modelId },
                            label = { Text(orModelLabels[modelId] ?: modelId, fontFamily = CairoFont, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF10B981),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF151B2B),
                                labelColor = TextSecondary
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }
                }

            // 2. Chat Area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isTyping) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .background(Color(0xFF0B0F19), RoundedCornerShape(16.dp))
                                    .border(1.dp, GoldPrimary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                    .padding(14.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        QabasGlowingSpinner(size = 20.dp, strokeWidth = 2.dp, color = GoldPrimary)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            "جاري الصياغة والتحليل بالذكاء الاصطناعي... ✦",
                                            color = GoldPrimary,
                                            fontFamily = CairoFont,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    QabasIndeterminateProgressBar(height = 3.dp)
                                }
                            }
                        }
                    }
                }

                items(messages.size) { i ->
                    val msgIndex = messages.size - 1 - i
                    val msg = messages[msgIndex]
                    val isAi = msg.first

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                    ) {
                        if (isAi) {
                            Surface(
                                color = GoldPrimary.copy(alpha = 0.2f),
                                shape = CircleShape,
                                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Column(
                            horizontalAlignment = if (isAi) Alignment.Start else Alignment.End,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (isAi) 0.92f else 0.85f)
                                    .background(
                                        if (isAi) Color(0xFF0B0F19) else Color(0xFF151B2B),
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = if (isAi) 4.dp else 16.dp,
                                            bottomEnd = if (isAi) 16.dp else 4.dp
                                        )
                                    )
                                    .border(
                                        1.dp,
                                        if (isAi) GoldPrimary.copy(alpha = 0.35f) else GoldPrimary.copy(alpha = 0.6f),
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = if (isAi) 4.dp else 16.dp,
                                            bottomEnd = if (isAi) 16.dp else 4.dp
                                        )
                                    )
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = msg.second,
                                    color = if (isAi) Color(0xFFF1F5F9) else GoldPrimary,
                                    fontFamily = NotoSansFont,
                                    fontSize = 13.5.sp,
                                    lineHeight = 22.sp
                                )
                            }

                            // Action Buttons Strip
                            if (isAi) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // If message indicates an error and we have a failed prompt, show Retry button
                                    if (msg.second.startsWith("⚠️") && lastFailedPrompt != null) {
                                        Surface(
                                            onClick = {
                                                lastFailedPrompt?.let { sendMessage(it) }
                                            },
                                            color = Color(0xFFEF4444).copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFFEF4444))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color(0xFFFCA5A5), modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("إعادة المحاولة 🔄", color = Color(0xFFFCA5A5), fontSize = 12.sp, fontFamily = NotoSansFont)
                                            }
                                        }
                                    }

                                    // Quick Copy Full Text
                                    Surface(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val clip = ClipData.newPlainText("Qabas Script", msg.second)
                                            clipboard?.setPrimaryClip(clip)
                                            Toast.makeText(context, "تم نسخ النص كاملاً إلى الحافظة 📋", Toast.LENGTH_SHORT).show()
                                        },
                                        color = Color(0xFF151B2B),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF1E293B))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = GoldSecondary, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("نسخ 📋", color = TextSecondary, fontSize = 12.sp, fontFamily = NotoSansFont)
                                        }
                                    }

                                    // Quick Copy Quranic Verses / Hadith if found
                                    val containsQuranOrHadith = msg.second.contains("﴿") || msg.second.contains("قال رسول الله") || msg.second.contains("حديث")
                                    if (containsQuranOrHadith) {
                                        Surface(
                                            onClick = {
                                                val text = msg.second
                                                val quote = if (text.contains("﴿") && text.contains("﴾")) {
                                                    val start = text.indexOf("﴿")
                                                    val end = text.indexOf("﴾", start) + 1
                                                    if (end > start) text.substring(start, end) else text
                                                } else {
                                                    text
                                                }
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                val clip = ClipData.newPlainText("Qabas Sharia Quote", quote)
                                                clipboard?.setPrimaryClip(clip)
                                                Toast.makeText(context, "تم نسخ الشاهد القرآني/الحديث بدقة 📖✨", Toast.LENGTH_SHORT).show()
                                            },
                                            color = Color(0xFF0F2E1B),
                                            shape = RoundedCornerShape(8.dp),
                                            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF10B981))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.AutoStories, contentDescription = "Copy Quote", tint = Color(0xFF34D399), modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("نسخ الشاهد 📖", color = Color(0xFF6EE7B7), fontSize = 12.sp, fontFamily = NotoSansFont)
                                            }
                                        }
                                    }

                                    // Create Reel From Script
                                    Surface(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val clip = ClipData.newPlainText("Qabas Reel Script", msg.second)
                                            clipboard?.setPrimaryClip(clip)

                                            onCreateReelFromScript(msg.second)
                                            Toast.makeText(context, "جاري تحويل السكريبت إلى استوديو الريلز والمونتاج 🎬✨", Toast.LENGTH_LONG).show()
                                        },
                                        color = GoldPrimary.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, GoldPrimary)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Movie, contentDescription = "Reel", tint = GoldPrimary, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("صناعة ريلز 🎬", color = GoldPrimary, fontSize = 12.sp, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    // Use in input box
                                    Surface(
                                        onClick = {
                                            query = "طور هذا النص: ${msg.second.take(80)}..."
                                        },
                                        color = Color(0xFF151B2B),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF1E293B))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("تعديل ✍️", color = TextSecondary, fontSize = 12.sp, fontFamily = NotoSansFont)
                                        }
                                    }
                                }
                            } else {
                                // User message action (Quick Create Project from Idea)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        onClick = {
                                            onCreateReelFromScript(msg.second)
                                            Toast.makeText(context, "جاري تحويل فكرتك إلى مشروع مونتاج جديد 🎬", Toast.LENGTH_SHORT).show()
                                        },
                                        color = Color(0xFF151B2B),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF1E293B))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(11.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("بدء مشروع بهذه الفكرة", color = GoldPrimary, fontSize = 11.sp, fontFamily = CairoFont)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Categorized Quick Suggestions Strip (أفكار – تصحيح – سكريبت – دليل شرعي)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0F19), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF151B2B), RoundedCornerShape(16.dp))
                    .padding(8.dp)
            ) {
                // Category Tabs
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(suggestionCategories) { cat ->
                        val isCatSelected = selectedSuggestionCategory == cat.id
                        Surface(
                            onClick = { selectedSuggestionCategory = cat.id },
                            color = if (isCatSelected) GoldPrimary else Color(0xFF151B2B),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isCatSelected) GoldPrimary else Color(0xFF1E293B)
                            )
                        ) {
                            Text(
                                text = cat.name,
                                color = if (isCatSelected) DeepSlate else TextPrimary,
                                fontFamily = CairoFont,
                                fontSize = 11.sp,
                                fontWeight = if (isCatSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Suggestion Pills for active category
                val activeCategory = suggestionCategories.find { it.id == selectedSuggestionCategory } ?: suggestionCategories[0]
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(activeCategory.suggestions) { suggestionText ->
                        Surface(
                            onClick = { sendMessage(suggestionText) },
                            color = Color(0xFF161F2E),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, GoldPrimary.copy(alpha = 0.35f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Icon(Icons.Default.NorthWest, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = suggestionText,
                                    color = Color(0xFFF1F5F9),
                                    fontFamily = CairoFont,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 4. Input Area
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "اطلب سكريبت، تدقيق حديث، أو خطافات ريلز...",
                            color = Color.Gray,
                            fontFamily = CairoFont,
                            fontSize = 12.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedBorderColor = GoldPrimary,
                        unfocusedContainerColor = Color(0xFF0B0F19),
                        focusedContainerColor = Color(0xFF0B0F19),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                val isSendEnabled = query.isNotBlank() && !isTyping

                IconButton(
                    onClick = { sendMessage(query) },
                    modifier = Modifier
                        .size(48.dp)
                        .background(if (isSendEnabled) GoldPrimary else Color(0xFF151B2B), CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "إرسال",
                        tint = if (isSendEnabled) DeepSlate else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // System Instruction Customization Dialog
        if (showSystemInstructionDialog) {
        AlertDialog(
            onDismissRequest = { showSystemInstructionDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = GoldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("تخصيص تعليمات النظام (System Instruction)", color = GoldPrimary, fontFamily = TajawalFont, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "تتيح لك تعليمات النظام توجيه الذكاء الاصطناعي (Gemini 1.5 Flash) بالأسلوب والدور المطلوب لاستوديو قبس:",
                        color = TextSecondary,
                        fontFamily = CairoFont,
                        fontSize = 11.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("التعليمات الافتراضية للنمط المحدد:", color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B0F19), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(currentPreset.systemInstruction, color = TextSecondary, fontSize = 12.sp, fontFamily = NotoSansFont)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("أضف تعليمات مخصصة إضافية (اختياري):", color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = customInstructionText,
                        onValueChange = { customInstructionText = it },
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                        placeholder = { Text("مثال: اجعل الأسلوب بالعامية المبسطة، أو ركز على سكريبتات الشورتس السريعة...", color = Color.DarkGray, fontSize = 12.sp, fontFamily = NotoSansFont) },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedBorderColor = GoldPrimary,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSystemInstructionDialog = false
                        Toast.makeText(context, "تم حفظ تعليمات النظام بنجاح ✦", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text("حفظ وتطبيق", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    customInstructionText = ""
                    showSystemInstructionDialog = false
                }) {
                    Text("إعادة الضبط", color = Color.Gray, fontFamily = CairoFont)
                }
            },
            containerColor = Color(0xFF151B2B)
        )
    }
    }
}
