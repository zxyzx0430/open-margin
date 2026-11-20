package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class ScreenshotAuditInterpreterTest {
    private val now = 1795064400000L
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun page(vararg blocks: TextBlock) =
        ScreenshotPageInterpreter.interpret(blocks.toList(), now, zone)

    @Test
    fun `success inside a note cannot establish completion`() {
        val draft = page(
            TextBlock("微信支付", 0, 10, 300, 30),
            TextBlock("付款给 商店", 0, 40, 300, 60),
            TextBlock("-¥20.00", 0, 70, 300, 90),
            TextBlock("备注 支付成功", 0, 100, 300, 120),
        ).drafts.single()
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
    }

    @Test
    fun `success inside help question cannot establish completion`() {
        val draft = page(
            TextBlock("支付宝", 0, 10, 300, 30),
            TextBlock("付款给 商店", 0, 40, 300, 60),
            TextBlock("-¥20.00", 0, 70, 300, 90),
            TextBlock("支付成功但对方未收到？", 0, 100, 300, 120),
        ).drafts.single()
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
    }

    @Test
    fun `remote status is not attached by vertical card grouping alone`() {
        val draft = page(
            TextBlock("微信支付", 0, 10, 300, 30),
            TextBlock("付款给 商店", 0, 40, 300, 60),
            TextBlock("-¥20.00", 0, 70, 300, 90),
            TextBlock("支付成功", 0, 800, 300, 820),
        ).drafts.single()
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
    }
    @Test
    fun `remote refund status is not enough to mark refund complete`() {
        val draft = page(
            TextBlock("微信支付", 0, 10, 300, 30),
            TextBlock("退款金额 ¥20.00", 0, 70, 300, 90),
            TextBlock("退款成功", 0, 800, 300, 820),
        ).drafts.single()
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
    }
    @Test
    fun `note success requests confirmation instead of automatic booking`() {
        val plan = ScreenshotImportPipeline.plan(
            request = ScreenshotIntakeRequest(
                source = IntakeSource.SHARE,
                uri = "content://media/1",
                mimeType = "image/png",
                capturedAtMs = now,
                imageFingerprint = "a".repeat(64),
            ),
            blocks = listOf(
                TextBlock("微信支付", 0, 10, 300, 30),
                TextBlock("付款给 商店", 0, 40, 300, 60),
                TextBlock("-¥20.00", 0, 70, 300, 90),
                TextBlock("备注 支付成功", 0, 100, 300, 120),
            ),
            existing = emptyList(),
            timeZone = zone,
        )
        assertEquals(com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action.ASK_USER,
            plan.decisions.single().action)
    }
    @Test
    fun `nearby payment success cannot bind a distant refund success`() {
        val draft = page(
            TextBlock("微信支付", 0, 10, 300, 30),
            TextBlock("退款金额 ¥20.00", 0, 70, 300, 90),
            TextBlock("支付成功", 0, 100, 300, 120),
            TextBlock("退款成功", 0, 800, 300, 820),
        ).drafts.single()
        assertEquals(CaptureCandidate.COMPLETION_UNKNOWN, draft.completionStatus)
    }
}
