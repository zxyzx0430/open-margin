package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `contact only merchant advert with three prices is not a payment page`() {
        val evidence = page(
            TextBlock("首页", 0, 10, 300, 30),
            TextBlock("Fixture shop AI", 0, 40, 300, 60),
            TextBlock("¥103.01", 0, 70, 100, 90),
            TextBlock("¥230.70", 110, 70, 200, 90),
            TextBlock("¥30.15", 210, 70, 300, 90),
            TextBlock("微信同号", 0, 100, 300, 120),
            TextBlock("价格及库存以网站为准", 0, 130, 300, 150),
            TextBlock("考试安排", 0, 160, 300, 180),
        )
        assertEquals(CaptureCandidate.PLATFORM_UNKNOWN, evidence.platform)
        assertFalse(evidence.transactionLike)
        assertTrue(evidence.drafts.isEmpty())
    }

    @Test
    fun `explicit contact phrases alone cannot identify a payment platform`() {
        listOf("联系微信", "添加微信", "加微信", "微信号：test", "微信 同号",
            "支付宝同号", "联系支付宝", "支付宝号: test").forEach { contact ->
            val evidence = page(
                TextBlock(contact, 0, 10, 300, 30),
                TextBlock("¥20.00", 0, 40, 300, 60),
            )
            assertEquals(contact, CaptureCandidate.PLATFORM_UNKNOWN, evidence.platform)
            assertTrue(contact, evidence.drafts.isEmpty())
        }
    }

    @Test
    fun `contact footer must not suppress real payment evidence`() {
        val evidence = page(
            TextBlock("微信支付", 0, 10, 300, 30),
            TextBlock("付款给 商店", 0, 40, 300, 60),
            TextBlock("-¥20.00", 0, 70, 300, 90),
            TextBlock("支付成功", 0, 100, 300, 120),
            TextBlock("微信同号", 0, 150, 300, 170),
        )
        assertEquals(CaptureCandidate.PLATFORM_WECHAT, evidence.platform)
        assertEquals(2000L, evidence.drafts.single().amountCents)
    }

    @Test
    fun `standalone platform name still supports uncertain detail review`() {
        val evidence = page(
            TextBlock("微信", 0, 10, 300, 30),
            TextBlock("商店", 0, 40, 300, 60),
            TextBlock("¥20.00", 0, 70, 300, 90),
        )
        assertEquals(CaptureCandidate.PLATFORM_WECHAT, evidence.platform)
        assertEquals(2000L, evidence.drafts.single().amountCents)
        assertFalse(evidence.drafts.single().completionExplicit)
    }

    @Test
    fun `contact phrase cannot override separate Alipay evidence`() {
        val evidence = page(
            TextBlock("支付宝", 0, 10, 300, 30),
            TextBlock("商店", 0, 40, 300, 60),
            TextBlock("-¥20.00", 0, 70, 300, 90),
            TextBlock("交易成功", 0, 100, 300, 120),
            TextBlock("微信同号", 0, 150, 300, 170),
        )
        assertEquals(CaptureCandidate.PLATFORM_ALIPAY, evidence.platform)
        assertEquals(2000L, evidence.drafts.single().amountCents)
    }

    @Test
    fun `payment platform in a longer payment phrase remains recognized`() {
        val evidence = page(
            TextBlock("联系微信支付客服", 0, 10, 300, 30),
            TextBlock("商店", 0, 40, 300, 60),
            TextBlock("-¥20.00", 0, 70, 300, 90),
        )
        assertEquals(CaptureCandidate.PLATFORM_WECHAT, evidence.platform)
    }

}
