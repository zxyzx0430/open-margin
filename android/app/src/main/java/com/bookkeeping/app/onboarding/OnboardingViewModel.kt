package com.bookkeeping.app.onboarding

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.i18n.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    data object Showing : TourState
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
            val saved = preferences.initialize(hasBills, Resources.getSystem().configuration.locales[0].language)
            if (!hasBills && !AppLanguage.hasStoredChoice(context)) AppLanguage.set(context, saved.locale)
            mutex.withLock {
                mutableState.value = if (saved.completed) TourState.Hidden else TourState.Showing
                if (!saved.completed) Log.i(TAG, "step_view step=1")
            }
        }
    }

    fun complete(skipped: Boolean = false) {
        viewModelScope.launch {
            mutex.withLock {
                if (mutableState.value != TourState.Showing) return@withLock
                preferences.complete()
                mutableState.value = TourState.Hidden
                if (skipped) Log.i(TAG, "step_skip step=1")
                Log.i(TAG, "completed")
            }
        }
    }

    fun restart() {
        viewModelScope.launch {
            mutex.withLock {
                preferences.restart()
                mutableState.value = TourState.Showing
                Log.i(TAG, "step_view step=1")
            }
        }
    }

    private companion object { const val TAG = "Onboarding" }
}
