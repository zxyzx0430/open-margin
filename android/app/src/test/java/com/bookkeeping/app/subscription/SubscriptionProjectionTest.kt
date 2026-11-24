package com.bookkeeping.app.subscription

import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SubscriptionProjectionTest {
    private val tz: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun sampleSub(
        name: String = "ChatGPT Plus",
        amountCents: Long = 14500L,
        currency: String = "CNY",
        cadence: String = SubscriptionCadence.MONTHLY,
        nextDueMs: Long = dateMs(2026, 11, 15),
        startMs: Long? = null,
        active: Boolean = true,
        deletedAtMs: Long? = null,
    ) = SubscriptionEntity(
        id = 1L,
        syncUuid = "sub-1",
        name = name,
        amountCents = amountCents,
        currency = currency,
        cadence = cadence,
        nextDueMs = nextDueMs,
        startMs = startMs,
        active = active,
        deletedAtMs = deletedAtMs,
        updatedAtMs = 1763950000000L,
    )

    @Test
    fun monthEnd31stHandlesLeapAndNonLeapFebruaryAndRecovers31stInMarch() {
        // Leap year 2024: Jan 31 -> Feb 29 -> Mar 31
        val jan31Leap = dateMs(2024, 1, 31)
        val febLeap = SubscriptionProjection.advanceDueMs(jan31Leap, SubscriptionCadence.MONTHLY, 31, tz)
        assertEquals(dateMs(2024, 2, 29), febLeap)
        val marLeap = SubscriptionProjection.advanceDueMs(febLeap, SubscriptionCadence.MONTHLY, 31, tz)
        assertEquals(dateMs(2024, 3, 31), marLeap)

        // Non-leap year 2025: Jan 31 -> Feb 28 -> Mar 31
        val jan31NonLeap = dateMs(2025, 1, 31)
        val febNonLeap = SubscriptionProjection.advanceDueMs(jan31NonLeap, SubscriptionCadence.MONTHLY, 31, tz)
        assertEquals(dateMs(2025, 2, 28), febNonLeap)
        val marNonLeap = SubscriptionProjection.advanceDueMs(febNonLeap, SubscriptionCadence.MONTHLY, 31, tz)
        assertEquals(dateMs(2025, 3, 31), marNonLeap)
    }

    @Test
    fun yearlyLeapDayClampsToFeb28AndRestoresOnNextLeapYear() {
        val leap2024 = dateMs(2024, 2, 29)
        val y2025 = SubscriptionProjection.advanceDueMs(leap2024, SubscriptionCadence.YEARLY, 29, tz)
        assertEquals(dateMs(2025, 2, 28), y2025)
        val y2028 = SubscriptionProjection.advanceDueMs(dateMs(2027, 2, 28), SubscriptionCadence.YEARLY, 29, tz)
        assertEquals(dateMs(2028, 2, 29), y2028)
    }

    @Test
    fun weeklyOccurrencesAndForecastFindHeaviestPeakMonth() {
        val weekly = sampleSub(
            name = "买菜周卡",
            amountCents = 1000L,
            cadence = SubscriptionCadence.WEEKLY,
            nextDueMs = dateMs(2026, 11, 2),
        )
        val novOccurrences = SubscriptionProjection.occurrencesInMonth(weekly, 2026, 11, tz)
        // Nov 2, 9, 16, 23, 30 -> 5 occurrences
        assertEquals(5, novOccurrences.size)

        val annual = sampleSub(
            name = "淘宝 88VIP",
            amountCents = 8800L,
            cadence = SubscriptionCadence.YEARLY,
            nextDueMs = dateMs(2027, 3, 15),
        ).copy(syncUuid = "sub-2")

        val forecast = SubscriptionProjection.forecast12Months(
            subscriptions = listOf(weekly, annual),
            startYear = 2026,
            startMonth = 11,
            timeZone = tz,
        )
        assertEquals(12, forecast.months.size)
        val march2027 = forecast.months.first { it.year == 2027 && it.month == 3 }
        // March 2027 has Mondays on 1, 8, 15, 22, 29 (5 * 1000) + annual 8800 = 13800
        assertEquals(13800L, march2027.totalCnyCents)
        assertNotNull(forecast.peakMonth)
        assertEquals(2027, forecast.peakMonth?.year)
        assertEquals(3, forecast.peakMonth?.month)
        assertEquals(13800L, forecast.peakMonth?.totalCnyCents)
    }

    @Test
    fun monthlyEquivalentComputesForeignCurrencyAndCadences() {
        val usdMonthly = sampleSub(
            amountCents = 1000L,
            currency = "USD",
            cadence = SubscriptionCadence.MONTHLY,
        )
        assertEquals(7250L, SubscriptionProjection.monthlyEquivalentCnyCents(usdMonthly))

        val cnyYearly = sampleSub(
            amountCents = 12000L,
            currency = "CNY",
            cadence = SubscriptionCadence.YEARLY,
        )
        assertEquals(1000L, SubscriptionProjection.monthlyEquivalentCnyCents(cnyYearly))
    }

    @Test
    fun matchesTransactionChecksMerchantAmountToleranceAndThreeDayWindow() {
        val sub = sampleSub(
            name = "OpenAI · ChatGPT Plus",
            amountCents = 14000L,
            currency = "CNY",
            nextDueMs = dateMs(2026, 11, 15),
        )
        val matchingTx = TransactionEntity(
            id = 10L,
            amountCents = 14500L, // +3.57% within 5%
            type = "EXPENSE",
            category = "SUBSCRIPTION",
            merchant = "ChatGPT",
            source = "ALIPAY",
            timestamp = dateMs(2026, 11, 17), // +2 days within +-3 days
        )
        assertTrue(SubscriptionProjection.matchesTransaction(sub, matchingTx, timeZone = tz))

        val tooFarAmount = matchingTx.copy(amountCents = 15000L) // +7.14% > 5%
        assertFalse(SubscriptionProjection.matchesTransaction(sub, tooFarAmount, timeZone = tz))

        val outsideWindow = matchingTx.copy(timestamp = dateMs(2026, 11, 20)) // +5 days > 3 days
        assertFalse(SubscriptionProjection.matchesTransaction(sub, outsideWindow, timeZone = tz))

        val excludedTx = matchingTx.copy(remoteReviewStatus = "refund")
        assertFalse(SubscriptionProjection.matchesTransaction(sub, excludedTx, timeZone = tz))
    }
}
