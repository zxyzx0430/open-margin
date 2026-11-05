package com.bookkeeping.app.sync

import com.bookkeeping.app.data.local.entity.TransactionEntity

/** The exact fields the server accepts for a legacy v1 upload. */
data class UploadEventFields(
    val eventId: String,
    val timestampMs: Long,
    val amountCents: Long,
    val currency: String,
    val direction: String,
    val source: String,
    val category: String,
    val merchant: String,
)

/**
 * The exact fields the server accepts for a v2 record.
 * `note` and `account` are intentionally absent: raw notification/SMS text
 * and bank/card hints must never leave the phone.
 */
data class SyncRecordFields(
    val timestampMs: Long,
    val amountCents: Long,
    val currency: String,
    val direction: String,
    val source: String,
    val category: String,
    val merchant: String,
    val reviewStatus: String?,
)

object UploadPayload {
    val SOURCES = setOf("WECHAT", "ALIPAY", "BANK_SMS", "MANUAL", "OTHER")
    val DIRECTIONS = setOf("EXPENSE", "INCOME")
    val REVIEW_STATUSES = setOf(
        "unverified", "confirmed", "excluded", "transfer", "possible_duplicate",
    )

    fun isUploadable(entity: TransactionEntity): Boolean =
        entity.amountCents > 0L &&
            entity.type in DIRECTIONS &&
            entity.source.uppercase() in SOURCES

    fun v1Event(entity: TransactionEntity, installationId: String): UploadEventFields =
        UploadEventFields(
            eventId = "android:$installationId:${entity.id}",
            timestampMs = entity.timestamp,
            amountCents = entity.amountCents,
            currency = entity.currency,
            direction = entity.type,
            source = entity.source.uppercase(),
            category = entity.category,
            merchant = entity.merchant.take(140),
        )

    fun v2Record(entity: TransactionEntity): SyncRecordFields =
        SyncRecordFields(
            timestampMs = entity.timestamp,
            amountCents = entity.amountCents,
            currency = entity.currency,
            direction = entity.type,
            source = entity.source.uppercase(),
            category = entity.category,
            merchant = entity.merchant.take(140),
            reviewStatus = entity.remoteReviewStatus.takeIf { it in REVIEW_STATUSES },
        )
}
