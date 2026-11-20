package com.bookkeeping.app.notification.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotAuditReviewSafetyTest {
    private fun candidate(direction: String, allowed: Boolean = true) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:detail",
        amountCents = 1000,
        merchantCandidates = listOf("商店"),
        orderId = null,
        capturedAtMs = 1795064400000L,
        screenshotPath = null,
        confidence = 0.6f,
        reason = "test",
        direction = direction,
        directionExplicit = direction != CaptureCandidate.DIRECTION_UNKNOWN,
        ledgerWriteAllowed = allowed,
    )

    @Test
    fun `unknown direction cannot be confirmed`() {
        assertFalse("unknown direction must not allow ledger write", CaptureBookGate.mayConfirm(candidate(CaptureCandidate.DIRECTION_UNKNOWN)))
    }

    @Test
    fun `known directions preserve permission to confirm`() {
        assertTrue(CaptureBookGate.mayConfirm(candidate(CaptureCandidate.DIRECTION_EXPENSE)))
        assertTrue(CaptureBookGate.mayConfirm(candidate(CaptureCandidate.DIRECTION_INCOME)))
        assertFalse(CaptureBookGate.mayConfirm(candidate(CaptureCandidate.DIRECTION_INCOME, allowed = false)))
    }
}
