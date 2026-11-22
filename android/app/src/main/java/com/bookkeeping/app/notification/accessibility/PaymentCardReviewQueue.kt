package com.bookkeeping.app.notification.accessibility

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CandidateSaveFailure
import com.bookkeeping.app.notification.capture.CandidateSaveResult
import com.bookkeeping.app.notification.capture.CaptureCandidateStore

/**
 * Maps parsed page cards into the persistent review inbox.
 *
 * This route intentionally has no repository dependency: multi-card and
 * ambiguous-geometry pages can only create pending cards here, never ledger
 * rows.
 */
object PaymentCardReviewQueue {
    sealed interface Route {
        data class HeldForReview(
            val saved: SaveResult,
            val reason: String,
        ) : Route

        data class SingleCandidate(val candidate: CaptureCandidate) : Route
        data object NoBookableCandidate : Route
    }

    data class SaveResult(
        val saved: List<CaptureCandidate>,
        val failed: Int,
        val indexFull: Int,
    )

    /** Production routing boundary shared by accessibility-tree and OCR capture. */
    fun route(
        detections: List<PaymentCardDetection>,
        capturedAtMs: Long,
        store: CaptureCandidateStore,
        screenshot: ByteArray? = null,
    ): Route {
        if (detections.size > 1 || detections.any { it.reviewRequired }) {
            val reason = if (detections.any { it.reviewRequired }) {
                AMBIGUOUS_GEOMETRY_REASON
            } else {
                MULTI_CARD_REASON
            }
            val saved = saveForReview(
                candidates = detections.mapNotNull { candidate(it, capturedAtMs) },
                store = store,
                screenshot = screenshot,
                reason = reason,
            )
            return Route.HeldForReview(saved = saved, reason = reason)
        }
        val candidate = detections.singleOrNull()
            ?.takeIf { it.isFinal && it.direction != PaymentDirection.UNKNOWN && !it.reviewRequired }
            ?.let { candidate(it, capturedAtMs) }
            ?: return Route.NoBookableCandidate
        return Route.SingleCandidate(candidate)
    }

    fun candidate(
        detection: PaymentCardDetection,
        capturedAtMs: Long,
    ): CaptureCandidate? {
        if (detection.amountCents <= 0L) return null
        val kind = when (detection.kind) {
            PaymentCardKind.PAYMENT -> CaptureCandidate.KIND_PAYMENT
            PaymentCardKind.TRANSFER -> CaptureCandidate.KIND_TRANSFER
            PaymentCardKind.RED_PACKET -> CaptureCandidate.KIND_RED_PACKET
            PaymentCardKind.WITHDRAWAL -> CaptureCandidate.KIND_WITHDRAWAL
            PaymentCardKind.REFUND -> CaptureCandidate.KIND_REFUND
            PaymentCardKind.PAY_AFTER_USE -> CaptureCandidate.KIND_AUTO_PAY
            PaymentCardKind.BILL -> CaptureCandidate.KIND_BILL
        }
        val direction = when (detection.direction) {
            PaymentDirection.EXPENSE -> CaptureCandidate.DIRECTION_EXPENSE
            PaymentDirection.INCOME -> CaptureCandidate.DIRECTION_INCOME
            PaymentDirection.UNKNOWN -> CaptureCandidate.DIRECTION_UNKNOWN
        }
        val completion = when (detection.status) {
            PaymentCardStatus.COMPLETED -> CaptureCandidate.COMPLETION_COMPLETED
            PaymentCardStatus.PENDING -> CaptureCandidate.COMPLETION_PENDING
            PaymentCardStatus.CREATED -> CaptureCandidate.COMPLETION_CREATED
            PaymentCardStatus.CANCELLED -> CaptureCandidate.COMPLETION_CANCELLED
            PaymentCardStatus.FAILED -> CaptureCandidate.COMPLETION_FAILED
            PaymentCardStatus.UNKNOWN -> CaptureCandidate.COMPLETION_UNKNOWN
        }
        return CaptureCandidate(
            packageName = detection.source.packageName,
            pageIdentity = detection.pageIdentity,
            amountCents = detection.amountCents,
            merchantCandidates = detection.merchantCandidates,
            orderId = detection.orderId,
            capturedAtMs = capturedAtMs,
            screenshotPath = null,
            confidence = detection.confidence.coerceIn(0f, 1f),
            reason = detection.reason.ifBlank { "page candidate needs review" }.take(80),
            kind = kind,
            eventTimeMs = detection.eventTimeMs ?: capturedAtMs,
            direction = direction,
            eventTimeNeedsConfirmation = detection.eventTimeMs == null,
            completionStatus = completion,
            completionExplicit = detection.completionExplicit,
            directionExplicit = detection.directionExplicit,
            ledgerWriteAllowed = true,
        )
    }

    fun saveForReview(
        candidates: List<CaptureCandidate>,
        store: CaptureCandidateStore,
        screenshot: ByteArray? = null,
        reason: String = MULTI_CARD_REASON,
    ): SaveResult {
        val saved = mutableListOf<CaptureCandidate>()
        var failed = 0
        var indexFull = 0
        candidates.forEach { candidate ->
            val pending = candidate.copy(
                reason = reason.take(80),
                status = CaptureCandidate.STATUS_PENDING,
                ledgerWriteAllowed = true,
            )
            when (val result = store.saveWithResult(pending, screenshot)) {
                is CandidateSaveResult.Saved -> saved += result.candidate
                is CandidateSaveResult.Failed -> {
                    failed++
                    if (result.reason == CandidateSaveFailure.INDEX_FULL) indexFull++
                }
            }
        }
        return SaveResult(saved = saved, failed = failed, indexFull = indexFull)
    }

    const val MULTI_CARD_REASON = "multiple payment cards; confirm each separately"
    const val AMBIGUOUS_GEOMETRY_REASON = "invalid OCR bounds; confirm transaction details"
}
