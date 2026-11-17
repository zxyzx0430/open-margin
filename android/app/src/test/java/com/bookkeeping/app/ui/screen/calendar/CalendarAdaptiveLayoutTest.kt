package com.bookkeeping.app.ui.screen.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarAdaptiveLayoutTest {
    @Test
    fun gridRowHeightStaysWithinTheTouchTargetRange() {
        assertEquals(44f, calendarGridRowHeightDp(360f), 0f)
        assertEquals(46f, calendarGridRowHeightDp(483f), 0.001f)
        assertEquals(48f, calendarGridRowHeightDp(600f), 0f)
    }

    @Test
    fun dayAmountFontScalesWithTheCompressedGridRow() {
        assertEquals(8f, calendarCellAmountFontSizeSp(5, showBoth = false, rowHeightDp = 48f), 0f)
        assertEquals(7f, calendarCellAmountFontSizeSp(5, showBoth = true, rowHeightDp = 48f), 0f)
        assertEquals(6f, calendarCellAmountFontSizeSp(9, showBoth = false, rowHeightDp = 48f), 0f)
        assertEquals(
            6.4167f,
            calendarCellAmountFontSizeSp(5, showBoth = true, rowHeightDp = 44f),
            0.001f,
        )
    }

    @Test
    fun overflowFallbackContinuesShrinkingWithoutEllipsizingTheAmount() {
        assertEquals(7.5f, nextCalendarAmountFontSizeSp(8f), 0f)
        assertEquals(2f, nextCalendarAmountFontSizeSp(2f), 0f)
        assertEquals(2f, nextCalendarAmountFontSizeSp(2.5f), 0f)
    }
}
