package com.qabas.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** نتيجة قياس واحدة: زمن الاستجابة + سرعة التحميل. */
data class SpeedSample(val latencyMs: Long, val downKbps: Long, val ok: Boolean)

/**
 * عدّاد سرعة حقيقي — لا أرقام وهمية:
 * - زمن الاستجابة: HEAD خفيف على generate_204 (بلا تكلفة بيانات)
 * - سرعة التحميل: تنزيل ~100KB كل دورة وحساب KB/s فعلياً
 */
object SpeedMeter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun measure(): SpeedSample = withContext(Dispatchers.IO) {
        try {
            // 1) زمن الاستجابة
            val t0 = System.nanoTime()
            val pingOk = runCatching {
                client.newCall(Request.Builder().url("https://www.google.com/generate_204").head().build())
                    .execute().use { it.code == 204 }
            }.getOrDefault(false)
            val latencyMs = (System.nanoTime() - t0) / 1_000_000
            if (!pingOk) return@withContext SpeedSample(latencyMs, 0, false)
            // 2) سرعة التحميل (~128KB من CDN سريع)
            val t1 = System.nanoTime()
            val bytes = runCatching {
                client.newCall(
                    Request.Builder()
                        .url("https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png")
                        .build()
                ).execute().use { it.body?.bytes()?.size ?: 0 }
            }.getOrDefault(0)
            val secs = (System.nanoTime() - t1) / 1_000_000_000.0
            val kbps = if (bytes > 0 && secs > 0) ((bytes / 1024.0) / secs).toLong() else 0
            SpeedSample(latencyMs, kbps, true)
        } catch (_: Exception) {
            SpeedSample(-1, 0, false)
        }
    }

    fun formatSpeed(kbps: Long): String = when {
        kbps <= 0 -> "—"
        kbps >= 1024 -> String.format("%.1f Mb/s", kbps / 1024.0)
        else -> "$kbps Kb/s"
    }
}

/** عدّاد رقمي حديث بأرقام monospace يتحدث كل ثانية. */
@Composable
fun DigitalSpeedCounter() {
    val context = LocalContext.current
    var sample by remember { mutableStateOf<SpeedSample?>(null) }
    var measuring by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            if (!NetworkUtils.isNetworkAvailable(context)) {
                sample = SpeedSample(-1, 0, false)
            } else {
                measuring = true
                sample = SpeedMeter.measure()
                measuring = false
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    val accent = if (sample?.ok == true) Color(0xFF10B981) else Color(0xFFEF4444)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Surface(
        color = Color(0xFF0B0F19).copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(min = 92.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text("⚡", fontSize = 18.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (measuring) "···" else sample?.let {
                        if (!it.ok || it.latencyMs < 0) "OFFLINE" else "${it.latencyMs} ms"
                    } ?: "···",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = accent,
                    maxLines = 1
                )
                Text(
                    text = if (measuring) "…" else sample?.let {
                        if (!it.ok) "—" else SpeedMeter.formatSpeed(it.downKbps)
                    } ?: "…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
    }
}
