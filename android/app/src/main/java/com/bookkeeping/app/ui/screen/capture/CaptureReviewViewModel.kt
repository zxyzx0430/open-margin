package com.bookkeeping.app.ui.screen.capture

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.repository.CrossSourceDuplicatePolicy
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureBookGate
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
    @ApplicationContext private val context: Context,
    private val repository: TransactionRepository,
    private val settings: SettingsPreferences,
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
        direction: String? = null,
    ) {
        val resolved = CaptureReviewDirection.resolve(candidate, direction)
        if (!CaptureBookGate.mayConfirm(resolved)) {
            val message = when {
                CaptureReviewDirection.requiresSelection(candidate) &&
                    !CaptureReviewDirection.isChoice(direction) ->
                    AppLanguage.tr(context, "请先选择收支方向")
                else -> AppLanguage.tr(context, "仅预览，不能记入账本")
            }
            _state.update { it.copy(message = message) }
            return
        }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val entity = CaptureReviewMapping.toTransactionEntity(
                        candidate = resolved,
                        merchant = merchant,
                        category = category,
                    )
                    repository.insertIfNotDuplicate(
                        entity,
                        notifySuccess = false,
                        diagnosticInput = TransactionDiagnosticInput(
                            attemptId = candidate.diagnosticAttemptId
                                ?: TransactionDiagnostics.newAttemptId(),
                        ),
                        crossSourceWindowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                    )
                }
            }
            val insertedId = result.getOrNull()
            val inserted = result.isSuccess && insertedId != null && insertedId > 0L
            if (result.isSuccess && merchant.isNotBlank()) {
                settings.recordCategoryLearning(merchant, category)
            }
            if (result.isSuccess) {
                val status = if (inserted) {
                    CaptureCandidate.STATUS_APPROVED
                } else {
                    CaptureCandidate.STATUS_IGNORED
                }
                withContext(Dispatchers.IO) { store.mark(resolved, status) }
            }
            val pending = withContext(Dispatchers.IO) { store.pending() }
            _state.value = UiState(
                pending = pending,
                busy = false,
                message = when {
                    result.isFailure -> AppLanguage.tr(context, "记入失败，请稍后重试")
                    inserted -> AppLanguage.tr(context, "已记入账本")
                    else -> AppLanguage.tr(context, "这笔记录已存在，已从待确认中移除")
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
                message = AppLanguage.tr(context, "扫描结果已忽略"),
            )
        }
    }
}

/**
 * Direction review rules for the capture inbox.
 *
 * A screenshot whose expense/income direction could not be read stays
 * [CaptureCandidate.DIRECTION_UNKNOWN] and [CaptureBookGate.mayConfirm] keeps
 * refusing it. The review UI breaks that dead end by asking the user for the
 * direction: a valid pick replaces the unknown value and marks it explicit, so
 * the same gate now allows the write. Pure so the rule is testable without
 * Android or Compose.
 */
object CaptureReviewDirection {
    /** The only directions the review UI may offer, in display order. */
    val directions: Set<String> = linkedSetOf(
        CaptureCandidate.DIRECTION_EXPENSE,
        CaptureCandidate.DIRECTION_INCOME,
    )

    fun isChoice(value: String?): Boolean = value != null && value in directions

    /** True when the candidate has no readable direction and needs a user pick. */
    fun requiresSelection(candidate: CaptureCandidate): Boolean =
        candidate.direction == CaptureCandidate.DIRECTION_UNKNOWN

    /**
     * Candidate handed to the confirm chain. A valid pick supplies the direction
     * and marks it explicit; no pick, or a value outside [directions], leaves the
     * candidate untouched so the gate keeps refusing the unknown direction.
     */
    fun resolve(candidate: CaptureCandidate, choice: String?): CaptureCandidate {
        if (!requiresSelection(candidate)) return candidate
        val picked = choice?.takeIf { isChoice(it) } ?: return candidate
        return candidate.copy(direction = picked, directionExplicit = true)
    }

    /** Whether the confirm action may book the candidate with [choice] applied. */
    fun canConfirm(candidate: CaptureCandidate, choice: String?): Boolean =
        CaptureBookGate.mayConfirm(resolve(candidate, choice))
}
