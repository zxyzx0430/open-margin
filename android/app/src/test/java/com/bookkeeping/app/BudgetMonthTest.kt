package com.bookkeeping.app

import com.bookkeeping.app.data.budget.BudgetMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BudgetMonthTest {

    @Test fun keysAndValidation() {
        assertEquals("2026-11", BudgetMonth.keyOf(2026, 11))
        assertEquals("2026-01", BudgetMonth.keyOf(2026, 1))
        assertEquals("2026-11", BudgetMonth.currentKey(at(2026, 11, 17)))
        assertTrue(BudgetMonth.isValid("2026-11"))
        assertFalse(BudgetMonth.isValid("2026-1"))
        assertFalse(BudgetMonth.isValid("2026-13"))
        assertFalse(BudgetMonth.isValid("26-11"))
        assertNull(BudgetMonth.parse("2026-1"))
    }

    @Test fun daysInMonthHandlesLeapYear() {
        assertEquals(29, BudgetMonth.daysInMonth("2024-02"))
        assertEquals(28, BudgetMonth.daysInMonth("2026-02"))
        assertEquals(30, BudgetMonth.daysInMonth("2026-11"))
        assertEquals(31, BudgetMonth.daysInMonth("2026-12"))
        assertEquals(0, BudgetMonth.daysInMonth("bad"))
    }

    @Test fun previousKeyCrossesYear() {
        assertEquals("2025-12", BudgetMonth.previousKey("2026-01"))
        assertEquals("2026-10", BudgetMonth.previousKey("2026-11"))
    }

    @Test fun currentMonthElapsedAndRemaining() {
        val now = at(2026, 11, 17)
        assertEquals(17, BudgetMonth.daysElapsed("2026-11", now))
        assertEquals(14, BudgetMonth.daysRemaining("2026-11", now))
        assertTrue(BudgetMonth.isCurrent("2026-11", now))
    }

    @Test fun pastAndFutureMonths() {
        val now = at(2026, 11, 17)
        assertEquals(31, BudgetMonth.daysElapsed("2026-10", now))
        assertEquals(0, BudgetMonth.daysRemaining("2026-10", now))
        assertEquals(0, BudgetMonth.daysElapsed("2026-12", now))
        assertEquals(31, BudgetMonth.daysRemaining("2026-12", now))
        assertFalse(BudgetMonth.isCurrent("2026-10", now))
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
