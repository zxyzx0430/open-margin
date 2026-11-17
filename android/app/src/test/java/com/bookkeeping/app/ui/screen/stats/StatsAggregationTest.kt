package com.bookkeeping.app.ui.screen.stats

import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class StatsAggregationTest {

    @Test
    fun dayAndCategoryCountsMatchFilteredAmounts() {
        val transactions = listOf(
            transaction(100L, TransactionType.EXPENSE, TransactionCategory.FOOD, at(2026, 11, 1)),
            transaction(
                200L,
                TransactionType.EXPENSE,
                TransactionCategory.FOOD,
                at(2026, 11, 2),
                currency = "USD",
            ),
            transaction(
                300L,
                TransactionType.EXPENSE,
                TransactionCategory.FOOD,
                at(2026, 11, 3),
                duplicateOfId = 1L,
            ),
            transaction(
                400L,
                TransactionType.EXPENSE,
                TransactionCategory.FOOD,
                at(2026, 11, 4),
                status = "excluded",
            ),
            transaction(500L, TransactionType.INCOME, TransactionCategory.SALARY, at(2026, 11, 5)),
            transaction(
                600L,
                TransactionType.INCOME,
                TransactionCategory.SALARY,
                at(2026, 11, 6),
                status = "possible_duplicate",
            ),
        )

        val expenseCategory = StatsAggregation.categoryStats(
            transactions,
            TransactionType.EXPENSE,
        ).single()
        val incomeCategory = StatsAggregation.categoryStats(
            transactions,
            TransactionType.INCOME,
        ).single()

        assertEquals(100L, StatsAggregation.totalCents(transactions, TransactionType.EXPENSE))
        assertEquals(500L, StatsAggregation.totalCents(transactions, TransactionType.INCOME))
        assertEquals(1, expenseCategory.count)
        assertEquals(100L, expenseCategory.totalCents)
        assertEquals(1, incomeCategory.count)
        assertEquals(500L, incomeCategory.totalCents)
        assertEquals(2, StatsAggregation.transactionCount(transactions))
    }

    @Test
    fun incomeCountsTransferStatusAndTransferCategory() {
        val transactions = listOf(
            transaction(
                amountCents = 20_000L,
                type = TransactionType.INCOME,
                category = TransactionCategory.GIFT,
                timestampMs = at(2026, 11, 2),
                status = "transfer",
            ),
            transaction(
                amountCents = 5_000L,
                type = TransactionType.INCOME,
                category = TransactionCategory.TRANSFER,
                timestampMs = at(2026, 11, 3),
                status = "transfer",
            ),
            transaction(
                amountCents = 1_000L,
                type = TransactionType.INCOME,
                category = TransactionCategory.SALARY,
                timestampMs = at(2026, 11, 4),
                status = "confirmed",
            ),
        )

        assertEquals(26_000L, StatsAggregation.totalCents(transactions, TransactionType.INCOME))
        val stats = StatsAggregation.categoryStats(transactions, TransactionType.INCOME)
        assertEquals(
            listOf(
                TransactionCategory.GIFT,
                TransactionCategory.TRANSFER,
                TransactionCategory.SALARY,
            ),
            stats.map { it.category },
        )
        assertEquals(1.0, stats.sumOf { it.percentage.toDouble() }, 0.0001)
    }

    @Test
    fun incomeCountsRefundButExcludesExcludedAndDuplicateRows() {
        val transactions = listOf(
            transaction(100L, TransactionType.INCOME, TransactionCategory.SALARY, at(2026, 11, 1)),
            transaction(
                200L,
                TransactionType.INCOME,
                TransactionCategory.SALARY,
                at(2026, 11, 2),
                status = "refund",
            ),
            transaction(
                300L,
                TransactionType.INCOME,
                TransactionCategory.SALARY,
                at(2026, 11, 3),
                status = "excluded",
            ),
            transaction(
                400L,
                TransactionType.INCOME,
                TransactionCategory.SALARY,
                at(2026, 11, 4),
                status = "possible_duplicate",
            ),
            transaction(
                500L,
                TransactionType.INCOME,
                TransactionCategory.SALARY,
                at(2026, 11, 5),
                status = "duplicate",
            ),
            transaction(
                600L,
                TransactionType.INCOME,
                TransactionCategory.SALARY,
                at(2026, 11, 6),
                status = "unverified",
                duplicateOfId = 9L,
            ),
            transaction(
                700L,
                TransactionType.INCOME,
                TransactionCategory.SALARY,
                at(2026, 11, 7),
                currency = "USD",
            ),
            transaction(800L, TransactionType.EXPENSE, TransactionCategory.FOOD, at(2026, 11, 8)),
        )

        assertEquals(300L, StatsAggregation.totalCents(transactions, TransactionType.INCOME))
    }

    @Test
    fun expenseCountsTransferAndRefundButExcludesExcludedAndDuplicateRows() {
        val transactions = listOf(
            transaction(100L, TransactionType.EXPENSE, TransactionCategory.FOOD, at(2026, 11, 1)),
            transaction(
                200L,
                TransactionType.EXPENSE,
                TransactionCategory.FOOD,
                at(2026, 11, 2),
                status = "transfer",
            ),
            transaction(
                300L,
                TransactionType.EXPENSE,
                TransactionCategory.TRANSFER,
                at(2026, 11, 3),
            ),
            transaction(
                400L,
                TransactionType.EXPENSE,
                TransactionCategory.FOOD,
                at(2026, 11, 4),
                status = "refund",
            ),
            transaction(
                500L,
                TransactionType.EXPENSE,
                TransactionCategory.FOOD,
                at(2026, 11, 5),
                status = "excluded",
            ),
            transaction(
                600L,
                TransactionType.EXPENSE,
                TransactionCategory.FOOD,
                at(2026, 11, 6),
                status = "possible_duplicate",
            ),
            transaction(
                700L,
                TransactionType.EXPENSE,
                TransactionCategory.FOOD,
                at(2026, 11, 7),
                status = "unverified",
                duplicateOfId = 1L,
            ),
            transaction(
                800L,
                TransactionType.EXPENSE,
                TransactionCategory.FOOD,
                at(2026, 11, 8),
                currency = "USD",
            ),
            transaction(
                900L,
                TransactionType.INCOME,
                TransactionCategory.GIFT,
                at(2026, 11, 9),
                status = "transfer",
            ),
        )

        assertEquals(1_000L, StatsAggregation.totalCents(transactions, TransactionType.EXPENSE))
    }

    @Test
    fun dailyTotalsKeepMonthViewIntact() {
        val transactions = listOf(
            transaction(100L, TransactionType.INCOME, TransactionCategory.SALARY, at(2026, 11, 1)),
            transaction(200L, TransactionType.INCOME, TransactionCategory.GIFT, at(2026, 11, 30)),
        )

        val totals = StatsAggregation.dailyTotals(transactions, TransactionType.INCOME, "2026-11")
        assertEquals(30, totals.size)
        assertEquals(100L, totals[0].totalCents)
        assertEquals(0L, totals[1].totalCents)
        assertEquals(200L, totals[29].totalCents)
    }

    @Test
    fun monthlyTotalsPartitionYearWithoutDoubleCounting() {
        val year = 2026
        val transactions = listOf(
            transaction(10_000L, TransactionType.INCOME, TransactionCategory.SALARY, at(year, 1, 2)),
            transaction(
                5_000L,
                TransactionType.INCOME,
                TransactionCategory.GIFT,
                at(year, 1, 20),
                status = "transfer",
            ),
            transaction(20_000L, TransactionType.INCOME, TransactionCategory.BONUS, at(year, 2, 10)),
            transaction(
                30_000L,
                TransactionType.INCOME,
                TransactionCategory.TRANSFER,
                at(year, 6, 15),
                status = "transfer",
            ),
            transaction(
                40_000L,
                TransactionType.INCOME,
                TransactionCategory.OTHER,
                at(year, 12, 31, hour = 23, minute = 59),
            ),
            transaction(
                50_000L,
                TransactionType.INCOME,
                TransactionCategory.SALARY,
                at(year + 1, 1, 1, hour = 0),
            ),
        )

        val monthly = StatsAggregation.monthlyTotals(transactions, TransactionType.INCOME, year)
        assertEquals(12, monthly.size)
        assertEquals((1..12).toList(), monthly.map { it.month })
        assertEquals(15_000L, monthly[0].totalCents)
        assertEquals(20_000L, monthly[1].totalCents)
        assertEquals(0L, monthly[2].totalCents)
        assertEquals(30_000L, monthly[5].totalCents)
        assertEquals(40_000L, monthly[11].totalCents)
        assertEquals(105_000L, monthly.sumOf { it.totalCents })

        val inYear = transactions.filter {
            calendarOf(it.timestamp).get(Calendar.YEAR) == year
        }
        assertEquals(
            StatsAggregation.totalCents(inYear, TransactionType.INCOME),
            monthly.sumOf { it.totalCents },
        )

        // Summing the 12 calendar-month ranges in the DAO must equal the
        // direct year range: no boundary transaction is counted twice.
        val perCalendarMonth = (1..12).sumOf { month ->
            val (start, end) = BudgetMonth.range(BudgetMonth.keyOf(year, month))
            StatsAggregation.totalCents(
                transactions.filter { it.timestamp >= start && it.timestamp < end },
                TransactionType.INCOME,
            )
        }
        assertEquals(105_000L, perCalendarMonth)
    }

    @Test
    fun yearRangeCoversLocalCalendarYear() {
        val (start, end) = StatsAggregation.yearRange(2026)
        assertEquals(at(2026, 1, 1, hour = 0), start)
        assertEquals(at(2027, 1, 1, hour = 0), end)

        val (lastStart, lastEnd) = StatsAggregation.yearRange(2099)
        assertEquals(at(2099, 1, 1, hour = 0), lastStart)
        assertEquals(at(2100, 1, 1, hour = 0), lastEnd)
    }

    @Test
    fun yearDailyAverageUsesCalendarYearDays() {
        assertEquals(366, StatsAggregation.daysInYear(2024))
        assertEquals(365, StatsAggregation.daysInYear(2026))
        assertEquals(1_000L, StatsAggregation.averagePerDay(365_000L, 2026))
        assertEquals(0L, StatsAggregation.averagePerDay(0L, 2026))
    }

    private fun transaction(
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        timestampMs: Long,
        status: String = "confirmed",
        currency: String = "CNY",
        duplicateOfId: Long? = null,
    ): TransactionEntity = TransactionEntity(
        amountCents = amountCents,
        type = type.name,
        category = category.name,
        source = TransactionSource.MANUAL.name,
        timestamp = timestampMs,
        currency = currency,
        remoteReviewStatus = status,
        duplicateOfId = duplicateOfId,
    )

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
    ): Long = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun calendarOf(timestampMs: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = timestampMs }
}
