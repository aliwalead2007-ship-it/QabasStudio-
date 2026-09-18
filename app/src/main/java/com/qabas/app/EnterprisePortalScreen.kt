package com.qabas.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*

data class EnterpriseSponsorshipTier(
    val title: String,
    val capacity: String,
    val monthlyCostUsd: Int,
    val impactDescription: String,
    val badgeTitle: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterprisePortalScreen(
    onBack: () -> Unit,
    onNavigate: (AppState) -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(0) } // 0: White-Label Builder, 1: Sponsorships, 2: Official Invoice Generator, 3: Enterprise Retainers

    // 1. White Label Custom App Builder State
    var orgName by remember { mutableStateOf("") }
    var orgEmail by remember { mutableStateOf("") }
    var customAppName by remember { mutableStateOf("") }
    var selectedColorTheme by remember { mutableStateOf("ذهبي ورمادي داكن (قبس الأصلي)") }
    var isPrivateServerRequired by remember { mutableStateOf(false) }
    var customRequirements by remember { mutableStateOf("") }

    // 2. Sponsorship State
    val sponsorshipTiers = listOf(
        EnterpriseSponsorshipTier("رعاية المبادرة الفضية 🥈", "تغطية 50 صانع محتوى", 300, "إنتاج ~7,500 فيديو وبطاقة حديث شهرياً", "برعاية فضية 🌟"),
        EnterpriseSponsorshipTier("رعاية المؤسسة الذهبية 🏆", "تغطية 200 صانع محتوى", 1000, "إنتاج ~30,000 فيديو وبطاقة حديث شهرياً", "برعاية ذهبية 👑"),
        EnterpriseSponsorshipTier("رعاية المأثرة الماسية 💎", "تغطية 1,000 صانع محتوى", 4500, "إنتاج ~150,000 فيديو وبطاقة حديث شهرياً", "برعاية ماسية 💎")
    )
    var selectedSponsorshipTier by remember { mutableStateOf(sponsorshipTiers[1]) }

    // 3. Invoice / Quote State
    var invoiceClientName by remember { mutableStateOf("") }
    var invoiceServiceType by remember { mutableStateOf("تطبيق مخصص White-Label + سيرفر خاص") }
    var invoiceAmountUsd by remember { mutableStateOf("1500") }
    var generatedQuoteText by remember { mutableStateOf("") }

    // 4. Saved Retainers
    var activeRetainers by remember {
        mutableStateOf(
            listOf(
                ClientRetainer(clientName = "جمعية آيات الدعوية", serviceType = "تطبيق مخصص White-Label", monthlyAmount = 1500.0, paymentStatus = "مدفوع 🟢", deliveryProgress = 1.0f),
                ClientRetainer(clientName = "مركز هداية العالمي", serviceType = "رعاية 200 صانع محتوى", monthlyAmount = 1000.0, paymentStatus = "مدفوع 🟢", deliveryProgress = 0.85f),
                ClientRetainer(clientName = "منصة تدبر القرآن", serviceType = "تطبيق مخصص + سيرفر Gemini خاص", monthlyAmount = 2200.0, paymentStatus = "معلق 🟡", deliveryProgress = 0.50f)
            )
        )
    }

    Scaffold(
        containerColor = DeepSlate,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "بوابة المؤسسات والرعايات B2B 🏢",
                        color = GoldPrimary,
                        fontFamily = CairoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = GoldPrimary
                        )
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

            // Header Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.5.dp, GoldPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "لوحة حلول الجمعيات والدعم المؤسسي 🤝",
                                color = GoldPrimary,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "تمكين المؤسسات والجمعيات الخيرية من بناء تطبيقات دعوية مخصصة باسمهم أو رعاية آلاف الحسابات المبتدئة.",
                                color = TextSecondary,
                                fontFamily = NotoSansFont,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                        Icon(Icons.Default.Business, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(36.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tab Selector
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = activeTab == 0,
                            onClick = { activeTab = 0 },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4)
                        ) {
                            Text("تطبيق مخصص", fontSize = 12.sp, fontFamily = NotoSansFont)
                        }
                        SegmentedButton(
                            selected = activeTab == 1,
                            onClick = { activeTab = 1 },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4)
                        ) {
                            Text("رعاية الدعاة", fontSize = 12.sp, fontFamily = NotoSansFont)
                        }
                        SegmentedButton(
                            selected = activeTab == 2,
                            onClick = { activeTab = 2 },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4)
                        ) {
                            Text("عرض سعر", fontSize = 12.sp, fontFamily = NotoSansFont)
                        }
                        SegmentedButton(
                            selected = activeTab == 3,
                            onClick = { activeTab = 3 },
                            shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4)
                        ) {
                            Text("السجل", fontSize = 12.sp, fontFamily = NotoSansFont)
                        }
                    }
                }
            }

            // TAB 0: White-Label Custom App Request Builder
            if (activeTab == 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF182232)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "📲 طلب بناء تطبيق مخصص باسم الجمعية/المؤسسة (White-Label):",
                            color = GoldPrimary,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        Text(
                            "نقوم ببرمجة نسخة أندرويد مستقلة بالكامل تحمل اسم وشعار واللون الخاص بمؤسستكم مع ربط محتواكم الخاص ومكتبتكم الصوتية.",
                            color = TextSecondary,
                            fontFamily = NotoSansFont,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        OutlinedTextField(
                            value = orgName,
                            onValueChange = { orgName = it },
                            label = { Text("اسم الجمعية / المؤسسة", fontFamily = CairoFont) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary)
                        )

                        OutlinedTextField(
                            value = customAppName,
                            onValueChange = { customAppName = it },
                            label = { Text("الاسم المقترح للتطبيق (مثلاً: تطبيق هداية الدعوي)", fontFamily = CairoFont) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary)
                        )

                        OutlinedTextField(
                            value = orgEmail,
                            onValueChange = { orgEmail = it },
                            label = { Text("البريد الإلكتروني الرسمي للتواصل", fontFamily = CairoFont) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary)
                        )

                        OutlinedTextField(
                            value = customRequirements,
                            onValueChange = { customRequirements = it },
                            label = { Text("متطلبات إضافية (أقسام مخصصة، مكتبة تلاوات، إلخ)", fontFamily = CairoFont) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = isPrivateServerRequired,
                                onCheckedChange = { isPrivateServerRequired = it },
                                colors = CheckboxDefaults.colors(checkedColor = GoldPrimary)
                            )
                            Text("ربط سيرفر ذكاء اصطناعي وخادم خاص بالجمعية 🔒", color = TextPrimary, fontFamily = NotoSansFont, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                if (orgName.isBlank() || orgEmail.isBlank()) {
                                    Toast.makeText(context, "يرجى تعبئة اسم المؤسسة والبريد الإلكتروني", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Submit Enterprise Request to AppRequestService
                                    val reqTitle = "طلب تطبيق مخصص (White-Label): $orgName ($customAppName)"
                                    val reqDesc = "جهة الطلب: $orgName\nالبريد: $orgEmail\nاسم التطبيق: $customAppName\nسيرفر خاص: $isPrivateServerRequired\nملاحظات: $customRequirements"
                                    val appReq = AppRequestService.AppRequest(
                                        userEmail = orgEmail,
                                        title = reqTitle,
                                        description = reqDesc,
                                        goal = "بناء تطبيق دعوي مخصص للمؤسسات B2B White-Label"
                                    )
                                    AppRequestService.submitRequest(context, appReq)
                                    
                                    Toast.makeText(context, "تم رفع طلب التطبيق المخصص بنجاح إلى فريق الهندسة! 🚀", Toast.LENGTH_LONG).show()
                                    orgName = ""
                                    orgEmail = ""
                                    customAppName = ""
                                    customRequirements = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = DeepSlate)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تقديم طلب التشييد الهندسي للمؤسسة 🚀", color = DeepSlate, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }

            // TAB 1: Sponsorship Tiers
            if (activeTab == 1) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF182232)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "🎁 باقات رعاية الحسابات والمبادرات الدعوية:",
                            color = GoldPrimary,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        Text(
                            "بإمكان الجمعيات كفالة تكاليف خوادم الذكاء الاصطناعي لفتح الحدود المجانية أمام مئات الدعاة مع إبراز شعار الجمعية كـ (راعي رسمي).",
                            color = TextSecondary,
                            fontFamily = NotoSansFont,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        sponsorshipTiers.forEach { tier ->
                            val isSelected = selectedSponsorshipTier == tier
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSponsorshipTier = tier }
                                    .border(
                                        1.5.dp,
                                        if (isSelected) GoldPrimary else Color(0xFF1E293B),
                                        RoundedCornerShape(14.dp)
                                    ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFF2C1E12) else Color(0xFF151B2B)
                                ),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(tier.title, color = GoldPrimary, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text("$${tier.monthlyCostUsd} / شهرياً", color = Color(0xFF38BDF8), fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(tier.capacity, color = TextPrimary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                    Text(tier.impactDescription, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                }
                            }
                        }

                        Button(
                            onClick = {
                                val quote = "عرض رعاية مؤسسية رسمية - ${selectedSponsorshipTier.title}\nالتكلفة: $${selectedSponsorshipTier.monthlyCostUsd}/شهرياً\nالأثر: ${selectedSponsorshipTier.impactDescription}\nالشارة: ${selectedSponsorshipTier.badgeTitle}\nمنصة قبس للدعوة الرقمية QABAS B2B"
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("Sponsorship Quote", quote)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "تم نسخ تفاصيل باقة الرعاية المختارة إلى الحافظة! 📋", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = DeepSlate)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("نسخ عقد الرعاية الرسمي للمؤسسة 📋", color = DeepSlate, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }

            // TAB 2: Official B2B Quote & Invoice Generator
            if (activeTab == 2) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF182232)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "🧾 مولد عروض الأسعار والفواتير الرسمية للمؤسسات:",
                            color = GoldPrimary,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        OutlinedTextField(
                            value = invoiceClientName,
                            onValueChange = { invoiceClientName = it },
                            label = { Text("اسم الجهة / الجمعية المستفيدة", fontFamily = CairoFont) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary)
                        )

                        OutlinedTextField(
                            value = invoiceServiceType,
                            onValueChange = { invoiceServiceType = it },
                            label = { Text("نوع الخدمة / البند", fontFamily = CairoFont) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary)
                        )

                        OutlinedTextField(
                            value = invoiceAmountUsd,
                            onValueChange = { invoiceAmountUsd = it },
                            label = { Text("المبلغ الإجمالي (USD)", fontFamily = CairoFont) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary)
                        )

                        Button(
                            onClick = {
                                if (invoiceClientName.isBlank()) {
                                    Toast.makeText(context, "أدخل اسم الجهة أولاً", Toast.LENGTH_SHORT).show()
                                } else {
                                    val refNo = "QBS-INV-${(10000..99999).random()}"
                                    generatedQuoteText = """
                                        ========================================
                                        📜 عرض سعر رسمي - منصة قبس B2B ENTERPRISE
                                        رقم المرجع: $refNo
                                        التاريخ: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}
                                        ========================================
                                        المُستفيد: $invoiceClientName
                                        بند الخدمة: $invoiceServiceType
                                        المبلغ الإجمالي: $${invoiceAmountUsd} دولار أمريكي
                                        
                                        الميزات المضمنة:
                                        • تطبيق أندرويد مخصص White-Label بالهوية الذهبية
                                        • ربط سيرفرات خوادم Gemini AI و Pexels الخاصة
                                        • لوحة إدارة متقدمة للتحكم بالمحتوى الداخلي
                                        • دعم علمي وبرمجي متواصل 24/7
                                        ========================================
                                        معتمد رسمياً من فريق هندسة QABAS STUDIO
                                    """.trimIndent()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C1E12)),
                            border = BorderStroke(1.dp, GoldPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = GoldPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("توليد الفاتورة الرسمية الموثقة 🧾✨", color = GoldPrimary, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        if (generatedQuoteText.isNotBlank()) {
                            Surface(
                                color = Color(0xFF0B0F19),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, GoldSecondary.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(generatedQuoteText, color = TextPrimary, fontFamily = NotoSansFont, fontSize = 12.sp, lineHeight = 16.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val clip = ClipData.newPlainText("Invoice", generatedQuoteText)
                                            clipboard?.setPrimaryClip(clip)
                                            Toast.makeText(context, "تم نسخ الفاتورة الرسمية! 📋", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Text("نسخ الفاتورة 📋", color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // TAB 3: Retainers & Active Sponsorships
            if (activeTab == 3) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF182232)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "📂 سجل الجمعيات والمؤسسات الراعية الحالية:",
                            color = GoldPrimary,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        activeRetainers.forEach { client ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(client.clientName, color = TextPrimary, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(client.paymentStatus, color = GoldPrimary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("نوع الخدمة: ${client.serviceType}", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { client.deliveryProgress },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                        color = GoldPrimary,
                                        trackColor = Color(0xFF1E293B)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
