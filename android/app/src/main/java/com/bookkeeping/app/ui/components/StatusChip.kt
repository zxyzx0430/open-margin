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
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingMintSoft
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingWarning
import com.bookkeeping.app.ui.theme.QingWarningContainer

enum class StatusTone { POSITIVE, WARNING, ERROR, NEUTRAL }

@Composable
fun StatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val (background, foreground) = when (tone) {
        StatusTone.POSITIVE -> QingMintSoft to QingIncome
        StatusTone.WARNING -> QingWarningContainer to QingWarning
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer to QingExpense
        StatusTone.NEUTRAL -> QingSurfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
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
    val (background, foreground) = when (tone) {
        StatusTone.POSITIVE -> QingMintSoft to QingIncome
        StatusTone.WARNING -> QingWarningContainer to QingWarning
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer to QingExpense
        StatusTone.NEUTRAL -> QingSurfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
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
