package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlin.math.abs

/**
 * Local-only rule for "similar money, but not enough evidence to auto-merge".
 * Similar amount, merchant and time never suppress a same-source ledger row;
 * when this rule matches, the new row is retained and marked for review.
 */
object DuplicateReviewPolicy {
    const val WINDOW_MS: Long = 3 * 60_000L
    const val SAME_SOURCE_BURST_WINDOW_MS: Long = 15_000L

    fun shouldFlag(existing: TransactionEntity?, candidate: TransactionEntity): Boolean {
        if (existing == null) return false
        return existing.amountCents == candidate.amountCents &&
            existing.type == candidate.type &&
            existing.currency == candidate.currency &&
            abs(existing.timestamp - candidate.timestamp) <= WINDOW_MS
    }

    /**
     * Same-source, same-merchant, same-amount burst within seconds without an order ID
     * (e.g. accessibility window-content events re-firing while staying on one bill page).
     * Suppresses duplicate insertion instead of stacking identical `possible_duplicate` rows.
     */
    fun isSameSourceBurstDuplicate(
        existing: TransactionEntity?,
        candidate: TransactionEntity,
    ): Boolean {
        if (existing == null) return false
        if (candidate.source == "MANUAL") return false
        if (existing.source != candidate.source) return false
        if (!candidate.sourceTransactionId.isNullOrBlank()) return false
        return existing.amountCents == candidate.amountCents &&
            existing.type == candidate.type &&
            existing.currency == candidate.currency &&
            existing.merchant.trim().equals(candidate.merchant.trim(), ignoreCase = true) &&
            abs(existing.timestamp - candidate.timestamp) <= SAME_SOURCE_BURST_WINDOW_MS
    }
}
