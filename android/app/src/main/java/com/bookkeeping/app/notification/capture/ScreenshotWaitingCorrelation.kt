package com.bookkeeping.app.notification.capture

import java.util.Locale

/**
 * Cross-screenshot state evidence.
 *
 * A waiting candidate (unclaimed transfer, created future debit, cancelled or
 * failed payment) is only a placeholder: when a later screenshot arrives with a
 * final outcome for the same transaction, that older candidate must be settled
 * instead of leaving two actionable items, or booking a second ledger row.
 * Anything that is not an unambiguous match is left alone — guessing would merge
 * two genuinely different payments.
 *
 * Pure Kotlin so the "one item, no duplicate" rule is testable without Android.
 */
object ScreenshotWaitingCorrelation {

    /** Same window the store has always used for late evidence. */
    const val CORRELATION_WINDOW_MS = 10L * 60 * 1000

    /**
     * Final outcomes only. A page that is still pending or created says nothing
     * about the earlier placeholder, while a cancelled / failed page does: that
     * order will never move money, so its placeholder must stop waiting.
     */
    fun isSettlingEvidence(candidate: CaptureCandidate): Boolean =
        candidate.completionStatus in SETTLING_STATUSES

    /**
     * The single waiting candidate [incoming] settles, or null when none or more
     * than one candidate could be meant.
     *
     * Positive identification only:
     * 1. platform + exact order id;
     * 2. platform + kind + amount + direction + normalized merchant, inside
     *    [CORRELATION_WINDOW_MS], and only when both sides carry the merchant.
     *
     * An empty merchant is missing evidence, not a wildcard, and a different
     * kind is a different transaction: a completed payment never closes a
     * waiting transfer, even when the numbers line up.
     */
    fun settleTarget(
        incoming: CaptureCandidate,
        waiting: List<CaptureCandidate>,
        nowMs: Long,
    ): CaptureCandidate? {
        val candidates = waiting.filter {
            it.candidateId != incoming.candidateId && it.status == CaptureCandidate.STATUS_WAITING
        }
        if (candidates.isEmpty()) return null

        val orderId = incoming.orderId?.trim()?.takeIf(String::isNotEmpty)
        if (orderId != null) {
            val exact = candidates.filter {
                it.paymentPlatform == incoming.paymentPlatform && it.orderId?.trim() == orderId &&
                    kindsCompatible(it.kind, incoming.kind)
            }
            // An order id that matches nothing is positively a different
            // transaction; never fall back to fuzzy fields in that case.
            return exact.singleOrNull()
        }

        val merchant = normalizeMerchant(incoming.merchantCandidates.firstOrNull())
        if (merchant.isEmpty()) return null
        val matches = candidates.filter { previous ->
            // Two order ids cannot describe one transaction, and a placeholder
            // that already carries one is identified by it alone.
            previous.orderId?.trim().isNullOrEmpty() &&
                previous.paymentPlatform == incoming.paymentPlatform &&
                previous.kind == incoming.kind &&
                previous.amountCents == incoming.amountCents &&
                previous.direction == incoming.direction &&
                normalizeMerchant(previous.merchantCandidates.firstOrNull()) == merchant &&
                kotlin.math.abs(nowMs - previous.capturedAtMs) <= CORRELATION_WINDOW_MS
        }
        return matches.singleOrNull()
    }

    private fun normalizeMerchant(value: String?): String =
        value.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

    /**
     * A future-debit placeholder (先用后付) is the same order as the payment that
     * later charges it, so its kind may differ. Any other kind change means a
     * different transaction: a completed payment must never close a waiting
     * transfer placeholder that happens to share an order id or an amount.
     */
    private fun kindsCompatible(waitingKind: String, incomingKind: String): Boolean =
        waitingKind == incomingKind || waitingKind == CaptureCandidate.KIND_AUTO_PAY

    private val SETTLING_STATUSES = setOf(
        CaptureCandidate.COMPLETION_COMPLETED,
        CaptureCandidate.COMPLETION_CANCELLED,
        CaptureCandidate.COMPLETION_FAILED,
    )
}
