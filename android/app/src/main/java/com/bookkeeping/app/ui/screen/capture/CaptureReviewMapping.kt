package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import com.bookkeeping.app.notification.capture.CaptureCandidate

object CaptureReviewMapping {
    fun toTransactionEntity(
        candidate: CaptureCandidate,
        merchant: String,
        category: TransactionCategory,
    ): TransactionEntity {
        val source = when (candidate.packageName) {
            CaptureCandidate.WECHAT -> TransactionSource.WECHAT
            CaptureCandidate.ALIPAY -> TransactionSource.ALIPAY
            else -> TransactionSource.OTHER
        }
        val sourceLabel = when (candidate.packageName) {
            CaptureCandidate.WECHAT -> "微信"
            CaptureCandidate.ALIPAY -> "支付宝"
            else -> "页面"
        }
        val refund = candidate.kind == CaptureCandidate.KIND_REFUND
        val income = refund || candidate.direction == CaptureCandidate.DIRECTION_INCOME
        val redPacketIncome = candidate.kind == CaptureCandidate.KIND_RED_PACKET && income
        val transferLike = candidate.kind == CaptureCandidate.KIND_TRANSFER ||
            candidate.kind == CaptureCandidate.KIND_WITHDRAWAL ||
            (candidate.kind == CaptureCandidate.KIND_RED_PACKET && !income)
        val name = merchant.trim().ifBlank {
            when (candidate.kind) {
                CaptureCandidate.KIND_REFUND -> "${sourceLabel}退款"
                CaptureCandidate.KIND_TRANSFER -> "${sourceLabel}转账"
                CaptureCandidate.KIND_RED_PACKET -> "${sourceLabel}红包"
                CaptureCandidate.KIND_WITHDRAWAL -> "${sourceLabel}提现"
                else -> "未知商户"
            }
        }
        val note = buildString {
            append(
                when (candidate.kind) {
                    CaptureCandidate.KIND_REFUND -> "[退款] "
                    CaptureCandidate.KIND_TRANSFER -> "[转账] "
                    CaptureCandidate.KIND_RED_PACKET -> "[红包] "
                    CaptureCandidate.KIND_WITHDRAWAL -> "[提现] "
                    CaptureCandidate.KIND_AUTO_PAY -> "[自动支付] "
                    CaptureCandidate.KIND_BILL -> "[账单] "
                    else -> "[扫描] "
                },
            )
            append(sourceLabel)
            when (candidate.kind) {
                CaptureCandidate.KIND_TRANSFER,
                CaptureCandidate.KIND_RED_PACKET -> append(if (income) " · 收入" else " · 支出")
                else -> Unit
            }
            candidate.orderId?.let { append(" · 单号 ").append(it) }
        }
        return TransactionEntity(
            amountCents = candidate.amountCents,
            type = if (income) TransactionType.INCOME.name else TransactionType.EXPENSE.name,
            category = when {
                refund -> TransactionCategory.OTHER.name
                redPacketIncome -> TransactionCategory.GIFT.name
                transferLike -> TransactionCategory.TRANSFER.name
                else -> category.name
            },
            merchant = name,
            note = note,
            source = source.name,
            timestamp = candidate.eventTimeMs.takeIf { it > 0L } ?: candidate.capturedAtMs,
            merchantIconKey = MerchantIconCatalog.forMerchant(name)?.key,
            remoteReviewStatus = when {
                refund -> "refund"
                transferLike -> "transfer"
                else -> "unverified"
            },
            sourceTransactionId = candidate.orderId,
            captureConfidence = candidate.confidence,
            captureKind = candidate.kind,
            captureObservedAtMs = candidate.capturedAtMs,
        )
    }
}
