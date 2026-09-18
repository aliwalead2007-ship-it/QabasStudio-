package com.qabas.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qabas.app.ui.theme.*

/**
 * طقم تصميم موحد لمراكز اللوحة (الصحة 💰📊🩺):
 * تبويبات حبوب (pills) + بطاقة رأس متدرجة — هوية واحدة بدل ثلاثة أساليب.
 */
@Composable
fun CenterTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = selected == index
            Surface(
                modifier = Modifier.clickable { onSelect(index) },
                color = if (isSelected) GoldPrimary else CardSurface,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSelected) GoldPrimary else GoldPrimary.copy(alpha = 0.25f)
                )
            ) {
                Text(
                    title,
                    color = if (isSelected) DeepSlate else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = CairoFont,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun CenterTabRowWithIcons(
    titles: List<String>,
    icons: List<ImageVector>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        titles.forEachIndexed { index, title ->
            val isSelected = selected == index
            Surface(
                modifier = Modifier.clickable { onSelect(index) },
                color = if (isSelected) GoldPrimary else CardSurface,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSelected) GoldPrimary else GoldPrimary.copy(alpha = 0.25f)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Icon(
                        icons[index],
                        contentDescription = null,
                        tint = if (isSelected) DeepSlate else TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        title,
                        color = if (isSelected) DeepSlate else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = CairoFont
                    )
                }
            }
        }
    }
}

/** بطاقة رأس متدرجة لمركز: رقم بارز + عنوان + وصف + زر إجراء اختياري. */
@Composable
fun CenterHeaderCard(
    badge: String,
    title: String,
    subtitle: String,
    accent: Color,
    actionLabel: String? = null,
    actionDoneLabel: String? = null,
    actionDone: Boolean = false,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardSurface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.14f), Color.Transparent)
                    )
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = accent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        badge,
                        color = accent,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = TajawalFont,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = CairoFont
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        subtitle,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = NotoSansFont,
                        lineHeight = 15.sp
                    )
                }
                if (actionLabel != null && onAction != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onAction,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GoldPrimary.copy(alpha = 0.2f)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (actionDone && actionDoneLabel != null) actionDoneLabel else actionLabel,
                            color = GoldPrimary,
                            fontSize = 11.sp,
                            fontFamily = CairoFont,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
