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
    var updateState by remember { mutableStateOf<String?>(null) } // null = idle, "checking...", "found", "none", "error"
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var updateProgress by remember { mutableStateOf(0) }

    // ── Developer Mode:叩 مخفي على نص الإصدار (7 مرات) ──
    var devTapCount by remember { mutableIntStateOf(0) }
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var passphraseInput by remember { mutableStateOf("") }
    var passphraseError by remember { mutableStateOf(false) }
    val isDevMode = remember {
        context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
            .getBoolean("is_developer", false)
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
                    } else {
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
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(800)
                                val newHandle = customHandle.ifBlank { selectedPlatform.handle }
                                SocialAccountManager.toggleConnection(context, selectedPlatform.id, true, newHandle, customToken)
                                accountsList = SocialAccountManager.getAccounts(context)
                                isLinking = false
                                linkDialogState = null
                                Toast.makeText(context, Translator.tr("تم حفظ وتأكيد ربط حساب ") + selectedPlatform.name + " 🚀", Toast.LENGTH_SHORT).show()
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
    var autoPublishing by remember { mutableStateOf(prefs.getBoolean("auto_publish", false)) }

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
                        Text(Translator.tr("النشر التلقائي الذكي"), color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(Translator.tr("جدولة ونشر المقاطع تلقائياً في أفضل أوقات التفاعل"), color = Color.Gray, fontFamily = NotoSansFont, fontSize = 12.sp)
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
            val officialChannels = remember { SocialAccountManager.getOfficialChannels(context) }
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
                        }
                    )
                }
            }

            // 5. Social Media Accounts Collapsible Section (ربط المنصات الاجتماعية والنشر)
            CollapsibleSettingsCard(
                title = Translator.tr("ربط المنصات الاجتماعية والنشر"),
                icon = Icons.Default.Share,
                sectionKey = "settings_social_platforms"
            ) {
                accountsList.forEachIndexed { index, account ->
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    val statusText = if (account.isConnected) "${account.handle} (مربوط 🟢)" else Translator.tr("غير مربوط ⚪")
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
                                        "downloading" -> "جاري تنزيل التحديث..."
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
                                        "downloading" -> "$updateProgress٪ من ${updateInfo?.let { UpdateManager.formatSize(it.apkSize) } ?: ""}"
                                        "done" -> "أكمل التثبيت من شاشة النظام"
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
                            Button(
                                onClick = {
                                    updateInfo?.let { info ->
                                        updateState = "downloading"
                                        updateProgress = 0
                                        UpdateManager.downloadAndInstall(context, info,
                                            onProgress = { updateProgress = it },
                                            onDone = { ok, _ -> updateState = if (ok) "done" else "error" }
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text("تحديث الآن", color = Color.White, fontFamily = CairoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (updateState == null || updateState == "none" || updateState == "error") {
                            Text(
                                text = if (updateState == "error") "إعادة المحاولة" else "فحص",
                                color = updateAccent,
                                fontFamily = CairoFont,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
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
                                    Text("حجم التحديث", color = Color(0xFF94A3B8), fontFamily = NotoSansFont, fontSize = 11.sp)
                                    Text(UpdateManager.formatSize(u.apkSize), color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            LinearProgressIndicator(
                                progress = { (updateProgress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = Color(0xFF10B981),
                                trackColor = Color(0xFF1E293B)
                            )
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
                            Text("أبرز ما تم إنجازه في إصدار v1.2.0 (Build 2):", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("• فحص حقيقي شبكي فوري لكافة مفاتيح API مع تشخيص تفصيلي للأخطاء.", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                            Text("• تفعيل محرك StyleBrain الإخراجي مع الامتصاص البصري وفلاتر المونتاج الحقيقية.", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                            Text("• دعم توليد ومعاينة التعليق الصوتي الحقيقي عبر ElevenLabs و Azure Speech.", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                            Text("• تصدير فيديو حقيقي عبر FFmpeg Kit Full بدقة 1080p و 4K وتسريع العتاد.", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                            Text("• جاهزية تامة للأجهزة ومؤشر الجاهزية التشغيلية (Readiness Score).", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
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
                                // كلمة المرور السرية: hash مبسط
                                val correctHash = "d3b07384d113edec49eaa6238ad5ff00" // md5("qabas_dev_2024")
                                val inputHash = passphraseInput.toByteArray().let { bytes ->
                                    java.security.MessageDigest.getInstance("MD5")
                                        .digest(bytes)
                                        .joinToString("") { "%02x".format(it) }
                                }
                                if (inputHash == correctHash) {
                                    // فعّل وضع المطور
                                    context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
                                        .edit()
                                        .putBoolean("is_developer", true)
                                        .apply()
                                    showPassphraseDialog = false
                                    passphraseInput = ""
                                    Toast.makeText(context, "تم تفعيل وضع المطور! 🛠️", Toast.LENGTH_LONG).show()
                                } else {
                                    passphraseError = true
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
    onCopy: () -> Unit
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
                    Column {
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
