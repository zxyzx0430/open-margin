package com.bookkeeping.app.data.subscription

import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

data class MonthlyForecastPoint(
    val year: Int,
    val month: Int,
    val totalCnyCents: Long,
    val occurrenceCount: Int,
)

data class TwelveMonthForecast(
    val months: List<MonthlyForecastPoint>,
    val peakMonth: MonthlyForecastPoint?,
)

object SubscriptionProjection {
    private const val DAY_MS = 86_400_000L
    private const val MAX_ADVANCE_STEPS = 2000

    fun startOfDayMs(timestampMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestampMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun dayOfMonth(timestampMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestampMs
        }
        return cal.get(Calendar.DAY_OF_MONTH)
    }

    fun advanceDueMs(
        dueMs: Long,
        cadence: String,
        anchorDayOfMonth: Int? = null,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = dueMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetDay = anchorDayOfMonth ?: cal.get(Calendar.DAY_OF_MONTH)
        when (cadence.lowercase(Locale.US)) {
            SubscriptionCadence.WEEKLY -> cal.add(Calendar.DAY_OF_MONTH, 7)
            SubscriptionCadence.MONTHLY -> addMonthsClamped(cal, 1, targetDay)
            SubscriptionCadence.QUARTERLY -> addMonthsClamped(cal, 3, targetDay)
            SubscriptionCadence.YEARLY -> addYearsClamped(cal, 1, targetDay)
            else -> addMonthsClamped(cal, 1, targetDay)
        }
        return cal.timeInMillis
    }

