package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors

enum class StatusTone { POSITIVE, WARNING, ERROR, NEUTRAL }

/**
 * 状态色唯一映射：tone → (底色, 前景色)。
 *
 * 主题里的 tertiaryContainer 是薄荷绿，此前被当作 WARNING 用，导致「同步异常」
 * 和「同步正常」看起来一样。警告统一走语义盘的橙色，阻断/授权失败走红色，
 * 中性灰只留给未配置、未开启这类无风险状态。
 */
@Composable
internal fun statusToneColors(tone: StatusTone): Pair<Color, Color> {
    val semantic = LocalQingSemanticColors.current
    return when (tone) {
        StatusTone.POSITIVE -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.WARNING -> semantic.warningContainer to semantic.warningText
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun StatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val (background, foreground) = statusToneColors(tone)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Text("●", color = foreground, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = text,
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
fun SoftBadge(
    text: String,
    tone: StatusTone = StatusTone.WARNING,
    modifier: Modifier = Modifier,
) {
    val (background, foreground) = statusToneColors(tone)
    Text(
        text = text,
        color = foreground,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
