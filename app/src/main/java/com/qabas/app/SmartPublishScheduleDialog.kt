package com.qabas.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPublishScheduleDialog(
    initialTitle: String,
    initialHashtags: String = "#قبس #أثر_لا_ينقطع #ريلز_دعوي",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialTitle.ifBlank { "فيديو دعوي مبارك من استوديو قبس 🌟" }) }
    var selectedPlatformId by remember { mutableStateOf("tiktok") }
    
    val platforms = remember {
        listOf(
            Triple("tiktok", "TikTok Studio", Color(0xFF00F2FE)),
            Triple("youtube", "YouTube Shorts", Color(0xFFF44336)),
            Triple("instagram", "Instagram Reels", Color(0xFFE1306C)),
            Triple("twitter", "X (Twitter)", Color(0xFF1DA1F2)),
            Triple("facebook", "Facebook Reels", Color(0xFF1877F2))
        )
    }

    val slots = remember(selectedPlatformId) {
        SocialAccountManager.getOptimalPublishSlots(context, selectedPlatformId)
    }

    var selectedSlot by remember(selectedPlatformId) {
        mutableStateOf(slots.firstOrNull { it.isRecommended } ?: slots.firstOrNull())
    }

    var scheduledList by remember {
        mutableStateOf(SocialAccountManager.getActiveScheduledPublishItems(context))
    }

    var customHour by remember { mutableStateOf("") }
    var customMinute by remember { mutableStateOf("") }
    // موعد مخصص يكتبه المستخدم — يظهر كخيار مع المواعيد الذكية عند صلاحيته
    val customSlot = remember(customHour, customMinute) {
        val h = customHour.trim().toIntOrNull()
        val m = customMinute.trim().toIntOrNull()
        if (h != null && m != null && h in 0..23 && m in 0..59) {
            val label = when {
                h == 0 -> "12:$m ص"
                h < 12 -> "$h:$m ص"
                h == 12 -> "12:$m م"
                else -> "${h - 12}:$m م"
            }
            SmartPublishSlot(
                id = "custom_time",
                platformId = selectedPlatformId,
                platformName = platforms.firstOrNull { it.first == selectedPlatformId }?.second ?: selectedPlatformId,
                timeLabel = "$label (مخصص)",
                hourOfDay = h,
                minute = m,
                engagementScore = 0,
                rationale = "موعد اخترته بنفسك",
                isRecommended = false
            )
        } else null
    }
    val allSlots = remember(slots, customSlot) {
        if (customSlot != null) slots + customSlot else slots
    }

    var activeTab by remember { mutableIntStateOf(0) } // 0: New Schedule, 1: Active Reminders

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        containerColor = DeepSlate,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Alarm, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("جدولة النشر الذكية والمواقيت المثالية ⏰", color = GoldPrimary, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Tab Row for New Schedule vs Active Reminders
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = Color(0xFF0B0F19),
                    contentColor = GoldPrimary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = {
                            Text("جدولة تذكير جديد", fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = {
                            Text("التذكيرات النشطة (${scheduledList.size})", fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (activeTab == 0) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                    ) {
                        // Title Input
                        item {
                            Column {
                                Text("عنوان المقطع للتذكير 📝", color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = title,
                                    onValueChange = { title = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = GoldPrimary,
                                        unfocusedBorderColor = Color(0xFF1E293B),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = CardSurface,
                                        unfocusedContainerColor = CardSurface
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }

                        // Target Platform Selection Chips
                        item {
                            Column {
                                Text("المنصة المستهدفة 🌐", color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    platforms.take(3).forEach { (id, name, color) ->
                                        val isSelected = selectedPlatformId == id
                                        Surface(
                                            onClick = { selectedPlatformId = id },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) color.copy(alpha = 0.2f) else CardSurface,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) color else Color(0xFF1E293B)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                                Text(name.split(" ").first(), color = if (isSelected) color else Color.LightGray, fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    platforms.drop(3).forEach { (id, name, color) ->
                                        val isSelected = selectedPlatformId == id
                                        Surface(
                                            onClick = { selectedPlatformId = id },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) color.copy(alpha = 0.2f) else CardSurface,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) color else Color(0xFF1E293B)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                                Text(name, color = if (isSelected) color else Color.LightGray, fontFamily = NotoSansFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Custom time (موعد مخصص يكتبه المستخدم)
                        item {
                            Column {
                                Text("موعد مخصص ⏱️ (اختياري)", color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = customHour,
                                        onValueChange = { customHour = it.filter { c -> c.isDigit() }.take(2) },
                                        label = { Text("ساعة 0-23", fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = GoldPrimary,
                                            unfocusedBorderColor = Color(0xFF1E293B),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    OutlinedTextField(
                                        value = customMinute,
                                        onValueChange = { customMinute = it.filter { c -> c.isDigit() }.take(2) },
                                        label = { Text("دقيقة 0-59", fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = GoldPrimary,
                                            unfocusedBorderColor = Color(0xFF1E293B),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                            }
                        }

                        // Smart Slots based on Audience Analytics
                        item {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AutoGraph, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("مواعيد النشر المثالية بناءً على تفاعل الجمهور 📊", color = GoldPrimary, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    allSlots.forEach { slot ->
                                        val isSelected = selectedSlot?.id == slot.id
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (isSelected) GoldPrimary.copy(alpha = 0.15f) else Color(0xFF151B2B), RoundedCornerShape(12.dp))
                                                .border(1.dp, if (isSelected) GoldPrimary else Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                                .clickable { selectedSlot = slot }
                                                .padding(10.dp)
                                        ) {
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        RadioButton(
                                                            selected = isSelected,
                                                            onClick = { selectedSlot = slot },
                                                            colors = RadioButtonDefaults.colors(selectedColor = GoldPrimary)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(slot.timeLabel, color = Color.White, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                    }

                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = if (slot.isRecommended) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color(0xFF3B82F6).copy(alpha = 0.2f),
                                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (slot.isRecommended) Color(0xFF4CAF50) else Color(0xFF3B82F6))
                                                    ) {
                                                        Text(
                                                            if (slot.id == "custom_time") "موعدك ⏱️" else "${slot.engagementScore}% تفاعل 🚀",
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                            color = if (slot.isRecommended) Color(0xFF4CAF50) else Color(0xFF60A5FA),
                                                            fontFamily = CairoFont,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(slot.rationale, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Active Scheduled Items List
                    if (scheduledList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.NotificationsNone, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("لا توجد تذكيرات نشر مجدولة حالياً", color = Color.Gray, fontFamily = NotoSansFont, fontSize = 12.sp)
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp)
                        ) {
                            items(scheduledList) { item ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CardSurface, RoundedCornerShape(10.dp))
                                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.AccessTime, contentDescription = null, tint = GoldPrimary, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(item.formattedTime, color = GoldPrimary, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFF0B0F19)
                                                ) {
                                                    Text(item.platformName, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = Color.LightGray, fontFamily = NotoSansFont, fontSize = 12.sp)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(item.title, color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }

                                        IconButton(onClick = {
                                            SocialAccountManager.cancelScheduledPublish(context, item.id)
                                            scheduledList = SocialAccountManager.getActiveScheduledPublishItems(context)
                                            Toast.makeText(context, "تم إلغاء التذكير 🗑️", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "حذف", tint = Color.Red.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (activeTab == 0) {
                Button(
                    onClick = {
                        val slot = selectedSlot
                        if (slot == null) {
                            Toast.makeText(context, "يرجى اختيار موعد النشر المثالي", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val scheduledItem = SocialAccountManager.schedulePublishReminder(
                            context = context,
                            title = title,
                            platformId = slot.platformId,
                            platformName = slot.platformName,
                            hourOfDay = slot.hourOfDay,
                            minute = slot.minute,
                            hashtags = initialHashtags
                        )
                        scheduledList = SocialAccountManager.getActiveScheduledPublishItems(context)
                        Toast.makeText(context, "تمت جدولة التذكير بنجاح في ${scheduledItem.formattedTime} ⏰✨", Toast.LENGTH_LONG).show()
                        activeTab = 1
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.AlarmAdd, contentDescription = null, tint = DeepSlate, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("حفظ وجدولة التذكير الذكي ⏰", color = DeepSlate, fontFamily = NotoSansFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = Color.Gray, fontFamily = CairoFont)
            }
        }
    )
}
