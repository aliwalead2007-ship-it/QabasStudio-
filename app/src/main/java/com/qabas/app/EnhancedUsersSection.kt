package com.qabas.app

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * إدارة المستخدمين المتقدمة:
 * - بحث + فلترة حسب الرتبة/الحالة.
 * - مشروع المستخدم (مشاريعه) وزر تغيير الرتبة / الإيقاف بتأكيد.
 * - تصدير CSV للأعضاء المفلترين عبر CreateDocument.
 * - سجل تدقيق لكل إجراء حساس.
 */
@Composable
fun EnhancedUsersSection(context: Context, devUsers: androidx.compose.runtime.snapshots.SnapshotStateList<DevUser>) {
    var searchQuery by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("الكل") }
    var statusFilter by remember { mutableStateOf("الكل") }
    var selectedUser by remember { mutableStateOf<DevUser?>(null) }
    var userTimeline by remember { mutableStateOf<List<Map<String, Any>>?>(null) }
    LaunchedEffect(selectedUser?.id) {
        userTimeline = if (selectedUser != null) {
            CloudServices.Database.getUserTimeline(selectedUser!!.id)
        } else null
    }
    var confirmRoleUser by remember { mutableStateOf<DevUser?>(null) }
    var confirmSuspendUser by remember { mutableStateOf<DevUser?>(null) }
    var confirmNewRole by remember { mutableStateOf<String?>(null) }
    var tempRoleDays by remember { mutableStateOf(0) } // 0 = دائم
    var roleMenuUser by remember { mutableStateOf<DevUser?>(null) }
    var showAddUserDialog by remember { mutableStateOf(false) }
    var newUserName by remember { mutableStateOf("") }
    var newUserEmail by remember { mutableStateOf("") }
    var newUserType by remember { mutableStateOf("Freemium") }
    val coroutineScope = rememberCoroutineScope()

    // إرجاع الرتب المؤقتة المنتهية عند فتح القسم
    LaunchedEffect(Unit) {
        val swept = CloudServices.Database.sweepExpiredRoles()
        if (swept > 0) {
            Toast.makeText(context, "انتهت $swept رتبة مؤقتة وعادت إلى Freemium", Toast.LENGTH_LONG).show()
        }
    }

    val types = listOf("الكل", "مطور", "خاص", "Freemium")
    val statuses = listOf("الكل", "نشط", "موقوف")

    val filteredUsers = devUsers.filter { u ->
        val matchesQuery = u.name.contains(searchQuery, ignoreCase = true) ||
            u.email.contains(searchQuery, ignoreCase = true)
        val matchesType = typeFilter == "الكل" || u.type == typeFilter
        val matchesStatus = statusFilter == "الكل" ||
            (statusFilter == "موقوف" && u.isSuspended) ||
            (statusFilter == "نشط" && !u.isSuspended)
        matchesQuery && matchesType && matchesStatus
    }.sortedWith(compareByDescending<DevUser> { it.regDate })

    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val csv = buildUsersCsv(filteredUsers)
            context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
            Toast.makeText(context, "تم تصدير ${filteredUsers.size} مستخدم CSV ✅", Toast.LENGTH_SHORT).show()
            AuditLogger.log(context, "users_export_csv", "تصدير ${filteredUsers.size} مستخدم")
        }
    }

    // ---- إضافة مستخدم جديد ----
    if (showAddUserDialog) {
        AlertDialog(
            onDismissRequest = { showAddUserDialog = false },
            title = { Text("إضافة مستخدم جديد", fontFamily = CairoFont, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newUserName,
                        onValueChange = { newUserName = it },
                        label = { Text("الاسم", color = Color.Gray, fontFamily = CairoFont) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newUserEmail,
                        onValueChange = { newUserEmail = it },
                        label = { Text("البريد الإلكتروني", color = Color.Gray, fontFamily = CairoFont) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("الرتبة: $newUserType", color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newUserName.isNotBlank() && newUserEmail.isNotBlank()) {
                            val newId = java.util.UUID.randomUUID().toString()
                            val newUser = DevUser(
                                id = newId,
                                name = newUserName,
                                email = newUserEmail,
                                type = newUserType,
                                projectCount = 0,
                                regDate = DevDashboardFormatters.formatRegDate(System.currentTimeMillis()),
                                isSuspended = false
                            )
                            devUsers.add(newUser)
                            newUserName = ""
                            newUserEmail = ""
                            showAddUserDialog = false
                            coroutineScope.launch {
                                val saved = runCatching {
                                    CloudServices.Database.saveUserToCloud(
                                        userId = newId,
                                        name = newUser.name,
                                        email = newUser.email,
                                        type = newUser.type,
                                        projectCount = 0
                                    )
                                }.getOrDefault(false)
                                AuditLogger.log(context, "user_add", "${newUser.email} (رتبة $newUserType)")
                                Toast.makeText(
                                    context,
                                    if (saved) "تمت الإضافة وحفظها سحابياً ✅" else "أُضيف محلياً فقط — فشل الحفظ السحابي",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                ) { Text("إضافة", color = Color.Black, fontFamily = CairoFont, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddUserDialog = false }) { Text("إلغاء", color = Color.Gray) }
            },
            containerColor = DeepSlate
        )
    }

    // ---- تأكيد تغيير الرتبة (دائم أو مؤقت) ----
    confirmRoleUser?.let { user ->
        AlertDialog(
            onDismissRequest = { confirmRoleUser = null; confirmNewRole = null; tempRoleDays = 0 },
            title = { Text("تأكيد تغيير رتبة المستخدم", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تغيير رتبة «${user.name}» إلى «${confirmNewRole ?: ""}»؟", color = Color.White, fontFamily = NotoSansFont)
                    Text("مدة الرتبة:", color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "دائم", 7 to "7 أيام", 30 to "30 يوم").forEach { (days, label) ->
                            FilterChip(
                                selected = tempRoleDays == days,
                                onClick = { tempRoleDays = days },
                                label = { Text(label, fontFamily = CairoFont, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newType = confirmNewRole ?: return@TextButton
                    val days = tempRoleDays
                    coroutineScope.launch {
                        val success = if (days > 0) {
                            CloudServices.Database.grantTemporaryRole(user.id, user.email, newType, days)
                        } else {
                            CloudServices.Database.updateUserRole(user.id, user.email, newType)
                        }
                        if (success) {
                            val idx = devUsers.indexOf(user)
                            if (idx != -1) devUsers[idx] = devUsers[idx].copy(type = if (days > 0) "$newType (مؤقت $days يوم)" else newType)
                            AuditLogger.log(context, "user_role_change", "${user.email} -> $newType" + if (days > 0) " ($days يوم)" else "")
                            Toast.makeText(context, "تم تغيير الرتبة وحفظه سحابياً ✅", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "فشل تغيير الرتبة: لاحظ حماية حساب المالك/الاتصال", Toast.LENGTH_LONG).show()
                        }
                    }
                    confirmRoleUser = null; confirmNewRole = null; tempRoleDays = 0
                }) { Text("تأكيد ✓", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRoleUser = null; confirmNewRole = null; tempRoleDays = 0 }) { Text("إلغاء", color = Color.Gray) }
            },
            containerColor = DeepSlate
        )
    }

    // ---- تأكيد الإيقاف ----
    confirmSuspendUser?.let { user ->
        AlertDialog(
            onDismissRequest = { confirmSuspendUser = null },
            title = { Text("تأكيد إيقاف الحساب", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold) },
            text = { Text("إيقاف حساب «${user.name}» (${user.email})؟", color = Color.White, fontFamily = NotoSansFont) },
            confirmButton = {
                TextButton(onClick = {
                    val newStatus = !user.isSuspended
                    coroutineScope.launch {
                        val success = CloudServices.Database.updateUserSuspension(user.id, user.email, newStatus)
                        if (success) {
                            val idx = devUsers.indexOf(user)
                            if (idx != -1) devUsers[idx] = devUsers[idx].copy(isSuspended = newStatus)
                            AuditLogger.log(context, "user_suspend_toggle", "${user.email} -> ${if (newStatus) "موقوف" else "نشط"}")
                            Toast.makeText(context, if (newStatus) "تم الإيقاف وحفظه سحابياً" else "تم إلغاء الإيقاف", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "فشل التحديث: حماية المالك أو الاتصال", Toast.LENGTH_LONG).show()
                        }
                    }
                    confirmSuspendUser = null
                }) { Text("تأكيد ✓", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSuspendUser = null }) { Text("إلغاء", color = Color.Gray) }
            },
            containerColor = DeepSlate
        )
    }

    // ---- تفاصيل المستخدم + خطه الزمني ----
    selectedUser?.let { user ->
        val timeline = userTimeline
        AlertDialog(
            onDismissRequest = { selectedUser = null },
            title = { Text("تفاصيل المستخدم", color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("الاسم: ${user.name}", color = Color.White, fontFamily = NotoSansFont)
                    Text("البريد: ${user.email}", color = Color.White, fontFamily = NotoSansFont)
                    Text("الرتبة: ${user.type}", color = GoldPrimary, fontFamily = CairoFont)
                    Text("المشاريع: ${user.projectCount}", color = Color.White, fontFamily = NotoSansFont)
                    Text("التسجيل: ${user.regDate}", color = Color.White, fontFamily = NotoSansFont)
                    Text("الحالة: ${if (user.isSuspended) "موقوف 🔴" else "نشط 🟢"}", color = Color.White, fontFamily = NotoSansFont)
                    HorizontalDivider(color = Color(0xFF1E293B))
                    Text("الخط الزمني:", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    when {
                        timeline == null -> Text("جاري التحميل...", color = Color.Gray, fontFamily = NotoSansFont, fontSize = 12.sp)
                        timeline!!.isEmpty() -> Text("لا نشاط مسجّل لهذا المستخدم.", color = Color.Gray, fontFamily = NotoSansFont, fontSize = 12.sp)
                        else -> {
                            for (ev in timeline!!.take(12)) {
                                val kind = ev["kind"] as? String ?: ""
                                val icon = when (kind) {
                                    "project" -> "🎬"
                                    "purchase" -> "💰"
                                    "role_expiry" -> "⏳"
                                    else -> "•"
                                }
                                val ts = (ev["ts"] as? Long) ?: 0L
                                val date = if (ts > 0) DevDashboardFormatters.formatRegDate(ts) else ""
                                Text(
                                    "$icon ${ev["title"]} — $date",
                                    color = Color.White, fontFamily = NotoSansFont, fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedUser = null }) { Text("إغلاق", color = GoldPrimary, fontWeight = FontWeight.Bold) }
            },
            containerColor = DeepSlate
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            // أدوات
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("بحث بالاسم أو البريد...", color = Color.Gray, fontFamily = NotoSansFont, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GoldPrimary, unfocusedBorderColor = Color(0xFF1E293B),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = statusFilter != "الكل",
                    onClick = {
                        statusFilter = when (statusFilter) {
                            "الكل" -> "نشط"
                            "نشط" -> "موقوف"
                            else -> "الكل"
                        }
                    },
                    label = { Text("الحالة: $statusFilter", color = if (statusFilter == "موقوف") Color(0xFFEF4444) else GoldSecondary, fontFamily = CairoFont, fontSize = 11.sp) }
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    csvExport.launch("qabas_users_${System.currentTimeMillis()}.csv")
                }) {
                    Text("تصدير CSV ⬇", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                IconButton(
                    onClick = { showAddUserDialog = true },
                    modifier = Modifier.background(GoldPrimary, RoundedCornerShape(10.dp)).size(40.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "إضافة مستخدم", tint = Color.Black)
                }
            }
            // نوع على نوع
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { t ->
                    FilterChip(
                        selected = typeFilter == t,
                        onClick = { typeFilter = t },
                        label = { Text(t, color = if (typeFilter == t) GoldSecondary else TextSecondary, fontFamily = CairoFont, fontSize = 11.sp) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Group, contentDescription = null, tint = GoldPrimary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("إجمالي الحسابات", color = Color.Gray, fontFamily = NotoSansFont, fontSize = 12.sp)
                        Text("${devUsers.size} حساب · ${filteredUsers.size} معروض", color = Color.White, fontFamily = TajawalFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
        }

        if (filteredUsers.isEmpty()) {
            item {
                Text("لا يوجد مستخدمون مطابقون.", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
            }
        }

        items(filteredUsers) { user ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (user.isSuspended) Color(0xFFEF4444).copy(alpha = 0.6f) else Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user.name, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(user.email, color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                        }
                        Text(
                            if (user.isSuspended) "موقوف 🔴" else "نشط 🟢",
                            color = if (user.isSuspended) Color(0xFFEF4444) else Color(0xFF10B981),
                            fontFamily = CairoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("الرتبة: ${user.type}", color = GoldPrimary, fontFamily = CairoFont, fontSize = 11.sp)
                        Text("· المشاريع: ${user.projectCount}", color = TextSecondary, fontFamily = NotoSansFont, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { selectedUser = user },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("تفاصيل", color = GoldPrimary, fontFamily = CairoFont, fontSize = 11.sp) }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { roleMenuUser = user },
                                modifier = Modifier.fillMaxWidth(),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("تغيير الرتبة", color = GoldSecondary, fontFamily = CairoFont, fontSize = 11.sp) }
                            DropdownMenu(expanded = roleMenuUser == user, onDismissRequest = { roleMenuUser = null }, containerColor = DeepSlate) {
                                listOf("مطور", "خاص", "Freemium").forEach { role ->
                                    DropdownMenuItem(
                                        text = { Text(role, color = if (role == user.type) GoldPrimary else Color.White, fontFamily = CairoFont, fontSize = 12.sp) },
                                        onClick = {
                                            roleMenuUser = null
                                            confirmNewRole = role
                                            confirmRoleUser = user
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { confirmSuspendUser = user },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text(if (user.isSuspended) "تفعيل" else "إيقاف", color = Color(0xFFEF4444), fontFamily = CairoFont, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

private fun buildUsersCsv(users: List<DevUser>): String {
    val sb = StringBuilder()
    sb.append("الاسم,البريد,الرتبة,المشاريع,التسجيل,الحالة\n")
    users.forEach { u ->
        sb.append("\"${u.name}\",\"${u.email}\",\"${u.type}\",${u.projectCount},\"${u.regDate}\",\"${if (u.isSuspended) "موقوف" else "نشط"}\"\n")
    }
    return sb.toString()
}
