package com.bookkeeping.app.notification.capture

/** Deterministic, explainable gates; this does not claim empirical OCR accuracy. */
object ScreenshotCapturePolicy {
    enum class DuplicateResult { CLEAR, EXACT_MATCH, AMBIGUOUS }
    enum class Action { AUTO_BOOK, ASK_USER, WAIT_FOR_EVIDENCE, LINK_EXISTING, IGNORE }

    /**
     * Kinds that move money in a way a payment page cannot prove.
     *
     * A refund is not ordinary income, a red packet is not a merchant payment
     * and a withdrawal moves money between the user's own accounts. These are
     * never auto booked, however high the confidence; the user confirms them
     * one at a time.
     *
     * Transfers are deliberately **not** in this set any more. A completed
     * transfer that names its own direction (已被接收 / 已收款) and comes from a
     * known platform is a settled payment, so [transferAutoBookAllowed] lets it
     * through. Anything weaker — 待接收, unknown completion, unproven direction
     * — still goes to [Action.ASK_USER] through that same function.
     */
    val CONFIRM_ONLY_KINDS = setOf(
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
            // A refund / red packet / withdrawal still only describes money the
            // page claims to have moved. Booking it automatically would turn a
            // reversal into ordinary income or a transfer into a merchant
            // payment, so it always asks first.
            requiresConfirmation(evidence.kind) -> Action.ASK_USER
            // Transfers are the one kind that may auto book, and only when the
            // page itself proves a settled payment in a known direction.
            evidence.kind == CaptureCandidate.KIND_TRANSFER &&
                !transferAutoBookAllowed(evidence) -> Action.ASK_USER
            high && allowAuto && !previewOnly -> Action.AUTO_BOOK
            else -> Action.ASK_USER
        }
        return Assessment(tier, action)
    }

    /**
     * 已完成、方向、平台三者都显式时，转账才允许自动入账。
     *
     * 待接收（方向未知、未完成）、状态不明、状态词与气泡位置矛盾（方向被置为
     * UNKNOWN）、平台未知的转账都返回 false，继续走人工确认；`allowAuto` /
     * `previewOnly` 仍由 [assess] 单独把关，不在这里放宽。
     */
    fun transferAutoBookAllowed(evidence: Evidence): Boolean =
        evidence.kind == CaptureCandidate.KIND_TRANSFER &&
            evidence.completionStatus == CaptureCandidate.COMPLETION_COMPLETED &&
            evidence.completionExplicit &&
            evidence.directionExplicit &&
            evidence.paymentPlatformKnown

    /** True for kinds that may only reach the ledger after the user confirms. */
    fun requiresConfirmation(kind: String): Boolean = kind in CONFIRM_ONLY_KINDS

    private val NON_FINAL_STATUSES = setOf(
        CaptureCandidate.COMPLETION_PENDING,
        CaptureCandidate.COMPLETION_CREATED,
        CaptureCandidate.COMPLETION_CANCELLED,
        CaptureCandidate.COMPLETION_FAILED,
    )
}
