package com.bookkeeping.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCallbackBurstFilterTest {

    @Test
    fun `identical callback within 3s window is suppressed`() {
        val filter = NotificationCallbackBurstFilter(windowMs = 3_000L)
        val key = "0|com.tencent.mm|101|null|1000|1795526913560|微信支付\n已支付¥4.00"
        assertFalse(filter.shouldSuppress(key, nowMs = 1_000L))
        assertTrue(filter.shouldSuppress(key, nowMs = 1_002L))
        assertTrue(filter.shouldSuppress(key, nowMs = 3_999L))
        assertFalse(filter.shouldSuppress(key, nowMs = 4_001L))
    }

    @Test
    fun `distinct payment text is never suppressed`() {
        val filter = NotificationCallbackBurstFilter(windowMs = 3_000L)
        assertFalse(filter.shouldSuppress("k1|已支付¥4.00", nowMs = 1_000L))
        assertFalse(filter.shouldSuppress("k1|已支付¥5.00", nowMs = 1_001L))
    }
}
