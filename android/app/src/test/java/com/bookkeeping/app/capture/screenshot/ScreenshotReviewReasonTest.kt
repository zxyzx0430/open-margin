package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.notification.capture.CaptureCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification's "why confirmation is needed" line used to say a transfer
 * always needs manual confirmation. Completed transfers auto-book now, so the
 * reason must name the actual missing proof instead.
 */
class ScreenshotReviewReasonTest {

    private fun candidate(
        kind: String = CaptureCandidate.KIND_TRANSFER,
        direction: String = CaptureCandidate.DIRECTION_EXPENSE,
        completionStatus: String = CaptureCandidate.COMPLETION_COMPLETED,
        completionExplicit: Boolean = true,
        reason: String = "test",
    ) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:detail",
        amountCents = 20_000L,
        merchantCandidates = listOf("张三"),
        orderId = null,
        capturedAtMs = 1_795_064_400_000L,
        screenshotPath = null,
        confidence = 0.6f,
        reason = reason,
        kind = kind,
        direction = direction,
        directionExplicit = direction != CaptureCandidate.DIRECTION_UNKNOWN,
        completionStatus = completionStatus,
        completionExplicit = completionExplicit,
        ledgerWriteAllowed = true,
    )

    @Test
    fun `transfer with auto booking off explains the setting, not the kind`() {
        val detail = ScreenshotReviewNotifier.candidateDetails(
            candidate(reason = "high confidence but auto booking disabled"),
        )

        assertTrue(detail.contains("自动记账已关闭"))
        assertFalse(detail.contains("这类交易需要人工确认"))
    }

    @Test
    fun `transfer with an unknown status explains the missing status`() {
        val detail = ScreenshotReviewNotifier.candidateDetails(
            candidate(
                completionStatus = CaptureCandidate.COMPLETION_UNKNOWN,
                completionExplicit = false,
                reason = "needs user confirmation",
            ),
        )

        assertTrue(detail.contains("截图未明确显示交易状态"))
        assertFalse(detail.contains("这类交易需要人工确认"))
    }

    @Test
    fun `transfer without a direction asks for the direction`() {
        val detail = ScreenshotReviewNotifier.candidateDetails(
            candidate(direction = CaptureCandidate.DIRECTION_UNKNOWN),
        )

        assertTrue(detail.contains("转账方向待确认，请先选择收入或支出"))
        assertFalse(detail.contains("这类交易需要人工确认"))
    }

    @Test
    fun `transfer that has not succeeded says it cannot be booked yet`() {
        val detail = ScreenshotReviewNotifier.candidateDetails(
            candidate(completionStatus = CaptureCandidate.COMPLETION_PENDING),
        )

        assertTrue(detail.contains("转账尚未成功，暂不能记账"))
        assertFalse(detail.contains("这类交易需要人工确认"))
    }

    @Test
    fun `refund no longer shows unconditional manual confirmation reason`() {
        val detail = ScreenshotReviewNotifier.candidateDetails(
            candidate(kind = CaptureCandidate.KIND_REFUND),
        )

        assertFalse(detail.contains("这类交易需要人工确认"))
        assertTrue(detail.contains("请核对截图识别结果"))
    }

    @Test
    fun `new transfer reasons have English text`() {
        val expected = mapOf(
            "转账方向待确认，请先选择收入或支出" to
                "The transfer direction needs confirmation; choose income or expense first",
            "转账尚未成功，暂不能记账" to
                "The transfer has not succeeded yet; it cannot be recorded now",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English transfer reason: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }
}
