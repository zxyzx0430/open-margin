package com.bookkeeping.app.notification.capture

/**
 * Single decision point for whether a capture pass may write the ledger.
 *
 * The screenshot-import preview path and any capture that is not allowed to
 * auto-book must pass through here. Keeping it as a pure object makes the
 * "preview writes nothing" rule testable without Android.
 */
object CaptureBookGate {
    enum class Decision {
        /** Candidate may go through the normal deduplicating repository path. */
        WRITE_LEDGER,

        /** Candidate is only stored locally for review; the ledger stays untouched. */
        HOLD_FOR_REVIEW,
    }

    fun decide(allowAuto: Boolean, previewOnly: Boolean): Decision =
        if (allowAuto && !previewOnly) Decision.WRITE_LEDGER else Decision.HOLD_FOR_REVIEW

    fun mayWriteLedger(allowAuto: Boolean, previewOnly: Boolean = false): Boolean =
        decide(allowAuto, previewOnly) == Decision.WRITE_LEDGER

    /** User confirmation is not permission to turn a preview into a ledger write. */
    fun mayConfirm(candidate: CaptureCandidate): Boolean = candidate.ledgerWriteAllowed &&
        candidate.direction in setOf(CaptureCandidate.DIRECTION_INCOME, CaptureCandidate.DIRECTION_EXPENSE)
}
