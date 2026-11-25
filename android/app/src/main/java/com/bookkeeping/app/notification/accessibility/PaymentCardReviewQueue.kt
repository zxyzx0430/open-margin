package com.bookkeeping.app.notification.accessibility

import com.bookkeeping.app.data.local.entity.TransactionEntity
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
        existingLedgerRows: List<TransactionEntity> = emptyList(),
        geometryReason: String = AMBIGUOUS_GEOMETRY_REASON,
    ): Route {
        // Never hold uncompleted/pending/created cards in the review inbox,
        // because they represent in-flight transfers or unreceived red packets.
        // Cancelled/failed cards on multi-card pages stay visible as non-bookable
        // previews per PaymentCardReviewQueueTest.
        val actionable = detections.filterNot {
            it.status in setOf(
                PaymentCardStatus.PENDING,
                PaymentCardStatus.CREATED,
            )
        }
        if (actionable.isEmpty()) return Route.NoBookableCandidate
        if (actionable.size > 1 || actionable.any { it.reviewRequired }) {
            val reason = if (actionable.any { it.reviewRequired }) {
                geometryReason
            } else {
                MULTI_CARD_REASON
            }
            val saved = saveForReview(
                candidates = actionable.mapNotNull { candidate(it, capturedAtMs) },
                store = store,
                screenshot = screenshot,
                reason = reason,
                existingLedgerRows = existingLedgerRows,
            )
            return Route.HeldForReview(saved = saved, reason = reason)
        }
        val candidate = actionable.singleOrNull()
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
            eventTimeSource = if (detection.eventTimeMs == null) CaptureCandidate.TIME_SOURCE_CAPTURE
                else CaptureCandidate.TIME_SOURCE_ACTUAL,
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
        existingLedgerRows: List<TransactionEntity> = emptyList(),
    ): SaveResult = store.withCandidateLock {
        val existingPending = store.pending().toMutableList()
        val saved = mutableListOf<CaptureCandidate>()
        var failed = 0
        var indexFull = 0
        candidates.forEach { candidate ->
            if (isDuplicatePending(candidate, existingPending) ||
                isAlreadyInLedger(candidate, existingLedgerRows)
            ) {
                return@forEach
            }
            val pending = candidate.copy(
                reason = reason.take(80),
                status = CaptureCandidate.STATUS_PENDING,
                ledgerWriteAllowed = true,
            )
            when (val result = store.saveWithResult(pending, screenshot)) {
                is CandidateSaveResult.Saved -> {
                    saved += result.candidate
                    existingPending += result.candidate
                }
                is CandidateSaveResult.Failed -> {
                    failed++
                    if (result.reason == CandidateSaveFailure.INDEX_FULL) indexFull++
                }
            }
        }
        SaveResult(saved = saved, failed = failed, indexFull = indexFull)
    }

    internal fun isDuplicatePending(
        candidate: CaptureCandidate,
        existingPending: List<CaptureCandidate>,
    ): Boolean {
        val orderId = candidate.orderId?.trim()?.takeIf { it.isNotEmpty() }
        return existingPending.any { existing ->
            val existingOrderId = existing.orderId?.trim()?.takeIf { it.isNotEmpty() }
            if (orderId != null && existingOrderId != null) {
                return@any orderId == existingOrderId
            }
            if (existing.packageName != candidate.packageName) return@any false
            if (existing.kind != candidate.kind) return@any false
            if (existing.amountCents != candidate.amountCents) return@any false
            if (existing.direction != candidate.direction &&
                existing.direction != CaptureCandidate.DIRECTION_UNKNOWN &&
                candidate.direction != CaptureCandidate.DIRECTION_UNKNOWN
            ) {
                return@any false
            }
            // A capture-time fallback is not an observed transaction timestamp.
            val bothActual = existing.eventTimeSource != CaptureCandidate.TIME_SOURCE_CAPTURE &&
                candidate.eventTimeSource != CaptureCandidate.TIME_SOURCE_CAPTURE &&
                !existing.eventTimeNeedsConfirmation && !candidate.eventTimeNeedsConfirmation &&
                existing.eventTimeMs != null && candidate.eventTimeMs != null
            val existingEventTime = if (bothActual) existing.eventTimeMs else existing.capturedAtMs
            val candidateEventTime = if (bothActual) candidate.eventTimeMs else candidate.capturedAtMs
            if (existingEventTime != null && candidateEventTime != null &&
                kotlin.math.abs(existingEventTime - candidateEventTime) > 60_000L
            ) {
                return@any false
            }
            val existingMerchant = normalizeMerchantForDedup(existing.merchantCandidates.firstOrNull())
            val candidateMerchant = normalizeMerchantForDedup(candidate.merchantCandidates.firstOrNull())
            if (existingMerchant != null && candidateMerchant != null &&
                !existingMerchant.contains(candidateMerchant) && !candidateMerchant.contains(existingMerchant)
            ) return@any false
            val samePage = existing.pageIdentity == candidate.pageIdentity
            val sameMerchant = existingMerchant != null && existingMerchant == candidateMerchant
            val closeInTime = kotlin.math.abs(existing.capturedAtMs - candidate.capturedAtMs) <= 120_000L
            // Without observed times, amount + capture proximity alone is too weak
            // to suppress an otherwise independent transaction on a different page.
            samePage || sameMerchant || (bothActual && closeInTime)
        }
    }

    internal fun isAlreadyInLedger(
        candidate: CaptureCandidate,
        existingLedgerRows: List<TransactionEntity>,
    ): Boolean {
        if (existingLedgerRows.isEmpty()) return false
        val orderId = candidate.orderId?.trim()?.takeIf { it.isNotEmpty() }
        val candidateTime = candidate.eventTimeMs ?: candidate.capturedAtMs
        val candidateMerchant = normalizeMerchantForDedup(candidate.merchantCandidates.firstOrNull())
        return existingLedgerRows.any { row ->
            if (row.syncDeleted) return@any false
            val rowOrderId = row.sourceTransactionId?.trim()?.takeIf { it.isNotEmpty() }
            if (orderId != null && rowOrderId != null) {
                return@any orderId == rowOrderId
            }
            if (row.amountCents != candidate.amountCents) return@any false
            val directionMatch = when (candidate.direction) {
                CaptureCandidate.DIRECTION_EXPENSE -> row.type == "EXPENSE"
                CaptureCandidate.DIRECTION_INCOME -> row.type == "INCOME"
                else -> true
            }
            if (!directionMatch) return@any false
            if (kotlin.math.abs(row.timestamp - candidateTime) > 120_000L) return@any false
            val rowMerchant = normalizeMerchantForDedup(row.merchant)
            if (candidateMerchant != null && rowMerchant != null) {
                candidateMerchant.contains(rowMerchant) || rowMerchant.contains(candidateMerchant)
            } else {
                true
            }
        }
    }

    private fun normalizeMerchantForDedup(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed in GENERIC_MERCHANT_PLACEHOLDERS) return null
        return trimmed.lowercase().replace(Regex("[\\s·•\\-_]+"), "")
            .takeIf { it.isNotEmpty() }
    }

    private val GENERIC_MERCHANT_PLACEHOLDERS = setOf(
        "未知商户", "转入", "转出", "转账", "微信支付", "支付宝", "微信", "收款", "付款",
    )

    const val MULTI_CARD_REASON = "multiple payment cards; confirm each separately"
    const val AMBIGUOUS_GEOMETRY_REASON = "invalid OCR bounds; confirm transaction details"
    const val AMBIGUOUS_NODE_GEOMETRY_REASON = "invalid accessibility bounds; confirm transaction details"
}
