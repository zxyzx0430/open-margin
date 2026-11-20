package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotAuditNotificationSafetyTest {
    private fun candidate(direction: String) = CaptureCandidate(
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
        ledgerWriteAllowed = true,
    )

    @Test
    fun `unknown direction is not presented as an expense`() {
        val detail = ScreenshotReviewNotifier.candidateDetails(candidate(CaptureCandidate.DIRECTION_UNKNOWN))
        assertTrue("unknown direction must be labelled", detail.contains("方向待确认"))
        assertFalse("unknown direction must not be labelled as expense", detail.contains("支出"))
        assertFalse("unknown direction must not use expense sign", detail.contains("−¥"))
    }

    @Test
    fun `known directions remain accurately labelled`() {
        val income = ScreenshotReviewNotifier.candidateDetails(candidate(CaptureCandidate.DIRECTION_INCOME))
        val expense = ScreenshotReviewNotifier.candidateDetails(candidate(CaptureCandidate.DIRECTION_EXPENSE))
        assertTrue(income.contains("收入"))
        assertTrue(income.contains("+¥"))
        assertTrue(expense.contains("支出"))
        assertTrue(expense.contains("−¥"))
    }
}
