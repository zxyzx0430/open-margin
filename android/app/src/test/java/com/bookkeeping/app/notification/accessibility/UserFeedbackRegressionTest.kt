package com.bookkeeping.app.notification.accessibility

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class UserFeedbackRegressionTest {
    private val now = System.currentTimeMillis()

    @Test
    fun `wechat transfer chat pair with 已被接收 and 已收款 consolidates to single income`() {
        val lines = listOf(
            BillTextLine("微信转账", top = 100, bottom = 140, left = 100, right = 400),
            BillTextLine("¥0.02", top = 150, bottom = 190, left = 100, right = 300),
            BillTextLine("已被接收", top = 200, bottom = 240, left = 100, right = 300),
            BillTextLine("微信转账", top = 500, bottom = 540, left = 100, right = 400),
            BillTextLine("¥0.02", top = 550, bottom = 590, left = 100, right = 300),
            BillTextLine("已收款", top = 600, bottom = 640, left = 100, right = 300),
        )
        val detections = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.WECHAT,
            lines = lines,
            capturedAtMs = now,
            screenHeightPx = 2400,
            pageIdentity = "wechat:chat:transfer",
        )

        assertEquals(1, detections.size)
        assertEquals(PaymentDirection.INCOME, detections.single().direction)
        assertEquals(PaymentCardStatus.COMPLETED, detections.single().status)
        assertEquals(2L, detections.single().amountCents)
    }

    @Test
    fun `wechat transfer with 已被接收 alone is marked completed`() {
        val lines = listOf(
            BillTextLine("微信转账", top = 100, bottom = 140, left = 100, right = 400),
            BillTextLine("¥10.00", top = 150, bottom = 190, left = 100, right = 300),
            BillTextLine("已被接收", top = 200, bottom = 240, left = 100, right = 300),
        )
        val detections = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.WECHAT,
            lines = lines,
            capturedAtMs = now,
            screenHeightPx = 2400,
            pageIdentity = "wechat:chat:transfer-single",
        )

        assertEquals(1, detections.size)
        assertEquals(PaymentCardStatus.COMPLETED, detections.single().status)
        assertEquals(1000L, detections.single().amountCents)
    }

    @Test
    fun `repeated saveForReview for same pending transfer keeps only one pending card`() {
        val dir = Files.createTempDirectory("feedback-dedup-test").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val candidate = CaptureCandidate(
                packageName = PaymentCardDetector.WECHAT,
                pageIdentity = "wechat:chat:transfer",
                amountCents = 2L,
                merchantCandidates = listOf("微信转账"),
                orderId = null,
                capturedAtMs = now,
                screenshotPath = null,
                confidence = 0.85f,
                reason = "待确认",
                kind = CaptureCandidate.KIND_TRANSFER,
                direction = CaptureCandidate.DIRECTION_INCOME,
                eventTimeMs = now,
            )

            repeat(11) { offset ->
                PaymentCardReviewQueue.saveForReview(
                    candidates = listOf(candidate.copy(capturedAtMs = now + offset * 1000L)),
                    store = store,
                    reason = "待确认",
                )
            }

            assertEquals(1, store.pending().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `wechat bill list page is excluded from passive snapshot detection`() {
        val snapshot = ScreenSnapshot.of(
            PaymentCardDetector.WECHAT,
            now,
            SnapshotNode(
                children = listOf(
                    SnapshotNode(text = "全部账单", bounds = SnapshotRect(0, 100, 500, 150)),
                    SnapshotNode(text = "本月支出 ¥5000.00", bounds = SnapshotRect(0, 200, 500, 260)),
                    SnapshotNode(text = "星巴克", bounds = SnapshotRect(0, 400, 500, 450)),
                    SnapshotNode(text = "-35.00", bounds = SnapshotRect(0, 460, 500, 510)),
                ),
            ),
        )

        val detections = PaymentCardDetector.fromSnapshot(PaymentCardDetector.WECHAT, snapshot, now)

        assertEquals(0, detections.size)
    }

    @Test
    fun `wechat pay official account chat with 我的账单 tab is still detected`() {
        val snapshot = ScreenSnapshot.of(
            PaymentCardDetector.WECHAT,
            now,
            SnapshotNode(
                children = listOf(
                    SnapshotNode(text = "微信支付凭证", bounds = SnapshotRect(40, 100, 900, 140)),
                    SnapshotNode(text = "支付成功", bounds = SnapshotRect(40, 150, 900, 190)),
                    SnapshotNode(text = "星巴克咖啡", bounds = SnapshotRect(40, 200, 900, 240)),
                    SnapshotNode(text = "¥32.00", bounds = SnapshotRect(40, 250, 900, 290)),
                    SnapshotNode(text = "我的账单", bounds = SnapshotRect(40, 2100, 300, 2200)),
                ),
            ),
        )

        val detections = PaymentCardDetector.fromSnapshot(PaymentCardDetector.WECHAT, snapshot, now)

        assertEquals(1, detections.size)
        assertEquals(3200L, detections.single().amountCents)
        assertEquals(PaymentDirection.EXPENSE, detections.single().direction)
    }
}
