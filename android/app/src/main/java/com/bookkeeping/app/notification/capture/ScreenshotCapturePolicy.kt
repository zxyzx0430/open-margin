package com.bookkeeping.app.notification.capture

/** Deterministic, explainable gates; this does not claim empirical OCR accuracy. */
object ScreenshotCapturePolicy {
    enum class DuplicateResult { CLEAR, EXACT_MATCH, AMBIGUOUS }
    enum class Action { AUTO_BOOK, ASK_USER, WAIT_FOR_EVIDENCE, LINK_EXISTING, IGNORE }

    /** No transaction kind is a confirmation gate by itself. */
    val CONFIRM_ONLY_KINDS: Set<String> = emptySet()

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
        /**
         * True when completion is inferred from a settled bill-list row rather
         * than a status word. It is retained as evidence provenance; the settled
         * row is sufficient for any transaction kind, including transfers.
         */
        val completionInferredFromList: Boolean = false,
        val kind: String = CaptureCandidate.KIND_PAYMENT,
        val conflictingFields: Boolean = false,
        val duplicateResult: DuplicateResult = DuplicateResult.CLEAR,
    )

    data class Assessment(
        val tier: String,
        val action: Action,
    )

    /**
     * Admission gate for one draft.
     *
     * Amount and explicit direction are the core transaction facts. Missing or
     * conflicting core facts stay reviewable but cannot be written; absent
     * merchant/category/account/platform/exact time does not block a completed
     * transaction. Weak duplicate evidence can ask about that candidate, but
     * cannot link or suppress it.
     */
    fun assess(
        evidence: Evidence,
        allowAuto: Boolean,
        previewOnly: Boolean = false,
    ): Assessment {
        val completion = evidence.completionStatus
        if (completion in NON_FINAL_STATUSES) {
            return Assessment(CaptureCandidate.TIER_LOW, Action.WAIT_FOR_EVIDENCE)
        }
        if (!evidence.transactionLike) return Assessment(CaptureCandidate.TIER_LOW, Action.IGNORE)
        if (evidence.amountCents != null && evidence.amountCents !in 1..CaptureCandidate.MAX_CENTS) {
            return Assessment(CaptureCandidate.TIER_LOW, Action.IGNORE)
        }
        if (evidence.amountCents == null || !evidence.amountUnambiguous || evidence.conflictingFields) {
            return Assessment(CaptureCandidate.TIER_MEDIUM, Action.ASK_USER)
        }
        val directionReadable = evidence.direction in setOf(
            CaptureCandidate.DIRECTION_EXPENSE,
            CaptureCandidate.DIRECTION_INCOME,
        ) && evidence.directionExplicit
        if (!directionReadable) {
            return Assessment(CaptureCandidate.TIER_MEDIUM, Action.ASK_USER)
        }
        if (evidence.duplicateResult == DuplicateResult.EXACT_MATCH) {
            return Assessment(CaptureCandidate.TIER_HIGH, Action.LINK_EXISTING)
        }

        val high = evidence.supportedPaymentPage && evidence.paymentPlatformKnown &&
            completion == CaptureCandidate.COMPLETION_COMPLETED &&
            (evidence.completionExplicit || evidence.completionInferredFromList) &&
            evidence.duplicateResult == DuplicateResult.CLEAR
        val tier = if (high) CaptureCandidate.TIER_HIGH else CaptureCandidate.TIER_MEDIUM
        val action = when {
            evidence.completionStatus != CaptureCandidate.COMPLETION_COMPLETED -> Action.ASK_USER
            evidence.duplicateResult == DuplicateResult.AMBIGUOUS -> Action.ASK_USER
            allowAuto && !previewOnly -> Action.AUTO_BOOK
            else -> Action.ASK_USER
        }
        return Assessment(tier, action)
    }

    /**
     * Transfer helper kept for callers that need a kind-specific predicate.
     * Completion can be explicit or inferred from a settled bill-list row, and
     * platform metadata is optional; only a clear direction and completed state
     * are required. [assess] still enforces amount, conflict, duplicate, preview
     * and allow-auto gates.
     */
    fun transferAutoBookAllowed(evidence: Evidence): Boolean =
        evidence.kind == CaptureCandidate.KIND_TRANSFER &&
            evidence.completionStatus == CaptureCandidate.COMPLETION_COMPLETED &&
            evidence.direction in setOf(
                CaptureCandidate.DIRECTION_EXPENSE,
                CaptureCandidate.DIRECTION_INCOME,
            ) &&
            evidence.directionExplicit

    /** Always false for transaction kinds; confirmation is based on evidence. */
    fun requiresConfirmation(kind: String): Boolean = kind in CONFIRM_ONLY_KINDS

    /**
     * Completion states that mean the money has not settled (or never will).
     * Shared with [CaptureBookGate.mayConfirm] so the two gates cannot drift.
     */
    internal val NON_FINAL_STATUSES = setOf(
        CaptureCandidate.COMPLETION_PENDING,
        CaptureCandidate.COMPLETION_CREATED,
        CaptureCandidate.COMPLETION_CANCELLED,
        CaptureCandidate.COMPLETION_FAILED,
    )
}
