package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import com.bookkeeping.app.notification.CategoryClassifier
import com.bookkeeping.app.notification.ClassificationDirection
import com.bookkeeping.app.notification.ClassificationInput
import com.bookkeeping.app.notification.ClassificationKind
import com.bookkeeping.app.notification.ClassificationResult
import com.bookkeeping.app.notification.ClassificationSource
import com.bookkeeping.app.notification.ClassificationStatus
import com.bookkeeping.app.notification.capture.CaptureCandidate

object CaptureReviewMapping {
    fun classificationFor(
        candidate: CaptureCandidate,
        merchant: String,
        contextText: String = "",
    ): ClassificationResult {
        val kind = when (candidate.kind) {
            CaptureCandidate.KIND_REFUND -> ClassificationKind.REFUND
            CaptureCandidate.KIND_TRANSFER -> ClassificationKind.TRANSFER
            CaptureCandidate.KIND_WITHDRAWAL -> ClassificationKind.WITHDRAWAL
            CaptureCandidate.KIND_RED_PACKET -> ClassificationKind.RED_PACKET
            CaptureCandidate.KIND_AUTO_PAY -> ClassificationKind.PAY_AFTER_USE
            CaptureCandidate.KIND_BILL -> ClassificationKind.BILL
            else -> ClassificationKind.PAYMENT
        }
        val direction = when (candidate.direction) {
            CaptureCandidate.DIRECTION_INCOME -> ClassificationDirection.INCOME
            CaptureCandidate.DIRECTION_EXPENSE -> ClassificationDirection.EXPENSE
            else -> ClassificationDirection.NEUTRAL
        }
        val source = when (candidate.packageName) {
            CaptureCandidate.WECHAT -> ClassificationSource.WECHAT
            CaptureCandidate.ALIPAY -> ClassificationSource.ALIPAY
            else -> ClassificationSource.OTHER
        }
        return CategoryClassifier.classify(
            ClassificationInput(
                merchantRaw = merchant,
                contextText = contextText,
                direction = direction,
                source = source,
                kind = kind,
                status = ClassificationStatus.COMPLETED,
                amountCents = candidate.amountCents,
            ),
        )
    }

    fun defaultCategory(candidate: CaptureCandidate): TransactionCategory =
        classificationFor(
            candidate = candidate,
            merchant = candidate.merchantCandidates.firstOrNull().orEmpty(),
        ).category

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
