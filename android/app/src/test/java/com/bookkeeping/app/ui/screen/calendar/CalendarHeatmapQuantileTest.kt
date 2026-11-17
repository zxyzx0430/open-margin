package com.bookkeeping.app.ui.screen.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarHeatmapQuantileTest {
    @Test
    fun noPositiveAmountOrZeroAmountHasNoHeat() {
        assertEquals(0f, heatmapAlpha(0, emptyList()), 0f)
        assertEquals(0f, heatmapAlpha(100, emptyList()), 0f)
        assertEquals(0f, heatmapAlpha(0, listOf(10, 20, 30, 40)), 0f)
    }

    @Test
    fun fewerThanFourRecordedDaysUseTheSameFallbackTier() {
        val oneDay = listOf(35L)
        val threeDays = listOf(10L, 20L, 30L)

        assertEquals(0.14f, heatmapAlpha(35, oneDay), 0f)
        assertEquals(0.14f, heatmapAlpha(10, threeDays), 0f)
        assertEquals(0.14f, heatmapAlpha(30, threeDays), 0f)
    }

    @Test
    fun nearestRankPercentileBoundariesUseFourFixedAlphaTiers() {
        val samples = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)

        // Nearest-rank thresholds are P50=50, P75=80, P90=90 for these samples.
        assertEquals(0.08f, heatmapAlpha(50, samples), 0f)
        assertEquals(0.14f, heatmapAlpha(51, samples), 0f)
        assertEquals(0.14f, heatmapAlpha(80, samples), 0f)
        assertEquals(0.20f, heatmapAlpha(81, samples), 0f)
        assertEquals(0.20f, heatmapAlpha(90, samples), 0f)
        assertEquals(0.28f, heatmapAlpha(91, samples), 0f)
    }
}
