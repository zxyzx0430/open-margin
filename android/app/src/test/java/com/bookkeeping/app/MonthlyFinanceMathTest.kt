package com.bookkeeping.app

import com.bookkeeping.app.data.budget.BudgetCalculations
import com.bookkeeping.app.data.budget.BudgetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import kotlin.math.roundToLong

class MonthlyFinanceMathTest {

    @Test fun statusThresholds() {
        assertEquals(BudgetStatus.UNSET, BudgetCalculations.status(0L, 1L))
        assertEquals(BudgetStatus.NORMAL, BudgetCalculations.status(100_000L, 79_999L))
        assertEquals(BudgetStatus.NEAR_LIMIT, BudgetCalculations.status(100_000L, 80_000L))
        assertEquals(BudgetStatus.NEAR_LIMIT, BudgetCalculations.status(100_000L, 100_000L))
        assertEquals(BudgetStatus.OVER, BudgetCalculations.status(100_000L, 100_001L))
    }

    @Test fun summaryCalculatesRemainingForecastAndStatus() {
        val now = at(2026, 11, 17)
        val summary = BudgetCalculations.summary(
            month = "2026-11",
            budgetCents = 100_000L,
            spentCents = 50_000L,
            nowMs = now,
        )
        assertEquals(50_000L, summary.remainingCents)
        assertEquals(14, summary.daysRemaining)
        assertEquals(BudgetStatus.NORMAL, summary.status)
        val expectedForecast = (50_000.0 * 30 / 17).roundToLong()
        assertEquals(expectedForecast, summary.forecastCents)
        assertEquals(100_000L - expectedForecast, summary.projectedRemainingCents)
    }

    @Test fun dailyAverageUsesCalendarDays() {
        assertEquals(14_423L, BudgetCalculations.averagePerDay(432_680L, "2026-11"))
        assertEquals(0L, BudgetCalculations.averagePerDay(0L, "2026-11"))
        assertEquals(0L, BudgetCalculations.averagePerDay(100L, "bad"))
    }

    @Test fun comparisonHandlesZeroPreviousMonth() {
        val comparison = BudgetCalculations.comparison(432_680L, 494_830L)
        assertEquals(-12.56f, comparison.percentChange ?: 0f, 0.05f)
        assertNull(BudgetCalculations.comparison(100L, 0L).percentChange)
    }

    @Test fun summaryForPastAndFutureMonth() {
        val now = at(2026, 11, 17)
        val past = BudgetCalculations.summary("2026-10", 100_000L, 40_000L, now)
        assertEquals(0, past.daysRemaining)
        assertEquals(40_000L, past.forecastCents)

        val future = BudgetCalculations.summary("2026-12", 100_000L, 0L, now)
        assertEquals(31, future.daysRemaining)
        assertEquals(0L, future.forecastCents)
        assertEquals(100_000L, future.projectedRemainingCents)
    }

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
        }.timeInMillis
}
