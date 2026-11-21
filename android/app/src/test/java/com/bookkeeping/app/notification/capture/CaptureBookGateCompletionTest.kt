package com.bookkeeping.app.notification.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confirmation may write only a settled, direction-known, allowed candidate. */
class CaptureBookGateCompletionTest {

    private fun candidate(
        completionStatus: String,
        direction: String = CaptureCandidate.DIRECTION_EXPENSE,
        ledgerWriteAllowed: Boolean = true,
    ) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:detail",
        amountCents = 20_000L,
        merchantCandidates = listOf("张三"),
        orderId = null,
        capturedAtMs = 1_795_064_400_000L,
        screenshotPath = null,
        confidence = 0.9f,
        reason = "test",
        kind = CaptureCandidate.KIND_TRANSFER,
        direction = direction,
        directionExplicit = direction != CaptureCandidate.DIRECTION_UNKNOWN,
        completionStatus = completionStatus,
        completionExplicit = completionStatus == CaptureCandidate.COMPLETION_COMPLETED,
        ledgerWriteAllowed = ledgerWriteAllowed,
    )

    @Test
    fun `non final completion can never be confirmed`() {
        listOf(
            CaptureCandidate.COMPLETION_PENDING,
            CaptureCandidate.COMPLETION_CREATED,
            CaptureCandidate.COMPLETION_CANCELLED,
            CaptureCandidate.COMPLETION_FAILED,
        ).forEach { status ->
            assertFalse(
                "$status must not be booked, even with a known direction",
                CaptureBookGate.mayConfirm(candidate(status)),
            )
        }
    }

    @Test
    fun `completed candidate with a known direction may be confirmed`() {
        assertTrue(CaptureBookGate.mayConfirm(candidate(CaptureCandidate.COMPLETION_COMPLETED)))
    }
}
