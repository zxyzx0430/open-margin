package com.bookkeeping.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingTextSecondary

@Composable
fun DayHeader(
    label: String,
    count: Int,
    expenseCents: Long,
    incomeCents: Long,
    modifier: Modifier = Modifier,
    hasForeignOnly: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$label · $count ${tr("笔")}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (expenseCents > 0L) {
                Text(
                    text = tr("支出") + " " + formatCny(expenseCents),
                    style = MaterialTheme.typography.labelSmall,
                    color = QingExpense,
                )
            }
            if (incomeCents > 0L) {
                Text(
                    text = tr("收入") + " " + formatCny(incomeCents),
                    style = MaterialTheme.typography.labelSmall,
                    color = QingIncome,
                )
            }
            if (expenseCents <= 0L && incomeCents <= 0L) {
                Text(
                    text = if (hasForeignOnly) tr("仅外币 · 未换算") else tr("—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = QingTextSecondary,
                )
            }
        }
    }
}
