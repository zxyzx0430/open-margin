package com.bookkeeping.app.ui.screen.calendar

import com.bookkeeping.app.data.local.entity.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class CalendarDayPolicyTest {
    @Test
    fun movingSelectedDateCrossesMonthAndYearBoundaries() {
        assertEquals(CalendarDate(2027, 1, 1), CalendarDayPolicy.shift(CalendarDate(2026, 12, 31), 1))
        assertEquals(CalendarDate(2026, 12, 31), CalendarDayPolicy.shift(CalendarDate(2027, 1, 1), -1))
        assertEquals(CalendarDate(2024, 2, 29), CalendarDayPolicy.shift(CalendarDate(2024, 2, 28), 1))
        assertEquals(CalendarDate(2023, 3, 1), CalendarDayPolicy.shift(CalendarDate(2023, 2, 28), 1))
    }

    @Test
    fun changingMonthClampsSelectedDayToMonthEnd() {
        assertEquals(CalendarDate(2025, 2, 28), CalendarDayPolicy.inMonth(CalendarDate(2025, 1, 31), 2025, 2))
        assertEquals(CalendarDate(2024, 2, 29), CalendarDayPolicy.inMonth(CalendarDate(2024, 1, 31), 2024, 2))
    }

    @Test
    fun dailySummaryUsesExistingStatsPolicyForExpenseIncomeAndBalance() {
        val summary = CalendarDayPolicy.summarize(
            listOf(
                transaction(id = 1, amount = 2_500, type = "EXPENSE"),
                transaction(id = 2, amount = 7_000, type = "INCOME"),
                transaction(id = 3, amount = 600, type = "EXPENSE", currency = "USD"),
                transaction(id = 4, amount = 900, type = "EXPENSE", status = "excluded"),
                transaction(id = 5, amount = 1_200, type = "INCOME", duplicateOfId = 1L),
                transaction(id = 6, amount = 300, type = "EXPENSE", deleted = true),
            ),
        )

        assertEquals(2_500L, summary.expenseCents)
        assertEquals(7_000L, summary.incomeCents)
        assertEquals(4_500L, summary.balanceCents)
    }

    @Test
    fun transactionsOnDateAreLocalDayFilteredAndNewestFirst() {
        val date = CalendarDate(2026, 11, 17)
        val first = localTimestamp(2026, Calendar.NOVEMBER, 17, 9)
        val second = localTimestamp(2026, Calendar.NOVEMBER, 17, 18)
        val nextDay = localTimestamp(2026, Calendar.NOVEMBER, 18, 1)
        val deleted = transaction(id = 4, amount = 400, type = "EXPENSE", timestamp = second, deleted = true)

        assertEquals(
            listOf(2L, 1L),
            CalendarDayPolicy.transactionsOn(
                listOf(
                    transaction(id = 1, amount = 100, type = "EXPENSE", timestamp = first),
                    transaction(id = 2, amount = 200, type = "EXPENSE", timestamp = second),
                    transaction(id = 3, amount = 300, type = "EXPENSE", timestamp = nextDay),
                    deleted,
                ),
                date,
            ).map { it.id },
        )
    }

    private fun transaction(
        id: Long,
        amount: Long,
        type: String,
        currency: String = "CNY",
        status: String = "unverified",
        duplicateOfId: Long? = null,
        timestamp: Long = 0L,
        deleted: Boolean = false,
    ) = TransactionEntity(
        id = id,
        amountCents = amount,
        type = type,
        category = "OTHER",
        source = "MANUAL",
        timestamp = timestamp,
        currency = currency,
        remoteReviewStatus = status,
        duplicateOfId = duplicateOfId,
        syncDeleted = deleted,
    )

    private fun localTimestamp(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis
}
