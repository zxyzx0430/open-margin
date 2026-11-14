package com.bookkeeping.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillParserTest {
    @Test
    fun parsesSuccessfulWechatPayment() {
        val bill = BillParser.parse(
            listOf(
                "微信支付",
                "支付成功",
                "商户：星巴克咖啡（杭州店）",
                "实付金额 ¥38.00",
                "订单号：4200001234567890",
                "2026-11-13 10:08:22",
            ),
        )
        assertEquals(3800L, bill.amountCents)
        assertEquals(BillStatus.PAID, bill.status)
        assertEquals("4200001234567890", bill.orderId)
        assertTrue(bill.merchantCandidates.contains("星巴克咖啡"))
        assertNotNull(bill.timestampMs)
    }

    @Test
    fun rejectsMultipleUnmarkedAmounts() {
        val bill = BillParser.parse(listOf("商户：A", "¥10.00", "¥20.00"))
        assertNull(bill.amountCents)
        assertTrue(bill.warnings.contains("multiple_amounts"))
    }

    @Test
    fun prefersActualPaidAmountOverDiscountAndOriginal() {
        val bill = BillParser.parse(
            listOf(
                "商户：盒马鲜生",
                "原价 ¥50.00",
                "优惠 ¥10.00",
                "实付 ¥40.00",
            ),
        )
        assertEquals(4000L, bill.amountCents)
    }

    @Test
    fun recognizesRefundAndFailedStates() {
        val refund = BillParser.parse(
            listOf("退款成功", "退款金额 ¥38.00", "商户：星巴克"),
        )
        assertEquals(BillStatus.REFUNDED, refund.status)
        assertEquals(3800L, refund.amountCents)

        val failed = BillParser.parse(listOf("支付失败", "请重试"))
        assertEquals(BillStatus.FAILED, failed.status)
        assertNull(failed.amountCents)

        val pending = BillParser.parse(listOf("待支付 ¥20.00", "订单号：ABC123456"))
        assertEquals(BillStatus.PENDING, pending.status)
        assertEquals(2000L, pending.amountCents)
    }

    @Test
    fun fallsBackToMerchantLineAndTodayTime() {
        val bill = BillParser.parse(
            listOf("星巴克", "¥38.00", "今天 10:08"),
            nowMs = 1_800_000_000_000L,
        )
        assertTrue(bill.merchantCandidates.contains("星巴克"))
        assertFalse(bill.merchantCandidates.any { it.contains("今天") })
        assertNotNull(bill.timestampMs)
    }
}
