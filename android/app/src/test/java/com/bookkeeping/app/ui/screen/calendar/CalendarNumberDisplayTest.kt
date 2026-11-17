package com.bookkeeping.app.ui.screen.calendar

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.CalendarDateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarNumberDisplayTest {
    @Test
    fun calendarNumbersAggregateDailyExpenseIncomeAndBalance() {
        val totals = calendarNumberTotals(
            listOf(
                transaction(1_400, TransactionType.EXPENSE),
                transaction(600, TransactionType.EXPENSE),
                transaction(900, TransactionType.INCOME),
                transaction(8_000, TransactionType.EXPENSE, currency = "USD"),
                transaction(5_000, TransactionType.EXPENSE, duplicateOfId = 2L),
            ),
        )

        assertEquals(2_000L, calendarNumberAmount(CalendarNumberMode.EXPENSE, totals))
        assertEquals(900L, calendarNumberAmount(CalendarNumberMode.INCOME, totals))
        assertEquals(-1_100L, calendarNumberAmount(CalendarNumberMode.NET, totals))
        assertNull(calendarNumberAmount(CalendarNumberMode.OFF, totals))
    }

    @Test
    fun daysWithoutEligibleRecordsHaveNoCalendarNumber() {
        val empty = calendarNumberTotals(emptyList())
        CalendarNumberMode.entries.forEach { mode ->
            assertNull(calendarNumberAmount(mode, empty))
        }
    }

    @Test
    fun compactAmountsUseIntegerCentsAndLanguageAppropriateUnits() {
        assertEquals("¥12.34", compactCalendarAmount(1_234, english = false))
        assertEquals("−¥12.34", compactCalendarAmount(-1_234, english = true))
        assertEquals("¥1.2万", compactCalendarAmount(1_200_000, english = false))
        assertEquals("¥12k", compactCalendarAmount(1_200_000, english = true))
        assertEquals("¥92233.7T", compactCalendarAmount(Long.MAX_VALUE, english = true))
        assertEquals("¥92234万亿", compactCalendarAmount(Long.MAX_VALUE, english = false))
    }

    @Test
    fun lunarAndNumberSettingsAreIndependentAndEnglishHasNoChineseLunarLabel() {
        assertTrue(shouldShowCalendarLunar(showLunar = true, english = false))
        assertFalse(shouldShowCalendarLunar(showLunar = false, english = false))
        assertFalse(shouldShowCalendarLunar(showLunar = true, english = true))

        assertEquals("日历数字：", calendarNumbersMenuPrefix(english = false))
        assertEquals("Calendar numbers: ", calendarNumbersMenuPrefix(english = true))
        assertEquals("不显示", calendarNumberModeLabel(CalendarNumberMode.OFF, english = false))
        assertEquals("Off", calendarNumberModeLabel(CalendarNumberMode.OFF, english = true))
        assertEquals("Nov 17", CalendarDateText.day(2026, 11, 17, english = true))
        assertEquals("Expense", calendarNumberModeLabel(CalendarNumberMode.EXPENSE, english = true))
    }

    private fun transaction(
        cents: Long,
        type: TransactionType,
        currency: String = "CNY",
        duplicateOfId: Long? = null,
    ) = TransactionEntity(
        amountCents = cents,
        type = type.name,
        category = "FOOD",
        source = "MANUAL",
        timestamp = 1L,
        currency = currency,
        duplicateOfId = duplicateOfId,
    )
}
