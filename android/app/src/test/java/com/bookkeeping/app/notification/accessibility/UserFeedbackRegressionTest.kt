package com.bookkeeping.app.notification.accessibility

import com.bookkeeping.app.capture.screenshot.ScreenshotPageInterpreter
import com.bookkeeping.app.capture.screenshot.TextBlock
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import java.nio.file.Files
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `same-day chat transfer pair with HH_mm clock consolidates to single income`() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val blocks = listOf(
            TextBlock("20:21", left = 40, top = 20, right = 140, bottom = 60),
            TextBlock("马奕轩", left = 160, top = 90, right = 340, bottom = 140),
            TextBlock("20:21", left = 480, top = 500, right = 600, bottom = 535),
            TextBlock("¥0.02", left = 120, top = 620, right = 300, bottom = 670),
            TextBlock("已被接收", left = 120, top = 680, right = 300, bottom = 720),
            TextBlock("转账", left = 120, top = 730, right = 220, bottom = 760),
            TextBlock("¥0.02", left = 680, top = 860, right = 860, bottom = 910),
            TextBlock("已收款", left = 680, top = 920, right = 860, bottom = 960),
            TextBlock("转账", left = 680, top = 970, right = 780, bottom = 1000),
        )

        val evidence = ScreenshotPageInterpreter.interpret(blocks, now, zone)

        assertTrue(evidence.transactionLike)
        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(2L, draft.amountCents)
        assertEquals(CaptureCandidate.KIND_TRANSFER, draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_INCOME, draft.direction)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, draft.completionStatus)
        assertEquals(listOf("马奕轩"), draft.merchantCandidates)
    }

    @Test
    fun `own app screenshot from OEM without package name in filename is ignored`() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val blocks = listOf(
            TextBlock("清衡", left = 60, top = 90, right = 200, bottom = 140),
            TextBlock("同步未开启", left = 400, top = 90, right = 620, bottom = 140),
            TextBlock("已忽略的截图·131", left = 60, top = 200, right = 420, bottom = 240),
            TextBlock("收入", left = 60, top = 300, right = 180, bottom = 340),
            TextBlock("支出", left = 360, top = 300, right = 480, bottom = 340),
            TextBlock("结余", left = 660, top = 300, right = 780, bottom = 340),
            TextBlock("¥2,253.55", left = 660, top = 350, right = 920, bottom = 400),
            TextBlock("微信转账", left = 120, top = 700, right = 320, bottom = 740),
            TextBlock("-¥0.01", left = 720, top = 700, right = 900, bottom = 740),
        )

        val evidence = ScreenshotPageInterpreter.interpret(blocks, now, zone)

        assertFalse(evidence.transactionLike)
        assertTrue(evidence.drafts.isEmpty())
    }

    @Test
    fun `clipped top node does not invalidate valid payment screen geometry`() {
        val detections = PaymentCardDetector.fromLines(
            packageName = PaymentCardDetector.WECHAT,
            lines = listOf(
                BillTextLine("顶栏被截断", top = -12, bottom = 18, left = 40, right = 300),
                BillTextLine("支付成功", top = 120, bottom = 160, left = 100, right = 400),
                BillTextLine("校园水吧", top = 200, bottom = 240, left = 100, right = 400),
                BillTextLine("¥3.50", top = 280, bottom = 330, left = 100, right = 400),
            ),
            capturedAtMs = 1_795_529_520_000L,
            screenHeightPx = 1200,
            pageIdentity = "clipped-top-node",
        )
        assertEquals(1, detections.size)
        assertFalse(detections.single().reviewRequired)
        assertEquals(350L, detections.single().amountCents)
    }

    @Test
    fun `wechat service card stream binds between-card time to next card and strips shou badge`() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = 1_795_529_520_000L // 2026-11-24 22:12
        val blocks = listOf(
            TextBlock("微信支付", left = 400, top = 80, right = 600, bottom = 120),
            TextBlock("校园水吧仲恺学院店", left = 180, top = 260, right = 700, bottom = 310),
            TextBlock("使用零钱支付", left = 360, top = 360, right = 640, bottom = 400),
            TextBlock("¥ 3.50", left = 380, top = 430, right = 620, bottom = 500),
            TextBlock("账单详情 >", left = 380, top = 540, right = 620, bottom = 580),
            // Timestamp divider sits closer to card 1's anchor/footer or between cards,
            // and in WeChat chat streams it timestamps card 2 below.
            TextBlock("22:09", left = 450, top = 620, right = 550, bottom = 650),
            TextBlock("收广州便利店", left = 180, top = 710, right = 620, bottom = 760),
            TextBlock("使用零钱支付", left = 360, top = 820, right = 640, bottom = 860),
            TextBlock("¥ 9.50", left = 380, top = 890, right = 620, bottom = 960),
            TextBlock("账单详情 >", left = 380, top = 1000, right = 620, bottom = 1040),
        )
        val evidence = ScreenshotPageInterpreter.interpret(blocks, now, zone)
        assertEquals(2, evidence.drafts.size)
        val first = evidence.drafts[0]
        val second = evidence.drafts[1]
        assertEquals(350L, first.amountCents)
        assertTrue(first.eventTimeNeedsConfirmation)
        assertEquals(950L, second.amountCents)
        assertFalse(second.eventTimeNeedsConfirmation)
        assertEquals("广州便利店", second.merchantCandidates.firstOrNull())
    }
}
