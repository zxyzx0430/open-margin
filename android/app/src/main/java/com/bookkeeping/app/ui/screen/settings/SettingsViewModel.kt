package com.bookkeeping.app.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.notification.KeepAliveService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val prefs: SettingsPreferences,
) : ViewModel() {

    val keepAliveEnabled: StateFlow<Boolean> = prefs.observeKeepAliveEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val successNotificationsEnabled: StateFlow<Boolean> =
        prefs.observeSuccessNotificationsEnabled()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val successPageCaptureEnabled: StateFlow<Boolean> =
        prefs.observeSuccessPageCaptureEnabled()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val compatibilityAccessibilityEnabled: StateFlow<Boolean> =
        prefs.observeCompatibilityAccessibilityEnabled()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setKeepAliveEnabled(enabled)
            if (enabled) KeepAliveService.start(appContext) else KeepAliveService.stop(appContext)
        }
    }

    fun setSuccessNotifications(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setSuccessNotificationsEnabled(enabled)
        }
    }

    fun setSuccessPageCapture(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setSuccessPageCaptureEnabled(enabled)
        }
    }

    fun setCompatibilityAccessibility(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setCompatibilityAccessibilityEnabled(enabled)
        }
    }
}
