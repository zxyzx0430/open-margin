package com.bookkeeping.app.notification

/** Pure policy for deciding whether a notification-record key can prove a replay. */
object NoticeReplayPolicy {

    enum class RecordKeyDecision {
        /** This key has not been seen; it may be recorded as a new notice link. */
        NEW_RECORD,

        /** The linked ledger row has this exact platform-scoped transaction ID. */
        EXACT_TRANSACTION,

        /** A trustworthy transaction ID proves the incoming payment differs. */
        DISTINCT_TRANSACTION,

        /** The same record key could be a refresh or a different payment. */
        AMBIGUOUS_REUSE,
    }

    fun classifyRecordKey(
        previousRecordExists: Boolean,
        hasTrustedTransactionId: Boolean,
        previousLinkMatchesTransactionId: Boolean,
    ): RecordKeyDecision = when {
        !previousRecordExists -> RecordKeyDecision.NEW_RECORD
        hasTrustedTransactionId && previousLinkMatchesTransactionId ->
            RecordKeyDecision.EXACT_TRANSACTION
        hasTrustedTransactionId -> RecordKeyDecision.DISTINCT_TRANSACTION
        else -> RecordKeyDecision.AMBIGUOUS_REUSE
    }

    /** A linked transaction-identity claim is idempotent independent of notice body or time. */
    fun isSamePlatformTransaction(
        previousFingerprint: String?,
        incomingFingerprint: String?,
        linkedTransactionId: Long?,
    ): Boolean = previousFingerprint != null &&
        previousFingerprint == incomingFingerprint &&
        linkedTransactionId != null
}
