package com.qabas.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private data class GoldenClip(val hook: String, val text: String)

/**
 * مقص المقاطع: خطبة/بودكاست طويل → 5 لحظات ذهبية جاهزة كريلزات.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipExtractorScreen(
    initialText: String = "",
    onBack: () -> Unit,
    onCreateReel: (scriptText: String, topic: String) -> Unit
) {
    var longText by remember { mutableStateOf(initialText) }
    var clips by remember { mutableStateOf<List<GoldenClip>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun localClips(t: String): List<GoldenClip> {
        val parts = t.split(Regex("[.،!؟\\n]")).map { it.trim() }.filter { it.length > 20 }.take(5)
        return parts.map { GoldenClip(hook = it.take(40) + "…", text = it) }
    }

    fun extract() {
        if (longText.length < 30 || working) return
        working = true
        scope.launch {
            val p = """
                من هذا النص الطويل: "${longText.take(3000)}"
                استخرج 5 لحظات ذهبية تصلح ريلزات قصيرة. أخرج JSON فقط: [{"hook":"جملة خطافة أول ثانيتين","text":"نص المقطع 30-50 كلمة"}].
            """.trimIndent()
            val raw = withContext(Dispatchers.IO) {
                try { RealGroqService.chatOrGenerate(p) } catch (_: Exception) { null }
                    ?: try { RealOpenAIService.chatOrGenerate(p) } catch (_: Exception) { null }
                    ?: try { RealGeminiService.generateScript(longText, "", "موعظة", "مؤثر").joinToString("\n") { it.title } } catch (_: Exception) { null }
            }
            clips = try {
                if (raw != null && raw.contains("[")) {
                    val arr = JSONArray(raw.substring(raw.indexOf("["), raw.lastIndexOf("]") + 1))
                    (0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        GoldenClip(o.optString("hook", "لحظة ذهبية"), o.optString("text", ""))
                    }.filter { it.text.isNotBlank() }
                } else localClips(longText)
            } catch (_: Exception) { localClips(longText) }
            if (clips.isEmpty()) clips = localClips(longText)
            working = false
        }
    }

    Scaffold(
        containerColor = DeepSlate,
        topBar = {
            TopAppBar(
                title = { Text("مقص المقاطع ✂️", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardSurface)
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(
                    value = longText, onValueChange = { longText = it },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    placeholder = { Text("الصق نص الخطبة أو التفريغ الطويل هنا...", fontFamily = CairoFont, color = TextSecondary) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary, unfocusedBorderColor = Color(0xFF1E293B), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Spacer(Modifier.height(8.dp))
                ProdPrimaryButton(text = if (working) "المقص يعمل..." else "قص اللحظات الذهبية ✂️", onClick = { extract() })
            }
            itemsIndexed(clips) { i, c ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("المقطع ${i + 1}: ${c.hook}", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(c.text, color = Color.White, fontFamily = CairoFont, fontSize = 13.sp)
                        Button(onClick = { onCreateReel(c.text, c.hook.take(30)) }, colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary), shape = RoundedCornerShape(10.dp)) {
                            Text("أنتجه ريلز 🎬", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
