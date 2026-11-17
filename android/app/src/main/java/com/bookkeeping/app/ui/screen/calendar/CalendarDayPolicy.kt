package com.bookkeeping.app.ui.screen.calendar

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.ui.screen.stats.StatsTransactionPolicy
import java.util.Calendar

/** A local-calendar date without a time component. */
internal data class CalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<CalendarDate> {
    override fun compareTo(other: CalendarDate): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.day })
}

internal data class CalendarDaySummary(
    val expenseCents: Long,
    val incomeCents: Long,
) {
    val balanceCents: Long get() = incomeCents - expenseCents
}

/** Pure presentation policy for selecting and summarizing a calendar day. */
internal object CalendarDayPolicy {
    fun shift(date: CalendarDate, days: Int): CalendarDate =
        Calendar.getInstance().apply {
            clear()
            set(date.year, date.month - 1, date.day, 12, 0, 0)
            add(Calendar.DAY_OF_MONTH, days)
        }.let { calendar ->
            CalendarDate(
                year = calendar.get(Calendar.YEAR),
                month = calendar.get(Calendar.MONTH) + 1,
                day = calendar.get(Calendar.DAY_OF_MONTH),
            )
        }

    /** Preserve the day when changing months, clamping month-end dates as needed. */
    fun inMonth(date: CalendarDate, year: Int, month: Int): CalendarDate {
        val lastDay = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
        return CalendarDate(year, month, date.day.coerceIn(1, lastDay))
    }

    fun transactionsOn(
        transactions: List<TransactionEntity>,
        date: CalendarDate,
    ): List<TransactionEntity> = transactions
        .asSequence()
        .filterNot { it.syncDeleted }
        .filter { localDate(it.timestamp) == date }
        .sortedByDescending { it.timestamp }
        .toList()

    fun summarize(transactions: List<TransactionEntity>): CalendarDaySummary {
        val active = transactions.filterNot { it.syncDeleted }
        val expense = active
            .filter { StatsTransactionPolicy.matches(it, TransactionType.EXPENSE) }
            .sumOf { it.amountCents }
        val income = active
            .filter { StatsTransactionPolicy.matches(it, TransactionType.INCOME) }
            .sumOf { it.amountCents }
        return CalendarDaySummary(expenseCents = expense, incomeCents = income)
    }

    private fun localDate(timestamp: Long): CalendarDate =
        Calendar.getInstance().apply { timeInMillis = timestamp }.let { calendar ->
            CalendarDate(
                year = calendar.get(Calendar.YEAR),
                month = calendar.get(Calendar.MONTH) + 1,
                day = calendar.get(Calendar.DAY_OF_MONTH),
            )
        }
}
