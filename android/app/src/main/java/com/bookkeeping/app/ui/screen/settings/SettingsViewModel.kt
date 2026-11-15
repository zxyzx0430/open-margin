package com.bookkeeping.app.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.entity.TransactionCategory
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

    val merchantRules: StateFlow<Map<String, TransactionCategory>> =
        prefs.observeMerchantRules()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val learningDisabled: StateFlow<Set<String>> = prefs.observeLearningDisabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun clearMerchantRules() {
        viewModelScope.launch { prefs.clearMerchantRules() }
    }

    fun saveMerchantRule(merchant: String, category: TransactionCategory) {
        viewModelScope.launch { prefs.rememberMerchantCategory(merchant, category) }
    }

    fun removeMerchantRule(merchant: String) {
        viewModelScope.launch { prefs.removeMerchantRule(merchant) }
    }

    fun setLearningDisabled(merchant: String, disabled: Boolean) {
        viewModelScope.launch { prefs.disableCategoryLearning(merchant, disabled) }
    }

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
}
