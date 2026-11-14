package com.bookkeeping.app.notification

import java.util.Locale
import kotlin.math.abs

/** Pure uptime formatting and monotonic/wall-clock fallback logic. */
object CaptureUptime {
    const val DEFAULT_PATTERN: String = "%02d 小时 %02d 分钟 %02d 秒"
    const val SECONDS_PATTERN: String = "%02d 秒"
    const val MINUTES_PATTERN: String = "%02d 分钟"
    const val HOURS_PATTERN: String = "%d 小时 %02d 分钟"
    const val MONOTONIC_DRIFT_TOLERANCE_MS: Long = 5 * 60_000L

    fun elapsed(
        connectedAtMs: Long,
        connectedElapsedMs: Long,
        nowMs: Long,
        nowElapsedMs: Long,
    ): Long {
        val wall = if (connectedAtMs > 0L) {
            (nowMs - connectedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        if (connectedElapsedMs <= 0L || nowElapsedMs < connectedElapsedMs) return wall
        val monotonic = nowElapsedMs - connectedElapsedMs
        // A stored elapsedRealtime baseline becomes invalid after a reboot. If it
        // disagrees with the wall clock by more than the tolerance, trust the wall
        // clock instead of showing a frozen or impossible duration.
        if (connectedAtMs > 0L &&
            abs(monotonic - wall) > MONOTONIC_DRIFT_TOLERANCE_MS
        ) {
            return wall
        }
        return monotonic
    }

    fun format(elapsedMs: Long, pattern: String = DEFAULT_PATTERN): String {
        val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.ROOT, pattern, hours, minutes, seconds)
    }

    fun formatDowntime(
        elapsedMs: Long,
        secondsPattern: String = SECONDS_PATTERN,
        minutesPattern: String = MINUTES_PATTERN,
        hoursPattern: String = HOURS_PATTERN,
    ): String {
        val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1000L
        return when {
            totalSeconds < 60L ->
                String.format(Locale.ROOT, secondsPattern, totalSeconds)
            totalSeconds < 3600L ->
                String.format(Locale.ROOT, minutesPattern, totalSeconds / 60L)
            else ->
                String.format(
                    Locale.ROOT,
                    hoursPattern,
                    totalSeconds / 3600L,
                    (totalSeconds % 3600L) / 60L,
                )
        }
    }
}
