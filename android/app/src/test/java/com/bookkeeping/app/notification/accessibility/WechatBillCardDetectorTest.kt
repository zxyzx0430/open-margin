package com.bookkeeping.app.notification.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WechatBillCardDetectorTest {

    private val capturedAt = 1_795_000_000_000L

    private fun line(text: String, top: Int): BillTextLine =
        BillTextLine(text = text, top = top, bottom = top + 40, left = 40, right = 1000)

    @Test
    fun parsesRefundArrivalCardWithMerchantAndTime() {
        val lines = listOf(
            line("昨天 21:21", 100),
            line("自来充", 160),
            line("使用零钱支付", 240),
            line("¥6.00", 320),
            line("账单详情", 400),
            line("05:27", 700),
            line("退款到账通知", 800),
            line("退款金额", 880),
            line("¥1.45", 940),
            line("商品详情", 1040),
            line("充电订单-预付6元", 1090),
            line("商户名称", 1160),
            line("自来充", 1210),
            line("退款方式", 1280),
            line("退回零钱", 1330),
            line("到账时间", 1400),
            line("2026年11月14日 05:26:54", 1450),
        )
        val cards = WechatBillCardDetector.fromLines(
            WechatBillCardDetector.WECHAT, lines, capturedAt, 2400, "test-page",
        )
        assertEquals(2, cards.size)
        val refund = cards.single { it.kind == BillCardKind.REFUND }
        assertEquals(145L, refund.amountCents)
        assertEquals(listOf("自来充"), refund.merchantCandidates)
        assertTrue(refund.confidence >= 0.9f)
        val time = Calendar.getInstance().apply { timeInMillis = refund.eventTimeMs!! }
        assertEquals(2026, time.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, time.get(Calendar.MONTH))
        assertEquals(14, time.get(Calendar.DAY_OF_MONTH))
        assertEquals(5, time.get(Calendar.HOUR_OF_DAY))
        assertEquals(26, time.get(Calendar.MINUTE))
    }

    @Test
    fun parsesPayAfterUseAutoPaymentCard() {
        val lines = listOf(
            line("15:31", 100),
            line("拼多多平台商户", 160),
            line("先用后付订单已完成，已自动支付", 240),
            line("通过零钱扣款", 320),
            line("¥8.40", 400),
            line("按时支付，记入微信支付分记录", 480),
            line("交易详情", 560),
            line("下单时间", 660),
            line("11月3日 23:36", 710),
            line("管理扣费服务", 800),
            line("查看商家订单", 880),
        )
        val cards = WechatBillCardDetector.fromLines(
            WechatBillCardDetector.WECHAT, lines, capturedAt, 2400, "auto-page",
        )
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(BillCardKind.AUTO_PAY, card.kind)
        assertEquals(840L, card.amountCents)
        assertEquals(listOf("拼多多平台商户"), card.merchantCandidates)
        assertNotNull(card.eventTimeMs)
        val time = Calendar.getInstance().apply { timeInMillis = card.eventTimeMs!! }
        assertEquals(15, time.get(Calendar.HOUR_OF_DAY))
        assertEquals(31, time.get(Calendar.MINUTE))
    }

    @Test
    fun parsesNormalMerchantPaymentCard() {
        val lines = listOf(
            line("昨天 21:21", 100),
            line("自来充", 160),
            line("使用零钱支付", 240),
            line("¥6.00", 320),
            line("账单详情", 400),
        )
        val cards = WechatBillCardDetector.fromLines(
            WechatBillCardDetector.WECHAT, lines, capturedAt, 2400, "pay-page",
        )
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(BillCardKind.PAYMENT, card.kind)
        assertEquals(600L, card.amountCents)
        assertEquals(listOf("自来充"), card.merchantCandidates)
    }

    @Test
    fun splitsMultipleCardsAndRejectsSummary() {
        val lines = listOf(
            line("本月统计", 80),
            line("11月已支出 ¥4251.87", 140),
            line("点击查看昨日收支明细，还能补充其他收支", 200),
            line("查看明细", 260),
            line("日报设置", 320),
            line("自来充", 700),
            line("使用零钱支付", 780),
            line("¥6.00", 860),
            line("账单详情", 940),
            line("退款到账通知", 1300),
            line("退款金额", 1380),
            line("¥1.45", 1440),
            line("商户名称", 1520),
            line("自来充", 1570),
            line("退款方式", 1640),
            line("退回零钱", 1690),
            line("到账时间", 1760),
            line("2026年11月14日 05:26:54", 1810),
        )
        val cards = WechatBillCardDetector.fromLines(
            WechatBillCardDetector.WECHAT, lines, capturedAt, 2400, "multi-page",
        )
        assertEquals(2, cards.size)
        assertTrue(cards.any { it.kind == BillCardKind.PAYMENT && it.amountCents == 600L })
        assertTrue(cards.any { it.kind == BillCardKind.REFUND && it.amountCents == 145L })
    }

    @Test
    fun rejectsFailureAndUnrelatedCards() {
        val lines = listOf(
            line("退款失败", 100),
            line("退款金额", 180),
            line("¥1.45", 240),
        )
        val cards = WechatBillCardDetector.fromLines(
            WechatBillCardDetector.WECHAT, lines, capturedAt, 2400, "failure-page",
        )
        assertTrue(cards.isEmpty())
        assertTrue(
            WechatBillCardDetector.fromLines(
                "com.example.other", lines, capturedAt, 2400, "other",
            ).isEmpty(),
        )
    }

    @Test
    fun refundTimeFallsBackToCapturedAtWhenLabelMissing() {
        val lines = listOf(
            line("退款到账通知", 100),
            line("退款金额", 180),
            line("¥1.45", 240),
            line("商户名称", 320),
            line("自来充", 370),
            line("退款方式", 440),
            line("退回零钱", 490),
        )
        val card = WechatBillCardDetector.fromLines(
            WechatBillCardDetector.WECHAT, lines, capturedAt, 2400, "refund-page",
        ).single()
        assertNotNull(card.eventTimeMs)
        assertEquals(true, card.eventTimeMs!! > 0L)
    }
}
