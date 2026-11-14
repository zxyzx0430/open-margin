package com.bookkeeping.app.data.budget

import java.util.Calendar
import java.util.Locale

data class MonthParts(val year: Int, val month: Int)

/** Month-key and month-range helpers shared by stats and budgets. */
object BudgetMonth {
    fun currentKey(nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        return keyOf(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    fun keyOf(year: Int, month: Int): String = String.format(Locale.ROOT, "%04d-%02d", year, month)

    fun parse(key: String): MonthParts? {
        if (key.length != 7 || key[4] != '-') return null
        val year = key.substring(0, 4).toIntOrNull() ?: return null
        val month = key.substring(5, 7).toIntOrNull() ?: return null
        if (year !in 2000..2099 || month !in 1..12) return null
        if (key != keyOf(year, month)) return null
        return MonthParts(year, month)
    }

    fun isValid(key: String): Boolean = parse(key) != null

    fun range(key: String): Pair<Long, Long> {
        val parts = parse(key) ?: return 0L to 0L
        val start = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, parts.year)
            set(Calendar.MONTH, parts.month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val startMs = start.timeInMillis
        start.add(Calendar.MONTH, 1)
        return startMs to start.timeInMillis
    }

    fun daysInMonth(key: String): Int {
        val parts = parse(key) ?: return 0
        return Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, parts.year)
            set(Calendar.MONTH, parts.month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /** Completed days for forecasting: current month -> today; past -> full; future -> 0. */
    fun daysElapsed(key: String, nowMs: Long = System.currentTimeMillis()): Int {
        val current = currentKey(nowMs)
        return when {
            key == current -> Calendar.getInstance().apply { timeInMillis = nowMs }
                .get(Calendar.DAY_OF_MONTH)
            key < current -> daysInMonth(key)
            else -> 0
        }
    }

    /** Remaining days including today for the current month; 0 for past; full month for future. */
    fun daysRemaining(key: String, nowMs: Long = System.currentTimeMillis()): Int {
        val current = currentKey(nowMs)
        return when {
            key == current -> {
                val today = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.DAY_OF_MONTH)
                (daysInMonth(key) - today + 1).coerceAtLeast(0)
            }
            key < current -> 0
            else -> daysInMonth(key)
        }
    }

    fun previousKey(key: String): String {
        val parts = parse(key) ?: return key
        return if (parts.month == 1) keyOf(parts.year - 1, 12) else keyOf(parts.year, parts.month - 1)
    }

    fun isCurrent(key: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        key == currentKey(nowMs)
}
