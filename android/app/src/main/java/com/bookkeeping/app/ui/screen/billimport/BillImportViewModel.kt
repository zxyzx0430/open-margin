package com.bookkeeping.app.ui.screen.billimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.billimport.BillImportFileReader
import com.bookkeeping.app.billimport.BillImportFileTooLargeException
import com.bookkeeping.app.billimport.BillImportParser
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillParserOptions
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
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
    private var fileAttempt = TransactionDiagnosticInput()

    fun load(uri: Uri) {
        if (_state.value.loading || _state.value.importing) return
        _state.value = UiState(loading = true)
        val attempt = TransactionDiagnosticInput()
        fileAttempt = attempt
        viewModelScope.launch {
            repository.recordDiagnostic(
                attemptId = attempt.attemptId,
                stage = TransactionDiagnostics.STAGE_PARSE,
                result = TransactionDiagnostics.RESULT_STARTED,
                details = "Import file parsing started",
            )
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val metadata = fileMetadata(uri)
                    val tooLargeMessage = AppLanguage.tr(context, "文件超过 20 MB，请先拆分")
                    if (metadata.sizeBytes != null && metadata.sizeBytes > MAX_FILE_BYTES.toLong()) {
                        error(tooLargeMessage)
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        try {
                            BillImportFileReader.readAtMost(input, MAX_FILE_BYTES)
                        } catch (_: BillImportFileTooLargeException) {
                            error(tooLargeMessage)
                        }
                    } ?: error(AppLanguage.tr(context, "无法读取文件"))
                    val transactions = repository.observeAll().first()
                    val existingKeys = BillImportMapping.existingRowKeys(transactions)
                    val parsed = BillImportParser.parse(
                        bytes = bytes,
                        fileName = metadata.displayName,
                        options = BillParserOptions(existingDuplicateKeys = existingKeys),
                    )
                    BillImportMapping.markExistingRows(
                        preview = parsed,
                        transactions = transactions,
                        duplicateMessage = AppLanguage.tr(context, "账本已有相同记录"),
                    )
                }
            }
            result.fold(
                onSuccess = { preview ->
                    BillImportDiagnosticPolicy.previewEvents(preview.rows.size).forEach { event ->
                        repository.recordDiagnostic(
                            attemptId = attempt.attemptId,
                            stage = TransactionDiagnostics.STAGE_PARSE,
                            result = event.result,
                            details = event.details,
                        )
                    }
                    _state.value = UiState(preview = preview)
                },
                onFailure = { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    repository.recordDiagnostic(
                        attemptId = attempt.attemptId,
                        stage = TransactionDiagnostics.STAGE_PARSE,
                        result = TransactionDiagnostics.RESULT_FAILURE,
                        details = "Import file parsing failed",
                    )
                    _state.value = UiState(error = failure.message ?: AppLanguage.tr(context, "无法解析账单文件"))
                },
            )
        }
    }

    fun confirm() {
        val current = _state.value
        val preview = current.preview ?: return
        if (current.importing || current.done) return
        val fileInput = fileAttempt
        _state.value = current.copy(importing = true, error = null)
        viewModelScope.launch {
            repository.recordDiagnostic(
                attemptId = fileInput.attemptId,
                stage = TransactionDiagnostics.STAGE_IMPORT,
                result = TransactionDiagnostics.RESULT_STARTED,
                details = "User confirmed bill import",
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val latest = repository.observeAll().first()
                    val existingKeys = BillImportMapping.existingRowKeys(latest)
                    val validRows = preview.rows
                        .filter { it.kind == com.bookkeeping.app.billimport.BillPreviewKind.VALID }
                        .mapNotNull { row -> row.parsed?.let { row to it } }
                        .filterNot { (_, row) ->
                            BillImportMapping.isAlreadyPresent(row, latest, existingKeys)
                        }
                    val entities = validRows.mapNotNull { (_, row) ->
                        BillImportMapping.toTransactionEntity(row)
                    }
                    require(entities.size == validRows.size) {
                        AppLanguage.tr(context, "部分有效行无法映射，未写入任何记录")
                    }
                    val insertInputs = validRows.map { TransactionDiagnosticInput() }
                    val latestById = latest.associateBy { it.id }
                    val updateRows = preview.rows
                        .filter {
                            it.kind == com.bookkeeping.app.billimport.BillPreviewKind.DUPLICATE &&
                                it.enrichable
                        }
                        .distinctBy { it.matchedTransactionId }
                    val updatePairs = updateRows.mapNotNull { row ->
                        val parsed = row.parsed ?: return@mapNotNull null
                        val existing = row.matchedTransactionId?.let(latestById::get)
                            ?: return@mapNotNull null
                        val updated = BillImportMapping.enrichExisting(existing, parsed)
                            ?: return@mapNotNull null
                        val input = TransactionDiagnosticInput()
                        existing.id to (updated to input)
                    }
                    val updates = updatePairs.map { it.second.first }
                    val updateInputs = updatePairs.associate { (id, pair) -> id to pair.second }
                    repository.importBillBatch(
                        inserts = entities,
                        updates = updates,
                        insertDiagnostics = insertInputs,
                        updateDiagnostics = updateInputs,
                    )
                }
            }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                repository.recordDiagnostic(
                    attemptId = fileInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_IMPORT,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = "Confirmed import failed",
                )
                _state.value = _state.value.copy(
                    importing = false,
                    error = AppLanguage.tr(context, "导入失败，未写入任何记录：") +
                        (failure.message ?: AppLanguage.tr(context, "数据库错误")),
                )
                return@launch
            }
            val (inserted, enriched) = result.getOrThrow()
            repository.recordDiagnostic(
                attemptId = fileInput.attemptId,
                stage = TransactionDiagnostics.STAGE_IMPORT,
                result = TransactionDiagnostics.RESULT_SUCCESS,
                details = "Confirmed import committed",
            )
            _state.value = _state.value.copy(
                importing = false,
                importedCount = inserted,
                enrichedCount = enriched,
                done = true,
            )
        }
    }

    fun reset() {
        if (_state.value.importing) return
        _state.value = UiState()
    }

    private data class FileMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )

    private fun fileMetadata(uri: Uri): FileMetadata = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                FileMetadata(null, null)
            } else {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                FileMetadata(
                    displayName = if (nameColumn >= 0) cursor.getString(nameColumn) else null,
                    sizeBytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        cursor.getLong(sizeColumn).takeIf { it >= 0L }
                    } else {
                        null
                    },
                )
            }
        } ?: FileMetadata(null, null)
    }.getOrDefault(FileMetadata(null, null))


    companion object {
        private const val MAX_FILE_BYTES = 20 * 1024 * 1024
    }
}
