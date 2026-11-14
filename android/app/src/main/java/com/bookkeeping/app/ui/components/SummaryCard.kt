package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingTextSecondary

@Composable
fun MonthlySummaryCard(
    expenseCents: Long,
    incomeCents: Long,
    modifier: Modifier = Modifier,
) {
    val diff = incomeCents - expenseCents
    val diffColor = if (diff >= 0) QingIncome else QingExpense
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(QingPrimaryContainer, QingPrimaryContainer.copy(alpha = 0.45f)),
                )
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryMetric(
                label = tr("本月支出"),
                value = formatCny(expenseCents),
                color = QingExpense,
                modifier = Modifier.weight(1f),
            )
            SummaryMetric(
                label = tr("本月收入"),
                value = formatCny(incomeCents),
                color = QingIncome,
                modifier = Modifier.weight(1f),
            )
            SummaryMetric(
                label = tr("本月收支差"),
                value = (if (diff >= 0) "+" else "−") + formatCny(kotlin.math.abs(diff)),
                color = diffColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = com.bookkeeping.app.ui.theme.Typography.labelSmall,
            color = QingTextSecondary,
            maxLines = 1,
        )
        Text(
            text = value,
            style = com.bookkeeping.app.ui.theme.Typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            textAlign = TextAlign.Start,
        )
    }
}
