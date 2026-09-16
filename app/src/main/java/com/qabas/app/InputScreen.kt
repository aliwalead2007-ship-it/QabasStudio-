package com.qabas.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputScreen(
    state: ProjectState,
    onStateChange: (ProjectState) -> Unit,
    onProceed: () -> Unit,
    onBack: () -> Unit = {},
    onOpenAudioLibrary: () -> Unit = {},
    onOpenDirector: () -> Unit = {},
    onOpenQasas: () -> Unit = {},
    onOpenClips: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedSourceType by remember {
        mutableStateOf<String?>(if (state.inputText.isNotBlank()) "idea" else null)
    }

    var trendingIdeas by remember { mutableStateOf<List<TrendingIdea>?>(null) }
    var isTrendingLoading by remember { mutableStateOf(true) }

    val context = LocalContext.current
    var savedUserStyles by remember { mutableStateOf<List<ClonedStylePreset>>(emptyList()) }
    var customStyleTitleInput by remember { mutableStateOf("") }
    var audioUsageMode by remember { mutableStateOf("voiceover") } // "voiceover" or "primary"

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            var fileName = "مقطع_صوتي.mp3"
            var fileSizeFormatted: String? = null
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) fileName = name
                    }
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        val size = cursor.getLong(sizeIndex)
                        if (size > 0) {
                            val sizeMb = size / (1024.0 * 1024.0)
                            fileSizeFormatted = if (sizeMb >= 1.0) {
                                String.format(java.util.Locale.US, "%.1f ميغابايت", sizeMb)
                            } else {
                                val sizeKb = size / 1024.0
                                String.format(java.util.Locale.US, "%.0f كيلوبايت", sizeKb)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            var durationFormatted: String? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retriever.release()
                if (durationMs != null && durationMs > 0) {
                    val totalSeconds = durationMs / 1000
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    durationFormatted = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
                }
            } catch (_: Exception) {}

            val metaParts = listOfNotNull(durationFormatted, fileSizeFormatted).joinToString(" • ")
            val label = if (metaParts.isNotBlank()) "$fileName ($metaParts)" else fileName

            onStateChange(
                state.copy(
                    sourceType = "audio",
                    sourceUris = listOf(uri.toString()),
                    sourceLabel = label,
                    projectTitle = if (state.projectTitle == "مشروع جديد" || state.projectTitle.isBlank()) "صوت: ${fileName.take(20)}" else state.projectTitle
                )
            )
            scope.launch {
                snackbarHostState.showSnackbar(Translator.tr("تم اختيار الملف الصوتي بنجاح: ") + fileName)
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(Translator.tr("تم إلغاء اختيار الملف الصوتي"))
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            var fileName = "فيديو_مستورد.mp4"
            var formattedDuration: String? = null
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) fileName = name
                    }
                }
            } catch (_: Exception) {}

            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retriever.release()
                if (durationMs != null && durationMs > 0) {
                    val totalSeconds = durationMs / 1000
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    formattedDuration = String.format("%02d:%02d", minutes, seconds)
                }
            } catch (_: Exception) {}

            val label = if (formattedDuration != null) "$fileName ($formattedDuration)" else fileName
            onStateChange(
                state.copy(
                    sourceType = "video",
                    sourceUris = listOf(uri.toString()),
                    sourceLabel = label,
                    projectTitle = if (state.projectTitle == "مشروع جديد" || state.projectTitle.isBlank()) "فيديو: ${fileName.take(20)}" else state.projectTitle
                )
            )
            scope.launch {
                snackbarHostState.showSnackbar(Translator.tr("تم اختيار الفيديو بنجاح: ") + fileName)
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(Translator.tr("تم إلغاء اختيار الفيديو"))
            }
        }
    }

    val imagesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 15)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val currentUris = if (state.sourceType == "images") state.sourceUris else emptyList()
            val mergedUris = (currentUris + uris.map { it.toString() }).distinct()
            val label = "${mergedUris.size} صور مختارة"
            onStateChange(
                state.copy(
                    sourceType = "images",
                    sourceUris = mergedUris,
                    sourceLabel = label,
                    projectTitle = if (state.projectTitle == "مشروع جديد" || state.projectTitle.isBlank()) "ألبوم صور (${mergedUris.size})" else state.projectTitle
                )
            )
            scope.launch {
                snackbarHostState.showSnackbar(Translator.tr("تم اختيار ${uris.size} صور بنجاح ✓"))
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(Translator.tr("تم إلغاء اختيار الصور"))
            }
        }
    }

    LaunchedEffect(Unit) {
        savedUserStyles = StyleVaultManager.getVaultStyles(context)
    }

    // Direct Style Analysis State
    var referenceUrlInput by remember { mutableStateOf("") }
    var isAnalyzingStyle by remember { mutableStateOf(false) }
    var showStyleModal by remember { mutableStateOf(false) }

    // Voice Input State
    var isRecordingVoice by remember { mutableStateOf(false) }
    var isEnhancingIdea by remember { mutableStateOf(false) }

    // Content Guard Pre-flight Validation State
    var guardInspectionResult by remember { mutableStateOf<ContentInspectionResult?>(null) }
    var showGuardDialog by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    LaunchedEffect(Unit) {
        try {
            isTrendingLoading = true
            trendingIdeas = AppServices.getTrendingIdeas()
        } catch (e: Exception) {
            trendingIdeas = null
        } finally {
            isTrendingLoading = false
        }
    }

    val goldGradient = Brush.horizontalGradient(colors = listOf(GoldSecondary, GoldPrimary))
    val styleButtonGradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF4F46E5), Color(0xFF7C3AED), GoldPrimary)
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DeepSlate,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            Translator.tr("صناعة الفكرة والإخراج 🎬"),
                            color = GoldPrimary,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            Translator.tr("استوديو قبس الذكي لإنتاج الفيديوهات"),
                            color = TextSecondary,
                            fontFamily = NotoSansFont,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(Color(0xFF151B2B), CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = Translator.tr("العودة"),
                            tint = GoldPrimary
                        )
                    }
                },
                actions = {
                    val streak = remember { try { StreakManager.getStreak(context) } catch (_: Exception) { 0 } }
                    if (streak > 0) {
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFF59E0B).copy(alpha = 0.15f), modifier = Modifier.padding(end = 12.dp)) {
                            Text("🔥 $streak", color = Color(0xFFF59E0B), fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepSlate)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                selectedSourceType == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                            border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.3f))
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = GoldPrimary.copy(alpha = 0.15f),
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(28.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    Translator.tr("اختر طريقة بدء صناعة الفيديو"),
                                    color = GoldPrimary,
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    Translator.tr("حدد مصدر المدخل الأساسي للبدء في توليد السيناريو والإنتاج"),
                                    color = TextSecondary,
                                    fontFamily = NotoSansFont,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        SourceTypeCard(
                            title = Translator.tr("المخرج — احكِ وهو يجهز"),
                            subtitle = Translator.tr("محادثة حرة: افهم فكرتك ويسألك عن الناقص ويبني موجز المشروع"),
                            icon = Icons.Default.AutoAwesome,
                            badge = Translator.tr("جديد ✨"),
                            badgeColor = Color(0xFF8B5CF6),
                            accentColor = Color(0xFF8B5CF6),
                            onClick = { onOpenDirector() }
                        )

                        SourceTypeCard(
                            title = Translator.tr("قاص قبس — قصص تتحول لريلز"),
                            subtitle = Translator.tr("5 قصص قرآنية مروية + اسأل القاص وحول أي قصة لفيديو"),
                            icon = Icons.Default.MenuBook,
                            badge = Translator.tr("جديد 📖"),
                            badgeColor = GoldPrimary,
                            accentColor = GoldPrimary,
                            onClick = { onOpenQasas() }
                        )

                        SourceTypeCard(
                            title = Translator.tr("مقص المقاطع — من الطويل للقصير"),
                            subtitle = Translator.tr("الصق خطبة أو تفريغاً طويلاً فيستخرج 5 لحظات ذهبية كريلزات"),
                            icon = Icons.Default.ContentCut,
                            badge = Translator.tr("جديد ✂️"),
                            badgeColor = Color(0xFF34D399),
                            accentColor = Color(0xFF34D399),
                            onClick = { onOpenClips() }
                        )

                        TrendRadarCard(
                            onPick = {
                                selectedSourceType = "idea"
                                onStateChange(state.copy(sourceType = "idea", inputText = it))
                            }
                        )

                        SourceTypeCard(
                            title = Translator.tr("ابدأ من فكرة نصية"),
                            subtitle = Translator.tr("كتابة سكريبت، قصة، نص دعوي أو توليد أفكار بالذكاء الاصطناعي"),
                            icon = Icons.Default.Lightbulb,
                            badge = Translator.tr("متاح الآن ✨"),
                            badgeColor = GoldPrimary,
                            accentColor = GoldPrimary,
                            onClick = {
                                selectedSourceType = "idea"
                                onStateChange(state.copy(sourceType = "idea"))
                            }
                        )

                        SourceTypeCard(
                            title = Translator.tr("ابدأ من ملف صوتي"),
                            subtitle = Translator.tr("تفريغ خطبة، مقطع صوتي، بودكاست أو تلاوة قرآنية وتحويلها لفيديو"),
                            icon = Icons.Default.GraphicEq,
                            badge = Translator.tr("متاح الآن 🎙️"),
                            badgeColor = Color(0xFF38BDF8),
                            accentColor = Color(0xFF38BDF8),
                            onClick = {
                                selectedSourceType = "audio"
                                onStateChange(state.copy(sourceType = "audio"))
                            }
                        )

                        SourceTypeCard(
                            title = Translator.tr("ابدأ من فيديو موجود"),
                            subtitle = Translator.tr("إعادة مونتاج، استخراج نصوص، أو استنساخ أسلوب مرئي"),
                            icon = Icons.Default.VideoLibrary,
                            badge = Translator.tr("متاح الآن 🎬"),
                            badgeColor = Color(0xFF34D399),
                            accentColor = Color(0xFF34D399),
                            onClick = {
                                selectedSourceType = "video"
                                onStateChange(state.copy(sourceType = "video"))
                            }
                        )

                        SourceTypeCard(
                            title = Translator.tr("ابدأ من صور"),
                            subtitle = Translator.tr("تحويل ألبوم صور، تصاميم أو لوحات إلى ريلز سينمائي متحرك"),
                            icon = Icons.Default.PhotoLibrary,
                            badge = Translator.tr("متاح الآن 🖼️"),
                            badgeColor = Color(0xFFFBBF24),
                            accentColor = Color(0xFFFBBF24),
                            onClick = {
                                selectedSourceType = "images"
                                onStateChange(state.copy(sourceType = "images"))
                            }
                        )
                    }
                }
                selectedSourceType == "video" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Top Indicator / Switcher Badge
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF151B2B), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF34D399).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    Translator.tr("المدخل المختار: فيديو من الجهاز 🎬"),
                                    color = Color(0xFF34D399),
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            TextButton(
                                onClick = {
                                    selectedSourceType = null
                                    onStateChange(state.copy(sourceType = "idea"))
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    Translator.tr("تغيير ↩️"),
                                    color = TextSecondary,
                                    fontFamily = NotoSansFont,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Header Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                            border = BorderStroke(1.dp, Color(0xFF34D399).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF34D399).copy(alpha = 0.15f),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(26.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        Translator.tr("استيراد فيديو من الجهاز 🎬"),
                                        color = Color.White,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        Translator.tr("إعادة المونتاج، تفريغ الصوت، وتطبيق قوالب قبس"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        if (state.sourceUris.isEmpty()) {
                            // Picker Card
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        videoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
                                border = BorderStroke(1.5.dp, Color(0xFF34D399).copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF34D399).copy(alpha = 0.18f),
                                        modifier = Modifier.size(68.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.UploadFile, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(34.dp))
                                        }
                                    }
                                    Text(
                                        Translator.tr("اضغط لاختيار فيديو من المعرض"),
                                        color = Color.White,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        Translator.tr("يدعم صيغ MP4 و MOV بدقة عالية"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(
                                        onClick = {
                                            videoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34D399)),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                                    ) {
                                        Icon(Icons.Default.VideoCall, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            Translator.tr("فتح معرض الفيديوهات 📂"),
                                            color = DeepSlate,
                                            fontFamily = CairoFont,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            // Preview Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                                border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = Color(0xFF34D399).copy(alpha = 0.15f),
                                            modifier = Modifier.size(52.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Movie, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(28.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = state.sourceLabel ?: Translator.tr("فيديو مختار"),
                                                color = Color.White,
                                                fontFamily = CairoFont,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                maxLines = 2
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color(0xFF10B981).copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = Translator.tr("جاهز للمونتاج والتعديل ✓"),
                                                    color = Color(0xFF34D399),
                                                    fontFamily = NotoSansFont,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Action buttons row (Change / Remove)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                videoPickerLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFF334155)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(Translator.tr("تغيير الفيديو"), color = TextPrimary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                onStateChange(
                                                    state.copy(
                                                        sourceUris = emptyList(),
                                                        sourceLabel = null
                                                    )
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(Translator.tr("إزالة 🗑️"), color = Color(0xFFF87171), fontFamily = NotoSansFont, fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Big Proceed Button
                                    Button(
                                        onClick = {
                                            val desc = state.inputText.ifBlank { "مشروع فيديو مستورد: ${state.sourceLabel ?: "مقطع مرئي"}" }
                                            onStateChange(
                                                state.copy(
                                                    sourceType = "video",
                                                    inputText = desc
                                                )
                                            )
                                            onProceed()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                    ) {
                                        Text(
                                            Translator.tr("متابعة إلى المونتاج والتعديل 🚀"),
                                            color = DeepSlate,
                                            fontFamily = CairoFont,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }

                        // Back Button
                        TextButton(
                            onClick = {
                                selectedSourceType = null
                                onStateChange(state.copy(sourceType = "idea"))
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(Translator.tr("تغيير نوع المدخل والعودة"), color = TextSecondary, fontFamily = CairoFont, fontSize = 13.sp)
                        }
                    }
                }
                selectedSourceType == "audio" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Top Indicator / Switcher Badge
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF151B2B), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    Translator.tr("المدخل المختار: ملف صوتي 🎙️"),
                                    color = Color(0xFF38BDF8),
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            TextButton(
                                onClick = {
                                    selectedSourceType = null
                                    onStateChange(state.copy(sourceType = "idea"))
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    Translator.tr("تغيير ↩️"),
                                    color = TextSecondary,
                                    fontFamily = NotoSansFont,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Header Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(26.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        Translator.tr("استيراد ملف صوتي 🎙️"),
                                        color = Color.White,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        Translator.tr("تفريغ، تعليق صوتي، أو مسار صوتي أساسي للمشروع"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        if (state.sourceUris.isEmpty()) {
                            // Picker Card
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        audioPickerLauncher.launch("audio/*")
                                    },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
                                border = BorderStroke(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF38BDF8).copy(alpha = 0.18f),
                                        modifier = Modifier.size(68.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Audiotrack, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(34.dp))
                                        }
                                    }
                                    Text(
                                        Translator.tr("اضغط لاختيار ملف صوتي من الجهاز"),
                                        color = Color.White,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        Translator.tr("يدعم صيغ MP3 و WAV و M4A و AAC و OGG"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                audioPickerLauncher.launch("audio/*")
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.AudioFile, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                Translator.tr("من الجهاز 📂"),
                                                color = DeepSlate,
                                                fontFamily = CairoFont,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = onOpenAudioLibrary,
                                            border = BorderStroke(1.dp, GoldPrimary),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                Translator.tr("مكتبة قبس 🎙️"),
                                                color = GoldPrimary,
                                                fontFamily = CairoFont,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // File Info & Usage Choice Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Info Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                                            modifier = Modifier.size(52.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(28.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = state.sourceLabel ?: Translator.tr("ملف صوتي مختار"),
                                                color = Color.White,
                                                fontFamily = CairoFont,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                maxLines = 2
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color(0xFF38BDF8).copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = Translator.tr("ملف صوتي مستورد ✓"),
                                                    color = Color(0xFF38BDF8),
                                                    fontFamily = NotoSansFont,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = Color(0xFF1E293B))

                                    // Usage Mode Choice
                                    Text(
                                        Translator.tr("اختر كيفية توظيف هذا الصوت:"),
                                        color = GoldPrimary,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )

                                    // Option A: Voiceover
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { audioUsageMode = "voiceover" },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (audioUsageMode == "voiceover") Color(0xFF1A2742) else Color(0xFF0F172A)
                                        ),
                                        border = BorderStroke(
                                            1.5.dp,
                                            if (audioUsageMode == "voiceover") Color(0xFF38BDF8) else Color(0xFF1E293B)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = audioUsageMode == "voiceover",
                                                onClick = { audioUsageMode = "voiceover" },
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = Color(0xFF38BDF8),
                                                    unselectedColor = TextSecondary
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    Translator.tr("استخدام كتعليق صوتي (Voiceover) 🎙️"),
                                                    color = Color.White,
                                                    fontFamily = CairoFont,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    Translator.tr("اعتماد هذا الصوت كإلقاء صوتي أساسي وتوليد مشاهد ملائمة لكلماته"),
                                                    color = TextSecondary,
                                                    fontFamily = NotoSansFont,
                                                    fontSize = 11.sp,
                                                    lineHeight = 16.sp
                                                )
                                            }
                                        }
                                    }

                                    // Option B: Primary Material
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { audioUsageMode = "primary" },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (audioUsageMode == "primary") Color(0xFF1A2742) else Color(0xFF0F172A)
                                        ),
                                        border = BorderStroke(
                                            1.5.dp,
                                            if (audioUsageMode == "primary") Color(0xFF38BDF8) else Color(0xFF1E293B)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = audioUsageMode == "primary",
                                                onClick = { audioUsageMode = "primary" },
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = Color(0xFF38BDF8),
                                                    unselectedColor = TextSecondary
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    Translator.tr("استخدامه كمادة أساسية للمشروع 🎵"),
                                                    color = Color.White,
                                                    fontFamily = CairoFont,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    Translator.tr("اعتماد الصوت كمسار أساسي للمشروع مع بناء المشاهد والسكريبت وفقاً له"),
                                                    color = TextSecondary,
                                                    fontFamily = NotoSansFont,
                                                    fontSize = 11.sp,
                                                    lineHeight = 16.sp
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    // Action buttons row (Change / Remove)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                audioPickerLauncher.launch("audio/*")
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFF334155)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(Translator.tr("تغيير الملف"), color = TextPrimary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                onStateChange(
                                                    state.copy(
                                                        sourceUris = emptyList(),
                                                        sourceLabel = null
                                                    )
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(Translator.tr("إزالة 🗑️"), color = Color(0xFFF87171), fontFamily = NotoSansFont, fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Big Proceed Button
                                    Button(
                                        onClick = {
                                            val cleanName = state.sourceLabel?.substringBefore(" (") ?: "ملف صوتي"
                                            val desc = if (audioUsageMode == "voiceover") {
                                                state.inputText.ifBlank { "مشروع بتعليق صوتي: $cleanName" }
                                            } else {
                                                state.inputText.ifBlank { "مشروع صوتي أساسي: $cleanName" }
                                            }
                                            onStateChange(
                                                state.copy(
                                                    sourceType = "audio",
                                                    inputText = desc,
                                                    voiceOver = if (audioUsageMode == "voiceover") "صوت مخصص: $cleanName" else state.voiceOver
                                                )
                                            )
                                            onProceed()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                    ) {
                                        Text(
                                            Translator.tr("متابعة إلى المسار الذكي 🚀"),
                                            color = DeepSlate,
                                            fontFamily = CairoFont,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }

                        // Back Button
                        TextButton(
                            onClick = {
                                selectedSourceType = null
                                onStateChange(state.copy(sourceType = "idea"))
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(Translator.tr("تغيير نوع المدخل والعودة"), color = TextSecondary, fontFamily = CairoFont, fontSize = 13.sp)
                        }
                    }
                }
                selectedSourceType == "images" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Top Indicator / Switcher Badge
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF151B2B), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    Translator.tr("المدخل المختار: ألبوم صور 🖼️"),
                                    color = Color(0xFFFBBF24),
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            TextButton(
                                onClick = {
                                    selectedSourceType = null
                                    onStateChange(state.copy(sourceType = "idea"))
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    Translator.tr("تغيير ↩️"),
                                    color = TextSecondary,
                                    fontFamily = NotoSansFont,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Header Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                            border = BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFFBBF24).copy(alpha = 0.15f),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(26.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        Translator.tr("استيراد ألبوم صور 🖼️"),
                                        color = Color.White,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        Translator.tr("تحويل الصور والتصاميم إلى ريلز متحرك مع مؤثرات بصرية"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        if (state.sourceUris.isEmpty()) {
                            // Picker Card
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        imagesPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
                                border = BorderStroke(1.5.dp, Color(0xFFFBBF24).copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFFFBBF24).copy(alpha = 0.18f),
                                        modifier = Modifier.size(68.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(34.dp))
                                        }
                                    }
                                    Text(
                                        Translator.tr("اضغط لاختيار صور من المعرض"),
                                        color = Color.White,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        Translator.tr("يمكنك اختيار حتى 15 صورة (JPEG, PNG, WEBP) لتوليد المشاهد"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(
                                        onClick = {
                                            imagesPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBF24)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(Translator.tr("فتح المعرض واختيار الصور"), color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        } else {
                            // Thumbnails Preview & Configuration Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                                border = BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Status Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color(0xFFFBBF24).copy(alpha = 0.2f),
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Collections, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(20.dp))
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "${state.sourceUris.size} " + Translator.tr("صور مختارة"),
                                                    color = Color.White,
                                                    fontFamily = CairoFont,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp
                                                )
                                                Text(
                                                    text = Translator.tr("جاهزة للتحويل إلى مشاهد ريلز ✓"),
                                                    color = Color(0xFF34D399),
                                                    fontFamily = NotoSansFont,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                imagesPickerLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            },
                                            modifier = Modifier
                                                .background(Color(0xFF1E293B), CircleShape)
                                                .size(36.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = Translator.tr("إضافة صور"), tint = Color(0xFFFBBF24), modifier = Modifier.size(20.dp))
                                        }
                                    }

                                    // Image Thumbnails Carousel
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        itemsIndexed(state.sourceUris) { index, uriString ->
                                            Box(
                                                modifier = Modifier
                                                    .size(88.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                                            ) {
                                                AsyncImage(
                                                    model = uriString,
                                                    contentDescription = "صورة ${index + 1}",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )

                                                // Badge number
                                                Surface(
                                                    shape = RoundedCornerShape(topStart = 0.dp, bottomEnd = 8.dp),
                                                    color = Color.Black.copy(alpha = 0.7f),
                                                    modifier = Modifier.align(Alignment.TopStart)
                                                ) {
                                                    Text(
                                                        text = "${index + 1}",
                                                        color = Color.White,
                                                        fontSize = 10.sp,
                                                        fontFamily = NotoSansFont,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }

                                                // Delete button
                                                IconButton(
                                                    onClick = {
                                                        val updated = state.sourceUris.filterIndexed { i, _ -> i != index }
                                                        onStateChange(
                                                            state.copy(
                                                                sourceUris = updated,
                                                                sourceLabel = if (updated.isNotEmpty()) "${updated.size} صور مختارة" else null
                                                            )
                                                        )
                                                    },
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .align(Alignment.TopEnd)
                                                        .padding(2.dp)
                                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = Translator.tr("حذف"), tint = Color(0xFFF87171), modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }

                                        item {
                                            // Add More Box in Carousel
                                            Box(
                                                modifier = Modifier
                                                    .size(88.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                                    .background(Color(0xFF0B0F19))
                                                    .clickable {
                                                        imagesPickerLauncher.launch(
                                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                        )
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(24.dp))
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(Translator.tr("إضافة"), color = TextSecondary, fontSize = 10.sp, fontFamily = CairoFont)
                                                }
                                            }
                                        }
                                    }

                                    // Script / Topic hint input
                                    OutlinedTextField(
                                        value = state.inputText,
                                        onValueChange = { onStateChange(state.copy(inputText = it)) },
                                        placeholder = {
                                            Text(
                                                Translator.tr("اكتب فكرة أو موضوع الريلز (اختياري)..."),
                                                color = TextSecondary.copy(alpha = 0.6f),
                                                fontSize = 13.sp,
                                                fontFamily = NotoSansFont
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFFFBBF24),
                                            unfocusedBorderColor = Color(0xFF334155),
                                            focusedContainerColor = Color(0xFF0B0F19),
                                            unfocusedContainerColor = Color(0xFF0B0F19),
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        maxLines = 2
                                    )

                                    // Action buttons row (Add More / Clear)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                imagesPickerLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFF334155)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(Translator.tr("إضافة صور"), color = TextPrimary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                onStateChange(
                                                    state.copy(
                                                        sourceUris = emptyList(),
                                                        sourceLabel = null
                                                    )
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(Translator.tr("مسح الكل 🗑️"), color = Color(0xFFF87171), fontFamily = NotoSansFont, fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Big Proceed Button
                                    Button(
                                        onClick = {
                                            val desc = state.inputText.ifBlank { "مشروع ريلز متحرك من ${state.sourceUris.size} صور" }
                                            onStateChange(
                                                state.copy(
                                                    sourceType = "images",
                                                    inputText = desc
                                                )
                                            )
                                            onProceed()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                    ) {
                                        Text(
                                            Translator.tr("متابعة إلى المسار الذكي 🚀"),
                                            color = DeepSlate,
                                            fontFamily = CairoFont,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }

                        // Back Button
                        TextButton(
                            onClick = {
                                selectedSourceType = null
                                onStateChange(state.copy(sourceType = "idea"))
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(Translator.tr("تغيير نوع المدخل والعودة"), color = TextSecondary, fontFamily = CairoFont, fontSize = 13.sp)
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Source Type Indicator / Switcher
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF151B2B), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lightbulb, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    Translator.tr("المدخل المختار: فكرة نصية ✍️"),
                                    color = GoldPrimary,
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            TextButton(
                                onClick = { selectedSourceType = null },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    Translator.tr("تغيير ↩️"),
                                    color = TextSecondary,
                                    fontFamily = NotoSansFont,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // 1. CORE PROMPT & IDEA FIELD CARD
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(12.dp, RoundedCornerShape(20.dp), spotColor = GoldPrimary.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF182232)),
                            border = BorderStroke(
                                1.5.dp,
                                if (state.inputText.isNotEmpty()) GoldPrimary else Color(0xFF1E293B)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Lightbulb, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            Translator.tr("اكتب فكرة المشهد أو النص ✍️"),
                                            color = GoldPrimary,
                                            fontFamily = CairoFont,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                    if (state.inputText.isNotEmpty()) {
                                        TextButton(onClick = { onStateChange(state.copy(inputText = "")) }) {
                                            Text(Translator.tr("مسح"), color = Color(0xFFEF4444), fontSize = 12.sp, fontFamily = NotoSansFont)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = state.inputText,
                                    onValueChange = { newVal -> onStateChange(state.copy(inputText = newVal)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 130.dp, max = 220.dp),
                                    placeholder = {
                                        Text(
                                            Translator.tr("مثال: شرح مبسط وعميق لفضل تدبر القرآن الكريم في حسم القرارات، بأسلوب سينمائي حماسي مع مشاهد طبيعية هادئة..."),
                                            color = TextSecondary.copy(alpha = 0.6f),
                                            fontFamily = NotoSansFont,
                                            fontSize = 13.sp,
                                            lineHeight = 22.sp
                                        )
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color(0xFF0B0F19),
                                        unfocusedContainerColor = Color(0xFF0B0F19),
                                        focusedIndicatorColor = GoldPrimary,
                                        unfocusedIndicatorColor = Color(0xFF1E293B),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        cursorColor = GoldPrimary
                                    ),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontSize = 15.sp,
                                        fontFamily = NotoSansFont,
                                        lineHeight = 24.sp
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Quick Assist Toolbar
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { isRecordingVoice = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary.copy(alpha = 0.15f), contentColor = GoldPrimary),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(Translator.tr("صوت 🎤"), fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val holySnippet = "« إِنَّ اللَّهَ وَمَلَائِكَتَهُ يُصَلُّونَ عَلَى النَّبِيِّ ۚ يَا أَيُّهَا الَّذِينَ آمَنُوا صَلُّوا عَلَيْهِ وَسَلِّمُوا تَسْلِيمًا »"
                                            onStateChange(
                                                state.copy(
                                                    inputText = if (state.inputText.isBlank()) holySnippet else "${state.inputText}\n$holySnippet"
                                                )
                                            )
                                        },
                                        border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(Translator.tr("نص شرعي 📖"), color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Magic Prompt Engineer Button
                                Button(
                                    onClick = {
                                        if (state.inputText.isNotBlank() && !isEnhancingIdea) {
                                            isEnhancingIdea = true
                                            scope.launch {
                                                kotlinx.coroutines.delay(1500)
                                                val enhanced = """
                                                    |[الهدف]: إنتاج فيديو دعوي/إسلامي قصير احترافي.
                                                    |[الجمهور المستهدف]: الشباب المسلم على شبكات التواصل.
                                                    |[النبرة]: ملهمة، سينمائية، ومؤثرة.
                                                    |[الفكرة الأساسية]: ${state.inputText.take(150)}${if (state.inputText.length > 150) "..." else ""}
                                                    |
                                                    |[الهيكلة المقترحة]:
                                                    |1. الخطاف (الثواني 0-3): مشهد يشد الانتباه مع سؤال مثير للتفكير.
                                                    |2. الجسد (القصة/المعنى): طرح القضية مع الاستشهاد بآية أو حديث بخلفية هادئة.
                                                    |3. الخاتمة (الدعوة للإجراء CTA): رسالة ختامية تترك أثراً مع دعوة للمشاركة.
                                                    |
                                                    |[التوجيهات المرئية]: ألوان داكنة مع تباين ذهبي، انتقالات ناعمة، ونصوص واضحة.
                                                """.trimMargin()
                                                
                                                onStateChange(state.copy(inputText = enhanced))
                                                isEnhancingIdea = false
                                                snackbarHostState.showSnackbar(Translator.tr("تمت هندسة الفكرة لتصبح برومبت احترافي! ✨"))
                                            }
                                        } else if (state.inputText.isBlank()) {
                                            scope.launch { snackbarHostState.showSnackbar(Translator.tr("يرجى كتابة الفكرة أولاً!")) }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    enabled = !isEnhancingIdea
                                ) {
                                    if (isEnhancingIdea) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(Translator.tr("جاري هندسة البرومبت ذكياً..."), color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(Translator.tr("الصياغة الهندسية الذكية للبرومبت 🪄"), color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                            }
                        }

                        // 2. VIDEO PARAMETERS CARD (المدة، النسبة، الجمهور المستهدف، النبرة)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                            border = BorderStroke(1.dp, Color(0xFF1E293B))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Header: Parameters
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        Translator.tr("إعدادات الفيديو الأساسية ⚙️"),
                                        color = GoldPrimary,
                                        fontFamily = CairoFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                // 1. Aspect Ratio (الأبعاد: طول / عرض)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        Translator.tr("أبعاد الفيديو (الارتفاع والعرض):"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            "9:16" to "طولي (Reels/Shorts)",
                                            "16:9" to "عرضي (YouTube)",
                                            "1:1" to "مربع (Feed)"
                                        ).forEach { (ratio, label) ->
                                            val isSelected = state.selectedRatio == ratio
                                            Surface(
                                                color = if (isSelected) GoldPrimary else Color(0xFF0B0F19),
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(1.dp, if (isSelected) GoldPrimary else Color(0xFF1E293B)),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { onStateChange(state.copy(selectedRatio = ratio)) }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        ratio,
                                                        color = if (isSelected) DeepSlate else Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = CairoFont
                                                    )
                                                    Text(
                                                        label,
                                                        color = if (isSelected) DeepSlate.copy(alpha = 0.8f) else TextSecondary,
                                                        fontSize = 9.sp,
                                                        fontFamily = NotoSansFont,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // 2. Video Duration (طول الفيديو)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        Translator.tr("طول الفيديو:"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            "15 ثانية",
                                            "30 ثانية",
                                            "60 ثانية",
                                            "90 ثانية"
                                        ).forEach { dur ->
                                            val isSelected = state.videoDuration == dur
                                            Surface(
                                                color = if (isSelected) GoldPrimary else Color(0xFF0B0F19),
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(1.dp, if (isSelected) GoldPrimary else Color(0xFF1E293B)),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { onStateChange(state.copy(videoDuration = dur)) }
                                            ) {
                                                Box(
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        dur,
                                                        color = if (isSelected) DeepSlate else Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = CairoFont
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // 3. Target Audience (موجه لمن)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        Translator.tr("الجمهور المستهدف (موجه لمن):"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            "الشباب ورواد التواصل 📱",
                                            "عامة المسلمين 🌍",
                                            "طلاب العلم والمهتمين 📚",
                                            "غير المسلمين والتعريف بالإسلام 🕊️",
                                            "الناشئة والأطفال 🌟"
                                        ).forEach { audience ->
                                            val isSelected = state.marketingGoal.contains(audience.take(6)) || state.marketingGoal == audience
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) Color(0xFF1E1B4B) else Color(0xFF0B0F19))
                                                    .border(1.dp, if (isSelected) GoldPrimary else Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                                    .clickable { onStateChange(state.copy(marketingGoal = audience)) }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    audience,
                                                    color = if (isSelected) GoldPrimary else TextSecondary,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    fontFamily = NotoSansFont
                                                )
                                            }
                                        }
                                    }
                                }

                                // 4. Tone of Content (نبرة المحتوى)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        Translator.tr("نبرة الطرح:"),
                                        color = TextSecondary,
                                        fontFamily = NotoSansFont,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            Translator.tr("خاشع وهادئ 🌿"),
                                            Translator.tr("ملحمي وحماسي ⚡"),
                                            Translator.tr("تأملي عميق 🌌"),
                                            Translator.tr("سرد وثائقي 🎙️")
                                        ).forEach { tone ->
                                            val cleanToneName = tone.split(" ")[0]
                                            val isSelected = state.contentTone.contains(cleanToneName)
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) Color(0xFF2C1E12) else Color(0xFF0B0F19))
                                                    .border(1.dp, if (isSelected) GoldPrimary else Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                                    .clickable { onStateChange(state.copy(contentTone = cleanToneName)) }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    tone,
                                                    color = if (isSelected) GoldPrimary else TextSecondary,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    fontFamily = NotoSansFont
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // QUICK INSPIRATION CAROUSEL
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                Translator.tr("أفكار مقترحة للانطلاق السريع:"),
                                color = TextSecondary,
                                fontFamily = CairoFont,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (isTrendingLoading) {
                                Box(modifier = Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = GoldPrimary, modifier = Modifier.size(20.dp))
                                }
                            } else {
                                val displayIdeas = trendingIdeas ?: emptyList()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    displayIdeas.take(6).forEach { idea ->
                                        Card(
                                            modifier = Modifier
                                                .width(200.dp)
                                                .clickable {
                                                    onStateChange(state.copy(inputText = idea.title + "\n" + idea.description))
                                                    scope.launch { snackbarHostState.showSnackbar(Translator.tr("تم تمكين الفكرة!")) }
                                                },
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF182232)),
                                            border = BorderStroke(1.dp, Color(0xFF1E293B)),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    if (idea.source == "FALLBACK") "${idea.title} (اقتراح افتراضي)" else idea.title,
                                                    color = GoldPrimary,
                                                    fontFamily = NotoSansFont,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    maxLines = 1
                                                )
                                                Text(idea.description, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp, maxLines = 2, lineHeight = 16.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(90.dp))
                    }

            // FLOATING GOLDEN ACTION BUTTON
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Button(
                    onClick = {
                        val text = state.inputText.trim()
                        if (text.isEmpty()) {
                            scope.launch { snackbarHostState.showSnackbar(Translator.tr("اكتب أو تحدث فكرتك أولاً للانتقال للإخراج!")) }
                            return@Button
                        }

                        // Run fast content guard check
                        val inspection = ContentFilterService.quickInspect(text)
                        
                        // Cloud sync & analytics
                        scope.launch {
                            CloudServices.Database.recordContentGuardCheck(
                                text = text,
                                verdict = inspection.verdict.name,
                                score = inspection.score,
                                reason = inspection.reason
                            )
                            AppServices.getAnalyticsService(context).logEvent(
                                "script_guard_check",
                                mapOf(
                                    "verdict" to inspection.verdict.name,
                                    "score" to inspection.score
                                )
                            )
                        }

                        if (inspection.verdict == GuardVerdict.REJECTED) {
                            // Only true explicit violations block the user
                            guardInspectionResult = inspection
                            showGuardDialog = true
                        } else {
                            // APPROPRIATE and NEEDS_REVIEW both proceed to production
                            onProceed()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .shadow(if (state.inputText.isNotBlank()) 16.dp else 0.dp, RoundedCornerShape(16.dp), spotColor = GoldPrimary),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (state.inputText.isNotBlank()) goldGradient else androidx.compose.ui.graphics.SolidColor(Color(0xFF1E293B)),
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (state.videoStyleAnalysis != null) {
                                    val activeName = state.videoStyleAnalysis?.detectedStyle?.ifEmpty { state.editingStyle } ?: ""
                                    Translator.tr("توليد الفيديو بـ (") + activeName + Translator.tr(") 🚀✨")
                                } else {
                                    Translator.tr("تحويل الفكرة إلى مشهد سينمائي 🚀")
                                },
                                color = if (state.inputText.isNotBlank()) DeepSlate else TextSecondary,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = if (state.inputText.isNotBlank()) DeepSlate else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            }
            }
        }

        // CONTENT GUARD INSPECTION DIALOG
        if (showGuardDialog && guardInspectionResult != null) {
            val guardRes = guardInspectionResult!!
            val isRejected = guardRes.verdict == GuardVerdict.REJECTED
            val bannerColor = if (isRejected) Color(0xFFEF4444) else Color(0xFFF59E0B)

            AlertDialog(
                onDismissRequest = { showGuardDialog = false },
                containerColor = Color(0xFF151B2B),
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(bannerColor.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isRejected) Icons.Default.Cancel else Icons.Default.Warning,
                            contentDescription = null,
                            tint = bannerColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = if (isRejected) "تنبيه حارس المحتوى: محتوى مخالف ❌" else "ملاحظة حارس المحتوى: يحتاج تدقيق ⚠️",
                        color = Color.White,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = guardRes.reason,
                            color = Color(0xFFE2E8F0),
                            fontFamily = NotoSansFont,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        if (guardRes.warnings.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            guardRes.warnings.forEach { w ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = bannerColor, modifier = Modifier.size(8.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(w, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                }
                            }
                        }

                        if (!isRejected && !guardRes.improvedScript.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "الصياغة المقترحة البديلة:",
                                color = GoldPrimary,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0B0F19), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    guardRes.improvedScript!!,
                                    color = Color.White,
                                    fontFamily = NotoSansFont,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (isRejected) {
                        Button(
                            onClick = { showGuardDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("تعديل النص ✍️", color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!guardRes.improvedScript.isNullOrBlank()) {
                                Button(
                                    onClick = {
                                        onStateChange(state.copy(inputText = guardRes.improvedScript!!))
                                        showGuardDialog = false
                                        onProceed()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("اعتماد الصياغة المصوبة والمتابعة ✨", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showGuardDialog = false },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                                ) {
                                    Text("تعديل ✍️", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                                }

                                TextButton(
                                    onClick = {
                                        showGuardDialog = false
                                        onProceed()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("المتابعة على أي حال", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                },
                dismissButton = null
            )
        }

        // VOICE RECORDING OVERLAY
        if (isRecordingVoice) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .scale(pulseScale)
                            .background(GoldPrimary.copy(alpha = glowAlpha), CircleShape)
                            .border(2.dp, GoldPrimary, CircleShape)
                            .clickable {
                                scope.launch {
                                    isRecordingVoice = false
                                    onStateChange(
                                        state.copy(
                                            inputText = Translator.tr("فيديو قصير عن أهمية التوكل على الله في الحياة، بأسلوب مؤثر وسينمائي مع التركيز على لقطات طبيعية هادئة وتلاوة قرآنية في الخلفية.")
                                        )
                                    )
                                    snackbarHostState.showSnackbar(Translator.tr("تم تحويل صوتك إلى نص وفكرة بنجاح! 🎤✨"))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(64.dp))
                    }

                    Text(
                        Translator.tr("تحدث بفكــرتك الآن...\nنحن نستمع إليك بالذكاء الاصطناعي 🎙️"),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    TextButton(onClick = { isRecordingVoice = false }) {
                        Text(Translator.tr("إلغاء"), color = Color.White, fontFamily = CairoFont)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceTypeCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badge: String,
    badgeColor: Color,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = accentColor.copy(alpha = 0.15f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badge,
                            color = badgeColor,
                            fontFamily = NotoSansFont,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontFamily = NotoSansFont,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Color(0xFF475569),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun TrendRadarCard(onPick: (String) -> Unit) {
    var topics by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        try { topics = TrendRadar.getDailyTopics() } catch (_: Exception) { topics = TrendRadar.EvergreenFallback }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Whatshot, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("رادار الترند اليوم 🔥", color = Color(0xFFF59E0B), fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            if (topics == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFF59E0B))
                    Spacer(Modifier.width(8.dp))
                    Text("نرصد الرائج الآن...", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                }
            } else {
                topics!!.forEach { t ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(Color(0xFF0B0F19), RoundedCornerShape(10.dp))
                            .clickable { onPick(t) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t, color = Color.White, fontFamily = CairoFont, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
