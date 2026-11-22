package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import org.junit.Assert.assertEquals
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

    @Test
    fun `weak duplicate and ambiguous notification key are warned before record action`() {
        val weakDuplicate = candidate(CaptureCandidate.DIRECTION_EXPENSE).copy(
            reason = CaptureCandidate.REASON_LIKELY_DUPLICATE,
        )
        assertEquals(
            ScreenshotReviewNotificationPolicy.Warning.WEAK_DUPLICATE,
            ScreenshotReviewNotificationPolicy.warning(weakDuplicate),
        )
        assertTrue(ScreenshotReviewNotificationPolicy.canRecord(weakDuplicate))
        val weakDetails = ScreenshotReviewNotifier.candidateDetails(weakDuplicate)
        assertTrue(
            weakDetails.contains("疑似与已有记录重复，请核对后再决定是否记入。"),
        )
        assertTrue(weakDetails.indexOf("确认原因") < weakDetails.indexOf("金额"))

        val notificationKeyAmbiguity = candidate(CaptureCandidate.DIRECTION_EXPENSE).copy(
            intakeMethod = CaptureCandidate.INTAKE_NOTIFICATION,
            reason = "Notification record key was seen before without transaction-level identity",
        )
        assertEquals(
            ScreenshotReviewNotificationPolicy.Warning.NOTIFICATION_KEY_AMBIGUITY,
            ScreenshotReviewNotificationPolicy.warning(notificationKeyAmbiguity),
        )
        assertTrue(ScreenshotReviewNotificationPolicy.canRecord(notificationKeyAmbiguity))
        val ambiguousDetails = ScreenshotReviewNotifier.candidateDetails(notificationKeyAmbiguity)
        assertTrue(
            ambiguousDetails.contains("通知记录标识存在歧义"),
        )
        assertTrue(ambiguousDetails.indexOf("确认原因") < ambiguousDetails.indexOf("金额"))

        val unknownDirection = notificationKeyAmbiguity.copy(
            direction = CaptureCandidate.DIRECTION_UNKNOWN,
            directionExplicit = false,
        )
        assertFalse("the warning cannot bypass direction review", ScreenshotReviewNotificationPolicy.canRecord(unknownDirection))
    }

    @Test
    fun `missing screenshot evidence is shown and notification offers review only`() {
        val candidate = candidate(CaptureCandidate.DIRECTION_EXPENSE).copy(
            reason = CaptureCandidate.REASON_LIKELY_DUPLICATE,
            screenshotUnavailableReason = CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_QUOTA,
        )

        assertTrue(ScreenshotReviewNotificationPolicy.requiresImageReview(candidate))
        assertFalse(ScreenshotReviewNotificationPolicy.canRecord(candidate))
        assertEquals(
            setOf(ScreenshotReviewNotificationPolicy.Action.OPEN_REVIEW),
            ScreenshotReviewNotificationPolicy.availableActions(candidate),
        )
        val details = ScreenshotReviewNotifier.candidateDetails(candidate)
        assertTrue(details.contains("疑似与已有记录重复"))
        assertTrue(details.contains("截图未保留（空间不足）"))
    }
}
