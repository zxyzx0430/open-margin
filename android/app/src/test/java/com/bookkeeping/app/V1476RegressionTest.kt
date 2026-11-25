package com.bookkeeping.app

import com.bookkeeping.app.capture.screenshot.ScreenshotPageInterpreter
import com.bookkeeping.app.capture.screenshot.TextBlock
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.DuplicateReviewPolicy
import com.bookkeeping.app.notification.accessibility.BillTextLine
import com.bookkeeping.app.notification.accessibility.PaymentCardDetector
import com.bookkeeping.app.notification.accessibility.PaymentCardKind
import com.bookkeeping.app.notification.accessibility.PaymentCardSource
import com.bookkeeping.app.notification.accessibility.PaymentDirection
import com.bookkeeping.app.notification.capture.CaptureCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class V1476RegressionTest {
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val nowMs = 1_795_578_900_000L

    private fun tx(
        id: Long = 1L,
        amountCents: Long = 3530L,
        type: String = "INCOME",
        merchant: String = "示例商户",
        source: String = "ALIPAY",
        timestamp: Long = nowMs,
        sourceTransactionId: String? = null,
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        type = type,
        category = "OTHER",
        merchant = merchant,
        source = source,
        timestamp = timestamp,
        sourceTransactionId = sourceTransactionId,
    )

    @Test
    fun `isSameSourceBurstDuplicate suppresses rapid identical no-orderId repeats`() {
        val existing = tx(id = 100L, timestamp = nowMs)
        val repeat = tx(id = 0L, timestamp = nowMs + 2_000L)
        assertTrue(DuplicateReviewPolicy.isSameSourceBurstDuplicate(existing, repeat))

        // Different source, manual entry, non-blank orderId, different merchant, or >15s apart -> false
        assertFalse(DuplicateReviewPolicy.isSameSourceBurstDuplicate(existing, repeat.copy(source = "WECHAT")))
        assertFalse(DuplicateReviewPolicy.isSameSourceBurstDuplicate(existing, repeat.copy(source = "MANUAL")))
        assertFalse(DuplicateReviewPolicy.isSameSourceBurstDuplicate(existing, repeat.copy(sourceTransactionId = "ORD12345678")))
        assertFalse(DuplicateReviewPolicy.isSameSourceBurstDuplicate(existing, repeat.copy(merchant = "另一商户")))
        assertFalse(DuplicateReviewPolicy.isSameSourceBurstDuplicate(existing, repeat.copy(timestamp = nowMs + 20_000L)))
    }

    @Test
    fun `Alipay bill detail refund with bare decimal amount parses as completed refund`() {
        val blocks = listOf(
            TextBlock("<账单详情", left = 40, top = 120, right = 240, bottom = 160),
            TextBlock("商户甲", left = 260, top = 260, right = 440, bottom = 300),
            TextBlock("5.70", left = 270, top = 340, right = 430, bottom = 400),
            TextBlock("退款成功", left = 280, top = 420, right = 420, bottom = 455),
            TextBlock("创建时间", left = 50, top = 600, right = 200, bottom = 635),
            TextBlock("2026-09-18 22:27:18", left = 240, top = 600, right = 600, bottom = 635),
            TextBlock("订单号", left = 50, top = 740, right = 180, bottom = 775),
            TextBlock("2026091823001121501417260565", left = 240, top = 740, right = 680, bottom = 775),
        )
        val evidence = ScreenshotPageInterpreter.interpret(blocks, nowMs, zone)
        assertTrue(evidence.transactionLike)
        assertEquals(CaptureCandidate.PLATFORM_ALIPAY, evidence.platform)
        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(570L, draft.amountCents)
        assertEquals(CaptureCandidate.KIND_REFUND, draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_INCOME, draft.direction)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, draft.completionStatus)
        assertEquals("商户甲", draft.merchantCandidates.firstOrNull())
    }

    @Test
    fun `Alipay bill detail negative payment with inline partial-refund badge parses as original expense`() {
        val blocks = listOf(
            TextBlock("<账单详情", left = 40, top = 120, right = 240, bottom = 160),
            TextBlock("商户乙", left = 290, top = 290, right = 410, bottom = 328),
            TextBlock("-71.60", left = 265, top = 349, right = 428, bottom = 406),
            TextBlock("已退款(¥35.30)>", left = 243, top = 426, right = 450, bottom = 453),
            TextBlock("支付时间", left = 50, top = 649, right = 180, bottom = 673),
            TextBlock("2026-08-09 10:07:18", left = 215, top = 649, right = 451, bottom = 673),
            TextBlock("订单号", left = 50, top = 710, right = 180, bottom = 735),
            TextBlock("2026080922001400001234567890", left = 215, top = 710, right = 660, bottom = 735),
        )
        val evidence = ScreenshotPageInterpreter.interpret(blocks, nowMs, zone)
        assertTrue(evidence.transactionLike)
        assertEquals(1, evidence.drafts.size)
        val draft = evidence.drafts.single()
        assertEquals(7160L, draft.amountCents)
        assertEquals(CaptureCandidate.KIND_PAYMENT, draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, draft.direction)
        assertEquals("商户乙", draft.merchantCandidates.firstOrNull())
    }

    @Test
    fun `PaymentCardDetector ignores inline partial-refund badge on negative Alipay bill detail`() {
        val lines = listOf(
            BillTextLine("账单详情", top = 120, bottom = 160, left = 40, right = 240),
            BillTextLine("商户乙", top = 290, bottom = 328, left = 290, right = 410),
            BillTextLine("-71.60", top = 349, bottom = 406, left = 265, right = 428),
            BillTextLine("已退款(¥35.30)>", top = 426, bottom = 453, left = 243, right = 450),
            BillTextLine("支付时间", top = 649, bottom = 673, left = 50, right = 180),
            BillTextLine("2026-08-09 10:07:18", top = 649, bottom = 673, left = 215, right = 451),
            BillTextLine("付款方式", top = 700, bottom = 730, left = 50, right = 180),
            BillTextLine("余额宝", top = 700, bottom = 730, left = 215, right = 340),
        )
        val cards = PaymentCardDetector.fromLines(
            PaymentCardDetector.ALIPAY,
            lines,
            nowMs,
            2400,
            "test-alipay-detail",
        )
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(PaymentCardSource.ALIPAY, card.source)
        assertEquals(7160L, card.amountCents)
        assertEquals(PaymentDirection.EXPENSE, card.direction)
        assertTrue(card.kind != PaymentCardKind.REFUND)
    }
}
