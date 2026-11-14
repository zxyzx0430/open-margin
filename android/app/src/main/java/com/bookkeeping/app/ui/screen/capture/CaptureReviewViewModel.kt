package com.bookkeeping.app.ui.screen.capture

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
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
class CaptureReviewViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: TransactionRepository,
) : ViewModel() {

    data class UiState(
        val pending: List<CaptureCandidate> = emptyList(),
        val busy: Boolean = false,
        val message: String? = null,
    )

    private val store = CaptureCandidateStore(context)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) { store.pending() }
            _state.update { it.copy(pending = pending) }
        }
    }

    fun approve(
        candidate: CaptureCandidate,
        merchant: String,
        category: TransactionCategory,
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val entity = CaptureReviewMapping.toTransactionEntity(
                        candidate = candidate,
                        merchant = merchant,
                        category = category,
                    )
                    repository.insertIfNotDuplicate(entity, notifySuccess = false)
                }
            }
            val insertedId = result.getOrNull()
            val inserted = result.isSuccess && insertedId != null && insertedId > 0L
            if (result.isSuccess) {
                val status = if (inserted) {
                    CaptureCandidate.STATUS_APPROVED
                } else {
                    CaptureCandidate.STATUS_IGNORED
                }
                withContext(Dispatchers.IO) { store.mark(candidate, status) }
            }
            val pending = withContext(Dispatchers.IO) { store.pending() }
            _state.value = UiState(
                pending = pending,
                busy = false,
                message = when {
                    result.isFailure -> "记入失败，请稍后重试"
                    inserted -> "已记入账本"
                    else -> "这笔记录已存在，已从待确认中移除"
                },
            )
        }
    }

    fun ignore(candidate: CaptureCandidate) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.mark(candidate, CaptureCandidate.STATUS_IGNORED)
            }
            val pending = withContext(Dispatchers.IO) { store.pending() }
            _state.value = UiState(
                pending = pending,
                busy = false,
                message = "扫描结果已忽略",
            )
        }
    }
}
