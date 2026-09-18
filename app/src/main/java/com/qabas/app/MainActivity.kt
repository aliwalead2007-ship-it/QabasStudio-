package com.qabas.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.qabas.app.ui.theme.*
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class Scene(
    val title: String,
    val description: String,
    val durationInSeconds: Int,
    val visualEffect: String = "بدون",
    val tempo: String = "عادي",
    val transitionType: String = "Fade",
    val mediaUrl: String? = null
)

@Composable
fun RatioButton(
    text: String,
    isSelected: Boolean,
    gradient: androidx.compose.ui.graphics.Brush,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Color.Transparent else DeepSlate
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isSelected) gradient
                    else androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                    RoundedCornerShape(8.dp)
                )
                .border(
                    1.dp,
                    if (isSelected) Color.Transparent else Color(0xFF333333),
                    RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = if (isSelected) DeepSlate else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle deep link: qabas://keys/import?gemini=...&groq=...
        handleDeepLink(intent)

        val prefs = getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
        val appLanguage = prefs.getString("app_language", "ar") ?: "ar"

        ThemeManager.init(this)
        Translator.setLanguage(appLanguage)
        val locale = java.util.Locale.forLanguageTag(appLanguage)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(locale)
        val localizedContext = createConfigurationContext(config)

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                StyleBrain.init(this@MainActivity)
                // إصلاح القوة الوهمية 50 عندما السمات فارغة
                StyleBrainLoadFix.repairPersistedEmptyCore(this@MainActivity)
                StyleManager.init(this@MainActivity)
            }
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val layoutDirection =
                    if (appLanguage == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(
                    LocalLayoutDirection provides layoutDirection,
                    LocalContext provides localizedContext,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides this@MainActivity
                ) {
                    LaunchedEffect(Unit) {
                        launch(Dispatchers.IO) {
                            runCatching {
                                LeagueService.checkAndProcessWeeklyLeague(this@MainActivity)
                                PointsManager.checkAndAwardDailyLogin(this@MainActivity)
                            }
                        }
                        launch {
                            delay(2500)
                            var isFirstLoad = true
                            var lastTransactionCount = 0
                            runCatching {
                                CloudServices.Database.observeAllTransactions().collect { transactions ->
                                    val sharedPrefs =
                                        getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
                                    val notifsEnabled =
                                        sharedPrefs.getBoolean("dev_transaction_notifications", false)

                                    if (isFirstLoad) {
                                        lastTransactionCount = transactions.size
                                        isFirstLoad = false
                                    } else if (
                                        transactions.size > lastTransactionCount && notifsEnabled
                                    ) {
                                        val newTx = transactions.firstOrNull()
                                        newTx?.let { tx ->
                                            val productId = tx["productId"] as? String ?: "Unknown"
                                            val price = tx["priceAmount"] as? Double ?: 0.0
                                            NotificationHelper.showNotification(
                                                this@MainActivity,
                                                "مبيعة جديدة! 💰",
                                                "تم بيع $productId بقيمة $${price}"
                                            )
                                        }
                                        lastTransactionCount = transactions.size
                                    } else {
                                        lastTransactionCount = transactions.size
                                    }
                                }
                            }
                        }
                    }
                    QabasStudioScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: android.content.Intent) {
        val result = KeyDeepLinkHandler.parseIntent(intent) ?: return

        when (result.action) {
            "import" -> {
                val (success, msg) = KeyDeepLinkHandler.applyImportedKeys(this, result.keys)
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                SystemLogsManager.addLog("DEEP_LINK", msg, if (success) androidx.compose.ui.graphics.Color(0xFF10B981) else androidx.compose.ui.graphics.Color(0xFFEF4444))
            }
            "sync" -> {
                when (result.syncDirection) {
                    "push" -> KeySyncService.pushToCloud(this) { _, msg ->
                        runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
                    }
                    "pull" -> KeySyncService.pullFromCloud(this) { _, msg ->
                        runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
                    }
                    else -> KeySyncService.syncBidirectional(this) { _, msg ->
                        runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
                    }
                }
            }
            "validate" -> {
                android.widget.Toast.makeText(this, "جاري التحقق من جميع المفاتيح...", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun QabasBottomNavigation(
    currentRoute: AppState,
    onNavigate: (AppState) -> Unit
) {
    val navItems = remember {
        listOf(
            Triple(AppState.HOME, Icons.Default.Home, "الرئيسية"),
            Triple(AppState.HADITH_STUDIO, Icons.Default.FormatQuote, "بطاقة حديث"),
            Triple(AppState.QURAN_HUB, Icons.Default.MenuBook, "القرآن"),
            Triple(AppState.REELS, Icons.Default.Movie, "ريلز"),
            Triple(AppState.SETTINGS, Icons.Default.Settings, "الإعدادات")
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                ambientColor = GoldPrimary.copy(alpha = 0.10f),
                spotColor = GoldPrimary.copy(alpha = 0.15f)
            )
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        Color(0xFF1E293B).copy(alpha = 0.4f),
                        GoldPrimary.copy(alpha = 0.5f),
                        GoldSecondary.copy(alpha = 0.3f),
                        Color(0xFF1E293B).copy(alpha = 0.4f)
                    )
                ),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ),
        color = Color(0xFF0B1120)
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            contentColor = GoldPrimary,
            tonalElevation = 0.dp,
            modifier = Modifier.height(72.dp)
        ) {
            navItems.forEach { (route, icon, labelKey) ->
                val label = Translator.tr(labelKey)
                val isSelected = currentRoute == route
                val iconScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isSelected) 1.15f else 1.0f,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                    ),
                    label = "nav_icon_scale"
                )

                NavigationBarItem(
                    icon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer(scaleX = iconScale, scaleY = iconScale)
                        )
                    },
                    label = {
                        Text(
                            text = label,
                            fontFamily = CairoFont,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    selected = isSelected,
                    onClick = { onNavigate(route) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DeepSlate,
                        unselectedIconColor = Color(0xFF94A3B8),
                        selectedTextColor = GoldPrimary,
                        unselectedTextColor = Color(0xFF94A3B8),
                        indicatorColor = GoldPrimary
                    )
                )
            }
        }
    }
}

