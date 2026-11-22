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

    @Test fun distinctNotificationRecordKeysHaveDistinctRecordFingerprints() {
        val first = NoticeFingerprint.forSourceEvent("com.tencent.mm", "notice-key-1")
        val second = NoticeFingerprint.forSourceEvent("com.tencent.mm", "notice-key-2")
        assertNotEquals(first, second)
    }

    @Test fun sameNotificationRecordKeyKeepsItsRecordFingerprint() {
        val original = NoticeFingerprint.forSourceEvent("com.tencent.mm", "notice-key-1")
        val refreshed = NoticeFingerprint.forSourceEvent("com.tencent.mm", "notice-key-1")
        assertEquals(original, refreshed)
    }

    @Test fun exactPlatformOrderIdentityDoesNotDependOnNotificationBodyOrRecordKey() {
        val original = NoticeFingerprint.forPlatformTransaction("WECHAT", "ORDER-1042")
        val refreshed = NoticeFingerprint.forPlatformTransaction("wechat", " ORDER-1042 ")
        assertEquals(original, refreshed)
        assertEquals(
            original,
            NoticeFingerprint.forPlatformTransaction("WECHAT", "ORDER-1042"),
        )
    }

    @Test fun transactionIdentityIsScopedToPlatformAndExactOrderId() {
        val wechat = NoticeFingerprint.forPlatformTransaction("WECHAT", "ORDER-1042")
        val alipay = NoticeFingerprint.forPlatformTransaction("ALIPAY", "ORDER-1042")
        val nextOrder = NoticeFingerprint.forPlatformTransaction("WECHAT", "ORDER-1043")
        assertNotEquals(wechat, alipay)
        assertNotEquals(wechat, nextOrder)
    }

    @Test fun reusedRecordKeyCanReferToDistinctPlatformTransactions() {
        val record = NoticeFingerprint.forSourceEvent("com.tencent.mm", "reused-id-8")
        val first = NoticeFingerprint.forPlatformTransaction("WECHAT", "ORDER-A")
        val second = NoticeFingerprint.forPlatformTransaction("WECHAT", "ORDER-B")
        assertEquals(record, NoticeFingerprint.forSourceEvent("com.tencent.mm", "reused-id-8"))
        assertNotEquals(first, second)
    }

    @Test fun missingOrBlankSourceIdentityCannotCreateAReplayFingerprint() {
        assertEquals(null, NoticeFingerprint.forSourceEvent("com.tencent.mm", null))
        assertEquals(null, NoticeFingerprint.forSourceEvent("com.tencent.mm", "  "))
    }
}
