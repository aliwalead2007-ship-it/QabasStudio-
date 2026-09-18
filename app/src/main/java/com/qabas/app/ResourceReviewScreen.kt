package com.qabas.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.qabas.app.ui.input.MediaPickerHelpers
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * شاشة مراجعة الموارد v2 — تصميم زجاجي مهندس:
 * جلب B-Roll + مراجعة + قبول/رفض + شريط إنتاج ثابت.
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
    val glassGradient = Brush.verticalGradient(colors = listOf(Color(0xFF1A2233), Color(0xFF0D1320)))

    var fetchedBRoll by remember { mutableStateOf<List<FetchedMedia>>(emptyList()) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var acceptedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var entranceVisible by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }

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

    LaunchedEffect(ideaText) {
        if (ideaText.isBlank()) return@LaunchedEffect
        isFetching = true
        fetchError = null
        try {
            val results = mutableListOf<FetchedMedia>()
            val queries = ideaText.split(" ", "،", ",").filter { it.length > 2 }.take(3).toMutableList()
            if (queries.isEmpty()) queries.add(ideaText.take(30))
            for (query in queries) {
                try {
                    val videoUrl = AppServices.fetchMedia(query, "video")
                    if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
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
                                thumbnailUrl = videoUrl
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
            fetchedBRoll = results
            acceptedIds = results.map { it.id }.toSet()
        } catch (e: Exception) {
            fetchError = e.message
        } finally {
            isFetching = false
            entranceVisible = true
        }
    }

    fun sourceColor(source: String): Color = when (source) {
        "Pexels" -> Color(0xFF34D399)
        "Pixabay" -> Color(0xFF38BDF8)
        "Mixkit" -> Color(0xFFA78BFA)
        else -> GoldPrimary
    }

    fun commitAcceptedAndNext() {
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
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DeepSlate,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            Translator.tr("مراجعة الموارد"),
                            color = GoldPrimary,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold
                        )
                        if (ideaText.isNotBlank()) {
                            Text(
                                ideaText.take(48) + if (ideaText.length > 48) "…" else "",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Translator.tr("العودة"), tint = GoldPrimary)
                    }
                },
                actions = {
                    if (fetchedBRoll.isNotEmpty()) {
                        Surface(
                            color = Color(0xFF0A1F12),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34D399).copy(alpha = 0.5f))
                        ) {
                            Text(
                                "${acceptedIds.size}/${fetchedBRoll.size}",
                                color = Color(0xFF34D399),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepSlate)
            )
        },
        bottomBar = {
            Surface(
                color = Color(0xFF0B0F19).copy(alpha = 0.97f),
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.25f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val totalAccepted = acceptedIds.size + mediaResources.size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (totalAccepted > 0) Icons.Default.CheckCircle else Icons.Default.Info,
                            null,
                            tint = if (totalAccepted > 0) Color(0xFF34D399) else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (totalAccepted > 0) "${Translator.tr("جاهز")} • $totalAccepted ${Translator.tr("مورد")}"
                            else Translator.tr("اختر مورداً أو تابع بالمواد المحلية"),
                            color = if (totalAccepted > 0) Color(0xFF34D399) else TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Button(
                        onClick = { commitAcceptedAndNext() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues()
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(goldGradient, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MovieCreation, null, tint = DeepSlate, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
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
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ═══ موارد الإنترنت ═══
            SectionHeader(
                icon = Icons.Default.CloudDownload,
                title = Translator.tr("موارد من الإنترنت"),
                trailing = {
                    when {
                        isFetching -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = GoldPrimary)
                        fetchedBRoll.isEmpty() -> null
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { acceptedIds = fetchedBRoll.map { it.id }.toSet() }) {
                                Text(Translator.tr("قبول الكل"), color = Color(0xFF34D399), fontSize = 12.sp)
                            }
                            TextButton(onClick = { acceptedIds = emptySet() }) {
                                Text(Translator.tr("رفض الكل"), color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            )

            AnimatedVisibility(
                visible = isFetching,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                GlassCard {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp, color = GoldPrimary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                Translator.tr("جارٍ جلب مواد مناسبة...") + " (${fetchedBRoll.size}/3)",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(4.dp)),
                                color = GoldPrimary,
                                trackColor = Color(0xFF1E293B)
                            )
                        }
                    }
                }
            }

            if (!isFetching && fetchError != null) {
                GlassCard(borderColor = Color(0xFFF59E0B).copy(alpha = 0.4f)) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(Translator.tr("تعذر البحث — سيعمل التطبيق بمواد محلية"), color = Color(0xFFF59E0B), fontSize = 12.sp)
                    }
                }
            }

            if (!isFetching && fetchError == null && fetchedBRoll.isEmpty()) {
                GlassCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CloudOff, null, tint = TextSecondary, modifier = Modifier.size(40.dp))
                        Text(
                            Translator.tr("لا نتائج بعد — ارفع مواردك أو تابع بالمواد المحلية"),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            fetchedBRoll.forEachIndexed { idx, media ->
                val isAccepted = media.id in acceptedIds
                AnimatedVisibility(
                    visible = entranceVisible,
                    enter = fadeIn(animationSpec = tween(300, delayMillis = idx * 120)) +
                        slideInVertically(animationSpec = tween(300, delayMillis = idx * 120)) { 40 + idx * 20 },
                    exit = fadeOut()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            acceptedIds = if (isAccepted) acceptedIds - media.id else acceptedIds + media.id
                        },
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            if (isAccepted) Brush.horizontalGradient(listOf(Color(0xFF34D399), GoldPrimary))
                                .let { Color(0xFF34D399).copy(alpha = 0.7f) }
                            else Color(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            modifier = Modifier.background(glassGradient).alpha(if (isAccepted) 1f else 0.55f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E293B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = media.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.PlayCircle,
                                            null,
                                            tint = Color.White.copy(alpha = 0.9f),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(
                                        color = sourceColor(media.source).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, sourceColor(media.source).copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            "● ${media.source}",
                                            color = sourceColor(media.source),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Text(
                                        media.query,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // ═══ مواردي ═══
            SectionHeader(
                icon = Icons.Default.Upload,
                title = Translator.tr("مواردي"),
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = GoldPrimary.copy(alpha = 0.15f), contentColor = GoldPrimary)
                        ) {
                            Icon(Icons.Default.Movie, null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(Translator.tr("فيديو"), fontSize = 12.sp)
                        }
                        FilledTonalButton(
                            onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = GoldPrimary.copy(alpha = 0.15f), contentColor = GoldPrimary)
                        ) {
                            Icon(Icons.Default.Image, null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(Translator.tr("صور"), fontSize = 12.sp)
                        }
                    }
                }
            )

            if (mediaResources.isEmpty()) {
                DashedHint(text = Translator.tr("لم تُرفق وسائط — اختياري، الذكاء يكمل تلقائياً ✨"))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(mediaResources, key = { _, r -> r.id }) { _, resource ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardSurface),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.width(150.dp)
                        ) {
                            Box {
                                if (resource.uri != null && (resource.type == "image" || resource.type == "broll")) {
                                    AsyncImage(
                                        model = resource.uri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxWidth().height(84.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(84.dp).background(Color(0xFF1E293B)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(resource.icon, null, tint = GoldPrimary, modifier = Modifier.size(30.dp))
                                    }
                                }
                                IconButton(
                                    onClick = { onRemoveResource(resource.id) },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(13.dp))
                                ) {
                                    Icon(Icons.Default.Close, null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                                }
                            }
                            Text(
                                resource.name,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // ═══ إعدادات (أكورديون مطوي) ═══
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { settingsExpanded = !settingsExpanded }.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, null, tint = GoldPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(Translator.tr("إعدادات الإنتاج"), color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Icon(
                        if (settingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = TextSecondary
                    )
                }
                AnimatedVisibility(visible = settingsExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BreatheIn(0) { ProdLabel(Translator.tr("الأبعاد")) }
                        BreatheIn(1) { ProdChipRow(listOf("9:16", "16:9", "1:1"), selectedRatio, onRatioChange) }
                        BreatheIn(2) { ProdLabel(Translator.tr("المدة")) }
                        BreatheIn(3) { ProdChipRow(listOf(Translator.tr("15 ثانية"), Translator.tr("30 ثانية"), Translator.tr("60 ثانية")), videoDuration, onDurationChange) }
                        BreatheIn(4) { ProdLabel(Translator.tr("الجودة")) }
                        BreatheIn(5) { ProdChipRow(listOf(Translator.tr("HD 1080p"), Translator.tr("4K Cinematic")), videoQuality, onQualityChange) }
                        BreatheIn(6) { ProdLabel(Translator.tr("صوت خلفي")) }
                        BreatheIn(7) { ProdChipRow(listOf(Translator.tr("طبيعة"), Translator.tr("مدينة"), Translator.tr("هادئ"), Translator.tr("بدون")), ambientSound, onAmbientChange) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = GoldPrimary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, color = GoldPrimary, fontSize = 16.sp, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
private fun GlassCard(
    borderColor: Color = Color(0xFF1E293B),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121A2A).copy(alpha = 0.85f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(16.dp),
        content = content
    )
}

@Composable
private fun DashedHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface.copy(alpha = 0.5f))
            .border(1.dp, GoldPrimary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

data class FetchedMedia(
    val id: String,
    val source: String,
    val query: String,
    val videoUrl: String,
    val thumbnailUrl: String
)
