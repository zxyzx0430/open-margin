package com.bookkeeping.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingBubblePlacementTest {
    @Test
    fun targetBelowScreenKeepsEntireBubbleInsideContent() {
        assertEquals(680, onboardingBubbleTop(
            targetTop = 1800, bubbleHeight = 220, contentHeight = 900, headerHeight = 56, gap = 8,
        ))
    }

    @Test
    fun targetNearTopKeepsBubbleBelowHeader() {
        assertEquals(56, onboardingBubbleTop(
            targetTop = 100, bubbleHeight = 220, contentHeight = 900, headerHeight = 56, gap = 8,
        ))
    }

    @Test
    fun veryShortContentNeverProducesNegativePosition() {
        assertEquals(0, onboardingBubbleTop(
            targetTop = 200, bubbleHeight = 240, contentHeight = 180, headerHeight = 56, gap = 8,
        ))
    }
}
