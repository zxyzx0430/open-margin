package com.bookkeeping.app.notification.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredPaymentPagePolicyTest {
    private fun event(pkg: String = "wechat", window: Int = 1, state: Boolean = false, activity: String = "") =
        DeferredPaymentPageEvent(pkg, window, activity, state)

    @Test
    fun `latest different window or package replaces the older hint`() {
        val old = event(state = true, activity = "OldActivity")
        val newWindow = event(window = 2)
        assertEquals(newWindow, DeferredPaymentPagePolicy.coalesce(old, newWindow))
        val newPackage = event(pkg = "alipay")
        assertEquals(newPackage, DeferredPaymentPagePolicy.coalesce(old, newPackage))
    }

    @Test
    fun `same window content event preserves latest activity hint`() {
        val old = event(state = true, activity = "PayActivity")
        assertEquals(old, DeferredPaymentPagePolicy.coalesce(old, event()))
        val replacement = event(state = true, activity = "NewActivity")
        assertEquals(replacement, DeferredPaymentPagePolicy.coalesce(old, replacement))
    }

    @Test
    fun `unchanged page cannot trigger a replay loop but changed page can`() {
        val key = DeferredPaymentPageKey("wechat", 1, "PayActivity", 12)
        assertFalse(DeferredPaymentPagePolicy.shouldReplay(key, key.copy()))
        assertTrue(DeferredPaymentPagePolicy.shouldReplay(key, key.copy(textHash = 13)))
        assertTrue(DeferredPaymentPagePolicy.shouldReplay(key, key.copy(windowId = 2)))
        assertTrue(DeferredPaymentPagePolicy.shouldReplay(key, key.copy(activity = "OtherActivity")))
        assertTrue(DeferredPaymentPagePolicy.shouldReplay(key, key.copy(packageName = "alipay")))
    }

    @Test
    fun `replay rejects stale positive window identifiers`() {
        assertFalse(DeferredPaymentPagePolicy.isCurrentWindow(1, 2))
        assertTrue(DeferredPaymentPagePolicy.isCurrentWindow(2, 2))
        assertTrue(DeferredPaymentPagePolicy.isCurrentWindow(-1, 2))
    }

    @Test
    fun `ocr outcome classifications preserve the reason without raw text`() {
        assertEquals("engine_error", OcrPageOutcomePolicy.noCandidate(true, true, 0))
        assertEquals("empty_text", OcrPageOutcomePolicy.noCandidate(false, true, 0))
        assertEquals("no_transaction_cards", OcrPageOutcomePolicy.noCandidate(false, false, 0))
        assertEquals("no_bookable_candidate", OcrPageOutcomePolicy.noCandidate(false, false, 1))
    }
}
