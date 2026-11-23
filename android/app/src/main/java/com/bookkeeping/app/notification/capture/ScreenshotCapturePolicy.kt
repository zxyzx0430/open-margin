package com.bookkeeping.app.notification.capture

/** Deterministic, explainable gates; this does not claim empirical OCR accuracy. */
object ScreenshotCapturePolicy {
    enum class DuplicateResult { CLEAR, EXACT_MATCH, AMBIGUOUS }
    enum class Action { AUTO_BOOK, ASK_USER, WAIT_FOR_EVIDENCE, LINK_EXISTING, IGNORE }

    /**
     * Kinds that are unconditionally held for manual confirmation regardless of
     * confidence. Currently empty: completed refunds, red packets, and
     * withdrawals with an explicit direction and known platform now follow the
     * normal high-confidence auto-book gate; incomplete or ambiguous ones still
     * wait for evidence or ask the user.
     */
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
         * True when the completed state was inferred from the page's layout
         * (one row of a settled bill list) rather than read from a status word.
         * For an ordinary payment that is enough; for a transfer it is not,
         * because a list row never prints 已被接收 / 已收款. See
         * [transferAutoBookAllowed].
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
     * Order matters: an unfinished payment waits, an exact duplicate links, and
     * only then is the draft asked to prove it is a transaction at all. A page
     * that shows a usable amount but no readable 支出 / 收入 word is still a
     * transaction — the user is asked to pick the direction instead of the
     * payment being dropped. Anything that fails the transaction-shaped gate
     * (no amount, ambiguous amount, contradictory fields, not transaction-like)
     * is ignored, exactly as before.
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
        if (evidence.duplicateResult == DuplicateResult.EXACT_MATCH) {
            return Assessment(CaptureCandidate.TIER_HIGH, Action.LINK_EXISTING)
        }
        // What the page must prove before it may become a card at all: it has
        // to look like money, the amount has to be singular and in range, and
        // the page must not contradict itself. A scenery photo, a chat
        // screenshot or a page whose two amounts disagree keeps returning
        // IGNORE — those are not transactions and must not become cards.
        val transactionShaped = evidence.transactionLike &&
            evidence.amountCents?.let { it in 1..CaptureCandidate.MAX_CENTS } == true &&
            evidence.amountUnambiguous && !evidence.conflictingFields
        if (!transactionShaped) return Assessment(CaptureCandidate.TIER_LOW, Action.IGNORE)
        val directionReadable = evidence.direction in setOf(
            CaptureCandidate.DIRECTION_EXPENSE,
            CaptureCandidate.DIRECTION_INCOME,
        ) && evidence.directionExplicit
        if (!directionReadable) {
            // 金额、商户、平台都清楚，只是页面上没有 支出 / 收入 / 付款给 / 收款
            // 这类字样：这是识别不出方向的**交易**，不是非交易图。旧的
            // TIER_LOW + IGNORE 会把它静默丢掉，用户既看不到卡也不知道少了一笔；
            // 复核卡本来就能让用户点选方向（[CaptureReviewDirection]），
            // [CaptureBookGate.mayConfirm] 也只放行显式方向，所以这里交给人选一次。
            return if (evidence.direction == CaptureCandidate.DIRECTION_UNKNOWN) {
                Assessment(CaptureCandidate.TIER_MEDIUM, Action.ASK_USER)
            } else {
                // 其它读不出来的取值不是 UI 能提供的选项，仍然不进收件箱。
                Assessment(CaptureCandidate.TIER_LOW, Action.IGNORE)
            }
        }

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
     * 已完成、方向、平台三者都显式、且完成态由页面明说时，转账才允许自动入账。
     *
     * 待接收（方向未知、未完成）、状态不明、状态词与气泡位置矛盾（方向被置为
     * UNKNOWN）、平台未知的转账都返回 false，继续走人工确认；`allowAuto` /
     * `previewOnly` 仍由 [assess] 单独把关，不在这里放宽。
     *
     * [Evidence.completionInferredFromList] 也必须为 false：账单列表行没有状态
     * 词，`COMPLETED` 是从「它在账单列表里」推断出来的。列表能证明一笔普通支付
     * 已成交（列表只列已成交的钱），但转账是否真的被接收，只能由气泡里的
     * 已被接收 / 已收款证明；推断出来的完成态对转账证据不足，仍要用户确认。
     */
    fun transferAutoBookAllowed(evidence: Evidence): Boolean =
        evidence.kind == CaptureCandidate.KIND_TRANSFER &&
            evidence.completionStatus == CaptureCandidate.COMPLETION_COMPLETED &&
            evidence.completionExplicit &&
            !evidence.completionInferredFromList &&
            evidence.directionExplicit &&
            evidence.paymentPlatformKnown

    /** True for kinds that may only reach the ledger after the user confirms. */
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
