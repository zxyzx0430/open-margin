package com.bookkeeping.app.ui.screen.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticRetention
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.dao.TransactionDiagnosticDao
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

internal inline fun handleDiagnosticQueryFailure(
    failure: Throwable,
    isCurrentRequest: Boolean,
    publishError: (Throwable) -> Unit,
) {
    if (failure is CancellationException) throw failure
    if (isCurrentRequest) publishError(failure)
}

@HiltViewModel
class TransactionDiagnosticsViewModel @Inject constructor(
    private val dao: TransactionDiagnosticDao,
    private val diagnostics: TransactionDiagnostics,
) : ViewModel() {
    data class UiState(
        val query: String = "",
        val selectedTransactionId: Long? = null,
        val transactionIds: List<Long> = emptyList(),
        val events: List<TransactionDiagnosticEventEntity> = emptyList(),
        val limit: Int = PAGE_SIZE,
        val loading: Boolean = true,
        val error: String? = null,
        val hasMore: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var eventJob: Job? = null
    private var reloadGeneration = 0L

    init {
        viewModelScope.launch {
            try {
                diagnostics.pruneExpired()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Pruning is best-effort and does not block loading filters.
            }
            dao.observeRecentTransactionIds(
                cutoffMs = TransactionDiagnosticRetention.cutoffMs(System.currentTimeMillis()),
                limit = MAX_BILL_FILTERS,
            ).catch { failure ->
                if (failure is CancellationException) throw failure
                _state.value = _state.value.copy(error = "无法加载账单筛选项")
            }.collect { ids ->
                _state.value = _state.value.copy(transactionIds = ids)
            }
        }
        reload()
    }

    fun setQuery(query: String) {
        if (_state.value.query == query) return
        _state.value = _state.value.copy(query = query, limit = PAGE_SIZE)
        reload()
    }

    fun selectTransaction(id: Long?) {
        if (_state.value.selectedTransactionId == id) return
        _state.value = _state.value.copy(selectedTransactionId = id, limit = PAGE_SIZE)
        reload()
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || !current.hasMore) return
        _state.value = current.copy(limit = current.limit + PAGE_SIZE)
        reload()
    }

    fun retry() {
        _state.value = _state.value.copy(error = null)
        reload()
    }

    private fun reload() {
        eventJob?.cancel()
        val request = _state.value
        val generation = ++reloadGeneration
        eventJob = viewModelScope.launch {
            if (generation != reloadGeneration) return@launch
            _state.value = request.copy(loading = true, error = null)
            delay(180L)
            try {
                diagnostics.pruneExpired()
                dao.observeEvents(
                    cutoffMs = TransactionDiagnosticRetention.cutoffMs(System.currentTimeMillis()),
                    transactionId = request.selectedTransactionId,
                    query = request.query.trim(),
                    limit = request.limit,
                    offset = 0,
                ).catch { failure ->
                    handleDiagnosticQueryFailure(failure, generation == reloadGeneration) {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = "无法加载诊断日志",
                        )
                    }
                }.collect { events ->
                    if (generation != reloadGeneration) return@collect
                    _state.value = _state.value.copy(
                        events = events,
                        loading = false,
                        error = null,
                        hasMore = events.size >= request.limit,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                handleDiagnosticQueryFailure(failure, generation == reloadGeneration) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "无法加载诊断日志",
                    )
                }
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_BILL_FILTERS = 200
    }
}
