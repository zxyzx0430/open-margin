package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingAmountExpense
import com.bookkeeping.app.ui.theme.QingAmountIncome
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.QingTypeTokens
import com.bookkeeping.app.ui.theme.qingColors

@Composable
fun MonthlySummaryCard(
    expenseCents: Long,
    incomeCents: Long,
    modifier: Modifier = Modifier,
    monthly: Boolean = true,
    budgetCents: Long = 0L,
) {
    val colors = qingColors
    val diff = incomeCents - expenseCents
    WalletCardSurface(modifier = modifier.fillMaxWidth(), hero = true) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(if (monthly) tr("本月支出") else tr("支出"), style = QingTypeTokens.supporting.copy(fontSize = 13.sp), color = colors.textSecondary)
                AutoSizeAmountText(formatCny(expenseCents), QingTypeTokens.heroAmount,
                    modifier = Modifier.fillMaxWidth(), color = colors.textPrimary, minimumSizeSp = 14f)
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.strokeHairline)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryMetric(if (monthly) tr("本月收入") else tr("收入"), "+" + formatCny(incomeCents), QingAmountIncome, Modifier.weight(1f))
                SummaryMetric(tr("结余"), (if (diff >= 0) "+" else "−") + formatCny(kotlin.math.abs(diff)),
                    if (diff >= 0) QingAmountIncome else QingAmountExpense, Modifier.weight(1f))
            }
            if (monthly && budgetCents > 0L) {
                val fraction = (expenseCents.toDouble() / budgetCents).toFloat().coerceIn(0f, 1f)
                val fill = if (expenseCents > budgetCents) colors.semanticWarning else colors.accentPrimary
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pill = RoundedCornerShape(QingRadiusTokens.radiusPill)
                    Box(Modifier.weight(1f).height(4.dp).clip(pill).background(colors.accentPrimary.copy(alpha = 0.12f))) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(pill).background(fill))
                    }
                    Text(formatCny(budgetCents - expenseCents), style = QingTypeTokens.numeric(QingTypeTokens.supporting), color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = QingTypeTokens.supporting, color = qingColors.textSecondary)
        AutoSizeAmountText(value, QingTypeTokens.rowAmount.copy(fontSize = 15.sp), modifier = Modifier.fillMaxWidth(),
            color = color, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start, minimumSizeSp = 14f)
    }
}
