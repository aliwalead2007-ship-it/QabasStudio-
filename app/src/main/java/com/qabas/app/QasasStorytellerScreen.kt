package com.qabas.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class QasasStory(val id: String, val title: String, val surah: String, val summary: String)

private val BuiltInStories = listOf(
    QasasStory("yusuf", "يوسف عليه السلام", "سورة يوسف", "رؤيا الأحد عشر كوكباً، ثم الجب والقصر والسجن، ثم التمكين. درس العفة والصبر وحسن الظن بالله."),
    QasasStory("ibrahim", "إبراهيم الخليل", "سورة الأنبياء", "حطم الأصنام بالحجة، وألقي في النار فكانت برداً وسلاماً. درس التوحيد والشجاعة في الحق."),
    QasasStory("musa", "موسى عليه السلام", "سورة القصص", "من التابوت في النيل إلى البحر الذي انفلق. درس الثقة بالله أمام الطغيان."),
    QasasStory("kahf", "أصحاب الكهف", "سورة الكهف", "فتية آمنوا فزادهم الله هدى وناموا سنين عدداً. درس صحبة الصالحين والثبات."),
    QasasStory("maryam", "مريم عليها السلام", "سورة مريم", "اصطفاها الله وصدقت بكلماته، فجاءت بعيسى آية للعالمين. درس الطهر والتوكل.")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QasasStorytellerScreen(
    onBack: () -> Unit,
    onCreateReel: (scriptText: String, topic: String) -> Unit
) {
    var selected by remember { mutableStateOf<QasasStory?>(null) }
    var myStory by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = DeepSlate,
        topBar = {
            TopAppBar(
                title = { Text("قاص قبس 📖", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardSurface)
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("اختر قصة تُروى، أو احكِ قصتك فيحولها القاص لريلز", color = TextSecondary, fontFamily = CairoFont, fontSize = 13.sp)
            }
            items(BuiltInStories) { s ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151B2B)),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().clickable { selected = s }
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.title, color = Color.White, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(s.surah, color = GoldPrimary, fontFamily = CairoFont, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(s.summary, color = TextSecondary, fontFamily = CairoFont, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onCreateReel("قصة ${s.title}: ${s.summary}", s.title) },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("حولها لريلز 🎬", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("أو اسأل القاص", color = GoldPrimary, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        OutlinedTextField(
                            value = myStory, onValueChange = { myStory = it },
                            modifier = Modifier.fillMaxWidth(), placeholder = { Text("احكِ قصتك أو اسأل عن قصة...", fontFamily = CairoFont, color = TextSecondary) },
                            shape = RoundedCornerShape(12.dp), maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GoldPrimary, unfocusedBorderColor = Color(0xFF1E293B), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                        if (answer.isNotBlank()) Text(answer, color = Color(0xFFE2E8F0), fontFamily = CairoFont, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (myStory.isBlank() || asking) return@Button
                                    asking = true
                                    scope.launch {
                                        val p = "أنت قاص إسلامي. أجب باختصار صادق (4 أسطر) عن: $myStory. إن كان سؤالاً عن قصة قرآنية اذكر السورة والدرس. لا تخترع آيات."
                                        val r = withContext(Dispatchers.IO) {
                                            try { RealGroqService.chatOrGenerate(p) } catch (_: Exception) { null }
                                                ?: try { RealOpenAIService.chatOrGenerate(p) } catch (_: Exception) { null }
                                                ?: "القاص يجهز حكايته — تحقق من الاتصال وحاول مجدداً."
                                        }
                                        answer = (r ?: "").take(800)
                                        asking = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary), shape = RoundedCornerShape(10.dp)
                            ) { Text(if (asking) "يروي..." else "اسأل القاص ✦", color = DeepSlate, fontFamily = CairoFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                            if (answer.isNotBlank()) {
                                OutlinedButton(onClick = { onCreateReel("$myStory\n$answer", "قصة المستخدم") }, shape = RoundedCornerShape(10.dp)) {
                                    Text("حولها لريلز 🎬", color = GoldPrimary, fontFamily = CairoFont, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
