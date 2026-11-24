package com.bookkeeping.app.ui.screen.billimport

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.backup.AndroidBackupStorage
import com.bookkeeping.app.backup.BackupPreview
import com.bookkeeping.app.backup.BackupRestoreOptions
import com.bookkeeping.app.backup.BackupRestoreResult
import com.bookkeeping.app.backup.FullBackupManager
import com.bookkeeping.app.i18n.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class FullBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    storage: AndroidBackupStorage,
) : ViewModel() {

    private val backupManager = FullBackupManager(
        storage = storage,
        appVersion = BuildConfig.VERSION_NAME,
    )

    data class UiState(
        val includeEvidence: Boolean = false,
        val exportingZip: Boolean = false,
        val exportFeedback: String? = null,
        val exportError: String? = null,
        val inspectingZip: Boolean = false,
        val pendingRestoreUri: Uri? = null,
        val restorePreview: BackupPreview? = null,
        val restoreOptions: BackupRestoreOptions = BackupRestoreOptions(),
        val restoringZip: Boolean = false,
        val restoreResult: BackupRestoreResult? = null,
        val restoreError: String? = null,
    ) {
        val isBusy: Boolean get() = exportingZip || inspectingZip || restoringZip
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setIncludeEvidence(enabled: Boolean) {
        if (_state.value.isBusy) return
        _state.update { it.copy(includeEvidence = enabled) }
    }

    fun exportZip(uri: Uri) {
        val current = _state.value
        if (current.isBusy) return
        val includeEvidence = current.includeEvidence
        _state.update {
            it.copy(
                exportingZip = true,
                exportFeedback = null,
                exportError = null,
                restoreError = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: context.contentResolver.openOutputStream(uri)
                        ?: error(l10n("无法写入备份文件", "Unable to open backup output file"))
                    output.use { stream ->
                        backupManager.exportToZip(stream, includeEvidence = includeEvidence)
                    }
                }
            }
            result.fold(
                onSuccess = { manifest ->
                    val summary = if (isEnglish()) {
                        buildString {
                            append("Backup exported (${manifest.transactionCount} transactions, ${manifest.subscriptionCount} subscriptions")
                            if (manifest.includesEvidence) {
                                append(", ${manifest.evidenceCount} images")
                            }
                            append(")")
                        }
                    } else {
                        buildString {
                            append("完整备份已导出（${manifest.transactionCount} 笔账单 · ${manifest.subscriptionCount} 项订阅")
                            if (manifest.includesEvidence) {
                                append(" · ${manifest.evidenceCount} 张原图")
                            }
                            append("）")
                        }
                    }
                    _state.update { it.copy(exportingZip = false, exportFeedback = summary) }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    _state.update {
                        it.copy(
                            exportingZip = false,
                            exportError = failure.message
                                ?: l10n("完整备份导出失败", "Failed to export backup"),
                        )
                    }
                },
            )
        }
    }

    fun inspectBackup(uri: Uri) {
        if (_state.value.isBusy) return
        _state.update {
            it.copy(
                inspectingZip = true,
                pendingRestoreUri = uri,
                restorePreview = null,
                restoreResult = null,
                restoreError = null,
                exportFeedback = null,
                exportError = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(l10n("无法读取备份文件", "Unable to read backup file"))
                    input.use { stream ->
                        backupManager.inspectZip(stream)
                    }
                }
            }
            result.fold(
                onSuccess = { preview ->
                    _state.update {
                        it.copy(
                            inspectingZip = false,
                            restorePreview = preview,
                            restoreOptions = BackupRestoreOptions(
                                restoreTransactions = true,
                                restoreSubscriptions = preview.manifest.subscriptionCount > 0 ||
                                    preview.newSubscriptionCount > 0 ||
                                    preview.updatedSubscriptionCount > 0,
                                restoreBudgets = preview.budgetMonthCount > 0,
                                restoreMerchantRules = preview.merchantRuleCount > 0,
                                restoreSettings = preview.settingsCount > 0,
                                restoreEvidence = preview.evidenceFileCount > 0,
                                overwriteExistingConfigs = false,
                            ),
                        )
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    _state.update {
                        it.copy(
                            inspectingZip = false,
                            pendingRestoreUri = null,
                            restoreError = failure.message
                                ?: l10n("无法解析备份文件", "Unable to inspect backup archive"),
                        )
                    }
                },
            )
        }
    }

    fun updateRestoreOptions(transform: (BackupRestoreOptions) -> BackupRestoreOptions) {
        if (_state.value.restoringZip) return
        _state.update { it.copy(restoreOptions = transform(it.restoreOptions)) }
    }

    fun confirmRestore() {
        val current = _state.value
        val uri = current.pendingRestoreUri ?: return
        if (current.isBusy || !current.restoreOptions.hasAnySelected) return
        _state.update { it.copy(restoringZip = true, restoreError = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(l10n("无法读取备份文件", "Unable to read backup file"))
                    input.use { stream ->
                        backupManager.restoreFromZip(stream, current.restoreOptions)
                    }
                }
            }
            result.fold(
                onSuccess = { restoreResult ->
                    _state.update {
                        it.copy(
                            restoringZip = false,
                            pendingRestoreUri = null,
                            restorePreview = null,
                            restoreResult = restoreResult,
                        )
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    _state.update {
                        it.copy(
                            restoringZip = false,
                            restoreError = failure.message
                                ?: l10n("恢复备份失败，请重试", "Failed to restore backup"),
                        )
                    }
                },
            )
        }
    }

    fun dismissRestorePreview() {
        if (_state.value.restoringZip) return
        _state.update {
            it.copy(
                pendingRestoreUri = null,
                restorePreview = null,
                restoreError = null,
            )
        }
    }

    fun resetFeedback() {
        if (_state.value.isBusy) return
        _state.update {
            it.copy(
                exportFeedback = null,
                exportError = null,
                restoreResult = null,
                restoreError = null,
                pendingRestoreUri = null,
                restorePreview = null,
            )
        }
    }

    private fun isEnglish(): Boolean = AppLanguage.read(context) == AppLanguage.ENGLISH

    private fun l10n(zh: String, en: String): String = if (isEnglish()) en else zh
}
