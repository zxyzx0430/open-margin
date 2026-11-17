package com.bookkeeping.app.ui.screen.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarListDrawerTest {
    @Test
    fun drawerUsesTheRequestedTwoHeightAnchors() {
        assertEquals(0.40f, CALENDAR_DRAWER_COLLAPSED_FRACTION, 0f)
        assertEquals(0.85f, CALENDAR_DRAWER_EXPANDED_FRACTION, 0f)
    }

    @Test
    fun drawerTitleUsesShortLocalizedDateAndCorrectItemPlural() {
        assertEquals("Nov 17 · 3 items", calendarDrawerTitle("Nov 17", 3, english = true))
        assertEquals("Nov 17 · 1 item", calendarDrawerTitle("Nov 17", 1, english = true))
        assertEquals("11月17日 · 3 笔", calendarDrawerTitle("11月17日", 3, english = false))
    }

    @Test
    fun emptyStateIsLocalizedForSingleDayAndRange() {
        assertEquals("No transactions for this day", calendarDrawerEmptyLabel(false, english = true))
        assertEquals("No transactions in this range", calendarDrawerEmptyLabel(true, english = true))
        assertEquals("这天没有记账", calendarDrawerEmptyLabel(false, english = false))
        assertEquals("这段时间没有记账", calendarDrawerEmptyLabel(true, english = false))
    }
}
