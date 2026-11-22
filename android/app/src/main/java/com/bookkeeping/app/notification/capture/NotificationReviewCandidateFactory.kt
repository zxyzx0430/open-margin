package com.bookkeeping.app.notification.capture

import com.bookkeeping.app.data.local.entity.TransactionEntity

/** Builds a user-actionable inbox card without retaining raw notification/SMS text. */
object NotificationReviewCandidateFactory {
    fun create(
        transaction: TransactionEntity,
        seenAtMs: Long,
        reason: String,
        diagnosticAttemptId: String? = null,
        matchedTransactionId: Long? = null,
    ): CaptureCandidate? {
        if (transaction.amountCents <= 0L || seenAtMs <= 0L) return null
        val source = transaction.source.takeIf { it in CaptureCandidate.TRANSACTION_SOURCES }
            ?: "OTHER"
        val packageName = when (source) {
            "WECHAT" -> CaptureCandidate.WECHAT
            "ALIPAY" -> CaptureCandidate.ALIPAY
            else -> null
        }
        val intakeMethod = if (source == "BANK_SMS") {
            CaptureCandidate.INTAKE_SMS
        } else {
            CaptureCandidate.INTAKE_NOTIFICATION
        }
        val merchant = transaction.merchant.trim().takeIf(String::isNotEmpty)
            ?: transaction.account?.trim()?.takeIf(String::isNotEmpty)
            ?: when (source) {
                "WECHAT" -> "微信支付"
                "ALIPAY" -> "支付宝"
                "BANK_SMS" -> "银行短信"
                else -> "其他来源"
            }
        val kind = transaction.captureKind?.takeIf { it in CaptureCandidate.KINDS }
            ?: CaptureCandidate.KIND_PAYMENT
        val direction = when (transaction.type) {
            "INCOME" -> CaptureCandidate.DIRECTION_INCOME
            "EXPENSE" -> CaptureCandidate.DIRECTION_EXPENSE
            else -> CaptureCandidate.DIRECTION_UNKNOWN
        }
        val candidate = CaptureCandidate(
            packageName = packageName,
            pageIdentity = "review:$source:$seenAtMs",
            amountCents = transaction.amountCents,
            merchantCandidates = listOf(merchant),
            orderId = transaction.sourceTransactionId?.trim()?.takeIf(String::isNotEmpty),
            capturedAtMs = seenAtMs,
            screenshotPath = null,
            confidence = 0.7f,
            reason = reason.ifBlank { "transaction identity needs review" }.take(80),
            kind = kind,
            eventTimeMs = transaction.timestamp.takeIf { it > 0L } ?: seenAtMs,
            direction = direction,
            diagnosticAttemptId = diagnosticAttemptId,
            intakeMethod = intakeMethod,
            paymentPlatform = CaptureCandidate.platformFromPackage(packageName),
            confidenceTier = CaptureCandidate.TIER_MEDIUM,
            completionStatus = CaptureCandidate.COMPLETION_COMPLETED,
            eventTimeSource = CaptureCandidate.TIME_SOURCE_ACTUAL,
            eventTimeNeedsConfirmation = transaction.eventTimeNeedsConfirmation,
            completionExplicit = true,
            directionExplicit = direction != CaptureCandidate.DIRECTION_UNKNOWN,
            matchedTransactionId = matchedTransactionId,
            ledgerWriteAllowed = true,
            transactionSource = source,
            transactionCategory = transaction.category.takeIf {
                it in CaptureCandidate.TRANSACTION_CATEGORIES
            },
            transactionAccount = transaction.account?.takeIf(String::isNotBlank),
            transactionCurrency = transaction.currency.takeIf { it.matches(Regex("[A-Z]{3}")) },
        )
        return candidate.takeIf(CaptureCandidate::isValid)
    }
}
