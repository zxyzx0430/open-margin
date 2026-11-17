package com.bookkeeping.app.ui.screen.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarListDrawerTest {
    @Test
    fun drawerUsesTheRequestedTwoHeightAnchors() {
        assertEquals(0.40f, CALENDAR_DRAWER_COLLAPSED_FRACTION, 0f)
        assertEquals(0.85f, CALENDAR_DRAWER_EXPANDED_FRACTION, 0f)
    }

    @Test
    fun fastDrawerFlingsOverrideTheNearestAnchor() {
        assertEquals(
            CalendarDrawerSnapTarget.EXPANDED,
            calendarDrawerSnapTarget(CALENDAR_DRAWER_COLLAPSED_FRACTION, -800f),
        )
        assertEquals(
            CalendarDrawerSnapTarget.HIDDEN,
            calendarDrawerSnapTarget(CALENDAR_DRAWER_EXPANDED_FRACTION, 800f),
        )
    }

    @Test
    fun slowDrawerDragsSnapToTheNearestAnchorAndResolveTiesToCollapsed() {
        assertEquals(
            CalendarDrawerSnapTarget.COLLAPSED,
            calendarDrawerSnapTarget(0.62f, 799f),
        )
        assertEquals(
            CalendarDrawerSnapTarget.EXPANDED,
            calendarDrawerSnapTarget(0.63f, -799f),
        )
        assertEquals(
            CalendarDrawerSnapTarget.COLLAPSED,
            calendarDrawerSnapTarget(0.625f, 0f),
        )
    }

    @Test
    fun menuTogglesExpandIntoDefaultChoiceAndCollapseFromAnyChoice() {
        assertFalse(isCalendarHighlightMenuExpanded(HeatmapMode.OFF))
        assertEquals(HeatmapMode.EXPENSE, toggleCalendarHighlightMode(HeatmapMode.OFF))
        assertTrue(isCalendarHighlightMenuExpanded(HeatmapMode.EXPENSE))
        assertEquals(HeatmapMode.OFF, toggleCalendarHighlightMode(HeatmapMode.INCOME))
        assertEquals(HeatmapMode.INCOME, setCalendarHighlightEnabled(HeatmapMode.INCOME, true))

        assertFalse(isCalendarNumberMenuExpanded(CalendarNumberMode.OFF))
        assertEquals(CalendarNumberMode.EXPENSE, toggleCalendarNumberMode(CalendarNumberMode.OFF))
        assertTrue(isCalendarNumberMenuExpanded(CalendarNumberMode.NET))
        assertEquals(CalendarNumberMode.OFF, toggleCalendarNumberMode(CalendarNumberMode.NET))
        assertEquals(CalendarNumberMode.NET, setCalendarNumberEnabled(CalendarNumberMode.NET, true))
    }

    @Test
    fun drawerTitleAddsAnOptionalSingleDayAmount() {
        assertEquals(
            "11月15日 · 2 笔 · 支出 ¥2.50",
            calendarDrawerTitle("11月15日", 2, english = false, singleDaySummary = "支出 ¥2.50"),
        )
        assertEquals(
            "Nov 15 · 2 items · Expense ¥2.50",
            calendarDrawerTitle("Nov 15", 2, english = true, singleDaySummary = "Expense ¥2.50"),
        )
    }

    @Test
    fun rangeDrawerTitleKeepsBothDatesAndUsesLocalizedItemCount() {
        assertEquals(
            "11月2日 – 11月15日 · 14 笔",
            calendarDrawerTitle("11月2日 – 11月15日", 14, english = false),
        )
        assertEquals(
            "Nov 2 – Nov 15 · 14 items",
            calendarDrawerTitle("Nov 2 – Nov 15", 14, english = true),
        )
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