enum class AppState {
    PREMIUM_UPGRADE, DATA_LOADING, ONBOARDING, LOGIN, REGISTER, FORGOT_PASSWORD,
    DEVELOPER_DASHBOARD, HOME, PROJECTS, INPUT, UNDERSTANDING, RESOURCES, PROCESSING, REVIEW,
    ADVANCED_EDIT, PAYMENT, SAVE_SHARE, SAVE_PROJECT, API_DOCS, SETTINGS, TELEPROMPTER,
    AUDIO_LIBRARY, AI_ASSISTANT, YOUTUBE_STUDIO, LEADERBOARD, PROFILE, APP_IDEA_FORM,
    REQUEST_CHAT, NOTIFICATIONS, REELS, REQUEST_DETAILS, TASTE_PROFILE, SMART_DIRECTOR, REWARDS,
    KNOWLEDGE_HUB, CONTENT_GUARD, QURAN_HUB, HADITH_STUDIO, ENTERPRISE_PORTAL, VIDEO_STYLE_CLONER,
    PHOTO_STUDIO, STYLE_SELECTION, CREATE_STYLE_OBJECT, DIRECTOR_CHAT,
    QASAS, TRANSCRIPT_EDIT, CLIP_EXTRACTOR
}

@Immutable
data class ProjectState(
    val selectedRatio: String = "9:16",
    val inputText: String = "",
    val contentType: String = Translator.tr("قصة تاريخية"),
    val contentTone: String = Translator.tr("ملحمي"),
    val targetPlatform: String = "TikTok",
    val marketingGoal: String = Translator.tr("تفاعل (Engagement)"),
    val callToAction: String = Translator.tr("متابعة للمزيد"),
    val videoStyleAnalysis: VideoStyleAnalysis? = null,
    val mediaResources: List<MediaResource> = emptyList(),
    val currentProjectId: String? = null,
    val projectTitle: String = Translator.tr("مشروع جديد"),
    val appState: AppState = AppState.HOME,
    val showChatSheet: Boolean = false,
    val videoDuration: String = Translator.tr("30 ثانية"),
    val editingStyle: String = Translator.tr("أسلوب 3nvus / نيون داكن"),
    val voiceOver: String = Translator.tr("عميق"),
    val musicVibe: String = Translator.tr("ملحمية"),
    val styleDescription: String = "",
    val selectedTemplate: String = Translator.tr("تلقائي"),
    val customTemplate: Template? = null,
    val videoQuality: String = Translator.tr("عالية الدقة 1080p"),
    val ambientSound: String = Translator.tr("تلقائي"),
    val libraryScenes: List<Scene> = emptyList(),
    val scenesToProcess: List<Scene>? = null,
    val projects: List<ProjectService.Project> = emptyList(),
    val finalVideoPath: String = "",
    val selectedRequestId: String? = null,
    val sourceType: String = "idea",
    val sourceUris: List<String> = emptyList(),
    val sourceLabel: String? = null
)

