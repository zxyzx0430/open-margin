package com.bookkeeping.app.ui.screen.billimport

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.backup.AndroidBackupStorage
import com.bookkeeping.app.backup.BackupPreview
import com.bookkeeping.app.backup.BackupRestoreOptions
import com.bookkeeping.app.backup.BackupRestoreResult
import com.bookkeeping.app.backup.webdav.WebDavBackupCoordinator
import com.bookkeeping.app.backup.webdav.WebDavBackupFile
import com.bookkeeping.app.backup.webdav.WebDavBackupWorker
import com.bookkeeping.app.backup.webdav.WebDavConfig
import com.bookkeeping.app.backup.webdav.WebDavPrefs
import com.bookkeeping.app.backup.webdav.WebDavPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class WebDavBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    storage: AndroidBackupStorage,
) : ViewModel() {

    private val prefs = WebDavPrefs(context)
    private val coordinator = WebDavBackupCoordinator(
        context = context,
        storage = storage,
        prefs = prefs,
    )

    data class UiState(
        val preset: WebDavPreset = WebDavPreset.JIANGUOYUN,
        val serverUrl: String = WebDavPreset.JIANGUOYUN.defaultUrl,
        val username: String = "",
        val password: String = "",
        val autoEnabled: Boolean = false,
        val wifiOnly: Boolean = false,
        val lastBackupMs: Long = 0L,
        val lastBackupStatus: String = "",
        val testing: Boolean = false,
        val backingUp: Boolean = false,
        val loadingRemote: Boolean = false,
        val restoring: Boolean = false,
        val remoteFiles: List<WebDavBackupFile> = emptyList(),
        val selectedFile: WebDavBackupFile? = null,
        val pendingZipBytes: ByteArray? = null,
        val preview: BackupPreview? = null,
        val restoreResult: BackupRestoreResult? = null,
        val feedback: String? = null,
        val error: String? = null,
    ) {
        val isBusy: Boolean get() = testing || backingUp || loadingRemote || restoring
    }

    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun loadInitial(): UiState {
        val snap = prefs.read()
        return UiState(
            preset = snap.preset,
            serverUrl = snap.serverUrl,
            username = snap.username,
            password = snap.password,
            autoEnabled = snap.autoEnabled,
            wifiOnly = snap.wifiOnly,
            lastBackupMs = snap.lastBackupMs,
            lastBackupStatus = snap.lastBackupStatus,
        )
    }

    fun refreshFromPrefs() {
        val snap = prefs.read()
        _state.update {
            it.copy(
                lastBackupMs = snap.lastBackupMs,
                lastBackupStatus = snap.lastBackupStatus,
            )
        }
    }

    fun selectPreset(preset: WebDavPreset) {
        _state.update {
            it.copy(
                preset = preset,
                serverUrl = preset.defaultUrl,
                feedback = null,
                error = null,
            )
        }
    }

    fun updateUrl(value: String) {
        _state.update { it.copy(serverUrl = value, feedback = null, error = null) }
    }

    fun updateUsername(value: String) {
        _state.update { it.copy(username = value, feedback = null, error = null) }
    }

    fun updatePassword(value: String) {
        _state.update { it.copy(password = value, feedback = null, error = null) }
    }

    fun setAutoEnabled(enabled: Boolean) {
        _state.update { it.copy(autoEnabled = enabled) }
        saveConfigInternal()
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        _state.update { it.copy(wifiOnly = wifiOnly) }
        saveConfigInternal()
    }

    fun saveConfigInternal() {
        val cur = _state.value
        prefs.saveConfig(
            preset = cur.preset,
            serverUrl = cur.serverUrl,
            username = cur.username,
            password = cur.password,
            autoEnabled = cur.autoEnabled,
            wifiOnly = cur.wifiOnly,
        )
        WebDavBackupWorker.syncSchedule(
            context = context,
            enabled = cur.autoEnabled && cur.serverUrl.startsWith("http") && cur.username.isNotBlank() && cur.password.isNotBlank(),
            wifiOnly = cur.wifiOnly,
        )
    }

    fun testConnection() {
        val cur = _state.value
        if (cur.isBusy) return
        saveConfigInternal()
        _state.update { it.copy(testing = true, feedback = null, error = null) }
        viewModelScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    coordinator.testConnection(WebDavConfig(cur.serverUrl, cur.username, cur.password))
                }
            }
            res.fold(
                onSuccess = { count ->
                    _state.update {
                        it.copy(
                            testing = false,
                            feedback = "连接成功，网盘里已有 $count 份清衡备份",
                        )
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(testing = false, error = err.message ?: "连接失败")
                    }
                },
            )
        }
    }

    fun backupNow() {
        val cur = _state.value
        if (cur.isBusy) return
        saveConfigInternal()
        _state.update { it.copy(backingUp = true, feedback = null, error = null) }
        viewModelScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    coordinator.backupNow(WebDavConfig(cur.serverUrl, cur.username, cur.password))
                }
            }
            res.fold(
                onSuccess = { manifest ->
                    val snap = prefs.read()
                    _state.update {
                        it.copy(
                            backingUp = false,
                            lastBackupMs = snap.lastBackupMs,
                            lastBackupStatus = snap.lastBackupStatus,
                            feedback = "备份成功（${manifest.transactionCount} 笔账单 · 自动保留最近 10 份）",
                        )
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(backingUp = false, error = err.message ?: "备份失败")
                    }
                },
            )
        }
    }

    fun loadRemoteBackups() {
        val cur = _state.value
        if (cur.isBusy) return
        saveConfigInternal()
        _state.update { it.copy(loadingRemote = true, feedback = null, error = null) }
        viewModelScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) { coordinator.listRemoteBackups() }
            }
            res.fold(
                onSuccess = { files ->
                    _state.update {
                        it.copy(
                            loadingRemote = false,
                            remoteFiles = files,
                            feedback = if (files.isEmpty()) "网盘目录里还没有备份文件" else null,
                        )
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(loadingRemote = false, error = err.message ?: "获取备份列表失败")
                    }
                },
            )
        }
    }

    fun inspectRemoteFile(file: WebDavBackupFile) {
        val cur = _state.value
        if (cur.isBusy) return
        _state.update {
            it.copy(
                loadingRemote = true,
                selectedFile = file,
                preview = null,
                pendingZipBytes = null,
                restoreResult = null,
                error = null,
            )
        }
        viewModelScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) { coordinator.downloadAndInspect(file) }
            }
            res.fold(
                onSuccess = { (bytes, preview) ->
                    _state.update {
                        it.copy(
                            loadingRemote = false,
                            pendingZipBytes = bytes,
                            preview = preview,
                        )
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            loadingRemote = false,
                            selectedFile = null,
                            error = err.message ?: "读取备份预览失败",
                        )
                    }
                },
            )
        }
    }

    fun confirmRestore(overwriteConfigs: Boolean) {
        val cur = _state.value
        val bytes = cur.pendingZipBytes ?: return
        if (cur.isBusy) return
        _state.update { it.copy(restoring = true, error = null) }
        viewModelScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    coordinator.restoreFromBytes(
                        zipBytes = bytes,
                        options = BackupRestoreOptions(
                            restoreEvidence = false,
                            overwriteExistingConfigs = overwriteConfigs,
                        ),
                    )
                }
            }
            res.fold(
                onSuccess = { result ->
                    _state.update {
                        it.copy(
                            restoring = false,
                            pendingZipBytes = null,
                            preview = null,
                            selectedFile = null,
                            restoreResult = result,
                            feedback = "恢复完成：新增 ${result.insertedTransactions} 笔账单 · 跳过 ${result.skippedDuplicateTransactions} 笔重复",
                        )
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(restoring = false, error = err.message ?: "恢复失败")
                    }
                },
            )
        }
    }

    fun dismissPreview() {
        _state.update {
            it.copy(selectedFile = null, pendingZipBytes = null, preview = null)
        }
    }
}
