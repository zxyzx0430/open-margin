package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.runtime.Immutable
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import com.bookkeeping.app.ui.theme.rememberReduceMotion


import androidx.compose.foundation.layout.heightIn
import com.bookkeeping.app.ui.theme.QingTypeTokens
import com.bookkeeping.app.ui.theme.QingAmountIncome
import com.bookkeeping.app.ui.theme.QingAmountExpense
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
import com.bookkeeping.app.ui.components.categoryDisplayName
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
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
    extraNote: String? = null,
    statusBadge: Pair<String, StatusTone>? = null,
    onClick: () -> Unit,
) {
    val model = remember(transaction, timeText) { LedgerRowUiModel.from(transaction, timeText) }
    LedgerTransactionRow(
        model, scrollTargetTransactionId, highlightedTransactionId,
        onScrollTargetReached, onHighlightFinished, extraNote, statusBadge, onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LedgerTransactionRow(
    model: LedgerRowUiModel,
    scrollTargetTransactionId: Long? = null,
    highlightedTransactionId: Long? = null,
    onScrollTargetReached: (Long) -> Unit = {},
    onHighlightFinished: (Long) -> Unit = {},
    extraNote: String? = null,
    statusBadge: Pair<String, StatusTone>? = null,
    onClick: () -> Unit,
) {
    val category = model.category
    val type = model.type
    val sourceTag = tr(model.sourceTagKey)
    val isScrollTarget = scrollTargetTransactionId == model.id
    val bringIntoViewRequester = if (isScrollTarget) remember(model.id) { BringIntoViewRequester() } else null
    val highlightAlpha = remember(model.id) { mutableFloatStateOf(0f) }
    val reduced = rememberReduceMotion()
    val highlightColor = MaterialTheme.colorScheme.primaryContainer

    if (bringIntoViewRequester != null) {
        LaunchedEffect(model.id) {
            bringIntoViewRequester.bringIntoView()
            onScrollTargetReached(model.id)
        }
    }
    if (highlightedTransactionId == model.id) {
        LaunchedEffect(model.id, reduced) {
            highlightAlpha.floatValue = 0.32f
            try {
                delay(1_100L)
                if (!reduced) Animatable(0.32f).animateTo(0f, tween(250)) {
                    highlightAlpha.floatValue = value
                }
                onHighlightFinished(model.id)
            } finally {
                highlightAlpha.floatValue = 0f
            }
        }
    }
    val haptics = LocalHapticFeedback.current
    val amount = model.amount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .drawBehind {
                if (highlightAlpha.floatValue > 0f) drawRoundRect(highlightColor.copy(alpha = highlightAlpha.floatValue),
                    cornerRadius = CornerRadius(14.dp.toPx()))
            }
            .then(if (bringIntoViewRequester != null) Modifier.bringIntoViewRequester(bringIntoViewRequester) else Modifier)
            .clickable(interactionSource = null, indication = ripple(), onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            })
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MerchantAvatar(
            merchant = model.merchant,
            merchantIconKey = model.merchantIconKey,
            merchantIconPath = model.merchantIconPath,
            category = category,
            size = 40.dp,
            cornerRadius = 12.dp,
            categoryKey = model.categoryKey,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = model.merchant.ifBlank { tr(categoryDisplayName(model.categoryKey)) },
                style = QingTypeTokens.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (model.eventTimeNeedsConfirmation) {
                    SoftBadge(text = tr("时间待确认"), tone = StatusTone.WARNING)
                }
                Text(
                    text = "$sourceTag · " + transactionSubtitle(
                        categoryLabel = tr(categoryDisplayName(model.categoryKey)),
                        sourceLabel = ledgerTransactionSourceLabel(model.source),
                        account = model.account,
                        timeText = model.timeText,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!extraNote.isNullOrBlank() || statusBadge != null) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    statusBadge?.let { (label, tone) ->
                        SoftBadge(text = label, tone = tone)
                    }
                    extraNote?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
        AutoSizeAmountText(
            text = (if (type == TransactionType.INCOME) "+" else "−") + amount,
            style = QingTypeTokens.rowAmount,
            modifier = Modifier.widthIn(min = 80.dp),
            minimumSizeSp = 14f,
            fontWeight = FontWeight.SemiBold,
            color = if (type == TransactionType.INCOME) QingAmountIncome else QingAmountExpense,
            textAlign = TextAlign.End,
        )
    }
}

/** 来源标注：每笔都显示一个来源，优先看扫描/导入前缀，其次看来源字段。 */
internal fun ledgerTransactionSourceTag(transaction: TransactionEntity): String = tr(ledgerSourceTagKey(transaction))

private fun ledgerSourceTagKey(transaction: TransactionEntity): String {
    val prefix = LEDGER_SOURCE_TAG.find(transaction.note)?.groupValues?.getOrNull(1)
    return when {
        transaction.captureKind != null || prefix == "扫描" -> "扫描"
        prefix == "导入" -> "导入"
        transaction.source == "BANK_SMS" -> "短信"
        transaction.source == "ALIPAY" || transaction.source == "WECHAT" -> "通知"
        transaction.source == "MANUAL" -> "手动"
        else -> "其他来源"
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

/** Presentation-only value: no sync metadata, database notes, or evidence bytes.
 * Construct on Default for the ledger. Translation still happens at draw time.
 */
@Immutable
internal data class LedgerRowUiModel(
    val id: Long,
    val merchant: String,
    val merchantIconKey: String?,
    val merchantIconPath: String?,
    val category: TransactionCategory,
    val categoryKey: String = category.name,
    val type: TransactionType,
    val source: String,
    val sourceTagKey: String,
    val account: String?,
    val timeText: String,
    val eventTimeNeedsConfirmation: Boolean,
    val amount: String,
) {
    companion object {
        fun from(transaction: TransactionEntity, timeText: String): LedgerRowUiModel = LedgerRowUiModel(
            id = transaction.id,
            merchant = transaction.merchant,
            merchantIconKey = transaction.merchantIconKey,
            merchantIconPath = transaction.merchantIconPath,
            category = runCatching { TransactionCategory.valueOf(transaction.category) }.getOrDefault(TransactionCategory.OTHER),
            categoryKey = transaction.category,
            type = runCatching { TransactionType.valueOf(transaction.type) }.getOrDefault(TransactionType.EXPENSE),
            source = transaction.source,
            sourceTagKey = ledgerSourceTagKey(transaction),
            account = transaction.account,
            timeText = timeText,
            eventTimeNeedsConfirmation = transaction.eventTimeNeedsConfirmation,
            amount = if (transaction.currency == "CNY") formatCny(transaction.amountCents)
                else transaction.currency + " " + "%.2f".format(Locale.CHINA, transaction.amountCents / 100.0),
        )
    }
}
