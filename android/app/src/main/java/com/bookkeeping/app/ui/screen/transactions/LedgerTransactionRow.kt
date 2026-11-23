package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.MerchantAvatar
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 主页与截图批量导入结果页共用的账单行卡片。
 *
 * 保持与账本主页完全一致的视觉层级：商户/分类头像、商户名 + 状态、来源/时间待确认徽章 +
 * 分类/账户/时间副标题、右侧自适应金额。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LedgerTransactionRow(
    transaction: TransactionEntity,
    timeText: String,
    scrollTargetTransactionId: Long? = null,
    highlightedTransactionId: Long? = null,
    onScrollTargetReached: (Long) -> Unit = {},
    onHighlightFinished: (Long) -> Unit = {},
    onClick: () -> Unit,
) {
    val category = runCatching { TransactionCategory.valueOf(transaction.category) }
        .getOrDefault(TransactionCategory.OTHER)
    val type = runCatching { TransactionType.valueOf(transaction.type) }
        .getOrDefault(TransactionType.EXPENSE)
    val semantic = LocalQingSemanticColors.current
    val sourceTag = ledgerTransactionSourceTag(transaction)
    val bringIntoViewRequester = remember(transaction.id) { BringIntoViewRequester() }
    val highlightAlpha = remember(transaction.id) { Animatable(0f) }

    LaunchedEffect(scrollTargetTransactionId) {
        if (scrollTargetTransactionId == transaction.id) {
            bringIntoViewRequester.bringIntoView()
            onScrollTargetReached(transaction.id)
        }
    }
    LaunchedEffect(highlightedTransactionId) {
        if (highlightedTransactionId == transaction.id) {
            highlightAlpha.snapTo(0.32f)
            delay(1_100L)
            highlightAlpha.animateTo(0f, animationSpec = tween(durationMillis = 500))
            onHighlightFinished(transaction.id)
        } else {
            highlightAlpha.snapTo(0f)
        }
    }

    val amount = if (transaction.currency == "CNY") {
        formatCny(transaction.amountCents)
    } else {
        transaction.currency + " " + "%.2f".format(Locale.CHINA, transaction.amountCents / 100.0)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = highlightAlpha.value),
                shape = RoundedCornerShape(14.dp),
            )
            .bringIntoViewRequester(bringIntoViewRequester)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
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
            Text(
                text = transaction.merchant.ifBlank { tr(category.displayName) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SoftBadge(text = sourceTag, tone = StatusTone.NEUTRAL)
                if (transaction.eventTimeNeedsConfirmation) {
                    SoftBadge(text = tr("时间待确认"), tone = StatusTone.WARNING)
                }
                Text(
                    text = transactionSubtitle(
                        categoryLabel = tr(category.displayName),
                        sourceLabel = ledgerTransactionSourceLabel(transaction.source),
                        account = transaction.account,
                        timeText = timeText,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AutoSizeAmountText(
            text = (if (type == TransactionType.INCOME) "+" else "−") + amount,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.widthIn(min = 80.dp),
            fontWeight = FontWeight.SemiBold,
            color = if (type == TransactionType.INCOME) semantic.income else semantic.expenseText,
            textAlign = TextAlign.End,
        )
    }
}

/** 来源标注：每笔都显示一个来源，优先看扫描/导入前缀，其次看来源字段。 */
internal fun ledgerTransactionSourceTag(transaction: TransactionEntity): String {
    val prefix = LEDGER_SOURCE_TAG.find(transaction.note)?.groupValues?.getOrNull(1)
    return when {
        transaction.captureKind != null || prefix == "扫描" -> tr("扫描")
        prefix == "导入" -> tr("导入")
        transaction.source == "BANK_SMS" -> tr("短信")
        transaction.source == "ALIPAY" || transaction.source == "WECHAT" -> tr("通知")
        transaction.source == "MANUAL" -> tr("手动")
        else -> tr("其他来源")
    }
}

private val LEDGER_SOURCE_TAG = Regex("^\\[(扫描|导入)]")

internal fun ledgerTransactionSourceLabel(source: String): String = when (source) {
    "ALIPAY" -> tr("支付宝")
    "WECHAT" -> tr("微信")
    "BANK_SMS" -> tr("银行短信")
    "MANUAL" -> tr("手动")
    else -> tr("其他来源")
}
