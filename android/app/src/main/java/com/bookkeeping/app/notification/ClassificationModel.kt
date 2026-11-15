package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory

/** Direction the user's money moved, independent of the ledger row type. */
enum class ClassificationDirection { EXPENSE, INCOME, NEUTRAL }

/** What the source says the event is. */
enum class ClassificationKind {
    PAYMENT,
    BILL,
    TRANSFER,
    REFUND,
    WITHDRAWAL,
    RED_PACKET,
    PAY_AFTER_USE,
    UNKNOWN,
}

enum class ClassificationStatus { COMPLETED, PENDING, CREATED, CANCELLED, FAILED, UNKNOWN }

enum class ClassificationSource { WECHAT, ALIPAY, BANK_SMS, MANUAL, OTHER }

/**
 * One source event in the shape the classifier understands. Every capture
 * path builds this object; the classifier never reads a notification, bill row
 * or page node directly.
 */
data class ClassificationInput(
    val merchantRaw: String = "",
    val contextText: String = "",
    val direction: ClassificationDirection = ClassificationDirection.EXPENSE,
    val source: ClassificationSource = ClassificationSource.OTHER,
    val kind: ClassificationKind = ClassificationKind.PAYMENT,
    val status: ClassificationStatus = ClassificationStatus.COMPLETED,
    /** Official source category, e.g. Alipay "餐饮美食". */
    val sourceCategory: String? = null,
    val amountCents: Long? = null,
)

data class ClassificationResult(
    val category: TransactionCategory,
    val confidence: Float,
    val ruleId: String,
    val reason: String,
    val alternatives: List<Pair<TransactionCategory, Float>> = emptyList(),
)
