package com.bookkeeping.app.i18n

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Month and day labels for the calendar screens.
 *
 * The patterns live here so no screen hardcodes 年 / 月 / 日 or a numeric
 * month-first order: Chinese reads 2026年11月 and 11月21日, English reads
 * November 2026 and Nov 21. Presentation only — stored timestamps, ledger
 * keys and sync values are never rewritten.
 */
object CalendarDateText {
    private const val ZH_MONTH_HEADING = "yyyy年M月"
    private const val ZH_MONTH_SHORT = "M月"
    private const val ZH_DAY = "M月d日"
    private const val ZH_MONTH_DAY = "M月d日"
    private const val ZH_DATE = "yyyy年M月d日"
    private const val EN_MONTH_HEADING = "MMMM yyyy"
    private const val EN_MONTH_SHORT = "MMM"
    private const val EN_DAY = "MMM d"
    private const val EN_DATE = "MMM d, yyyy"

    fun isEnglish(): Boolean = AppLanguage.current() == AppLanguage.ENGLISH

    /** Header above the month grid: 2026年11月 / November 2026. */
    fun monthHeading(year: Int, month: Int, english: Boolean = isEnglish()): String =
        format(year, month, 1, if (english) EN_MONTH_HEADING else ZH_MONTH_HEADING, english)

    /** Month without a year, for compact pickers: 11月 / Nov. */
    fun monthShort(year: Int, month: Int, english: Boolean = isEnglish()): String =
        format(year, month, 1, if (english) EN_MONTH_SHORT else ZH_MONTH_SHORT, english)

    /** A single day inside a month: 11月21日 / Nov 21. */
    fun day(year: Int, month: Int, day: Int, english: Boolean = isEnglish()): String =
        format(year, month, day, if (english) EN_DAY else ZH_DAY, english)

    /** A full date, for labels where the year is not shown elsewhere: 2026年11月21日 / Nov 21, 2026. */
    fun date(year: Int, month: Int, day: Int, english: Boolean = isEnglish()): String =
        format(year, month, day, if (english) EN_DATE else ZH_DATE, english)

    /** A selected day with its weekday: 11月17日 周二 / Nov 17 Tue. */
    fun dayWithWeekday(year: Int, month: Int, day: Int, english: Boolean = isEnglish()): String {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }
        val weekday = if (english) {
            SimpleDateFormat("EEE", Locale.ENGLISH).format(calendar.time)
        } else {
            listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[
                calendar.get(Calendar.DAY_OF_WEEK) - 1
            ]
        }
        return day(year, month, day, english) + " " + weekday
    }

    /** Snackbar text after selecting a month: 2026 年 12 月 / December 2026. */
    fun switchedMonth(year: Int, month: Int, english: Boolean = isEnglish()): String =
        if (english) "Switched to " + monthHeading(year, month, english = true)
        else "已切换到 $year 年 $month 月"

    /** Range inside one month: 11月21日–23日 / Nov 21–23. */
    fun sameMonthRange(
        year: Int,
        month: Int,
        startDay: Int,
        endDay: Int,
        english: Boolean = isEnglish(),
    ): String {
        if (startDay == endDay) return day(year, month, startDay, english)
        // The end of a same-month range repeats neither the month nor, in
        // English, anything else: 11月21日–23日 / Nov 21–23.
        return day(year, month, startDay, english) + "–" +
            if (english) endDay.toString() else "${endDay}日"
    }

    /** Range that crosses months: 2026年11月21日–12月3日 / Nov 21–Dec 3, 2026. */
    fun crossMonthRange(
        startYear: Int,
        startMonth: Int,
        startDay: Int,
        endYear: Int,
        endMonth: Int,
        endDay: Int,
        english: Boolean = isEnglish(),
    ): String {
        if (startYear != endYear) {
            return date(startYear, startMonth, startDay, english) +
                "–" +
                date(endYear, endMonth, endDay, english)
        }
        return if (english) {
            day(startYear, startMonth, startDay, true) +
                "–" +
                day(endYear, endMonth, endDay, true) +
                ", $endYear"
        } else {
            format(startYear, startMonth, startDay, ZH_DATE, false) +
                "–" +
                format(endYear, endMonth, endDay, ZH_MONTH_DAY, false)
        }
    }

    private fun format(year: Int, month: Int, day: Int, pattern: String, english: Boolean): String {
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }
        val locale = if (english) Locale.ENGLISH else Locale.CHINA
        return SimpleDateFormat(pattern, locale).format(cal.time)
    }
}
