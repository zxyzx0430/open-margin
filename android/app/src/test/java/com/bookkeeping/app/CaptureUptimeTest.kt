package com.bookkeeping.app

import com.bookkeeping.app.notification.CaptureUptime
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureUptimeTest {

    @Test fun formatsZero() {
        assertEquals("00 小时 00 分钟 00 秒", CaptureUptime.format(0L))
    }

    @Test fun formatsFiftyNineSeconds() {
        assertEquals("00 小时 00 分钟 59 秒", CaptureUptime.format(59_999L))
    }

    @Test fun formatsOneMinute() {
        assertEquals("00 小时 01 分钟 00 秒", CaptureUptime.format(60_000L))
    }

    @Test fun formatsOneHour() {
        assertEquals("01 小时 00 分钟 00 秒", CaptureUptime.format(3_600_000L))
    }

    @Test fun formatsMoreThanOneDay() {
        assertEquals("25 小时 00 分钟 00 秒", CaptureUptime.format(25L * 3_600_000L))
    }

    @Test fun clampsNegativeDuration() {
        assertEquals("00 小时 00 分钟 00 秒", CaptureUptime.format(-1L))
    }

    @Test fun formatsDowntimeUnderOneMinuteInSeconds() {
        assertEquals("05 秒", CaptureUptime.formatDowntime(5_000L))
        assertEquals("59 秒", CaptureUptime.formatDowntime(59_999L))
    }

    @Test fun formatsDowntimeInMinutes() {
        assertEquals("01 分钟", CaptureUptime.formatDowntime(60_000L))
        assertEquals("05 分钟", CaptureUptime.formatDowntime(5L * 60_000L))
    }

    @Test fun formatsDowntimeWithHours() {
        assertEquals("1 小时 00 分钟", CaptureUptime.formatDowntime(3_600_000L))
        assertEquals("1 小时 05 分钟", CaptureUptime.formatDowntime(3_900_000L))
    }

    @Test fun clampsNegativeDowntime() {
        assertEquals("00 秒", CaptureUptime.formatDowntime(-1L))
    }

    @Test fun prefersMonotonicElapsedTime() {
        assertEquals(
            5_000L,
            CaptureUptime.elapsed(
                connectedAtMs = 100L,
                connectedElapsedMs = 1_000L,
                nowMs = 9_999L,
                nowElapsedMs = 6_000L,
            ),
        )
    }

    @Test fun fallsBackToWallClockWhenMonotonicIsMissing() {
        assertEquals(
            5_000L,
            CaptureUptime.elapsed(
                connectedAtMs = 1_000L,
                connectedElapsedMs = 0L,
                nowMs = 6_000L,
                nowElapsedMs = 99_999L,
            ),
        )
    }

    @Test fun missingConnectedBaselineIsZero() {
        assertEquals(
            0L,
            CaptureUptime.elapsed(
                connectedAtMs = 0L,
                connectedElapsedMs = 0L,
                nowMs = 6_000L,
                nowElapsedMs = 99_999L,
            ),
        )
    }

    @Test fun rebootFallsBackToWallClockWhenMonotonicResets() {
        assertEquals(
            600_000L,
            CaptureUptime.elapsed(
                connectedAtMs = 1_000_000L,
                connectedElapsedMs = 500_000L,
                nowMs = 1_600_000L,
                nowElapsedMs = 100_000L,
            ),
        )
    }

    @Test fun staleMonotonicBaselineFallsBackToWallClock() {
        assertEquals(
            600_000L,
            CaptureUptime.elapsed(
                connectedAtMs = 1_000_000L,
                connectedElapsedMs = 100_000L,
                nowMs = 1_600_000L,
                nowElapsedMs = 110_000L,
            ),
        )
    }
}
