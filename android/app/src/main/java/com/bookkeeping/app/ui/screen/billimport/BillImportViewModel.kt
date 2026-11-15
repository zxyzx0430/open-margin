package com.bookkeeping.app.ui.screen.billimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.billimport.BillImportParser
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillParserOptions
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.i18n.AppLanguage
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
        val enrichedCount: Int? = null,
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
                        ?: error(AppLanguage.tr(context, "无法读取文件"))
                    require(bytes.size <= MAX_FILE_BYTES) {
                        AppLanguage.tr(context, "文件过大")
                    }
                    val transactions = repository.observeAll().first()
                    val existingKeys = BillImportMapping.existingRowKeys(transactions)
                    val parsed = BillImportParser.parse(
                        bytes = bytes,
                        fileName = name,
                        options = BillParserOptions(existingDuplicateKeys = existingKeys),
                    )
                    BillImportMapping.markExistingRows(
                        preview = parsed,
                        transactions = transactions,
                        duplicateMessage = AppLanguage.tr(context, "账本已有相同记录"),
                    )
                }
            }
            _state.value = result.fold(
                onSuccess = { preview -> UiState(preview = preview) },
                onFailure = { failure ->
                    UiState(error = failure.message ?: AppLanguage.tr(context, "无法解析账单文件"))
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
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val latest = repository.observeAll().first()
                    val existingKeys = BillImportMapping.existingRowKeys(latest)
                    val validRows = preview.rows
                        .filter { it.kind == com.bookkeeping.app.billimport.BillPreviewKind.VALID }
                        .mapNotNull { it.parsed }
                        .filterNot { row ->
                            BillImportMapping.isAlreadyPresent(row, latest, existingKeys)
                        }
                    val entities = validRows.mapNotNull(BillImportMapping::toTransactionEntity)
                    require(entities.size == validRows.size) {
                        AppLanguage.tr(context, "部分有效行无法映射，未写入任何记录")
                    }
                    val latestById = latest.associateBy { it.id }
                    val updates = preview.rows
                        .filter {
                            it.kind == com.bookkeeping.app.billimport.BillPreviewKind.DUPLICATE &&
                                it.enrichable
                        }
                        .distinctBy { it.matchedTransactionId }
                        .mapNotNull { row ->
                            val parsed = row.parsed ?: return@mapNotNull null
                            val existing = row.matchedTransactionId
                                ?.let(latestById::get)
                                ?: return@mapNotNull null
                            BillImportMapping.enrichExisting(existing, parsed)
                        }
                    repository.importBillBatch(entities, updates)
                }
            }
            _state.value = result.fold(
                onSuccess = { (inserted, enriched) ->
                    _state.value.copy(
                        importing = false,
                        importedCount = inserted,
                        enrichedCount = enriched,
                        done = true,
                    )
                },
                onFailure = { failure ->
                    _state.value.copy(
                        importing = false,
                        error = AppLanguage.tr(context, "导入失败，未写入任何记录：") +
                            (failure.message ?: AppLanguage.tr(context, "数据库错误")),
                    )
                },
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
