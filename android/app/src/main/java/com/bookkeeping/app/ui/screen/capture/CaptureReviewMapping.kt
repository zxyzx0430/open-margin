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
        val name = merchant.trim().ifBlank { "未知商户" }
        val note = buildString {
            append("[扫描] ")
            append(
                when (candidate.packageName) {
                    CaptureCandidate.WECHAT -> "微信"
                    CaptureCandidate.ALIPAY -> "支付宝"
                    else -> "页面"
                },
            )
            candidate.orderId?.let { append(" · 单号 ").append(it) }
        }
        return TransactionEntity(
            amountCents = candidate.amountCents,
            type = TransactionType.EXPENSE.name,
            category = category.name,
            merchant = name,
            note = note,
            source = source.name,
            timestamp = candidate.capturedAtMs,
            merchantIconKey = MerchantIconCatalog.forMerchant(name)?.key,
        )
    }
}
