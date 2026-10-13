package com.bookkeeping.app.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.TransactionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Local pending operations for per-record conflict review. */
@HiltViewModel
class PendingSyncViewModel @Inject constructor(
    dao: TransactionDao,
) : ViewModel() {
    val pending: StateFlow<List<TransactionEntity>> =
        dao.observePendingMutations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
