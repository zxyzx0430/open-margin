package com.bookkeeping.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.util.Locale

@Composable
fun TransactionRow(
    transaction: TransactionEntity,
    timeText: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = runCatching { TransactionType.valueOf(transaction.type) }
        .getOrDefault(TransactionType.EXPENSE)
    val category = runCatching { TransactionCategory.valueOf(transaction.category) }
        .getOrDefault(TransactionCategory.OTHER)
    var expanded by remember(transaction.id) { mutableStateOf(false) }
    val title = transaction.merchant.ifBlank { tr(category.displayName) }
    val duplicate = transaction.duplicateOfId != null ||
        transaction.remoteReviewStatus == "possible_duplicate"
    val note = transaction.note
        .removePrefix("[自动抓取] ")
        .removePrefix("[银行短信] ")
        .trim()
    val amountText = if (transaction.currency == "CNY") {
        formatCny(transaction.amountCents)
    } else {
        transaction.currency + " " + "%.2f".format(Locale.CHINA, transaction.amountCents / 100.0)
    }
    val amountColor = if (type == TransactionType.INCOME) QingIncome else QingExpense
    val sourceLabel = sourceLabel(transaction.source)
    val visual = categoryVisual(category)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MerchantAvatar(
                merchant = transaction.merchant,
                merchantIconKey = transaction.merchantIconKey,
                merchantIconPath = transaction.merchantIconPath,
                category = category,
                size = 42.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (duplicate) {
                        Spacer(Modifier.width(6.dp))
                        SoftBadge(tr("待确认重复"))
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = visual.icon,
                        contentDescription = null,
                        tint = visual.foreground.copy(alpha = 0.8f),
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = tr(category.displayName) + " · " + sourceLabel + " · " + timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (note.isNotBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = (if (type == TransactionType.INCOME) "+" else "−") + amountText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = amountColor,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = tr("展开详情"),
                    tint = QingTextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            if (transaction.remoteReviewStatus == "refund") {
                Text(
                    text = tr("已关联退款 · 请在网站管理退款关系"),
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        onReview(
                            if (transaction.remoteReviewStatus == "excluded") "unverified"
                            else "excluded"
                        )
                    }) {
                        Text(
                            if (transaction.remoteReviewStatus == "excluded") tr("恢复计入统计")
                            else tr("不计入统计")
                        )
                    }
                }
            }
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = tr("修改会双向同步"),
                    style = MaterialTheme.typography.labelSmall,
                    color = QingTextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEdit) { Text(tr("编辑")) }
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(tr("删除"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "ALIPAY" -> tr("支付宝")
    "WECHAT" -> tr("微信")
    "BANK_SMS" -> tr("银行短信")
    "MANUAL" -> tr("手动")
    else -> tr("其他来源")
}
