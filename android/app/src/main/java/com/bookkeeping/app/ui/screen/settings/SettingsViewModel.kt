package com.bookkeeping.app.ui.screen.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.billexport.BillExport
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.notification.KeepAliveService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val prefs: SettingsPreferences,
    private val transactionDao: TransactionDao,
    private val repository: TransactionRepository,
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

    val cleanupTransactions = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun categorizeOther(
        merchant: String,
        singleId: Long?,
        category: TransactionCategory,
        remember: Boolean,
        onComplete: (Int) -> Unit = {},
    ) {
        viewModelScope.launch {
            val updated = repository.categorizeOther(merchant, singleId, category, remember)
            onComplete(updated)
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
        applyToHistory: Boolean = true,
        onComplete: (Int) -> Unit = {},
    ) {
        viewModelScope.launch {
            prefs.saveMerchantRule(previousMerchant, merchant, category, learningPaused)
            val updated = if (applyToHistory) {
                repository.applyMerchantRuleToHistory(merchant, category)
            } else {
                0
            }
            onComplete(updated)
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

    fun exportBills(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val records = transactionDao.observeAll().first().map { transaction ->
                        BillExport.Record(
                            timestampMs = transaction.timestamp,
                            type = transaction.type,
                            amountCents = transaction.amountCents,
                            category = transaction.category,
                            merchant = transaction.merchant,
                            account = transaction.account,
                            source = transaction.source,
                            note = transaction.note,
                        )
                    }
                    val output = appContext.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Unable to open document output stream")
                    output.use { it.write(BillExport.csvBytes(records)) }
                }
                AppLanguage.tr(appContext, "账单已导出")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppLanguage.tr(appContext, "账单导出失败")
            }
            onResult(message)
        }
    }

    companion object {
        /** 「最近商户」候选数量：够覆盖常用商户，又不会把面板塞满。 */
        const val RECENT_MERCHANT_LIMIT = 30
    }
}
