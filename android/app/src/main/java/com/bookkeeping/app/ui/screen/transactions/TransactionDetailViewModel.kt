package com.bookkeeping.app.ui.screen.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: TransactionRepository,
) : ViewModel() {
    private val transactionId = savedStateHandle.get<Long>("id") ?: -1L

    val transaction: StateFlow<TransactionEntity?> = repository.observeAll()
        .map { transactions -> transactions.firstOrNull { it.id == transactionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
