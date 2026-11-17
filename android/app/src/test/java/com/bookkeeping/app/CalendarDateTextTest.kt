package com.bookkeeping.app

import com.bookkeeping.app.i18n.CalendarDateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Month/day labels are formatted by language instead of hardcoding 年 / 月 / 日
 * in the calendar screens. Chinese: 2026年11月 / 11月21日. English: November 2026 / Nov 21.
 */
class CalendarDateTextTest {
    @Test
    fun chineseLabelsUseYearMonthDayOrder() {
        assertEquals("2026年11月", CalendarDateText.monthHeading(2026, 11, english = false))
        assertEquals("11月", CalendarDateText.monthShort(2026, 11, english = false))
        assertEquals("11月21日", CalendarDateText.day(2026, 11, 21, english = false))
        assertEquals("2026年11月21日", CalendarDateText.date(2026, 11, 21, english = false))
    }

    @Test
    fun englishLabelsUseMonthNames() {
        assertEquals("November 2026", CalendarDateText.monthHeading(2026, 11, english = true))
        assertEquals("Nov", CalendarDateText.monthShort(2026, 11, english = true))
        assertEquals("Nov 21", CalendarDateText.day(2026, 11, 21, english = true))
        assertEquals("Nov 21, 2026", CalendarDateText.date(2026, 11, 21, english = true))
    }

    @Test
    fun selectedDayIncludesLocalizedWeekdayAndMonthSwitchText() {
        assertEquals("11月17日 周二", CalendarDateText.dayWithWeekday(2026, 11, 17, english = false))
        assertEquals("Nov 17 Tue", CalendarDateText.dayWithWeekday(2026, 11, 17, english = true))
        assertEquals("已切换到 2026 年 12 月", CalendarDateText.switchedMonth(2026, 12, english = false))
        assertEquals("Switched to December 2026", CalendarDateText.switchedMonth(2026, 12, english = true))
    }

    @Test
    fun rangesKeepOneMonthOrNameBoth() {
        assertEquals("11月21日–23日", CalendarDateText.sameMonthRange(2026, 11, 21, 23, english = false))
        assertEquals("Nov 21–23", CalendarDateText.sameMonthRange(2026, 11, 21, 23, english = true))
        assertEquals("11月21日", CalendarDateText.sameMonthRange(2026, 11, 21, 21, english = false))
        assertEquals("Nov 21", CalendarDateText.sameMonthRange(2026, 11, 21, 21, english = true))

        assertEquals(
            "2026年11月21日–12月3日",
            CalendarDateText.crossMonthRange(2026, 11, 21, 2026, 12, 3, english = false),
        )
        assertEquals(
            "Nov 21–Dec 3, 2026",
            CalendarDateText.crossMonthRange(2026, 11, 21, 2026, 12, 3, english = true),
        )
        assertEquals(
            "2026年12月30日–2027年1月2日",
            CalendarDateText.crossMonthRange(2026, 12, 30, 2027, 1, 2, english = false),
        )
        assertEquals(
            "Dec 30, 2026–Jan 2, 2027",
            CalendarDateText.crossMonthRange(2026, 12, 30, 2027, 1, 2, english = true),
        )
    }

    @Test
    fun noChineseNumeralsLeakIntoEnglishLabels() {
        // A "/" numeric date or a 年/月/日 suffix in English mode is the bug this guards.
        val english = listOf(
            CalendarDateText.monthHeading(2026, 1, english = true),
            CalendarDateText.day(2026, 1, 5, english = true),
            CalendarDateText.sameMonthRange(2026, 1, 5, 9, english = true),
            CalendarDateText.crossMonthRange(2026, 1, 5, 2026, 2, 9, english = true),
        )
        english.forEach { label ->
            listOf("年", "月", "日", "/").forEach { bad ->
                assertFalse("English label must not contain $bad: $label", label.contains(bad))
            }
        }
    }
}
