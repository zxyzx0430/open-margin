package com.bookkeeping.app.notification.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentPageDetectorTest {

    private fun snapshot(packageName: String, vararg texts: String): ScreenSnapshot =
        ScreenSnapshot.of(
            packageName,
            1_700_000_000_000L,
            SnapshotNode(children = texts.map { SnapshotNode(text = it) }),
        )

    private fun detect(packageName: String, vararg texts: String): PaymentPageDetection =
        PaymentPageDetector.detect(
            packageName,
            snapshot(packageName, *texts),
            1_700_000_000_000L,
        )

    @Test
    fun wechatSuccessPageWithMerchantAmountAndOrderIsAccepted() {
        val result = detect(
            PaymentPageDetector.PKG_WECHAT,
            "微信支付",
            "支付成功",
            "赵一鸣",
            "¥15.24",
            "订单号 4200001234567890",
            "银行卡",
        )
        assertTrue(result.isSuccessPage)
        assertEquals(1_524L, result.amountCents ?: -1L)
        assertEquals("4200001234567890", result.orderId)
        assertEquals(listOf("赵一鸣"), result.merchantCandidates)
        assertTrue(result.confidence >= 0.8f)
    }

    @Test
    fun alipaySuccessPageIsAccepted() {
        val result = detect(
            PaymentPageDetector.PKG_ALIPAY,
            "支付宝",
            "付款成功",
            "杭州游卡",
            "￥6.00",
            "交易号 ALI1234567890",
            "余额",
        )
        assertTrue(result.isSuccessPage)
        assertEquals(600L, result.amountCents ?: -1L)
        assertEquals("ALI1234567890", result.orderId)
        assertEquals(listOf("杭州游卡"), result.merchantCandidates)
    }

    @Test
    fun marketingAmountsAreIgnoredWhenOneActualAmountExists() {
        val result = detect(
            PaymentPageDetector.PKG_WECHAT,
            "支付成功",
            "星巴克",
            "原价 ¥17.24",
            "红包优惠 -2.00",
            "实付 ¥15.24",
            "订单号 4200001234567890",
        )
        assertTrue(result.isSuccessPage)
        assertEquals(1_524L, result.amountCents ?: -1L)
        assertEquals(listOf("星巴克"), result.merchantCandidates)
    }

    @Test
    fun multipleActualAmountsAreRejected() {
        val result = detect(
            PaymentPageDetector.PKG_WECHAT,
            "支付成功",
            "商户 A",
            "¥15.24",
            "另一笔 ¥99.00",
        )
        assertFalse(result.isSuccessPage)
        assertEquals("ambiguous amount", result.reason)
    }

    @Test
    fun failurePageIsRejected() {
        val result = detect(
            PaymentPageDetector.PKG_WECHAT,
            "支付失败",
            "商户 A",
            "¥15.24",
        )
        assertFalse(result.isSuccessPage)
        assertEquals("no success status", result.reason)
    }

    @Test
    fun passwordOrEditablePageIsRejectedEvenWithSuccessText() {
        val password = PaymentPageDetector.detect(
            PaymentPageDetector.PKG_WECHAT,
            ScreenSnapshot.of(
                PaymentPageDetector.PKG_WECHAT,
                1L,
                SnapshotNode(
                    children = listOf(
                        SnapshotNode(text = "支付成功"),
                        SnapshotNode(text = "¥15.24"),
                        SnapshotNode(text = "支付密码", password = true),
                    ),
                ),
            ),
            1L,
        )
        assertFalse(password.isSuccessPage)
        assertEquals("password field", password.reason)

        val editable = PaymentPageDetector.detect(
            PaymentPageDetector.PKG_ALIPAY,
            ScreenSnapshot.of(
                PaymentPageDetector.PKG_ALIPAY,
                1L,
                SnapshotNode(
                    children = listOf(
                        SnapshotNode(text = "付款成功"),
                        SnapshotNode(text = "￥6.00"),
                        SnapshotNode(text = "输入金额", editable = true),
                    ),
                ),
            ),
            1L,
        )
        assertFalse(editable.isSuccessPage)
        assertEquals("editable field", editable.reason)
    }

    @Test
    fun historyOrDetailPageWithoutSuccessStatusIsRejected() {
        val result = detect(
            PaymentPageDetector.PKG_WECHAT,
            "交易详情",
            "商户 A",
            "¥15.24",
        )
        assertFalse(result.isSuccessPage)
        assertEquals("no success status", result.reason)
    }

    @Test
    fun orderLabelWithoutNumericValueDoesNotCreateAnOrderId() {
        val result = detect(
            PaymentPageDetector.PKG_WECHAT,
            "支付成功",
            "商户 A",
            "¥15.24",
            "Transaction Details",
        )
        assertTrue(result.isSuccessPage)
        assertNull(result.orderId)
        assertEquals(listOf("商户 A"), result.merchantCandidates)
    }

    @Test
    fun pageIdentityIsStableForTheSameTextAndChangesWithNewText() {
        val first = detect(PaymentPageDetector.PKG_WECHAT, "支付成功", "商户 A", "¥15.24")
        val second = detect(PaymentPageDetector.PKG_WECHAT, "支付成功", "商户 A", "¥15.24")
        val third = detect(PaymentPageDetector.PKG_WECHAT, "支付成功", "商户 B", "¥15.24")
        assertEquals(first.pageIdentity, second.pageIdentity)
        assertFalse(first.pageIdentity == third.pageIdentity)
        assertNotNull(first.pageIdentity)
    }

    @Test
    fun unsupportedPackageIsRejected() {
        val result = detect("com.example.other", "支付成功", "¥15.24")
        assertFalse(result.isSuccessPage)
        assertEquals("unsupported package", result.reason)
    }
}
