package com.bookkeeping.app.subscription

import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.subscription.SubscriptionFxRatesCalculator
import com.bookkeeping.app.notification.SubscriptionReminderWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFxRatesTest {

    @Test
    fun convertsOfflineCurrenciesAndMergesServerRates() {
        assertEquals(1999L, SubscriptionFxRatesCalculator.estimateCnyCents(1999L, "CNY"))
        assertEquals(14493L, SubscriptionFxRatesCalculator.estimateCnyCents(1999L, "USD"))
        assertEquals(930L, SubscriptionFxRatesCalculator.estimateCnyCents(1000L, "HKD"))
        assertEquals(7850L, SubscriptionFxRatesCalculator.estimateCnyCents(1000L, "EUR"))
        assertEquals(480L, SubscriptionFxRatesCalculator.estimateCnyCents(10000L, "JPY"))
        assertEquals(9150L, SubscriptionFxRatesCalculator.estimateCnyCents(1000L, "GBP"))
        assertNull(SubscriptionFxRatesCalculator.estimateCnyCents(1000L, "ZZZ"))
        assertNull(SubscriptionFxRatesCalculator.estimateCnyCents(-100L, "USD"))

        val merged = SubscriptionFxRatesCalculator.parseRatesJson("""{"USD":7.10,"CAD":5.12}""")
        assertEquals(7.10, merged["USD"] ?: 0.0, 0.0001)
        assertEquals(5.12, merged["CAD"] ?: 0.0, 0.0001)
        assertEquals(0.93, merged["HKD"] ?: 0.0, 0.0001)
        assertEquals(1.0, merged["CNY"] ?: 0.0, 0.0001)
    }

    @Test
    fun reminderWordingDistinguishesNormalAndPendingCancel() {
        val normal = SubscriptionEntity(
            syncUuid = "s1",
            name = "手机话费",
            amountCents = 4700L,
            currency = "CNY",
            cadence = SubscriptionCadence.MONTHLY,
            nextDueMs = 1797264000000L,
            paymentChannel = "微信",
            pendingCancel = false,
            updatedAtMs = 1L,
        )
        val (normalTitle, normalBody) = SubscriptionReminderWorker.buildReminderContent(normal, 3)
        assertTrue(normalTitle.contains("[续费提醒]"))
        assertTrue(normalBody.contains("手机话费"))
        assertTrue(normalBody.contains("3 天后"))
        assertTrue(normalBody.contains("¥47"))
        assertTrue(normalBody.contains("微信"))

        val pendingCancel = normal.copy(
            name = "腾讯视频",
            amountCents = 2500L,
            pendingCancel = true,
        )
        val (cancelTitle, cancelBody) = SubscriptionReminderWorker.buildReminderContent(pendingCancel, 3)
        assertTrue(cancelTitle.contains("[准备取消]"))
        assertTrue(cancelBody.contains("[准备取消]"))
        assertTrue(cancelBody.contains("关闭自动续费"))
        assertTrue(cancelBody.contains("¥25"))
    }
}
