package com.bookkeeping.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OnboardingProgressTest {
    @Test
    fun forwardVisitsAllEightStepsBeforeCompletion() {
        var step = 0
        repeat(7) { expected ->
            val result = OnboardingProgress.next(step) as OnboardingProgress.Result.Show
            assertEquals(expected + 1, result.step)
            step = result.step
        }
        assertEquals(OnboardingProgress.Result.Complete, OnboardingProgress.next(step))
    }

    @Test
    fun backFromFirstStepExitsAndOtherStepsGoBack() {
        assertEquals(OnboardingProgress.Result.Exit, OnboardingProgress.back(0))
        for (step in 1..7) {
            assertEquals(OnboardingProgress.Result.Show(step - 1), OnboardingProgress.back(step))
        }
    }

    @Test
    fun invalidPositionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { OnboardingProgress.next(8) }
        assertThrows(IllegalArgumentException::class.java) { OnboardingProgress.back(-1) }
    }
}
