package com.bookkeeping.app.onboarding

/** Zero-based, persisted positions in the guided tour. */
internal object OnboardingProgress {
    const val LAST_STEP = 7

    sealed interface Result {
        data class Show(val step: Int) : Result
        data object Exit : Result
        data object Complete : Result
    }

    fun next(step: Int): Result {
        require(step in 0..LAST_STEP)
        return if (step == LAST_STEP) Result.Complete else Result.Show(step + 1)
    }

    fun back(step: Int): Result {
        require(step in 0..LAST_STEP)
        return if (step == 0) Result.Exit else Result.Show(step - 1)
    }
}
