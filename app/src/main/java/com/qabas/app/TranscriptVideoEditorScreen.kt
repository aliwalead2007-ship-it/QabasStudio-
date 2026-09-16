package com.qabas.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*

/**
 * محرر النص هو الفيديو: احذف جملة فيُحذف مشهدها، عدّل كلمة فيتحدث عنوان المشهد.
 * يستبدل التايم لاين المعقد — على طريقة Descript.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptVideoEditorScreen(
    scenes: List<Scene>,
    onBack: () -> Unit,
    onApply: (List<Scene>) -> Unit
) {
    var lines by remember { mutableStateOf(scenes.map { it.title }) }
    var deleted by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = DeepSlate,
        topBar = {
            TopAppBar(
                title = { Text("محرر النص 🎙️", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardSurface)
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                Text(if (deleted > 0) "حُذف $deleted مشاهد بحذف سطورها" else "احذف أي سطر فيُحذف مشهده فوراً", color = TextSecondary, fontFamily = CairoFont, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { lines = lines.map { SmartCut.clean(it) } },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("إزالة الحشو ✂️", color = GoldPrimary, fontFamily = CairoFont, fontSize = 13.sp) }
                    Button(
                        onClick = {
                            val out = scenes.mapIndexedNotNull { i, s ->
                                val t = lines.getOrNull(i)?.trim() ?: ""
                                if (t.isBlank()) null else s.copy(title = t)
                            }
                            onApply(out)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("طبّق ✓", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(lines) { i, line ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(8.dp)) {
                        OutlinedTextField(
                            value = line, onValueChange = { v -> lines = lines.toMutableList().also { it[i] = v } },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary, unfocusedBorderColor = Color(0xFF1E293B), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                        IconButton(onClick = {
                            lines = lines.toMutableList().also { it[i] = "" }
                            deleted++
                        }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFF87171)) }
                    }
                }
            }
        }
    }
}
