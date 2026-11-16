package com.bookkeeping.app.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.notification.KeepAliveService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val prefs: SettingsPreferences,
    private val transactionDao: TransactionDao,
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

    // 「添加规则」面板的候选：只在进入页面时读一次，商户名不会频繁变化，
    // 不值得为它常驻一个数据库观察者。
    private val _recentMerchants = MutableStateFlow<List<String>>(emptyList())
    val recentMerchants: StateFlow<List<String>> = _recentMerchants.asStateFlow()

    init {
        viewModelScope.launch {
            _recentMerchants.value = runCatching {
                transactionDao.recentMerchants(RECENT_MERCHANT_LIMIT)
            }.getOrDefault(emptyList())
        }
    }

    fun clearMerchantRules() {
        viewModelScope.launch { prefs.clearMerchantRules() }
    }

    /**
     * 商户分类记忆页的保存：新增时 [previousMerchant] 为 null，编辑时传原商户名，
     * 改名会先删旧 key 再写新 key（见 SettingsPreferences.saveMerchantRule）。
     */
    fun saveMerchantRule(
        previousMerchant: String?,
        merchant: String,
        category: TransactionCategory,
        learningPaused: Boolean,
    ) {
        viewModelScope.launch {
            prefs.saveMerchantRule(previousMerchant, merchant, category, learningPaused)
        }
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

    companion object {
        /** 「最近商户」候选数量：够覆盖常用商户，又不会把面板塞满。 */
        const val RECENT_MERCHANT_LIMIT = 30
    }
}
