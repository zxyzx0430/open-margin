package com.bookkeeping.app.notification.accessibility

import com.bookkeeping.app.data.repository.DuplicateReason
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore

/** Persists the recoverable page candidate for a weak cross-channel resemblance. */
object PageCaptureReviewPolicy {
    fun holdPossibleDuplicate(
        result: LedgerInsertResult,
        candidate: CaptureCandidate,
        store: CaptureCandidateStore,
    ): CaptureCandidate? {
        val duplicate = result as? LedgerInsertResult.Duplicate ?: return null
        if (duplicate.reason != DuplicateReason.CROSS_SOURCE_WEAK) return null
        val reviewCandidate = candidate.copy(
            reason = CaptureCandidate.REASON_LIKELY_DUPLICATE,
            matchedTransactionId = duplicate.existingTransactionId,
        )
        return reviewCandidate.takeIf {
            store.mark(it, CaptureCandidate.STATUS_PENDING)
        }
    }
}
