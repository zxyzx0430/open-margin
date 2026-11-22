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

    fun shouldFlag(existing: TransactionEntity?, candidate: TransactionEntity): Boolean {
        if (existing == null) return false
        return existing.amountCents == candidate.amountCents &&
            existing.type == candidate.type &&
            existing.currency == candidate.currency &&
            abs(existing.timestamp - candidate.timestamp) <= WINDOW_MS
    }
}
