package com.bookkeeping.app.ui.screen.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    repository: TransactionRepository,
) : ViewModel() {
    val transactions: StateFlow<List<TransactionEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
