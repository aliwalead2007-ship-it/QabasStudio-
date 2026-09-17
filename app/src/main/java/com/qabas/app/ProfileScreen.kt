package com.qabas.app

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.qabas.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onNavigate: (AppState) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
    
    // User Profile State with real persistence
    var displayName by remember { 
        mutableStateOf(prefs.getString("user_display_name", "")?.takeIf { it.isNotBlank() } ?: "صانع المحتوى الدعوي")
    }
    var userBio by remember {
        mutableStateOf(prefs.getString("user_bio", "") ?: "نشر الخير والقرآن الكريم عبر الذكاء الاصطناعي ✦")
    }
    var userCustomTag by remember {
        mutableStateOf(prefs.getString("user_tag", "") ?: "@qabas_creator")
    }
    var avatarUriString by remember {
        mutableStateOf(prefs.getString("user_avatar_uri", "") ?: "")
    }
    var selectedPresetAvatar by remember {
        mutableIntStateOf(prefs.getInt("user_avatar_preset", 0))
    }
    
val userEmail = prefs.getString("user_email", "user@example.com") ?: "user@example.com"

    val projectService = remember { ProjectService(context) }
    var projectsCount by remember { mutableIntStateOf(0) }

    // Social Accounts & Analytics State
    var socialAccounts by remember { mutableStateOf(SocialAccountManager.getAccounts(context)) }

    // Dialog States
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var selectedEditAccount by remember { mutableStateOf<SocialPlatformAccount?>(null) }
    var editHandleText by remember { mutableStateOf("") }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var showAvatarPickerSheet by remember { mutableStateOf(false) }

    // Image Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            avatarUriString = uri.toString()
            selectedPresetAvatar = -1
            prefs.edit().putString("user_avatar_uri", uri.toString()).putInt("user_avatar_preset", -1).apply()
            Toast.makeText(context, "تم تحديث الصورة الشخصية بنجاح 📸", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        projectsCount = projectService.getAllProjects().size
        socialAccounts = SocialAccountManager.getAccounts(context)
    }

    val totalPublished = socialAccounts.filter { it.isConnected }.sumOf { it.publishedCount }

    val badges = remember(projectsCount, totalPublished) {
        val list = mutableListOf<String>()
        if (projectsCount >= 1) list.add(Translator.tr("البداية القوية 🚀"))
        if (projectsCount >= 3) list.add(Translator.tr("صانع محتوى مبدع ✨"))
        if (totalPublished >= 5) list.add(Translator.tr("ناشر نشط 📤"))
        if (totalPublished >= 20) list.add(Translator.tr("مؤثر قدير 🌟"))
        if (list.isEmpty()) list.add(Translator.tr("صانع مبتدئ 🌿"))
        list
    }

    val goldGradient = Brush.horizontalGradient(colors = listOf(GoldSecondary, GoldPrimary))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Translator.tr("الملف الشخصي والمنصات"), fontFamily = TajawalFont, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GoldPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showEditProfileDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "تعديل الملف", tint = GoldPrimary)
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Profile Card Header (Full Social Media Profile Experience)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .luxuryCardStyle(shapeRadius = 20.dp, borderAlpha = 0.35f, glowElevation = 6.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Avatar with Edit Button Overlay
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .bouncingClickable { showAvatarPickerSheet = true },
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Surface(
                            modifier = Modifier.size(92.dp),
                            shape = CircleShape,
                            color = Color(0xFF151B2B),
                            border = androidx.compose.foundation.BorderStroke(2.5.dp, goldGradient),
                            shadowElevation = 6.dp
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
                                    modifier = Modifier.fillMaxSize().background(goldGradient),
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
                                        tint = DeepSlate,
                                        modifier = Modifier.size(50.dp)
                                    )
                                }
                            }
                        }

                        // Camera Badge to indicate editable photo
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(GoldPrimary)
                                .border(1.5.dp, DeepSlate, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = "تغيير الصورة", tint = DeepSlate, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Name & Custom Tag
                    Text(
                        text = displayName,
                        color = Color.White,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = userCustomTag,
                            color = GoldPrimary,
                            fontFamily = RobotoMonoFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = GoldPrimary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, GoldPrimary.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "صانع موثق ✓",
                                color = GoldPrimary,
                                fontFamily = CairoFont,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // User Email display
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Email, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = userEmail,
                            color = TextSecondary,
                            fontFamily = RobotoMonoFont,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bio / Description
                    Text(
                        text = userBio,
                        color = Color(0xFFCBD5E1),
                        fontFamily = CairoFont,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Quick Action Edit Profile Button
                    OutlinedButton(
                        onClick = { showEditProfileDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .bouncingClickable { showEditProfileDialog = true }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تعديل الاسم والنبذة والصورة", color = GoldPrimary, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Aggregated Stats 4-Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = Translator.tr("المشاريع"),
                    value = projectsCount.toString(),
                    icon = Icons.Default.Movie,
                    color = GoldPrimary
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = Translator.tr("المشاركات"),
                    value = totalPublished.toString(),
                    icon = Icons.Default.Publish,
                    color = Color(0xFF3B82F6)
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = Translator.tr("المنصات مربوطة"),
                    value = socialAccounts.count { it.isConnected }.toString(),
                    icon = Icons.Default.Link,
                    color = Color(0xFFE1306C)
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = Translator.tr("معدل الإنجاز"),
                    value = if (projectsCount > 0) "${(totalPublished * 100 / projectsCount).coerceAtMost(100)}%" else "0%",
                    icon = Icons.Default.TrendingUp,
                    color = Color(0xFF10B981)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Connected Social Media Accounts Dashboard
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .luxuryCardStyle(shapeRadius = 16.dp, borderAlpha = 0.25f, glowElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                Translator.tr("حسابات التواصل للنشر الفوري 🌐"),
                                color = Color.White,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Button(
                            onClick = { showScheduleDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF151B2B)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .bouncingClickable { showScheduleDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Alarm, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("المواقيت ⏰", color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        socialAccounts.forEach { account ->
                            SocialPlatformItemCard(
                                account = account,
                                onToggleConnect = {
                                    val newConnected = !account.isConnected
                                    SocialAccountManager.toggleConnection(context, account.id, newConnected)
                                    socialAccounts = SocialAccountManager.getAccounts(context)
                                    Toast.makeText(context, if (newConnected) "تم ربط ${account.name} 🟢" else "تم إلغاء ربط ${account.name}", Toast.LENGTH_SHORT).show()
                                },
                                onEditHandle = {
                                    selectedEditAccount = account
                                    editHandleText = account.handle
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Badges Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .luxuryCardStyle(shapeRadius = 14.dp, borderAlpha = 0.2f, glowElevation = 3.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(Translator.tr("الشارات والرتب الدعوية 🏆"), color = GoldPrimary, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        badges.forEach { badge ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(GoldPrimary.copy(alpha = 0.15f))
                                    .border(0.8.dp, GoldPrimary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(badge, color = GoldPrimary, fontFamily = NotoSansFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Account Plan & Quota Card
            val accountService = remember { AppServices.getAccountService(context) }
            val planTitle = when {
                accountService.isDeveloperOrAdmin -> "باقة المطور 🛠️"
                accountService.hasCustomKeys -> "المفاتيح الخاصة (BYOK) 🔑"
                accountService.isPremium -> "قبس برو 👑"
                else -> "باقة مجانية ⚡"
            }
            val isUnlimited = accountService.isDeveloperOrAdmin || accountService.hasCustomKeys || accountService.isPremium
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .luxuryCardStyle(shapeRadius = 16.dp, borderAlpha = 0.25f, glowElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                Translator.tr("باقة الحساب والرصيد"),
                                color = Color.White,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        
                        Surface(
                            color = if (isUnlimited) GoldPrimary.copy(alpha = 0.2f) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isUnlimited) GoldPrimary else Color(0xFF64748B)
                            )
                        ) {
                            Text(
                                text = planTitle,
                                color = if (isUnlimited) GoldPrimary else TextSecondary,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    
                    // Quota and points details row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B0F19), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("رصيد النقاط 💰", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("${accountService.walletBalance}", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0xFF1E293B)))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("فيديوهات اليوم 🎬", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (isUnlimited) "غير محدود 👑" else "${accountService.dailyVideoCount} / ${AccountService.MAX_DAILY_VIDEOS}",
                                color = if (isUnlimited) GoldPrimary else Color.White,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0xFF1E293B)))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("صور اليوم 🖼️", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (isUnlimited) "غير محدود 👑" else "${accountService.dailyImageCount} / ${AccountService.MAX_DAILY_IMAGES}",
                                color = if (isUnlimited) GoldPrimary else Color.White,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Quick Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onNavigate(AppState.PREMIUM_UPGRADE) },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("المتجر والترقية 👑", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // If 0 projects, show an actionable empty state banner
            if (projectsCount == 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .luxuryCardStyle(shapeRadius = 14.dp, borderAlpha = 0.2f, glowElevation = 3.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.MovieFilter, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "لم تنشئ أي مشروع دعوي بعد 🌿",
                            color = Color.White,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "حوّل أي فكرة أو آية قرآنية أو حديث شريف إلى ريلز احترافي في ثوانٍ",
                            color = TextSecondary,
                            fontFamily = NotoSansFont,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onNavigate(AppState.INPUT) },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("بدء مشروع جديد 🚀", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Comprehensive Profile Edit Dialog (Name, Bio, Tag)
    if (showEditProfileDialog) {
        var tempName by remember { mutableStateOf(displayName) }
        var tempTag by remember { mutableStateOf(userCustomTag) }
        var tempBio by remember { mutableStateOf(userBio) }

        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = {
                Text("تعديل بيانات الملف الشخصي 👤", color = GoldPrimary, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Display Name
                    Text("اسم الحساب / القناة الدعوية:", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        placeholder = { Text("مثال: قبس للإنتاج الدعوي", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Tag
                    Text("المعرف الرقمي (Tag):", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                    OutlinedTextField(
                        value = tempTag,
                        onValueChange = { tempTag = it },
                        placeholder = { Text("@qabas_creator", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Bio
                    Text("النبذة التعريفية (Bio):", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                    OutlinedTextField(
                        value = tempBio,
                        onValueChange = { tempBio = it },
                        placeholder = { Text("اكتب نبذة عن رسالتك وهدفك الدعوي...", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        displayName = tempName.ifBlank { "صانع المحتوى الدعوي" }
                        userCustomTag = if (tempTag.startsWith("@")) tempTag else "@$tempTag"
                        userBio = tempBio
                        prefs.edit()
                            .putString("user_display_name", displayName)
                            .putString("user_tag", userCustomTag)
                            .putString("user_bio", userBio)
                            .apply()
                        showEditProfileDialog = false
                        Toast.makeText(context, "تم حفظ الملف الشخصي بنجاح! 💾", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("حفظ التغييرات", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) {
                    Text("إلغاء", color = Color.Gray, fontFamily = CairoFont)
                }
            },
            containerColor = DeepSlate
        )
    }

    // Avatar Selection Dialog (Upload image from device or choose preset icons)
    if (showAvatarPickerSheet) {
        AlertDialog(
            onDismissRequest = { showAvatarPickerSheet = false },
            title = {
                Text("تغيير الصورة الشخصية 📸", color = GoldPrimary, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Upload from gallery button
                    Button(
                        onClick = {
                            showAvatarPickerSheet = false
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("رفع صورة من المعرض (Gallery)", color = DeepSlate, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    HorizontalDivider(color = Color(0xFF1E293B))

                    Text("أو اختر رمزاً دعوياً معتمداً:", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)

                    // Preset Avatars Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val presets = listOf(
                            0 to Icons.Default.Person,
                            1 to Icons.Default.AutoAwesome,
                            2 to Icons.Default.MovieFilter,
                            3 to Icons.Default.MenuBook,
                            4 to Icons.Default.Psychology
                        )

                        presets.forEach { (index, icon) ->
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(if (selectedPresetAvatar == index && avatarUriString.isBlank()) GoldPrimary else Color(0xFF151B2B))
                                    .border(1.dp, GoldPrimary, CircleShape)
                                    .clickable {
                                        selectedPresetAvatar = index
                                        avatarUriString = ""
                                        prefs.edit().putString("user_avatar_uri", "").putInt("user_avatar_preset", index).apply()
                                        showAvatarPickerSheet = false
                                        Toast.makeText(context, "تم تعيين الرمز الشخصي ✦", Toast.LENGTH_SHORT).show()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = if (selectedPresetAvatar == index && avatarUriString.isBlank()) DeepSlate else GoldPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAvatarPickerSheet = false }) {
                    Text("إغلاق", color = Color.Gray, fontFamily = CairoFont)
                }
            },
            containerColor = DeepSlate
        )
    }

    // Handle Edit Modal Dialog
    selectedEditAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { selectedEditAccount = null },
            title = {
                Text("تعديل معرف ${account.name} ✏️", color = GoldPrimary, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أدخل اسم المستخدم على منصة ${account.name}:", color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                    OutlinedTextField(
                        value = editHandleText,
                        onValueChange = { editHandleText = it },
                        placeholder = { Text("@user", color = Color.Gray, fontFamily = RobotoMonoFont) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        SocialAccountManager.toggleConnection(context, account.id, isConnected = true, handle = editHandleText)
                        socialAccounts = SocialAccountManager.getAccounts(context)
                        selectedEditAccount = null
                        Toast.makeText(context, "تم حفظ الحساب بنجاح! 💾", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("حفظ", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEditAccount = null }) {
                    Text("إلغاء", color = Color.Gray, fontFamily = CairoFont)
                }
            },
            containerColor = DeepSlate
        )
    }

    if (showScheduleDialog) {
        SmartPublishScheduleDialog(
            initialTitle = "جدولة نشر مقطع دعوي جديد",
            onDismiss = { showScheduleDialog = false }
        )
    }
}

@Composable
fun SocialPlatformItemCard(
    account: SocialPlatformAccount,
    onToggleConnect: () -> Unit,
    onEditHandle: () -> Unit
) {
    val platformColor = when (account.id) {
        "youtube" -> Color(0xFFF44336)
        "tiktok" -> Color(0xFF00F2FE)
        "instagram" -> Color(0xFFE1306C)
        "twitter" -> Color(0xFF1DA1F2)
        else -> Color(0xFF1877F2)
    }

    val platformIcon: ImageVector = when (account.id) {
        "youtube" -> Icons.Default.OndemandVideo
        "tiktok" -> Icons.Default.MusicVideo
        "instagram" -> Icons.Default.CameraAlt
        "twitter" -> Icons.Default.Tag
        else -> Icons.Default.Share
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0F19), RoundedCornerShape(10.dp))
            .border(1.dp, if (account.isConnected) platformColor.copy(alpha = 0.4f) else Color(0xFF151B2B), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(platformColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(platformIcon, contentDescription = null, tint = platformColor, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(account.name, color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(if (account.isConnected) account.handle else "غير مربوط", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (account.isConnected) {
                    IconButton(onClick = onEditHandle, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = GoldPrimary, modifier = Modifier.size(14.dp))
                    }
                }

                Button(
                    onClick = onToggleConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = if (account.isConnected) platformColor.copy(alpha = 0.2f) else Color(0xFF151B2B)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(
                        text = if (account.isConnected) "مربوط 🟢" else "ربط ⚪",
                        color = if (account.isConnected) platformColor else Color.Gray,
                        fontFamily = CairoFont,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

fun formatStatNumber(num: Int): String {
    return when {
        num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
        num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
        else -> num.toString()
    }
}

@Composable
fun StatCard(modifier: Modifier = Modifier, title: String, value: String, icon: ImageVector, color: Color) {
    Card(
        modifier = modifier
            .luxuryCardStyle(shapeRadius = 12.dp, borderAlpha = 0.2f, glowElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(title, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
        }
    }
}
