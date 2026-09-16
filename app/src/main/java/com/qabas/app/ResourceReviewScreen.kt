package com.qabas.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * شاشة مراجعة الموارد — تجلب B-Roll من Pexels/Pixabay وتعرضه للموافقة/الرفض
 * قبل الانتقال إلى المعالجة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceReviewScreen(
    ideaText: String,
    mediaResources: List<MediaResource>,
    onAddResource: (MediaResource) -> Unit,
    onUpdateResource: (MediaResource) -> Unit,
    onRemoveResource: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    selectedRatio: String,
    onRatioChange: (String) -> Unit,
    videoDuration: String,
    onDurationChange: (String) -> Unit,
    editingStyle: String,
    onStyleChange: (String) -> Unit,
    voiceOver: String,
    onVoiceChange: (String) -> Unit,
    ambientSound: String,
    onAmbientChange: (String) -> Unit,
    videoQuality: String,
    onQualityChange: (String) -> Unit,
    videoStyleAnalysis: VideoStyleAnalysis? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val goldGradient = Brush.horizontalGradient(colors = listOf(GoldSecondary, GoldPrimary))

    // --- State: fetched B-Roll ---
    var fetchedBRoll by remember { mutableStateOf<List<FetchedMedia>>(emptyList()) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var acceptedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // --- Pickers ---
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val meta = MediaPickerHelpers.extractVideoMeta(context, uri)
        onAddResource(
            MediaResource(
                id = UUID.randomUUID().toString(),
                type = "video",
                name = meta.fileName,
                icon = MediaResource.iconForType("video"),
                uri = uri.toString(),
                durationLabel = meta.durationFormatted,
                sizeLabel = meta.sizeFormatted
            )
        )
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 12)
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            val name = try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                } ?: "image_${System.currentTimeMillis()}.jpg"
            } catch (_: Exception) { "image_${System.currentTimeMillis()}.jpg" }
            onAddResource(
                MediaResource(
                    id = UUID.randomUUID().toString(),
                    type = "image",
                    name = name,
                    icon = MediaResource.iconForType("image"),
                    uri = uri.toString()
                )
            )
        }
    }

    // --- Auto-fetch B-Roll on idea change ---
    LaunchedEffect(ideaText) {
        if (ideaText.isBlank()) return@LaunchedEffect
        isFetching = true
        fetchError = null
        try {
            val results = mutableListOf<FetchedMedia>()
            val queries = ideaText.split(" ", "،", ",").filter { it.length > 2 }.take(3)
            if (queries.isEmpty()) queries.add(ideaText.take(30))

            for (query in queries) {
                try {
                    val videoUrl = AppServices.fetchMedia(query, "video")
                    if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                        val thumbnailUrl = videoUrl.replace(".mp4", ".jpg").replace("video", "image")
                        results.add(
                            FetchedMedia(
                                id = "fetched_${query.hashCode()}",
                                source = when {
                                    videoUrl.contains("pexels") -> "Pexels"
                                    videoUrl.contains("pixabay") -> "Pixabay"
                                    videoUrl.contains("mixkit") -> "Mixkit"
                                    else -> "AI"
                                },
                                query = query,
                                videoUrl = videoUrl,
                                thumbnailUrl = thumbnailUrl
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
            fetchedBRoll = results
            // Auto-accept all fetched
            acceptedIds = results.map { it.id }.toSet()
        } catch (e: Exception) {
            fetchError = e.message
        } finally {
            isFetching = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DeepSlate,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        Translator.tr("مراجعة الموارد"),
                        color = GoldPrimary,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Translator.tr("العودة"), tint = GoldPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepSlate)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ═══════════════════════════════════════════
            // Section 1: Fetched B-Roll from Internet
            // ═══════════════════════════════════════════
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDownload, null, tint = GoldPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            Translator.tr("موارد من الإنترنت"),
                            color = GoldPrimary,
                            fontSize = 16.sp,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (isFetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = GoldPrimary
                        )
                    } else if (fetchedBRoll.isNotEmpty()) {
                        Text(
                            "${acceptedIds.size}/${fetchedBRoll.size}",
                            color = Color(0xFF34D399),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isFetching) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = GoldPrimary)
                            Column {
                                Text(
                                    Translator.tr("جاري البحث عن مواد مناسبة..."),
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                            ideaText.take(60) + if (ideaText.length > 60) "..." else "",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                } else if (fetchError != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1017)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                            Text(
                                Translator.tr("تعذر البحث — سيعمل التطبيق بمواد محلية"),
                                color = Color(0xFFF59E0B),
                                fontSize = 12.sp
                            )
                        }
                    }
                } else if (fetchedBRoll.isEmpty() && !isFetching) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Info, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                            Text(
                                Translator.tr("لم يتم العثور على مواد — يمكنك رفع مواردك أو المتابعة بالمواد المحلية"),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Fetched B-Roll cards
                fetchedBRoll.forEach { media ->
                    val isAccepted = media.id in acceptedIds
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                acceptedIds = if (isAccepted) acceptedIds - media.id else acceptedIds + media.id
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isAccepted) Color(0xFF0A1F12) else Color(0xFF0F172A)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isAccepted) Color(0xFF34D399).copy(alpha = 0.6f) else Color(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Thumbnail placeholder
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PlayCircle,
                                    null,
                                    tint = if (isAccepted) Color(0xFF34D399) else GoldPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    media.source,
                                    color = if (isAccepted) Color(0xFF34D399) else GoldPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    media.query,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    Translator.tr("انقر لـ") + if (isAccepted) Translator.tr(" الإزالة") else Translator.tr(" القبول"),
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            Checkbox(
                                checked = isAccepted,
                                onCheckedChange = { checked ->
                                    acceptedIds = if (checked) acceptedIds + media.id else acceptedIds - media.id
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF34D399),
                                    uncheckedColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // ═══════════════════════════════════════════
            // Section 2: My Media (upload own)
            // ═══════════════════════════════════════════
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Upload, null, tint = GoldPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        Translator.tr("مواردي"),
                        color = GoldPrimary,
                        fontSize = 16.sp,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Movie, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(Translator.tr("فيديو"), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Image, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(Translator.tr("صور"), fontSize = 12.sp)
                    }
                }

                // User resources list
                if (mediaResources.isNotEmpty()) {
                    mediaResources.forEach { resource ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CardSurface)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(resource.icon, null, tint = GoldPrimary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(resource.name, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val meta = listOfNotNull(resource.type, resource.durationLabel, resource.sizeLabel).joinToString(" • ")
                                Text(meta, color = TextSecondary, fontSize = 11.sp)
                            }
                            IconButton(onClick = { onRemoveResource(resource.id) }) {
                                Icon(Icons.Default.Close, null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // ═══════════════════════════════════════════
            // Section 3: Quick Production Settings
            // ═══════════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF161E2E))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    Translator.tr("إعدادات سريعة"),
                    color = GoldPrimary,
                    fontFamily = CairoFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                // Ratio
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Translator.tr("الأبعاد"), color = TextSecondary, fontSize = 12.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("1:1", "16:9", "9:16").forEach { ratio ->
                            ChoiceChip(
                                text = ratio,
                                isSelected = selectedRatio == ratio,
                                onClick = { onRatioChange(ratio) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Duration
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Translator.tr("المدة"), color = TextSecondary, fontSize = 12.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Translator.tr("15 ثانية"), Translator.tr("30 ثانية"), Translator.tr("60 ثانية")).forEach { duration ->
                            ChoiceChip(
                                text = duration,
                                isSelected = videoDuration == duration,
                                onClick = { onDurationChange(duration) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Quality
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Translator.tr("جودة التصدير"), color = TextSecondary, fontSize = 12.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Translator.tr("HD 1080p"), Translator.tr("4K Cinematic")).forEach { q ->
                            ChoiceChip(
                                text = q,
                                isSelected = videoQuality == q,
                                onClick = { onQualityChange(q) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Ambient
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Translator.tr("صوت خلفي"), color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(Translator.tr("طبيعة"), Translator.tr("مدينة"), Translator.tr("هادئ"), Translator.tr("بدون")).forEach { ambient ->
                            ChoiceChip(
                                text = ambient,
                                isSelected = ambientSound == ambient,
                                onClick = { onAmbientChange(ambient) }
                            )
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════
            // Bottom: Summary + Action
            // ═══════════════════════════════════════════
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val totalAccepted = acceptedIds.size + mediaResources.size
                if (totalAccepted > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "${Translator.tr("جاهز")} • $totalAccepted ${Translator.tr("مورد")}",
                            color = Color(0xFF34D399),
                            fontSize = 13.sp,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Button(
                    onClick = {
                        // Add accepted fetched media to resources
                        fetchedBRoll.filter { it.id in acceptedIds }.forEach { media ->
                            if (mediaResources.none { it.uri == media.videoUrl }) {
                                onAddResource(
                                    MediaResource(
                                        id = "broll_${media.id}",
                                        type = "broll",
                                        name = "${media.source}: ${media.query}",
                                        icon = MediaResource.iconForType("broll"),
                                        uri = media.videoUrl,
                                        isBRoll = true
                                    )
                                )
                            }
                        }
                        onNext()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(goldGradient, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            Translator.tr("ابدأ الإنتاج"),
                            color = DeepSlate,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * بيانات مورد مُحمّل من الإنترنت
 */
data class FetchedMedia(
    val id: String,
    val source: String,
    val query: String,
    val videoUrl: String,
    val thumbnailUrl: String
)
