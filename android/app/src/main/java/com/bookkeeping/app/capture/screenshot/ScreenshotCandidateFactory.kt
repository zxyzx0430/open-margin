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
            reason = decision.reason.take(80).ifBlank { "screenshot" },
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
            candidateId = UUID.nameUUIDFromBytes(
                "$imageFingerprint|${decision.index}|${draft.amountCents}|${draft.direction}".toByteArray(),
            ).toString(),
            supportedPaymentPage = draft.supportedPaymentPage,
            transactionLike = true,
            amountUnambiguous = draft.amountUnambiguous,
            completionExplicit = draft.completionExplicit,
            directionExplicit = draft.directionExplicit,
            matchedTransactionId = decision.matchedTransactionId,
        )
    }

    private fun platformOf(pageIdentity: String): String = when {
        pageIdentity.startsWith("wechat") -> CaptureCandidate.PLATFORM_WECHAT
        pageIdentity.startsWith("alipay") -> CaptureCandidate.PLATFORM_ALIPAY
        else -> CaptureCandidate.PLATFORM_UNKNOWN
    }
}
