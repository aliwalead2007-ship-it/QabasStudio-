package com.qabas.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDetailsScreen(requestId: String, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("qabas_prefs", Context.MODE_PRIVATE)
    val isAdmin = prefs.getBoolean("is_admin", false)
    val isAdvancedMode = prefs.getBoolean("advanced_dev_mode", false)
    val request = remember { mutableStateOf(AppRequestService.getRequests(context, isDeveloper = true).find { it.id == requestId }) }
    val req = request.value
    var showPaymentDialog by remember { mutableStateOf(false) }

    val goldGradient = Brush.verticalGradient(colors = listOf(GoldPrimary, GoldSecondary))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Translator.tr("تفاصيل الطلب"), fontFamily = CairoFont, fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = GoldPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenChat) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat", tint = GoldPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepSlate)
            )
        },
        containerColor = DeepSlate
    ) { padding ->
        if (req == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(Translator.tr("الطلب غير موجود"), color = Color.White, fontFamily = CairoFont)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // البطاقة الذهبية الفاخرة للطلب
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(goldGradient)
                        .padding(2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(CardSurface)
                            .padding(20.dp)
                    ) {
                        Text(req.title, color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(req.goal, color = TextPrimary, fontFamily = TajawalFont, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Translator.tr("حالة الطلب:"), color = TextSecondary, fontFamily = NotoSansFont, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when(req.status) {
                                    "pending" -> Translator.tr("قيد المراجعة")
                                    "in_progress" -> Translator.tr("قيد التنفيذ")
                                    else -> Translator.tr("مكتمل")
                                },
                                color = if (req.status == "completed") Color.Green else GoldPrimary,
                                fontFamily = CairoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }


                // بطاقة عرض السعر: قبول / رفض من العميل
                if (req.priceStatus == "offered" && !req.isPaid) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, GoldPrimary.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A1503), RoundedCornerShape(12.dp))
                            .padding(20.dp)
                    ) {
                        Column {
                            Text("📋 عرض سعر من المطور", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("تكلفة تنفيذ «${req.title}»: ${req.cost}$", color = Color.White, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("لن يبدأ العمل قبل موافقتك. يمكنك الرفض وسيقترح المطور سعراً جديداً.", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        AppRequestService.updateRequestProgressAndPayment(context, req.id, priceStatus = "accepted")
                                        context.getSharedPreferences("qabas_requests_prefs", Context.MODE_PRIVATE)
                                            .edit().putBoolean("priced_${req.id}", true).apply()
                                        AppRequestService.sendMessage(
                                            context,
                                            AppRequestService.ChatMessage(
                                                requestId = req.id, senderEmail = req.userEmail,
                                                isDeveloper = false,
                                                message = "قبلت عرض السعر ✅ (${req.cost}$) — تفضل بالبدء."
                                            )
                                        )
                                        request.value = request.value?.copy(priceStatus = "accepted")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("أقبل ✅", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        AppRequestService.updateRequestProgressAndPayment(context, req.id, priceStatus = "rejected")
                                        AppRequestService.sendMessage(
                                            context,
                                            AppRequestService.ChatMessage(
                                                requestId = req.id, senderEmail = req.userEmail,
                                                isDeveloper = false,
                                                message = "السعر غير مناسب لي ❌ — هل يمكن تخفيضه؟"
                                            )
                                        )
                                        request.value = request.value?.copy(priceStatus = "rejected")
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
                                ) {
                                    Text("أرفض", color = Color(0xFFEF4444), fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // قسم الدفع والتقدم
                if (req.status != "pending") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, GoldSecondary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .background(Color(0xFF151515), RoundedCornerShape(12.dp))
                            .padding(20.dp)
                    ) {
                        Column {
                            Text(Translator.tr("متابعة المشروع"), color = GoldPrimary, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // شريط التقدم
                            Text(Translator.tr("نسبة الإنجاز: ") + "${req.progress}%", color = TextPrimary, fontFamily = NotoSansFont, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { req.progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = GoldPrimary,
                                trackColor = Color(0xFF333333)
                            )

                            if (isAdmin) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(Translator.tr("تعديل نسبة الإنجاز (للمطور فقط):"), color = GoldSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                Slider(
                                    value = req.progress.toFloat(),
                                    onValueChange = { newValue ->
                                        AppRequestService.updateRequestProgressAndPayment(context, req.id, progress = newValue.toInt())
                                        request.value = request.value?.copy(progress = newValue.toInt())
                                    },
                                    valueRange = 0f..100f,
                                    colors = SliderDefaults.colors(thumbColor = GoldPrimary, activeTrackColor = GoldPrimary)
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(Translator.tr("تحديد التكلفة (للمطور فقط):"), color = GoldSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                OutlinedTextField(
                                    value = req.cost.toString(),
                                    onValueChange = { newValue ->
                                        val newCost = newValue.toIntOrNull() ?: 0
                                        AppRequestService.updateRequestProgressAndPayment(context, req.id, cost = newCost)
                                        request.value = request.value?.copy(cost = newCost)
                                    },
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary, unfocusedBorderColor = Color(0xFF333333), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                                )
                            }

                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // الدفع
                            if (req.cost > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Column {
                                        Text(Translator.tr("تكلفة المشروع:"), color = TextSecondary, fontFamily = NotoSansFont, fontSize = 14.sp)
                                        Text("${req.cost}$", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                                    }
                                    if (req.isPaid) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = "Paid", tint = Color.Green, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(Translator.tr("تم الدفع"), color = Color.Green, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                showPaymentDialog = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(Translator.tr("إدفع الآن"), color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // إعدادات الذكاء الاصطناعي
                val modelConfig = remember(req) { AppRequestService.determineBestModelAndSettings(req) }
                
                Text(
                    text = Translator.tr("تخصيصات Google AI Studio"),
                    color = GoldPrimary,
                    fontFamily = CairoFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                
                var currentTemp by remember(modelConfig) { mutableStateOf(modelConfig.temperature) }
                var currentModel by remember(modelConfig) { mutableStateOf(modelConfig.modelName) }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (isAdvancedMode) GoldPrimary else Color(0xFF333333), RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = GoldSecondary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(Translator.tr("النموذج: ") + currentModel, color = Color.White, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            if (isAdvancedMode) {
                                TextButton(onClick = { currentModel = if (currentModel == "Gemini 1.5 Pro") "Gemini 1.5 Flash" else "Gemini 1.5 Pro" }) {
                                    Text(Translator.tr("تغيير"), color = GoldPrimary, fontFamily = CairoFont)
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Thermostat, contentDescription = null, tint = GoldSecondary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(Translator.tr("درجة الحرارة: ") + String.format("%.1f", currentTemp), color = TextSecondary, fontFamily = NotoSansFont, fontSize = 14.sp)
                            }
                        }
                        
                        if (isAdvancedMode) {
                            Slider(
                                value = currentTemp,
                                onValueChange = { currentTemp = it },
                                valueRange = 0f..2f,
                                colors = SliderDefaults.colors(thumbColor = GoldPrimary, activeTrackColor = GoldPrimary)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(Translator.tr("تعليمات النظام (System Instructions):"), color = GoldSecondary, fontFamily = NotoSansFont, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(modelConfig.systemInstructions, color = TextPrimary, fontFamily = NotoSansFont, fontSize = 14.sp)
                        
                        if (isAdvancedMode) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF332200), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFFFFAA00), RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFFFAA00), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(Translator.tr("تنبيه التخصيص"), color = Color(0xFFFFAA00), fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(Translator.tr("برومت 'هيكلة البيانات' يتطلب إضافة مخطط قاعدة البيانات (Schema) يدوياً قبل الإرسال."), color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // خطة المبرمج (Generated Prompts)
                Text(
                    text = Translator.tr("خطة المبرمج (الذكاء الاصطناعي)"),
                    color = GoldPrimary,
                    fontFamily = CairoFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )

                if (req.generatedPrompts.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = Translator.tr("جاري تحليل طلبك وتوليد الخطة..."),
                            color = TextSecondary,
                            fontFamily = CairoFont,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, GoldSecondary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .background(Color(0xFF111111), RoundedCornerShape(12.dp))
                            .padding(20.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = Translator.tr("تم التوليد بنجاح"),
                                    color = GoldPrimary,
                                    fontFamily = CairoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = req.generatedPrompts,
                                color = TextPrimary,
                                fontFamily = NotoSansFont,
                                fontSize = 15.sp,
                                lineHeight = 24.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPaymentDialog && req != null) {
        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            containerColor = CardSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Payment, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(Translator.tr("الدفع الآمن"), color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(Translator.tr("سيتم خصم مبلغ ") + "${req.cost}$" + Translator.tr(" لبدء تنفيذ المشروع بضمان منصة قبس."), color = TextPrimary, fontFamily = CairoFont)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = "**** **** **** 1234",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(Translator.tr("البطاقة الائتمانية"), color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = Color(0xFF333333),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        AppRequestService.updateRequestProgressAndPayment(context, req.id, isPaid = true)
                        request.value = request.value?.copy(isPaid = true)
                        showPaymentDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) {
                    Text(Translator.tr("تأكيد الدفع"), color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPaymentDialog = false }) {
                    Text(Translator.tr("إلغاء"), color = TextSecondary, fontFamily = CairoFont)
                }
            }
        )
    }
}
