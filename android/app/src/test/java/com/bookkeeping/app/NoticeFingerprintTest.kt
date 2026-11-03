package com.bookkeeping.app

import com.bookkeeping.app.notification.NoticeFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NoticeFingerprintTest {

    private val base = NoticeFingerprint.of(
        packageName = "com.tencent.mm",
        title = "微信支付",
        body = "向 麦当劳 付款 12.30 元",
        originalTimeMs = 1_700_000_000_000L,
    )

    @Test fun deterministicAndHex64() {
        val again = NoticeFingerprint.of(
            "com.tencent.mm", "微信支付", "向 麦当劳 付款 12.30 元", 1_700_000_000_000L,
        )
        assertEquals(base, again)
        assertEquals(64, base.length)
    }

    @Test fun whitespaceAndCaseNormalized() {
        val messy = NoticeFingerprint.of(
            "com.tencent.mm", "  微信支付 ", "向  麦当劳   付款 12.30 元", 1_700_000_000_000L,
        )
        assertEquals(base, messy)
        val latin = NoticeFingerprint.of("com.eg.android.AlipayGphone", "ALIPAY", "Paid 9.90", 5L)
        val latinLower = NoticeFingerprint.of("com.eg.android.AlipayGphone", "alipay", "paid 9.90", 5L)
        assertEquals(latin, latinLower)
    }

    @Test fun differentTimeBodyOrPackageDiffer() {
        assertNotEquals(base, NoticeFingerprint.of(
            "com.tencent.mm", "微信支付", "向 麦当劳 付款 12.30 元", 1_700_000_001_000L))
        assertNotEquals(base, NoticeFingerprint.of(
            "com.tencent.mm", "微信支付", "向 麦当劳 付款 12.31 元", 1_700_000_000_000L))
        assertNotEquals(base, NoticeFingerprint.of(
            "com.eg.android.AlipayGphone", "微信支付", "向 麦当劳 付款 12.30 元", 1_700_000_000_000L))
    }

    @Test fun nullTitleAndBodyStillStable() {
        val a = NoticeFingerprint.of("com.tencent.mm", null, null, 42L)
        val b = NoticeFingerprint.of("com.tencent.mm", "", "  ", 42L)
        assertEquals(a, b)
    }

    @Test fun fieldBoundaryNotAmbiguous() {
        // 归一化后字段整体平移不能撞指纹（分隔符是不可见控制字符，不是空格）。
        val x = NoticeFingerprint.of("pkg", "ab", "c", 1L)
        val y = NoticeFingerprint.of("pkg", "a", "bc", 1L)
        assertNotEquals(x, y)
    }
}