class ProjectViewModel(private val context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
    private val projectService = ProjectService(context)

    private val _state = MutableStateFlow(
        ProjectState(
            appState = AppState.DATA_LOADING,
            selectedRatio = prefs.getString("ratio", "9:16") ?: "9:16",
            inputText = prefs.getString("input", "") ?: "",
            videoDuration = prefs.getString("duration", Translator.tr("30 ثانية"))
                ?: Translator.tr("30 ثانية"),
            editingStyle = prefs.getString("style", Translator.tr("أسلوب 3nvus / نيون داكن"))
                ?: Translator.tr("أسلوب 3nvus / نيون داكن"),
            voiceOver = prefs.getString("voice", Translator.tr("عميق")) ?: Translator.tr("عميق"),
            musicVibe = prefs.getString("music", Translator.tr("ملحمية")) ?: Translator.tr("ملحمية"),
            styleDescription = prefs.getString("styleDesc", "") ?: "",
            selectedTemplate = prefs.getString("template", Translator.tr("تلقائي"))
                ?: Translator.tr("تلقائي"),
            videoQuality = prefs.getString("quality", Translator.tr("عالية الدقة 1080p"))
                ?: Translator.tr("عالية الدقة 1080p"),
            ambientSound = prefs.getString("ambient", Translator.tr("تلقائي"))
                ?: Translator.tr("تلقائي"),
        )
    )
    val state: StateFlow<ProjectState> = _state.asStateFlow()

    private var prefsJob: Job? = null
    private var dbJob: Job? = null

    init {
        loadProjects()
    }

    fun updateState(update: ProjectState.() -> ProjectState) {
        _state.value = _state.value.update()
        prefsJob?.cancel()
        prefsJob = viewModelScope.launch {
            delay(350)
            val s = _state.value
            withContext(Dispatchers.IO) {
                prefs.edit()
                    .putString("ratio", s.selectedRatio)
                    .putString("input", s.inputText)
                    .putString("duration", s.videoDuration)
                    .putString("style", s.editingStyle)
                    .putString("voice", s.voiceOver)
                    .putString("music", s.musicVibe)
                    .putString("styleDesc", s.styleDescription)
                    .putString("template", s.selectedTemplate)
                    .putString("quality", s.videoQuality)
                    .putString("ambient", s.ambientSound)
                    .apply()
            }
        }
        scheduleSaveProjectToDb()
    }

    private fun scheduleSaveProjectToDb() {
        dbJob?.cancel()
        dbJob = viewModelScope.launch {
            delay(600)
            saveProjectToDb()
        }
    }

    private suspend fun saveProjectToDb() {
        val s = _state.value
        val id = s.currentProjectId ?: return
        withContext(Dispatchers.IO) {
            val status = when (s.appState) {
                AppState.PAYMENT -> Translator.tr("مسودة")
                AppState.SAVE_SHARE -> Translator.tr("مدفوع")
                AppState.SAVE_PROJECT -> Translator.tr("مكتمل")
                else -> Translator.tr("مسودة")
            }
            projectService.saveProject(
                ProjectService.Project(
                    id = id,
                    title = s.projectTitle,
                    idea = s.inputText,
                    analysis = "",
                    resources = s.mediaResources.size.toString(),
                    settings = "${s.selectedRatio}, ${s.videoDuration}, ${s.editingStyle}, ${s.selectedTemplate}",
                    script = s.libraryScenes.size.toString(),
                    finalVideoPath = s.finalVideoPath,
                    cost = 0.0,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    status = status,
                    lastScreen = s.appState.name
                )
            )
        }
        loadProjects()
    }

    fun createProject(initialText: String = "") {
        updateState {
            copy(
                currentProjectId = java.util.UUID.randomUUID().toString(),
                projectTitle = Translator.tr("مشروع ") + (1000..9999).random(),
                inputText = initialText,
                mediaResources = emptyList(),
                libraryScenes = emptyList(),
                appState = AppState.INPUT
            )
        }
    }

    fun openProject(project: ProjectService.Project) {
        updateState {
            copy(
                currentProjectId = project.id,
                projectTitle = project.title,
                inputText = project.idea,
                appState = try {
                    AppState.valueOf(project.lastScreen)
                } catch (_: Exception) {
                    AppState.INPUT
                }
            )
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                projectService.deleteProject(id)
            }
            loadProjects()
        }
    }

    fun loadProjects() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                projectService.getAllProjects()
            }
            _state.value = _state.value.copy(projects = list)
        }
    }

    suspend fun loadProjectsSuspend() {
        val list = withContext(Dispatchers.IO) {
            projectService.getAllProjects()
        }
        _state.value = _state.value.copy(projects = list)
    }
}

class ProjectViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProjectViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProjectViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QabasStudioScreen() {
    QabasApp()
}

@Composable
fun ChoiceChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) GoldPrimary else CardSurface)
            .border(
                1.dp,
                if (isSelected) GoldPrimary else Color(0xFF1E293B),
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) DeepSlate else TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun StyleCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) CardSurface.copy(alpha = 0.8f) else CardSurface)
            .border(
                1.dp,
                if (isSelected) GoldPrimary else Color(0xFF1E293B),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                color = if (isSelected) GoldPrimary else TextPrimary,
                fontFamily = CairoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = GoldPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Text(
            text = description,
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
fun PlayerBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, GoldPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = GoldPrimary,
            fontSize = 12.sp,
            fontFamily = CairoFont,
            fontWeight = FontWeight.Bold
        )
    }
}

fun buildCinematicPrompt(
    userIdea: String,
    duration: String,
    style: String,
    aspectRatio: String,
    voiceOver: String,
    musicVibe: String
): String {
    val styleInstructions = when {
        style.contains("3nvus") || style.contains(Translator.tr("نيون")) ->
            "Visual Style: Dark moody aesthetic, high contrast. Typography: Kinetic glowing neon text synced to audio. Pacing: Fast viral transitions with ambient sound design."
        style.contains(Translator.tr("سينمائي")) ->
            "Visual Style: Deep warm tones, elegant cinematic lighting, slow-motion B-roll, premium and smooth transitions."
        style.contains(Translator.tr("وثائقي")) ->
            "Visual Style: Fast-paced documentary style, modern grid overlays, dynamic zooms, informative tone."
        else -> ""
    }

    val voiceInstruction = when {
        voiceOver.contains(Translator.tr("عميق")) -> "Voice Over Tone: Deep, Epic, authoritative."
        voiceOver.contains(Translator.tr("حماسي")) -> "Voice Over Tone: Energetic, fast-paced, motivating."
        voiceOver.contains(Translator.tr("رسمي")) -> "Voice Over Tone: Neutral, official, clear and documentary-style."
        else -> "Voice Over Tone: $voiceOver"
    }

    val soundEffectInstruction = when {
        musicVibe.contains(Translator.tr("طبيعة")) ->
            "Sound Effect Vibe: Nature sounds, birds, wind, flowing water."
        musicVibe.contains(Translator.tr("مدينة")) ->
            "Sound Effect Vibe: Urban ambience, traffic, distant chatter."
        musicVibe.contains(Translator.tr("هادئ")) ->
            "Sound Effect Vibe: Calm, minimal, subtle ambient white noise."
        musicVibe.contains(Translator.tr("نشاط")) ->
            "Sound Effect Vibe: Active, gym or sports ambient, energetic non-musical sounds."
        musicVibe.contains(Translator.tr("درامي")) ->
            "Sound Effect Vibe: Dramatic swooshes, impacts, cinematic non-musical sound effects."
        else -> "Sound Effect Vibe: $musicVibe"
    }

    return """
        [MASTER PROMPT]
        Duration: $duration
        Aspect Ratio: $aspectRatio
        
        $styleInstructions
        $voiceInstruction
        $soundEffectInstruction
        
        User Concept/Script:
        $userIdea
    """.trimIndent()
}
