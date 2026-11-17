package com.bookkeeping.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 首次进账单页的一次性提示气泡：「长按 + 1 秒，说话记账」。
 *
 * 挂在 FAB 上方，下缘的小三角指向 FAB。不是 Dialog、也不铺遮罩：
 * 气泡本身可点掉，页面上的任何一次按下也会让它消失（由调用方监听后收起），
 * 所以它不会挡住记账操作。
 */
@Composable
fun VoiceHintBubble(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubbleColor = MaterialTheme.colorScheme.inverseSurface
    val textColor = MaterialTheme.colorScheme.inverseOnSurface
    Column(
        modifier = modifier
            .widthIn(max = 232.dp)
            .clickable(role = Role.Button, onClick = onDismiss),
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .background(bubbleColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
        Canvas(
            modifier = Modifier
                .padding(end = 25.dp)
                .size(width = 14.dp, height = 7.dp),
        ) {
            drawPath(
                path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                },
                color = bubbleColor,
            )
        }
    }
}
