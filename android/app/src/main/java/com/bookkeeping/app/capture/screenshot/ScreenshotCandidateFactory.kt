package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import java.util.UUID

/**
 * Turns a pipeline decision into the stored candidate. Pure so the
 * "missing real transaction time uses the capture time but stays marked"
 * rule can be asserted without Android.
 */
object ScreenshotCandidateFactory {

    fun create(
        decision: CandidateDecision,
        source: IntakeSource,
        capturedAtMs: Long,
        imageFingerprint: String,
        diagnosticAttemptId: String? = null,
    ): CaptureCandidate {
        val draft = decision.draft
        val eventTime = draft.eventTimeMs ?: capturedAtMs
        val needsConfirmation = draft.eventTimeMs == null
        val confidence = when (decision.tier) {
            CaptureCandidate.TIER_HIGH -> 0.9f
            CaptureCandidate.TIER_MEDIUM -> 0.6f
            else -> 0.3f
        }
        return CaptureCandidate(
            packageName = null,
            pageIdentity = "screenshot:${draft.pageIdentity}:${decision.index}".take(190),
            amountCents = draft.amountCents,
            merchantCandidates = draft.merchantCandidates,
            orderId = draft.orderId,
            capturedAtMs = capturedAtMs,
            screenshotPath = null,
            confidence = confidence,
            reason = reasonOf(decision),
            kind = draft.kind,
            eventTimeMs = eventTime,
            direction = draft.direction,
            diagnosticAttemptId = diagnosticAttemptId,
            intakeMethod = source.candidateValue,
            paymentPlatform = platformOf(draft.pageIdentity),
            confidenceTier = decision.tier,
            completionStatus = draft.completionStatus,
            eventTimeSource = if (needsConfirmation) {
                CaptureCandidate.TIME_SOURCE_CAPTURE
            } else {
                CaptureCandidate.TIME_SOURCE_ACTUAL
            },
            eventTimeNeedsConfirmation = needsConfirmation,
            imageFingerprint = imageFingerprint,
            candidateId = candidateId(
                imageFingerprint = imageFingerprint,
                index = decision.index,
                amountCents = draft.amountCents,
                direction = draft.direction,
            ),
            supportedPaymentPage = draft.supportedPaymentPage,
            transactionLike = true,
            amountUnambiguous = draft.amountUnambiguous,
            completionExplicit = draft.completionExplicit,
            directionExplicit = draft.directionExplicit,
            matchedTransactionId = decision.matchedTransactionId,
            ledgerWriteAllowed = decision.ledgerWriteAllowed,
        )
    }

    /**
     * 候选的稳定身份：同一张图（同一 SHA-256）、同一草稿序号、同一金额与方向
     * 必然得到同一个 id。重复 ingest 因此能认出「这笔已经处理过」。
     */
    fun candidateId(
        imageFingerprint: String,
        index: Int,
        amountCents: Long?,
        direction: String,
    ): String = UUID.nameUUIDFromBytes(
        "$imageFingerprint|$index|$amountCents|$direction".toByteArray(),
    ).toString()

    /**
     * Stored reason for the card.
     *
     * A duplicate hint replaces the pipeline's prose with the exact constant the
     * review UI keys on, so the hint does not depend on the wording of
     * [CandidateDecision.reason]. Everything else keeps the free-text reason.
     */
    private fun reasonOf(decision: CandidateDecision): String = when (decision.duplicateHint) {
        DuplicateHint.CROSS_CHANNEL -> CaptureCandidate.REASON_LIKELY_DUPLICATE
        DuplicateHint.SAME_IMAGE -> CaptureCandidate.REASON_SAME_IMAGE_REPEAT
        DuplicateHint.NONE -> decision.reason.take(80).ifBlank { "screenshot" }
    }

    private fun platformOf(pageIdentity: String): String = when {
        pageIdentity.startsWith("wechat") -> CaptureCandidate.PLATFORM_WECHAT
        pageIdentity.startsWith("alipay") -> CaptureCandidate.PLATFORM_ALIPAY
        else -> CaptureCandidate.PLATFORM_UNKNOWN
    }
}
