package com.bookkeeping.app.onboarding

import android.content.res.Resources
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.i18n.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface TourState {
    data object Loading : TourState
    data object Hidden : TourState
    data class Resume(val step: Int) : TourState
    data class Showing(val step: Int) : TourState
}

@HiltViewModel
internal class OnboardingViewModel @Inject constructor(
    private val preferences: OnboardingPreferences,
    private val transactions: TransactionRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow<TourState>(TourState.Loading)
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    init {
        viewModelScope.launch {
            val hasBills = transactions.observeAll().first().isNotEmpty()
            val systemLanguage = Resources.getSystem().configuration.locales[0].language
            val saved = preferences.initialize(hasBills, systemLanguage)
            if (!hasBills && !AppLanguage.hasStoredChoice(context)) {
                AppLanguage.set(context, saved.locale)
            }
            mutex.withLock {
                mutableState.value = when {
                    saved.completed -> TourState.Hidden
                    saved.step > 0 -> TourState.Resume(saved.step)
                    else -> TourState.Showing(0).also { logStepView(0) }
                }
            }
        }
    }

    fun resume() {
        val current = mutableState.value as? TourState.Resume ?: return
        mutableState.value = TourState.Showing(current.step)
        logStepView(current.step)
    }

    fun next() = transition { OnboardingProgress.next(it) }
    fun back() = transition { OnboardingProgress.back(it) }

    private fun transition(action: (Int) -> OnboardingProgress.Result) {
        viewModelScope.launch {
            mutex.withLock {
                val step = (mutableState.value as? TourState.Showing)?.step ?: return@withLock
                when (val result = action(step)) {
                    is OnboardingProgress.Result.Show -> {
                        preferences.setStep(result.step)
                        mutableState.value = TourState.Showing(result.step)
                        logStepView(result.step)
                    }
                    OnboardingProgress.Result.Exit -> mutableState.value = TourState.Hidden
                    OnboardingProgress.Result.Complete -> completeLocked(skipped = false)
                }
            }
        }
    }

    fun complete() {
        viewModelScope.launch { mutex.withLock { completeLocked(skipped = false) } }
    }

    fun skip() {
        viewModelScope.launch { mutex.withLock { completeLocked(skipped = true) } }
    }

    private suspend fun completeLocked(skipped: Boolean) {
        val step = when (val current = mutableState.value) {
            is TourState.Showing -> current.step
            is TourState.Resume -> current.step
            else -> return
        }
        preferences.complete()
        mutableState.value = TourState.Hidden
        if (skipped) Log.i(TAG, "step_skip step=${step + 1}")
        Log.i(TAG, "completed")
    }

    fun restart() {
        viewModelScope.launch {
            mutex.withLock {
                preferences.restart()
                mutableState.value = TourState.Showing(0)
                logStepView(0)
            }
        }
    }

    private fun logStepView(step: Int) {
        Log.i(TAG, "step_view step=${step + 1}")
    }

    private companion object {
        const val TAG = "Onboarding"
    }
}
