package com.bookkeeping.app.notification.capture

import java.util.Locale

/**
 * Cross-screenshot state evidence.
 *
 * A waiting candidate (unclaimed transfer, created future debit, cancelled or
 * failed payment) is only a placeholder: when a later screenshot arrives with
 * completed or refund evidence for the same transaction, that older candidate
 * must be settled instead of leaving two actionable items, or booking a second
 * ledger row. Anything that is not an unambiguous match is left alone — guessing
 * would merge two genuinely different payments.
 *
 * Pure Kotlin so the "one item, no duplicate" rule is testable without Android.
 */
object ScreenshotWaitingCorrelation {

    /** Same window the store has always used for late evidence. */
    const val CORRELATION_WINDOW_MS = 10L * 60 * 1000

    /** Only a completed page can settle a waiting item; pending pages change nothing. */
    fun isSettlingEvidence(candidate: CaptureCandidate): Boolean =
        candidate.completionStatus == CaptureCandidate.COMPLETION_COMPLETED

    /**
     * The single waiting candidate [incoming] settles, or null when none or more
     * than one candidate could be meant.
     *
     * Match order:
     * 1. platform + exact order id;
     * 2. platform + kind + amount + direction + normalized merchant, inside
     *    [CORRELATION_WINDOW_MS], and only when neither side is missing the
     *    merchant name or carrying a competing order id.
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
                it.paymentPlatform == incoming.paymentPlatform && it.orderId?.trim() == orderId
            }
            // An order id that matches nothing is positively a different
            // transaction; never fall back to fuzzy fields in that case.
            return exact.singleOrNull()
        }

        val merchant = normalizeMerchant(incoming.merchantCandidates.firstOrNull())
        if (merchant.isEmpty()) return null
        val matches = candidates.filter { previous ->
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
}