    private fun addMonthsClamped(cal: Calendar, months: Int, targetDay: Int) {
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MONTH, months)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, min(targetDay, maxDay))
    }

    private fun addYearsClamped(cal: Calendar, years: Int, targetDay: Int) {
        val month = cal.get(Calendar.MONTH)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.YEAR, years)
        cal.set(Calendar.MONTH, month)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, min(targetDay, maxDay))
    }

    fun advancePast(
        nextDueMs: Long,
        cadence: String,
        referenceMs: Long,
        startMs: Long? = null,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val targetStartMs = startOfDayMs(referenceMs, timeZone)
        var cursor = startOfDayMs(nextDueMs, timeZone)
        val anchorDay = dayOfMonth(startMs ?: cursor, timeZone)
        var steps = 0
        while (cursor < targetStartMs && steps < MAX_ADVANCE_STEPS) {
            val next = advanceDueMs(cursor, cadence, anchorDay, timeZone)
            if (next <= cursor) break
            cursor = next
            steps++
        }
        return cursor
    }

    fun occurrencesInMonth(
        subscription: SubscriptionEntity,
        year: Int,
        month: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<Long> {
        if (!subscription.active || subscription.deletedAtMs != null) return emptyList()
        if (month !in 1..12) return emptyList()

        val monthStart = Calendar.getInstance(timeZone).apply {
            clear()
            set(year, month - 1, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthStartMs = monthStart.timeInMillis
        val nextMonthStart = Calendar.getInstance(timeZone).apply {
            timeInMillis = monthStartMs
            add(Calendar.MONTH, 1)
        }
        val nextMonthStartMs = nextMonthStart.timeInMillis

        var cursor = startOfDayMs(subscription.nextDueMs, timeZone)
        val anchorDay = dayOfMonth(subscription.startMs ?: cursor, timeZone)
        val occurrences = mutableListOf<Long>()
        var steps = 0
        while (cursor < nextMonthStartMs && steps < MAX_ADVANCE_STEPS) {
            if (cursor >= monthStartMs) {
                occurrences.add(cursor)
            }
            val next = advanceDueMs(cursor, subscription.cadence, anchorDay, timeZone)
            if (next <= cursor) break
            cursor = next
            steps++
        }
        return occurrences
    }

    fun estimateCnyCents(
        subscription: SubscriptionEntity,
        fxRates: Map<String, Double> = SubscriptionFxRatesCalculator.DEFAULT_RATES,
    ): Long {
        if (subscription.currency.equals("CNY", ignoreCase = true)) {
            return subscription.amountCents
        }
        return subscription.cnyEstimateCents
            ?: SubscriptionFxRatesCalculator.estimateCnyCents(
                subscription.amountCents,
                subscription.currency,
                fxRates,
            )
            ?: 0L
    }

    fun monthlyEquivalentCnyCents(
        subscription: SubscriptionEntity,
        fxRates: Map<String, Double> = SubscriptionFxRatesCalculator.DEFAULT_RATES,
    ): Long {
        if (!subscription.active || subscription.deletedAtMs != null) return 0L
        val baseCny = estimateCnyCents(subscription, fxRates)
        val factor = when (subscription.cadence.lowercase(Locale.US)) {
            SubscriptionCadence.MONTHLY -> 1.0
            SubscriptionCadence.YEARLY -> 1.0 / 12.0
            SubscriptionCadence.QUARTERLY -> 1.0 / 3.0
            SubscriptionCadence.WEEKLY -> 52.0 / 12.0
            else -> 0.0
        }
        return (baseCny * factor).roundToLong()
    }

    fun forecast12Months(
        subscriptions: List<SubscriptionEntity>,
        startYear: Int,
        startMonth: Int,
        fxRates: Map<String, Double> = SubscriptionFxRatesCalculator.DEFAULT_RATES,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): TwelveMonthForecast {
        val activeSubs = subscriptions.filter { it.active && it.deletedAtMs == null }
        val points = mutableListOf<MonthlyForecastPoint>()
        val cal = Calendar.getInstance(timeZone).apply {
            clear()
            set(startYear, startMonth - 1, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(12) {
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            var monthTotal = 0L
            var monthCount = 0
            for (sub in activeSubs) {
                val hits = occurrencesInMonth(sub, y, m, timeZone).size
                if (hits > 0) {
                    monthTotal += estimateCnyCents(sub, fxRates) * hits
                    monthCount += hits
                }
            }
            points.add(MonthlyForecastPoint(year = y, month = m, totalCnyCents = monthTotal, occurrenceCount = monthCount))
            cal.add(Calendar.MONTH, 1)
        }
        val peak = points.maxByOrNull { it.totalCnyCents }?.takeIf { it.totalCnyCents > 0L }
        return TwelveMonthForecast(months = points, peakMonth = peak)
    }

    fun daysUntilDue(
        nextDueMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Int {
        val dueDay = startOfDayMs(nextDueMs, timeZone)
        val today = startOfDayMs(nowMs, timeZone)
        return ((dueDay - today) / DAY_MS).toInt()
    }

    fun matchesTransaction(
        subscription: SubscriptionEntity,
        transaction: TransactionEntity,
        fxRates: Map<String, Double> = SubscriptionFxRatesCalculator.DEFAULT_RATES,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        if (!subscription.active || subscription.deletedAtMs != null) return false
        if (transaction.syncDeleted || transaction.duplicateOfId != null) return false
        if (transaction.type != "EXPENSE" || transaction.category == "TRANSFER") return false
        if (transaction.remoteReviewStatus in setOf("excluded", "transfer", "refund", "possible_duplicate")) {
            return false
        }

        val dayDiff = abs(daysUntilDue(subscription.nextDueMs, transaction.timestamp, timeZone))
        if (dayDiff > 3) return false

        if (!merchantsMatch(subscription.name, transaction.merchant)) return false

        val expectedCents: Long
        val actualCents: Long
        if (subscription.currency.equals(transaction.currency, ignoreCase = true)) {
            expectedCents = subscription.amountCents
            actualCents = transaction.amountCents
        } else {
            expectedCents = estimateCnyCents(subscription, fxRates)
            actualCents = SubscriptionFxRatesCalculator.estimateCnyCents(
                transaction.amountCents,
                transaction.currency,
                fxRates,
            ) ?: return false
        }
        if (expectedCents <= 0L) return false
        return abs(actualCents - expectedCents) * 100L <= expectedCents * 5L
    }

    internal fun merchantsMatch(subscriptionName: String, transactionMerchant: String): Boolean {
        val subNorm = normalizeMerchant(subscriptionName)
        val txNorm = normalizeMerchant(transactionMerchant)
        if (subNorm.length < 2 || txNorm.length < 2) return false
        if (txNorm.contains(subNorm) || subNorm.contains(txNorm)) return true

        val subTokens = tokenizeMerchant(subscriptionName)
        val txTokens = tokenizeMerchant(transactionMerchant)
        return subTokens.any { token ->
            token.length >= 2 && (txNorm.contains(token) || txTokens.any { it.contains(token) || token.contains(it) })
        }
    }

    private fun normalizeMerchant(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(Regex("[\\s·\\-_/()（）\\[\\]【】*]+"), "")

    private fun tokenizeMerchant(raw: String): List<String> =
        raw.lowercase(Locale.ROOT)
            .split(Regex("[\\s·\\-_/()（）\\[\\]【】*]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
}
