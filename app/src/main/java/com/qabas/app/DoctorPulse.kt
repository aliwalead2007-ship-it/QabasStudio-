package com.qabas.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.CairoFont

// نبض الطبيب الحي: نقطة تنبض بلون الحالة + رسالة آخر فحص
@Composable
fun DoctorPulse(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var pulse by remember { mutableStateOf(DoctorMonitor.readPulse(context)) }
    LaunchedEffect(Unit) { pulse = DoctorMonitor.readPulse(context) }

    val (state, _, msg) = pulse
    val base = when (state) {
        "red" -> Color(0xFFEF4444)
        "yellow" -> Color(0xFFE8C547)
        else -> Color(0xFF10B981)
    }
    val t = rememberInfiniteTransition(label = "doctor-pulse")
    val s by t.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "beat")

    Surface(
        modifier = modifier,
        color = base.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, base.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(8.dp).scale(s)
                    .clip(CircleShape).background(base)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when (state) {
                    "red" -> "يحتاج تدخل 🔴"
                    "yellow" -> "ملاحظة 🟡"
                    else -> "سليم 🟢"
                },
                color = base, fontFamily = CairoFont, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            if (msg.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(msg.take(40), color = base.copy(alpha = 0.75f), fontFamily = CairoFont, fontSize = 10.sp)
            }
        }
    }
}
