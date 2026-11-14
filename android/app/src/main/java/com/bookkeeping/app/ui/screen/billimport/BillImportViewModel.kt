package com.bookkeeping.app.ui.screen.billimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.billimport.BillImportParser
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillParserOptions
import com.bookkeeping.app.billimport.BillPreviewKind
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BillImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TransactionRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val preview: BillImportPreview? = null,
        val error: String? = null,
        val importing: Boolean = false,
        val importedCount: Int? = null,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(uri: Uri) {
        if (_state.value.loading || _state.value.importing) return
        _state.value = UiState(loading = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val name = displayName(uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取文件")
                    require(bytes.size <= MAX_FILE_BYTES) { "文件过大" }
                    val transactions = repository.observeAll().first()
                    val existingKeys = BillImportMapping.existingRowKeys(transactions)
                    val parsed = BillImportParser.parse(
                        bytes = bytes,
                        fileName = name,
                        options = BillParserOptions(existingDuplicateKeys = existingKeys),
                    )
                    parsed.copy(
                        rows = parsed.rows.map { row ->
                            val value = row.parsed
                            if (row.kind == BillPreviewKind.VALID && value != null &&
                                BillImportMapping.ledgerRowKey(value) in existingKeys
                            ) {
                                row.copy(
                                    kind = BillPreviewKind.DUPLICATE,
                                    message = "账本已有相同记录",
                                )
                            } else {
                                row
                            }
                        },
                    )
                }
            }
            _state.value = result.fold(
                onSuccess = { preview -> UiState(preview = preview) },
                onFailure = { failure ->
                    UiState(error = failure.message ?: "无法解析账单文件")
                },
            )
        }
    }

    fun confirm() {
        val current = _state.value
        val preview = current.preview ?: return
        if (current.importing || current.done) return
        _state.value = current.copy(importing = true, error = null)
        viewModelScope.launch {
            val inserted = withContext(Dispatchers.IO) {
                var count = 0
                preview.validRows.forEach { row ->
                    val entity = BillImportMapping.toTransactionEntity(row) ?: return@forEach
                    val id = runCatching { repository.insert(entity) }.getOrDefault(-1L)
                    if (id > 0L) count++
                }
                count
            }
            _state.value = _state.value.copy(
                importing = false,
                importedCount = inserted,
                done = true,
            )
        }
    }

    fun reset() {
        if (_state.value.importing) return
        _state.value = UiState()
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    companion object {
        private const val MAX_FILE_BYTES = 20 * 1024 * 1024
    }
}
