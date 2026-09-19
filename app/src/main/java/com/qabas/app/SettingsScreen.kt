package com.qabas.app

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.qabas.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit, 
    onLogout: () -> Unit, 
    onNavigateToTasteProfile: () -> Unit = {}, 
    onNavigateToPremiumUpgrade: () -> Unit = {}, 
    bottomBar: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    var accountsList by remember { mutableStateOf(SocialAccountManager.getAccounts(context)) }
    var linkDialogState by remember { mutableStateOf<String?>(null) } // "youtube", "tiktok", "instagram"
    var isLinking by remember { mutableStateOf(false) }
    var linkVerifyMsg by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    
    val selectedPlatform = remember(linkDialogState, accountsList) {
        accountsList.find { it.id == linkDialogState }
    }
    
    var customHandle by remember(selectedPlatform) {
        mutableStateOf(selectedPlatform?.handle ?: "")
    }
    var customToken by remember(selectedPlatform) {
        mutableStateOf(if (selectedPlatform != null) SocialAccountManager.getAccessToken(context, selectedPlatform.id) else "")
    }

    var showAboutAppDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<String?>(null) } // null = idle, "checking...", "found", "downloading", "done", "none", "error"
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var updateProgress by remember { mutableStateOf(0) }
    var updateDownloadedBytes by remember { mutableStateOf(0L) }
    var updateTotalBytes by remember { mutableStateOf(0L) }
    var updateSpeedBps by remember { mutableStateOf(0L) }
    var updatePhase by remember { mutableStateOf<UpdateManager.Phase?>(null) }
    var updateDoneMessage by remember { mutableStateOf("") }
    var updateActiveKey by remember { mutableStateOf<String?>(null) }
    var lastConsumedResult by remember { mutableStateOf<Triple<String, Boolean, String>?>(null) }

    // تقدّم حيّ من خدمة الخلفية — يبقى يعمل حتى لو غادرت الشاشة ورجعت
    LaunchedEffect(Unit) {
        UpdateManager.downloadProgress.collect { p ->
            if (p != null && updateState == "downloading" && updateActiveKey != null) {
                updateProgress = p.percent
                updateDownloadedBytes = p.bytesDownloaded
                updateTotalBytes = p.totalBytes
                updateSpeedBps = p.speedBytesPerSec
                updatePhase = p.phase
            }
        }
    }
    LaunchedEffect(Unit) {
        UpdateManager.downloadResult.collect { r ->
            if (r != null && r != lastConsumedResult && r.first == updateActiveKey && updateState == "downloading") {
                lastConsumedResult = r
                updateDoneMessage = r.third
                updateState = when {
                    r.second -> "done"
                    r.third.contains("أُلغي") -> "found"
                    else -> "error"
                }
            }
        }
    }

    // بدء تنزيل عبر خدمة الخلفية (لا يتوقف إلا بالإلغاء أو الاكتمال)
    fun beginServiceDownload(info: UpdateManager.UpdateInfo, forceFull: Boolean) {
        updateActiveKey = "${info.versionName}|${info.versionCode}"
        lastConsumedResult = null
        updateState = "downloading"
        updateProgress = 0
        updateDownloadedBytes = 0L
        updateTotalBytes = 0L
        updateSpeedBps = 0L
        updatePhase = null
        UpdateDownloadService.start(context, info, forceFull)
    }

    // فحص تلقائي عند فتح الإعدادات (يحترم كاش الـ 6 ساعات — لا يزعج المستخدم)
    LaunchedEffect(Unit) {
        if (updateState == null) {
            updateState = "checking..."
            runCatching {
                val info = UpdateManager.checkForUpdate(context)
                if (info != null) {
                    updateInfo = info
                    updateState = "found"
                    context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
                        .edit().putString("last_release_notes", info.releaseNotes).apply()
                } else {
                    updateState = null
                }
            }.onFailure {
                updateState = null
            }
        }
    }

    // ── Developer Mode: مخفي على نص الإصدار (7 مرات) ──
    var devTapCount by remember { mutableIntStateOf(0) }
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var passphraseInput by remember { mutableStateOf("") }
    var passphraseError by remember { mutableStateOf(false) }
    var isDevMode by remember {
        mutableStateOf(
            context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
                .getBoolean("is_developer", false)
        )
    }
    if (linkDialogState != null && selectedPlatform != null) {
        AlertDialog(
            onDismissRequest = { if (!isLinking) linkDialogState = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (selectedPlatform.id) {
                            "youtube" -> Icons.Default.OndemandVideo
                            "tiktok" -> Icons.Default.MusicVideo
                            else -> Icons.Default.CameraAlt
                        },
                        contentDescription = null,
                        tint = GoldPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        Translator.tr("إعدادات ربط ") + selectedPlatform.name,
                        color = GoldPrimary,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        Translator.tr("أدخل اسم قناتك/حسابك ومفتاح الوصول (OAuth Access Token) للتمكين المباشر للنشر التلقائي عبر API المنصة:"),
                        color = Color.White,
                        fontFamily = CairoFont,
                        fontSize = 12.sp
                    )

                    OutlinedTextField(
                        value = customHandle,
                        onValueChange = { customHandle = it },
                        label = { Text(Translator.tr("اسم الحساب / القناة"), fontFamily = CairoFont) },
                        placeholder = { Text("@your_channel_handle", fontFamily = NotoSansFont) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = customToken,
                        onValueChange = { customToken = it },
                        label = { Text(Translator.tr("رمز التفويض (OAuth Access Token) - اختياري"), fontFamily = CairoFont) },
                        placeholder = { Text("ya29.a0... / act_...", fontFamily = NotoSansFont) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )

                    if (isLinking) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator(color = GoldPrimary, modifier = Modifier.align(Alignment.CenterHorizontally))
                        linkVerifyMsg?.let {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(it, color = GoldSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                        }
                    } else {
                        linkVerifyMsg?.let {
                            Text(it, color = Color(0xFFE53935), fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = if (selectedPlatform.isConnected) "الحالة الحالية: مربوط بنجاح 🟢" else "الحالة الحالية: غير مربوط ⚪",
                            color = if (selectedPlatform.isConnected) Color(0xFF4CAF50) else Color.Gray,
                            fontFamily = CairoFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                if (!isLinking) {
                    Button(
                        onClick = {
                            isLinking = true
                            linkVerifyMsg = null
                            coroutineScope.launch {
                                val newHandle = customHandle.ifBlank { selectedPlatform.handle }
                                val token = customToken.trim()
                                // 1) تحقق حقيقي: هل الحساب موجود على المنصة؟
                                linkVerifyMsg = "نتحقق من وجود الحساب على ${selectedPlatform.name}..."
                                val exists = SocialAccountManager.verifyProfileExists(selectedPlatform.id, newHandle)
                                if (exists == SocialAccountManager.VerifyResult.MISSING) {
                                    isLinking = false
                                    linkVerifyMsg = "الحساب غير موجود على المنصة — تحقق من المعرّف"
                                    return@launch
                                }
                                // 2) إن أُدخل رمز Google (يوتيوب): تحقق حقيقي من صلاحيته
                                var tokenOk = true
                                if (token.isNotBlank() && selectedPlatform.id == "youtube") {
                                    linkVerifyMsg = "نتحقق من صلاحية الرمز..."
                                    tokenOk = SocialAccountManager.verifyGoogleToken(token)
                                    if (!tokenOk) {
                                        isLinking = false
                                        linkVerifyMsg = "الرمز مرفوض من Google — أدخل رمز OAuth صالحاً أو اتركه فارغاً"
                                        return@launch
                                    }
                                }
                                val verified = exists == SocialAccountManager.VerifyResult.EXISTS || tokenOk && token.isNotBlank()
                                SocialAccountManager.toggleConnection(context, selectedPlatform.id, true, newHandle, token)
                                SocialAccountManager.setConnectionVerified(context, selectedPlatform.id, verified)
                                accountsList = SocialAccountManager.getAccounts(context)
                                isLinking = false
                                linkDialogState = null
                                linkVerifyMsg = null
                                Toast.makeText(
                                    context,
                                    if (verified) "تم ربط ${selectedPlatform.name} (تم التحقق ✅)"
                                    else "تم حفظ ${selectedPlatform.name} (تعذّر التأكيد — تحقق يدوياً)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(Translator.tr("حفظ وتأكيد الربط 🟢"), color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isLinking) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selectedPlatform.isConnected) {
                            TextButton(onClick = {
                                isLinking = true
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(500)
                                    SocialAccountManager.toggleConnection(context, selectedPlatform.id, false)
                                    SocialAccountManager.setConnectionVerified(context, selectedPlatform.id, false)
                                    accountsList = SocialAccountManager.getAccounts(context)
                                    isLinking = false
                                    linkDialogState = null
                                    Toast.makeText(context, Translator.tr("تم إلغاء ربط الحساب 🔴"), Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text(Translator.tr("إلغاء الربط"), color = Color(0xFFE53935), fontFamily = CairoFont)
                            }
                        }
                        TextButton(onClick = { linkDialogState = null }) {
                            Text(Translator.tr("إغلاق"), color = Color.White, fontFamily = CairoFont)
                        }
                    }
                }
            },
            containerColor = CardSurface,
            shape = RoundedCornerShape(18.dp)
        )
    }

    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
    val userEmail = prefs.getString("user_email", "") ?: ""
    val isAdmin = prefs.getBoolean("is_admin", false)
    val isRelative = prefs.getBoolean("is_relative", false)
    val isSelfSufficient = isAdmin || isRelative
    val isGuest = userEmail == "guest@qabas.studio" || userEmail.isBlank()
    
    var currentLang by remember { mutableStateOf(prefs.getString("app_language", "ar") ?: "ar") }
    var defaultQuality by remember { mutableStateOf(prefs.getString("defaultQuality", "4k") ?: "4k") }
    var autoPublishing by remember { mutableStateOf(prefs.getBoolean("auto_publish", true)) }

    val savedDisplayName = prefs.getString("user_display_name", "")?.takeIf { it.isNotBlank() } ?: (if (isGuest) Translator.tr("حساب زائر") else userEmail.substringBefore("@"))
    val avatarUriString = prefs.getString("user_avatar_uri", "") ?: ""
    val selectedPresetAvatar = prefs.getInt("user_avatar_preset", 0)

    Scaffold(
        containerColor = DeepSlate,
        topBar = {
            TopAppBar(
                title = { Text(Translator.tr("الإعدادات الحسابية والفنية"), color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 22.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Translator.tr("العودة"), tint = GoldPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepSlate)
            )
        },
        bottomBar = bottomBar
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // 1. Profile Info Container (Always expanded header profile card)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .luxuryCardStyle(shapeRadius = 20.dp, borderAlpha = 0.35f, glowElevation = 6.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = Color(0xFF151B2B),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, GoldPrimary)
                    ) {
                        if (avatarUriString.isNotBlank()) {
                            AsyncImage(
                                model = avatarUriString,
                                contentDescription = "Avatar",
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize().background(GoldPrimary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (selectedPresetAvatar) {
                                        1 -> Icons.Default.AutoAwesome
                                        2 -> Icons.Default.MovieFilter
                                        3 -> Icons.Default.MenuBook
                                        4 -> Icons.Default.Psychology
                                        else -> Icons.Default.Person
                                    },
                                    contentDescription = null,
                                    tint = GoldPrimary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = savedDisplayName,
                            color = Color.White,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            text = if (isGuest) "guest@qabas.studio" else userEmail,
                            color = Color.Gray,
                            fontFamily = NotoSansFont,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isAdmin || isRelative) GoldPrimary else Color(0xFF2D3748))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = when {
                                    isAdmin -> Translator.tr("مطور النظام (صلاحيات كاملة) 👑")
                                    isRelative -> Translator.tr("حساب الاكتفاء الذاتي (مفاتيح خاصة) 🔑")
                                    isGuest -> Translator.tr("حساب مجاني 🏷️")
                                    else -> Translator.tr("عضوية ذهبية (Pro) ⭐")
                                }, 
                                color = if (isAdmin || isRelative) DeepSlate else Color.White, 
                                fontFamily = CairoFont, 
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // الحسابات المربوطة — يراها صاحب الملف فوراً
                        val linkedNow = accountsList.filter { it.isConnected }
                        if (linkedNow.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                linkedNow.take(5).forEach { acc ->
                                    val ok = SocialAccountManager.isConnectionVerified(context, acc.id)
                                    val (ic, tint) = when (acc.id) {
                                        "youtube" -> Icons.Default.OndemandVideo to Color(0xFFF44336)
                                        "tiktok" -> Icons.Default.MusicVideo to Color.White
                                        "instagram" -> Icons.Default.CameraAlt to Color(0xFFE1306C)
                                        "twitter" -> Icons.Default.AlternateEmail to Color(0xFF1DA1F2)
                                        else -> Icons.Default.Share to GoldPrimary
                                    }
                                    Surface(
                                        color = Color(0xFF151B2B),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (ok) Color(0xFF10B981).copy(alpha = 0.5f) else Color(0xFFF59E0B).copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(ic, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                acc.handle.take(14),
                                                color = Color.White, fontFamily = NotoSansFont,
                                                fontSize = 10.sp, maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!isAdmin && !isGuest && !isRelative) {
                        IconButton(onClick = onNavigateToPremiumUpgrade) {
                            Icon(Icons.Default.WorkspacePremium, contentDescription = "ترقية", tint = GoldPrimary)
                        }
                    }
                }
            }

            // 2. AI Automation & Taste Profile Collapsible Section
            CollapsibleSettingsCard(
                title = Translator.tr("هوية الاستوديو والذكاء الاصطناعي"),
                icon = Icons.Default.Psychology,
                sectionKey = "settings_ai_identity"
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141C27))
                        .clickable { onNavigateToTasteProfile() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(Translator.tr("هوية الاستوديو (Taste Engine)"), color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(Translator.tr("تنسيق أسلوب ونبرة الذكاء الاصطناعي وفقاً لأسلوبك الخاص"), color = Color.Gray, fontFamily = NotoSansFont, fontSize = 12.sp)
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                }

                HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Translator.tr("تذكيرات مواعيد النشر"), color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(Translator.tr("إشعار عند حلول وقت النشر المثالي الذي جدولته"), color = Color.Gray, fontFamily = NotoSansFont, fontSize = 12.sp)
                    }
                    Switch(
                        checked = autoPublishing,
                        onCheckedChange = { 
                            autoPublishing = it
                            prefs.edit().putBoolean("auto_publish", it).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GoldPrimary, 
                            checkedTrackColor = GoldPrimary.copy(alpha = 0.5f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
                }
            }

            // 3. General Settings & Export Quality Collapsible Section
            CollapsibleSettingsCard(
                title = Translator.tr("التفضيلات والجودة والتصدير"),
                icon = Icons.Default.Tune,
                sectionKey = "settings_preferences"
            ) {
                // Dark / Light Theme Toggle
                val isDarkTheme by ThemeManager.isDarkTheme.collectAsState()
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(Translator.tr("المظهر"), color = Color.White, fontFamily = NotoSansFont, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Card(
                            modifier = Modifier.weight(1f).clickable { ThemeManager.setDarkTheme(context, true) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDarkTheme) GoldPrimary.copy(alpha = 0.2f) else Color(0xFF151B2B)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, if (isDarkTheme) GoldPrimary else Color(0xFF2A3040))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.DarkMode, contentDescription = null, tint = if (isDarkTheme) GoldPrimary else Color.Gray, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(Translator.tr("داكن"), color = if (isDarkTheme) GoldPrimary else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                            }
                        }
                        Card(
                            modifier = Modifier.weight(1f).clickable { ThemeManager.setDarkTheme(context, false) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (!isDarkTheme) GoldPrimary.copy(alpha = 0.2f) else Color(0xFF151B2B)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, if (!isDarkTheme) GoldPrimary else Color(0xFF2A3040))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.LightMode, contentDescription = null, tint = if (!isDarkTheme) GoldPrimary else Color.Gray, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(Translator.tr("فاتح"), color = if (!isDarkTheme) GoldPrimary else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 10.dp))

                // Language
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(Translator.tr("لغة الواجهة والتطبيق"), color = Color.White, fontFamily = NotoSansFont, fontSize = 14.sp)
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF151B2B))
                    ) {
                        Text(
                            "العربية",
                            modifier = Modifier
                                .background(if (currentLang == "ar") GoldPrimary else Color.Transparent)
                                .clickable {
                                    currentLang = "ar"
                                    prefs.edit().putString("app_language", "ar").apply()
                                    Toast.makeText(context, "تم تطبيق اللغة العربية.", Toast.LENGTH_SHORT).show()
                                    (context as? android.app.Activity)?.recreate()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (currentLang == "ar") DeepSlate else Color.Gray,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            "English",
                            modifier = Modifier
                                .background(if (currentLang == "en") GoldPrimary else Color.Transparent)
                                .clickable {
                                    currentLang = "en"
                                    prefs.edit().putString("app_language", "en").apply()
                                    Toast.makeText(context, "Language set to English.", Toast.LENGTH_SHORT).show()
                                    (context as? android.app.Activity)?.recreate()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (currentLang == "en") DeepSlate else Color.Gray,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
                
                HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 10.dp))
                
                // Export Quality
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(Translator.tr("جودة التصدير الافتراضية للفيديو"), color = Color.White, fontFamily = NotoSansFont, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Card(
                            modifier = Modifier.weight(1f).clickable { defaultQuality = "1080p"; prefs.edit().putString("defaultQuality", "1080p").apply() },
                            colors = CardDefaults.cardColors(
                                containerColor = if (defaultQuality == "1080p") GoldPrimary.copy(alpha = 0.2f) else Color(0xFF151B2B)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, if (defaultQuality == "1080p") GoldPrimary else Color(0xFF2A3040))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("HD", color = if (defaultQuality == "1080p") GoldPrimary else Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = NotoSansFont)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(Translator.tr("سريع ⚡"), color = if (defaultQuality == "1080p") GoldPrimary else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                            }
                        }
                        Card(
                            modifier = Modifier.weight(1f).clickable { defaultQuality = "4k"; prefs.edit().putString("defaultQuality", "4k").apply() },
                            colors = CardDefaults.cardColors(
                                containerColor = if (defaultQuality == "4k") GoldPrimary.copy(alpha = 0.2f) else Color(0xFF151B2B)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, if (defaultQuality == "4k") GoldPrimary else Color(0xFF2A3040))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("4K", color = if (defaultQuality == "4k") GoldPrimary else Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = NotoSansFont)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(Translator.tr("أفضل جودة 👁️‍🗨️"), color = if (defaultQuality == "4k") GoldPrimary else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = CairoFont)
                            }
                        }
                    }
                }
            }

            // 4. Official Accounts Collapsible Section (الحسابات الرسمية)
            // المستخدم العادي: عرض فقط. المطور: تعديل/إضافة/حذف.
            val canManageChannels = isAdmin || isDevMode
            var officialChannels by remember { mutableStateOf(SocialAccountManager.getOfficialChannels(context)) }
            fun refreshChannels() {
                officialChannels = SocialAccountManager.getOfficialChannels(context)
            }
            var channelDraftId by remember { mutableStateOf<String?>(null) } // null=مغلق، ""=جديد
            var chPlatform by remember { mutableStateOf("") }
            var chDisplay by remember { mutableStateOf("") }
            var chHandle by remember { mutableStateOf("") }
            var chUrl by remember { mutableStateOf("") }
            var chDesc by remember { mutableStateOf("") }
            var chUrlError by remember { mutableStateOf(false) }
            fun openChannelEditor(c: OfficialChannelInfo?) {
                channelDraftId = c?.id ?: ""
                chPlatform = c?.platformName ?: ""
                chDisplay = c?.displayName ?: ""
                chHandle = c?.handle ?: ""
                chUrl = c?.url ?: ""
                chDesc = c?.description ?: ""
                chUrlError = false
            }
            CollapsibleSettingsCard(
                title = Translator.tr("الحسابات الرسمية"),
                icon = Icons.Default.Verified,
                sectionKey = "settings_official_accounts",
                badge = "معتمدة ✦"
            ) {
                Text(
                    Translator.tr("تابع وتواصل مع منصات وقنوات قبس الرسمية على مختلف شبكات التواصل."),
                    color = Color.Gray,
                    fontFamily = CairoFont,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                officialChannels.forEachIndexed { index, channel ->
                    if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                    OfficialAccountItem(
                        channel = channel,
                        onOpen = {
                            SocialAccountManager.openOfficialChannel(context, channel.url)
                        },
                        onCopy = {
                            SocialAccountManager.copyToClipboard(context, channel.url, channel.platformName)
                        },
                        showDevActions = canManageChannels,
                        onEdit = { openChannelEditor(channel) },
                        onDelete = if (channel.id !in SocialAccountManager.FIXED_CHANNEL_IDS) {
                            {
                                SocialAccountManager.deleteCustomChannel(context, channel.id)
                                refreshChannels()
                                Toast.makeText(context, "حُذف الرابط", Toast.LENGTH_SHORT).show()
                            }
                        } else null
                    )
                }
                if (canManageChannels) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { openChannelEditor(null) },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إضافة رابط رسمي", color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            // حوار إضافة/تعديل رابط (مطور فقط)
            if (channelDraftId != null && canManageChannels) {
                val editingId = channelDraftId!!
                val isFixed = editingId in SocialAccountManager.FIXED_CHANNEL_IDS
                AlertDialog(
                    onDismissRequest = { channelDraftId = null },
                    containerColor = CardSurface,
                    title = { Text(if (editingId.isBlank()) "إضافة رابط رسمي" else "تعديل الرابط", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isFixed) {
                                OutlinedTextField(value = chPlatform, onValueChange = { chPlatform = it }, label = { Text("اسم المنصة", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = chDisplay, onValueChange = { chDisplay = it }, label = { Text("الاسم المعروض", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            OutlinedTextField(value = chHandle, onValueChange = { chHandle = it }, label = { Text("المعرّف (handle)", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = chUrl, onValueChange = { chUrl = it; chUrlError = false },
                                label = { Text("الرابط https://...", fontSize = 11.sp) }, singleLine = true,
                                isError = chUrlError, modifier = Modifier.fillMaxWidth()
                            )
                            if (chUrlError) Text("أدخل رابطاً صالحاً يبدأ بـ http", color = Color(0xFFE53935), fontSize = 11.sp, fontFamily = CairoFont)
                            if (!isFixed) {
                                OutlinedTextField(value = chDesc, onValueChange = { chDesc = it }, label = { Text("الوصف (اختياري)", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val url = chUrl.trim()
                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    chUrlError = true
                                    return@Button
                                }
                                if (editingId.isBlank()) {
                                    val id = "custom_" + System.currentTimeMillis()
                                    SocialAccountManager.saveCustomChannel(
                                        context,
                                        OfficialChannelInfo(
                                            id = id,
                                            platformName = chPlatform.trim().ifBlank { "رابط" },
                                            handle = chHandle.trim(),
                                            displayName = chDisplay.trim().ifBlank { chPlatform.trim().ifBlank { "رابط رسمي" } },
                                            description = chDesc.trim(),
                                            url = url,
                                            followersDisplay = "رسمي ✦"
                                        )
                                    )
                                    Toast.makeText(context, "أُضيف الرابط ✅", Toast.LENGTH_SHORT).show()
                                } else if (isFixed) {
                                    SocialAccountManager.updateOfficialChannel(context, editingId, chHandle, url)
                                    Toast.makeText(context, "حُفظ التعديل ✅", Toast.LENGTH_SHORT).show()
                                } else {
                                    val old = officialChannels.firstOrNull { it.id == editingId }
                                    if (old != null) {
                                        SocialAccountManager.saveCustomChannel(
                                            context,
                                            old.copy(
                                                platformName = chPlatform.trim().ifBlank { old.platformName },
                                                displayName = chDisplay.trim().ifBlank { old.displayName },
                                                handle = chHandle.trim(),
                                                description = chDesc.trim(),
                                                url = url
                                            )
                                        )
                                        Toast.makeText(context, "حُفظ التعديل ✅", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                channelDraftId = null
                                refreshChannels()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) { Text("حفظ", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { channelDraftId = null }) {
                            Text("إلغاء", color = Color.Gray, fontFamily = CairoFont)
                        }
                    }
                )
            }

            // 5. Social Media Accounts Collapsible Section (ربط المنصات الاجتماعية والنشر)
            CollapsibleSettingsCard(
                title = Translator.tr("ربط المنصات الاجتماعية والنشر"),
                icon = Icons.Default.Share,
                sectionKey = "settings_social_platforms"
            ) {
                accountsList.forEachIndexed { index, account ->
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    val verified = account.isConnected && SocialAccountManager.isConnectionVerified(context, account.id)
                    val statusText = when {
                        !account.isConnected -> Translator.tr("غير مربوط ⚪")
                        verified -> "${account.handle} (مربوط ✅)"
                        else -> "${account.handle} (غير مؤكد ⚠️)"
                    }
                    val icon = when (account.id) {
                        "youtube" -> Icons.Default.OndemandVideo
                        "tiktok" -> Icons.Default.MusicVideo
                        "instagram" -> Icons.Default.CameraAlt
                        "twitter" -> Icons.Default.AlternateEmail
                        else -> Icons.Default.Share
                    }
                    val iconColor = when (account.id) {
                        "youtube" -> Color(0xFFF44336)
                        "tiktok" -> Color.White
                        "instagram" -> Color(0xFFE1306C)
                        "twitter" -> Color(0xFF1DA1F2)
                        else -> GoldPrimary
                    }
                    SocialAccountItem(
                        platform = account.name,
                        status = statusText,
                        isLinked = account.isConnected,
                        icon = icon,
                        iconColor = iconColor
                    ) {
                        linkVerifyMsg = null
                        linkDialogState = account.id
                    }
                }
            }

            // 6. About & Platform Info Collapsible Section
            CollapsibleSettingsCard(
                title = Translator.tr("حول تطبيق قبس والمعلومات"),
                icon = Icons.Default.Info,
                sectionKey = "settings_about_app",
                badge = "v${BuildConfig.VERSION_NAME}"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(Translator.tr("إصدار المنصة الرسمي"), color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "الإصدار: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                            color = GoldPrimary,
                            fontFamily = NotoSansFont,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                devTapCount++
                                if (devTapCount >= 7 && !isDevMode) {
                                    showPassphraseDialog = true
                                    devTapCount = 0
                                } else if (isDevMode) {
                                    Toast.makeText(context, "وضع المطور مفعّل بالفعل", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    DoctorPulse()
                }

                // ── بطاقة التحديثات ──
                HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 10.dp))

                val updateAccent = when (updateState) {
                    "found", "downloading", "done", "none" -> Color(0xFF10B981)
                    "checking..." -> AiViolet
                    "error" -> Color(0xFFF97316)
                    else -> GoldPrimary
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = updateState != "checking..." && updateState != "downloading") {
                        updateState = "checking..."
                        updateProgress = 0
                        coroutineScope.launch {
                            try {
                                val info = UpdateManager.checkForUpdate(context, force = true)
                                if (info != null) {
                                    updateInfo = info
                                    updateState = "found"
                                    prefs.edit().putString("last_release_notes", info.releaseNotes).apply()
                                } else {
                                    updateState = "none"
                                }
                            } catch (e: Exception) {
                                updateState = "error"
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, updateAccent.copy(alpha = 0.45f)),
                    color = Color(0xFF111827)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Surface(
                                color = updateAccent.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = when (updateState) {
                                        "found" -> Icons.Default.SystemUpdate
                                        "checking..." -> Icons.Default.Sync
                                        "downloading" -> Icons.Default.Download
                                        "done" -> Icons.Default.CheckCircle
                                        "none" -> Icons.Default.CheckCircle
                                        "error" -> Icons.Default.ErrorOutline
                                        else -> Icons.Default.CloudDownload
                                    },
                                    contentDescription = null,
                                    tint = updateAccent,
                                    modifier = Modifier.padding(8.dp).size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (updateState) {
                                        "checking..." -> "جاري فحص التحديثات..."
                                        "found" -> "تحديث جديد متاح"
                                        "none" -> "نسختك محدّثة"
                                        "error" -> "تعذّر الاتصال بالخادم"
                                        "downloading" -> when (updatePhase) {
                                            UpdateManager.Phase.DOWNLOADING_DELTA -> "تنزيل تحديث صغير (دلتا)..."
                                            UpdateManager.Phase.APPLYING_PATCH -> "تطبيق التحديث الصغير..."
                                            UpdateManager.Phase.DOWNLOADING_FULL -> "تنزيل التحديث الكامل..."
                                            else -> "جاري تنزيل التحديث..."
                                        }
                                        "done" -> "اكتمل التنزيل"
                                        else -> "التحديثات"
                                    },
                                    color = Color.White,
                                    fontFamily = CairoFont,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when (updateState) {
                                        "found" -> "v${BuildConfig.VERSION_NAME} ← v${updateInfo?.versionName}"
                                        "none" -> "v${BuildConfig.VERSION_NAME} • الأحدث ✓"
                                        "error" -> "تحقق من الإنترنت ثم أعد المحاولة"
                                        "downloading" -> {
                                            val downloaded = UpdateManager.formatSize(updateDownloadedBytes)
                                            val total = if (updateTotalBytes > 0) UpdateManager.formatSize(updateTotalBytes) else "…"
                                            val speed = if (updateSpeedBps > 0) " • ${UpdateManager.formatSpeed(updateSpeedBps)}" else ""
                                            "$updateProgress٪ • $downloaded من $total$speed"
                                        }
                                        "done" -> updateDoneMessage.ifBlank { "أكمل التثبيت من شاشة النظام" }
                                        "checking..." -> "نقارن نسختك مع آخر إصدار على GitHub"
                                        else -> "آخر فحص يقارن نسختك مع GitHub Releases"
                                    },
                                    color = Color(0xFF94A3B8),
                                    fontFamily = NotoSansFont,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (updateState == "found") {
                            if (updateInfo?.deltaUrl != null && (updateInfo?.deltaSize ?: 0) > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            updateInfo?.let { info -> beginServiceDownload(info, false) }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        Text("تحديث صغير", color = Color.White, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = {
                                            updateInfo?.let { info -> beginServiceDownload(info, true) }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        Text("تحديث كامل", color = Color.Black, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else if (updateState == "downloading") {
                            OutlinedButton(
                                onClick = {
                                    UpdateDownloadService.cancel(context)
                                },
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إلغاء", color = Color(0xFFEF4444), fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (updateState == null || updateState == "none" || updateState == "error") {
                            Text(
                                text = if (updateState == "error") "إعادة المحاولة" else "فحص",
                                color = updateAccent,
                                fontFamily = CairoFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else if (updateState == "done") {
                            Text(
                                text = updateDoneMessage.ifBlank { "اكتمل التنزيل — أكمل التثبيت من شاشة النظام" },
                                color = Color(0xFF10B981),
                                fontFamily = CairoFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Button(
                                onClick = {
                                    updateInfo?.let { info -> beginServiceDownload(info, false) }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text("تحديث الآن", color = Color.White, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        }
                        if (updateState == "found" && updateInfo != null) {
                            val u = updateInfo!!
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = Color(0xFF1A1F2E),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (u.deltaUrl != null && u.deltaSize > 0) "حجم التحديث (دلتا متوقعة)" else "حجم التحديث",
                                        color = Color(0xFF94A3B8), fontFamily = NotoSansFont, fontSize = 11.sp
                                    )
                                    Text(
                                        text = if (u.deltaUrl != null && u.deltaSize > 0)
                                            "${UpdateManager.formatSize(u.deltaSize)} (كامل: ${UpdateManager.formatSize(u.apkSize)})"
                                        else UpdateManager.formatSize(u.apkSize),
                                        color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            if (u.releaseNotes.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = u.releaseNotes.take(220),
                                    color = Color(0xFFCBD5E1),
                                    fontFamily = NotoSansFont,
                                    fontSize = 11.sp,
                                    maxLines = 3
                                )
                            }
                        }
                        if (updateState == "downloading") {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(modifier = Modifier.fillMaxWidth()) {
                                LinearProgressIndicator(
                                    progress = { (updateProgress / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(6.dp)),
                                    color = Color(0xFF10B981),
                                    trackColor = Color(0xFF1E293B)
                                )
                                // عداد رقمي حي فوق الشريط — نسبة % واضحة أثناء التنزيل
                                Text(
                                    text = "$updateProgress٪",
                                    color = Color.White,
                                    fontFamily = NotoSansFont,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF222222), modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showAboutAppDialog = true },
                        modifier = Modifier.weight(1f).height(40.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("عن قبس ✦", color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { showChangelogDialog = true },
                        modifier = Modifier.weight(1f).height(40.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = GoldSecondary, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("سجل التحديثات", color = Color.White, fontFamily = CairoFont, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = { showPrivacyDialog = true },
                        modifier = Modifier.weight(1f).height(40.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Gavel, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("الضوابط الشرعية", color = Color.LightGray, fontFamily = CairoFont, fontSize = 11.sp)
                    }
                }
            }

            // About App Dialog
            if (showAboutAppDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutAppDialog = false },
                    containerColor = CardSurface,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(26.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("منظومة قبس (Qabas Studio)", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "﴿ إِذْ رَأَىٰ نَارًا فَقَالَ لِأَهْلِهِ امْكُثُوا إِنِّي آنَسْتُ نَارًا لَّعَلِّي آتِيكُم مِّنْهَا بِقَبَسٍ ﴾",
                                color = Color(0xFFF8FAFC),
                                fontFamily = AmiriFont,
                                fontSize = 15.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "قبس هو استوديو متكامل في جيبك لصناعة المحتوى الدعوي والإسلامي الهادف بالذكاء الاصطناعي، يجمع بين توليد النصوص، الإخراج، التعليق الصوتي، والمونتاج المتقدم بتقنيات FFmpeg و StyleBrain.",
                                color = TextSecondary,
                                fontFamily = CairoFont,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                            Surface(
                                color = Color(0xFF0B0F19),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("⚡ المعمارية: Kotlin + Jetpack Compose M3", color = GoldSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                                    Text("🎬 محرك المونتاج: FFmpeg Full + Hardware Acceleration", color = GoldSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                                    Text("🧠 العقل الإخراجي: Qabas StyleBrain Vision Engine", color = GoldSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                                    Text("🛡️ الرقابة: Islamic Content Guard & Hadith Validator", color = GoldSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showAboutAppDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) {
                            Text("إغلاق", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // Changelog Dialog
            if (showChangelogDialog) {
                AlertDialog(
                    onDismissRequest = { showChangelogDialog = false },
                    containerColor = CardSurface,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("سجل تحديثات قبس v${BuildConfig.VERSION_NAME}", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "إصدار v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}):",
                                color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                            val savedNotes = prefs.getString("last_release_notes", "").orEmpty()
                            if (savedNotes.isNotBlank()) {
                                Text(savedNotes.take(800), color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                            } else {
                                Text("افحص التحديثات من بطاقة التحديثات بالأعلى لعرض أحدث الملاحظات.", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showChangelogDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) {
                            Text("رائع ✦", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // Privacy & Islamic Guidelines Dialog
            if (showPrivacyDialog) {
                AlertDialog(
                    onDismissRequest = { showPrivacyDialog = false },
                    containerColor = CardSurface,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("الضوابط الشرعية وسياسة الخصوصية", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ميثاق النزاهة والمحتوى الهادف:", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("1. يلتزم تطبيق قبس بضمان خلو المحتوى من أي مخالفات شرعية عبر فلتر المحتوى الذكي (ContentGuard).", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                            Text("2. جميع التلاوات والأناشيد والمؤثرات الصوتية خالية من الآلات الموسيقية المحرمة.", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                            Text("3. مفاتيح الـ API والبيانات تحفظ محلياً ومشفرة ولا يتم مشاركتها خارج جهازك.", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showPrivacyDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                        ) {
                            Text("أوافق وملتزم", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // ── حوار تفعيل وضع المطور (叩 7 مرات على الإصدار) ──
            if (showPassphraseDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showPassphraseDialog = false
                        passphraseInput = ""
                        passphraseError = false
                    },
                    containerColor = Color(0xFF0D1117),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DeveloperMode, contentDescription = null, tint = AiViolet, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تفعيل وضع المطور", color = AiViolet, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("أدخل كلمة المرور السرية لتفعيل وضع المطور:", color = Color.White, fontFamily = CairoFont, fontSize = 13.sp)
                            OutlinedTextField(
                                value = passphraseInput,
                                onValueChange = {
                                    passphraseInput = it
                                    passphraseError = false
                                },
                                placeholder = { Text("كلمة المرور", color = Color.Gray, fontFamily = CairoFont) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AiViolet,
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (passphraseError) {
                                Text("كلمة المرور غير صحيحة", color = Color(0xFFF97316), fontFamily = CairoFont, fontSize = 11.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                // بوابة ردع محلية فقط: أي سر داخل APK قابل للاستخراج بالتفكيك.
                                // SHA-256 مملّح + حد محاولات — لا يغني عن تحقق خادم حقيقي.
                                val devPrefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
                                val lockedUntil = devPrefs.getLong("dev_unlock_locked_until", 0)
                                if (System.currentTimeMillis() < lockedUntil) {
                                    Toast.makeText(context, "محاولات كثيرة — انتظر قليلاً ثم أعد المحاولة", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val salt = "Qbs" + "::DevGate::" + "v1"
                                val digest = java.security.MessageDigest.getInstance("SHA-256")
                                    .digest((passphraseInput + salt).toByteArray())
                                    .joinToString("") { "%02x".format(it) }
                                // البصمة مجزأة عمداً حتى لا تظهر كسلسلة واحدة في ثنائية التطبيق
                                val expected = "284308ac" + "cd46b74003578263" + "92758c1e0d189e543" + "43d3777156a550cbb123bfa"
                                if (digest == expected) {
                                    // فعّل وضع المطور
                                    devPrefs.edit()
                                        .putBoolean("is_developer", true)
                                        .remove("dev_unlock_attempts")
                                        .remove("dev_unlock_locked_until")
                                        .apply()
                                    isDevMode = true
                                    showPassphraseDialog = false
                                    passphraseInput = ""
                                    Toast.makeText(context, "تم تفعيل وضع المطور! 🛠️", Toast.LENGTH_LONG).show()
                                } else {
                                    val attempts = devPrefs.getInt("dev_unlock_attempts", 0) + 1
                                    val edit = devPrefs.edit().putInt("dev_unlock_attempts", attempts)
                                    if (attempts >= 5) {
                                        edit.putLong("dev_unlock_locked_until", System.currentTimeMillis() + 5 * 60 * 1000L)
                                        edit.putInt("dev_unlock_attempts", 0)
                                        Toast.makeText(context, "تجاوزت المحاولات — قُفل لـ 5 دقائق", Toast.LENGTH_LONG).show()
                                    } else {
                                        passphraseError = true
                                    }
                                    edit.apply()
                                    passphraseInput = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AiViolet),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("تفعيل", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showPassphraseDialog = false
                            passphraseInput = ""
                            passphraseError = false
                        }) {
                            Text("إلغاء", color = Color.Gray, fontFamily = CairoFont)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = {
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val freed = runCatching {
                            var total = 0L
                            context.cacheDir.walkTopDown().forEach { f ->
                                if (f.isFile) {
                                    total += f.length()
                                    f.delete()
                                }
                            }
                            total
                        }.getOrDefault(0L)
                        val msg = "مُسحت الملفات المؤقتة (${UpdateManager.formatSize(freed)})"
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("مسح الملفات المؤقتة 🧹", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
            }

            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isGuest) GoldPrimary else Color(0xFFE53935)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(if (isGuest) Icons.Default.Login else Icons.Default.Logout, contentDescription = null, tint = if (isGuest) GoldPrimary else Color(0xFFE53935))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isGuest) Translator.tr("تسجيل الدخول / إنشاء حساب") else Translator.tr("تسجيل الخروج من الحساب"), 
                    color = if (isGuest) GoldPrimary else Color(0xFFE53935), 
                    fontFamily = CairoFont, 
                    fontWeight = FontWeight.Bold, 
                    fontSize = 15.sp
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SocialAccountItem(platform: String, status: String, isLinked: Boolean, icon: ImageVector, iconColor: Color, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141C27))
            .border(1.dp, if (isLinked) GoldPrimary.copy(alpha = 0.4f) else Color(0xFF222222), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isLinked) iconColor.copy(alpha = 0.15f) else Color(0xFF222222)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (isLinked) iconColor else Color.Gray, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(platform, color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(status, color = if (isLinked) GoldPrimary else Color.Gray, fontFamily = NotoSansFont, fontSize = 12.sp)
            }
        }
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text(
                if (isLinked) Translator.tr("إلغاء الربط") else Translator.tr("ربط الحساب"), 
                color = if (isLinked) Color(0xFFE53935) else GoldPrimary, 
                fontFamily = CairoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun OfficialAccountItem(
    channel: OfficialChannelInfo,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    showDevActions: Boolean = false,
    onEdit: () -> Unit = {},
    onDelete: (() -> Unit)? = null
) {
    val (icon, brandColor) = when (channel.id) {
        "youtube" -> Icons.Default.OndemandVideo to Color(0xFFFF0000)
        "facebook" -> Icons.Default.ThumbUp to Color(0xFF1877F2)
        "instagram" -> Icons.Default.CameraAlt to Color(0xFFE1306C)
        "threads" -> Icons.Default.AlternateEmail to Color(0xFFE2E8F0)
        "tiktok" -> Icons.Default.MusicVideo to Color(0xFF00F2FE)
        else -> Icons.Default.Public to GoldPrimary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141C27)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(brandColor.copy(alpha = 0.15f))
                            .border(1.dp, brandColor.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = brandColor, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                channel.displayName, 
                                color = Color.White, 
                                fontFamily = CairoFont, 
                                fontWeight = FontWeight.Bold, 
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = GoldPrimary.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, GoldPrimary.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    channel.badge, 
                                    color = GoldPrimary, 
                                    fontFamily = CairoFont, 
                                    fontSize = 9.sp, 
                                    fontWeight = FontWeight.Bold, 
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            channel.handle, 
                            color = GoldPrimary, 
                            fontFamily = NotoSansFont, 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (showDevActions) {
                        Row {
                            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = GoldPrimary, modifier = Modifier.size(16.dp))
                            }
                            if (onDelete != null) {
                                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "حذف", tint = Color(0xFFE53935), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                channel.description, 
                color = Color.LightGray, 
                fontFamily = CairoFont, 
                fontSize = 11.sp, 
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1.4f).height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("زيارة القناة / الصفحة", color = DeepSlate, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f).height(38.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("نسخ الرابط", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun CollapsibleSettingsCard(
    title: String,
    icon: ImageVector,
    sectionKey: String,
    badge: String? = null,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("qabas_settings_container_prefs", Context.MODE_PRIVATE) }
    
    val initialExpanded = remember(sectionKey) {
        prefs.getBoolean("settings_expanded_$sectionKey", defaultExpanded)
    }
    var isExpanded by remember(sectionKey) { mutableStateOf(initialExpanded) }

    fun toggleExpanded() {
        val newState = !isExpanded
        isExpanded = newState
        prefs.edit().putBoolean("settings_expanded_$sectionKey", newState).apply()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .luxuryCardStyle(shapeRadius = 18.dp, borderAlpha = 0.25f, glowElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bouncingClickable { toggleExpanded() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(GoldPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(title, color = GoldPrimary, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = GoldPrimary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary)
                        ) {
                            Text(
                                badge, 
                                color = GoldPrimary, 
                                fontFamily = CairoFont, 
                                fontSize = 10.sp, 
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                
                IconButton(
                    onClick = { toggleExpanded() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "طي" else "توسيع",
                        tint = GoldPrimary
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(14.dp))
                    content()
                }
            }
        }
    }
}
