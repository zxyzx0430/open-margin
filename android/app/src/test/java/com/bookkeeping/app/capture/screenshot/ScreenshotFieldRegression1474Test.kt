package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Anonymized geometry fixtures reconstructed from field OCR and screenshot layouts. */
class ScreenshotFieldRegression1474Test {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val now = Calendar.getInstance(zone).apply { clear(); set(2026, 10, 25, 3, 0) }.timeInMillis
    private fun b(t: String, y: Int, x: Int = 55, right: Int = 610) =
        TextBlock(t, x, y, right, y + 24)
    private fun stream(vararg rows: TextBlock) = listOf(b("微信支付", 105)) +
        rows.toList() + listOf(b("我的账单", 1450, 90, 200), b("支付服务", 1450, 300, 440), b("优惠", 1450, 520, 620))
    private fun plan(blocks: List<TextBlock>, existing: List<LedgerFingerprint> = emptyList()) =
        ScreenshotImportPipeline.plan(
            ScreenshotIntakeRequest(IntakeSource.MEDIASTORE, "content://media/external/images/media/9", "image/jpeg", now, byteSize = 300000),
            blocks, existing, timeZone = zone,
        )

    @Test fun dailyReportDoesNotHidePayment() {
        val blocks = stream(
            b("记账日报", 200), b("11月23日(周一)日报", 250), b("昨日支出", 320),
            b("¥21.83，共6笔", 320, 220), b("昨日入账", 380), b("¥500.03，共3笔", 380, 220),
            b("本月统计", 440), b("11月已支出¥652.04，已入账", 440, 220), b("¥1412.34", 480, 220),
            b("点击查看昨日收支明细，还能补充其他收支", 540), b("查看明细", 620), b("日报设置", 690),
            b("昨天 21:25", 800, 285, 405), b("充电商户", 900, 110, 260),
            b("使用中国银行储蓄卡(1234)支付", 1030, 165, 530),
            b("¥4.00", 1100, 255, 435), b("账单详情 >", 1200, 280, 420),
        )
        val result = plan(blocks)
        assertEquals(1, result.decisions.size)
        val d = result.decisions.single()
        assertEquals(400L, d.draft.amountCents)
        assertEquals("充电商户", d.draft.merchantCandidates.first())
        assertEquals(Action.AUTO_BOOK, d.action)
    }

    @Test fun pureReportIsNotMoney() {
        val result = plan(stream(b("记账日报", 200), b("昨日入账", 300), b("¥500.03", 300, 220), b("日报设置", 500)))
        assertTrue(result.bookings.isEmpty())
        assertTrue(result.decisions.isEmpty())
    }

    @Test fun clippedNextCardDoesNotPolluteSinglePayment() {
        val result = plan(stream(b("善款使用通知", 220), b("查看详情", 500),
            b("周一16:43", 605, 280, 410), b("面包店", 700, 110, 270),
            b("使用中国银行储蓄卡(1234)支付", 835, 165, 530), b("¥7.00", 920, 250, 440),
            b("账单详情 >", 1005, 280, 420), b("周一17:49", 1260, 280, 410), b("零钱提现发起", 1350)))
        assertEquals(1, result.decisions.size)
        assertEquals(700L, result.decisions.single().draft.amountCents)
        assertEquals(Action.AUTO_BOOK, result.decisions.single().action)
    }

    @Test fun separatedPaymentAndFutureDebitKeepOwnStatus() {
        val result = plan(stream(b("11月19日22:01", 240, 270, 440), b("餐饮商户", 340, 110, 310),
            b("使用零钱支付", 480, 270, 430), b("¥10.00", 560, 245, 440), b("交易详情 >", 650, 280, 420),
            b("11月20日02:17", 900, 270, 440), b("购物商户", 1000, 110, 260),
            b("「支付分先用后付」订单已生成", 1100), b("预计扣费金额", 1200), b("6.77元", 1200, 290, 400),
            b("预计扣费时间", 1290), b("11月30日自动确认收货后扣款", 1290, 290, 650)))
        assertEquals(Action.AUTO_BOOK, result.decisions.single { it.draft.amountCents == 1000L }.action)
        assertEquals(Action.WAIT_FOR_EVIDENCE, result.decisions.single { it.draft.amountCents == 677L }.action)
    }

    @Test fun croppedPaymentBelowAnotherCardUsesOwnDateAndMerchant() {
        val result = plan(stream(b("11月19日13:30", 170, 270, 440), b("商户甲", 270, 110, 300),
            b("使用零钱支付", 400, 270, 430), b("¥48.00", 490, 245, 440),
            b("支付成功，对方已收款", 610, 210, 540), b("查看交易详情", 710),
            b("11月19日14:01", 1020, 270, 440), b("商户乙", 1110, 110, 250),
            b("使用零钱支付", 1250, 270, 430), b("¥17.90", 1330, 245, 440)))
        assertEquals(2, result.bookings.size)
        assertEquals(listOf("商户甲", "商户乙"), result.bookings.map { it.draft.merchantCandidates.first() })
        assertNotEquals(result.bookings[0].draft.eventTimeMs, result.bookings[1].draft.eventTimeMs)
    }

    @Test fun ledgerResemblanceIsExplicitRatherThanGenericReview() {
        val blocks = stream(b("11月16日15:59", 195, 270, 440), b("饮品店", 285, 110, 250),
            b("使用零钱支付", 420, 270, 430), b("¥2.00", 510, 250, 440), b("交易详情 >", 590, 280, 420))
        val draft = plan(blocks).decisions.single().draft
        val result = plan(blocks, listOf(LedgerFingerprint(7, CaptureCandidate.PLATFORM_WECHAT, 200,
            CaptureCandidate.DIRECTION_EXPENSE, merchant = "饮品店", eventTimeMs = requireNotNull(draft.eventTimeMs))))
        val d = result.decisions.single()
        assertEquals(Action.ASK_USER, d.action)
        assertEquals(DuplicateHint.LEDGER_AMBIGUOUS, d.duplicateHint)
    }

    @Test fun oppositeComplementaryChatWithoutTimeIsOneReview() {
        val blocks = listOf(b("联系人", 105, 290, 400), b("在吗", 250, 100, 220),
            b("¥0.01", 745, 210, 320), b("已被接收", 780, 210, 330), b("转账", 830, 120, 170),
            b("找到了", 900, 110, 210), b("¥0.01", 1000, 400, 510), b("已收款", 1040, 400, 510),
            b("转账", 1090, 180, 240), b("按住说话", 1450, 260, 440))
        val result = plan(blocks)
        assertEquals(1, result.decisions.size)
        assertEquals(CaptureCandidate.DIRECTION_INCOME, result.decisions.single().draft.direction)
        assertEquals(Action.ASK_USER, result.decisions.single().action)
        assertNull(result.decisions.single().draft.eventTimeMs)
    }
}
