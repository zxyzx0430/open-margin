package com.bookkeeping.app.notification.capture

/** Deterministic, explainable gates; this does not claim empirical OCR accuracy. */
object ScreenshotCapturePolicy {
    enum class DuplicateResult { CLEAR, EXACT_MATCH, AMBIGUOUS }
    enum class Action { AUTO_BOOK, ASK_USER, WAIT_FOR_EVIDENCE, LINK_EXISTING, IGNORE }

    /**
     * Kinds that move money between accounts or reverse an earlier payment.
     * A payment screenshot proves the page, not the ledger semantics: a
     * "received transfer" arrives on someone else's page and a refund is not
     * ordinary income. These are never auto booked, however high the
     * confidence; the user confirms them one at a time.
     */
    val CONFIRM_ONLY_KINDS = setOf(
        CaptureCandidate.KIND_TRANSFER,
        CaptureCandidate.KIND_REFUND,
        CaptureCandidate.KIND_RED_PACKET,
        CaptureCandidate.KIND_WITHDRAWAL,
    )

    data class Evidence(
        val supportedPaymentPage: Boolean,
        val paymentPlatformKnown: Boolean,
        val transactionLike: Boolean,
        val amountCents: Long?,
        val amountUnambiguous: Boolean,
        val direction: String,
        val directionExplicit: Boolean,
        val completionStatus: String,
        val completionExplicit: Boolean,
        val kind: String = CaptureCandidate.KIND_PAYMENT,
        val conflictingFields: Boolean = false,
        val duplicateResult: DuplicateResult = DuplicateResult.CLEAR,
    )

    data class Assessment(
        val tier: String,
        val action: Action,
    )

    fun assess(
        evidence: Evidence,
        allowAuto: Boolean,
        previewOnly: Boolean = false,
    ): Assessment {
        val completion = evidence.completionStatus
        if (completion in NON_FINAL_STATUSES) {
            return Assessment(CaptureCandidate.TIER_LOW, Action.WAIT_FOR_EVIDENCE)
        }
        if (evidence.duplicateResult == DuplicateResult.EXACT_MATCH) {
            return Assessment(CaptureCandidate.TIER_HIGH, Action.LINK_EXISTING)
        }
        val usefulCandidate = evidence.transactionLike &&
            evidence.amountCents?.let { it in 1..CaptureCandidate.MAX_CENTS } == true &&
            evidence.amountUnambiguous &&
            evidence.direction in setOf(
                CaptureCandidate.DIRECTION_EXPENSE,
                CaptureCandidate.DIRECTION_INCOME,
            ) && evidence.directionExplicit && !evidence.conflictingFields
        if (!usefulCandidate) return Assessment(CaptureCandidate.TIER_LOW, Action.IGNORE)

        val high = evidence.supportedPaymentPage && evidence.paymentPlatformKnown &&
            completion == CaptureCandidate.COMPLETION_COMPLETED &&
            evidence.completionExplicit && evidence.duplicateResult == DuplicateResult.CLEAR
        val tier = if (high) CaptureCandidate.TIER_HIGH else CaptureCandidate.TIER_MEDIUM
        val action = when {
            // A completed transfer / refund / red packet / withdrawal still
            // only describes money the page claims to have moved. Booking it
            // automatically would turn a counterparty's page or a reversal
            // into ordinary income or expense, so it always asks first.
            requiresConfirmation(evidence.kind) -> Action.ASK_USER
            high && allowAuto && !previewOnly -> Action.AUTO_BOOK
            else -> Action.ASK_USER
        }
        return Assessment(tier, action)
    }

    /** True for kinds that may only reach the ledger after the user confirms. */
    fun requiresConfirmation(kind: String): Boolean = kind in CONFIRM_ONLY_KINDS

    private val NON_FINAL_STATUSES = setOf(
        CaptureCandidate.COMPLETION_PENDING,
        CaptureCandidate.COMPLETION_CREATED,
        CaptureCandidate.COMPLETION_CANCELLED,
        CaptureCandidate.COMPLETION_FAILED,
    )
}
