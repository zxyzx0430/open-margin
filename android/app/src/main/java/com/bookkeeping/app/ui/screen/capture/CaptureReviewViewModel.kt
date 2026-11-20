package com.bookkeeping.app.ui.screen.capture

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
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
    private val diagnostics: TransactionDiagnostics,
) : ViewModel() {

    data class UiState(
        val pending: List<CaptureCandidate> = emptyList(),
        /** A full-list reload is running; per-card writes use [busyIds]. */
        val busy: Boolean = false,
        /** Cards with a write in flight. One card never blocks another. */
        val busyIds: Set<String> = emptySet(),
        val message: String? = null,
    )

    private val store = CaptureCandidateStore(context)
    private val queue = CaptureReviewQueue()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val pending = withContext(Dispatchers.IO) { store.pending() }
            queue.replacePending(pending)
            publish()
        }
    }

    private fun publish(message: String? = _state.value.message) {
        _state.value = UiState(
            pending = queue.pending(),
            busy = false,
            busyIds = queue.busyIds(),
            message = message,
        )
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
        // Only this card is blocked while it writes; other cards stay actionable.
        if (!queue.begin(resolved)) return
        publish(message = null)
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
                    )
                }
            }
            val insertedId = result.getOrNull()
            val inserted = result.isSuccess && insertedId != null && insertedId > 0L
            if (result.isSuccess && merchant.isNotBlank()) {
                settings.recordCategoryLearning(merchant, category)
            }
            if (result.isFailure) {
                queue.rollback(resolved)
                publish(message = AppLanguage.tr(context, "记入失败，请稍后重试"))
                recordFailure(
                    resolved,
                    "approve: ledger write failed (${result.exceptionOrNull()?.javaClass?.simpleName})",
                )
                return@launch
            }
            val status = if (inserted) {
                CaptureCandidate.STATUS_APPROVED
            } else {
                CaptureCandidate.STATUS_IGNORED
            }
            val marked = withContext(Dispatchers.IO) { store.mark(resolved, status) }
            if (!marked) {
                // The ledger part succeeded, only the candidate status did not:
                // keep the card and say so instead of pretending it is done.
                queue.rollback(resolved)
                val statusText = AppLanguage.tr(context, CaptureReviewText.IGNORE_FAILED)
                publish(
                    message = if (inserted) {
                        AppLanguage.tr(context, "已记入账本") + " · " + statusText
                    } else {
                        statusText
                    },
                )
                recordFailure(
                    resolved,
                    "approve: ledger inserted=$inserted but store.mark returned false",
                )
                return@launch
            }
            queue.finish(resolved.candidateId)
            queue.replacePending(withContext(Dispatchers.IO) { store.pending() })
            publish(
                message = when {
                    inserted -> AppLanguage.tr(context, "已记入账本")
                    else -> AppLanguage.tr(context, "这笔记录已存在，已从待确认中移除")
                },
            )
        }
    }

    fun ignore(candidate: CaptureCandidate) {
        // Only the same card is refused while it writes; a tap on another card
        // is never dropped, which is what made users tap twice.
        if (!queue.begin(candidate)) return
        publish(message = null)
        viewModelScope.launch {
            val marked = withContext(Dispatchers.IO) {
                store.mark(candidate, CaptureCandidate.STATUS_IGNORED)
            }
            val outcome = queue.settleIgnore(candidate, marked)
            if (outcome == IgnoreOutcome.IGNORED) {
                queue.replacePending(withContext(Dispatchers.IO) { store.pending() })
            }
            publish(message = AppLanguage.tr(context, CaptureReviewText.ignore(outcome)))
            if (outcome == IgnoreOutcome.MARK_FAILED) {
                recordFailure(candidate, "ignore: store.mark returned false")
            }
        }
    }

    /** One failure event per rejected write; diagnostics never change bookkeeping. */
    private suspend fun recordFailure(candidate: CaptureCandidate, details: String) {
        withContext(Dispatchers.IO) {
            diagnostics.record(
                attemptId = candidate.diagnosticAttemptId ?: TransactionDiagnostics.newAttemptId(),
                stage = TransactionDiagnostics.STAGE_EDIT,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = details,
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

/** Outcome of one ignore attempt, free of Android so the rules stay unit-testable. */
enum class IgnoreOutcome {
    IGNORED,
    MARK_FAILED,
}

/**
 * Per-card review queue: which candidates are still waiting and which ones
 * already have a write in flight.
 *
 * It is pure Kotlin on purpose. The dialog used to gate every card on one
 * global `busy` flag, so a tap on another card was silently dropped while a
 * write was running; keeping the rule here makes that behaviour directly
 * testable.
 */
class CaptureReviewQueue(initial: List<CaptureCandidate> = emptyList()) {

    private val items = mutableListOf<CaptureCandidate>()
    private val inFlight = linkedSetOf<String>()

    init {
        replacePending(initial)
    }

    fun pending(): List<CaptureCandidate> = items.toList()

    fun busyIds(): Set<String> = inFlight.toSet()

    /** Store truth wins, but a card with a write in flight stays hidden until it settles. */
    fun replacePending(list: List<CaptureCandidate>) {
        items.clear()
        items.addAll(list.filterNot { it.candidateId in inFlight })
        inFlight.retainAll(list.mapTo(mutableSetOf()) { it.candidateId })
    }

    /**
     * Accept a tap for [candidate]: the card leaves the list and joins the
     * in-flight set. False means this card must not start another write.
     */
    fun begin(candidate: CaptureCandidate): Boolean {
        if (items.none { it.candidateId == candidate.candidateId }) return false
        // A card only blocks itself: two taps on the same card must not write
        // twice, while a write on A must never swallow a tap on B.
        if (!inFlight.add(candidate.candidateId)) return false
        items.removeAll { it.candidateId == candidate.candidateId }
        return true
    }

    /** Settle an ignore write. A failed mark must put the card back. */
    fun settleIgnore(candidate: CaptureCandidate, marked: Boolean): IgnoreOutcome {
        if (!marked) {
            rollback(candidate)
            return IgnoreOutcome.MARK_FAILED
        }
        finish(candidate.candidateId)
        return IgnoreOutcome.IGNORED
    }

    /** Put a card back after a failed write, keeping capture-time order. */
    fun rollback(candidate: CaptureCandidate) {
        // Clear the in-flight mark even if a concurrent store refresh already
        // dropped this id from bookkeeping; a failed write must stay visible.
        inFlight.remove(candidate.candidateId)
        if (items.any { it.candidateId == candidate.candidateId }) return
        val index = items.indexOfFirst { it.capturedAtMs > candidate.capturedAtMs }
        if (index < 0) items.add(candidate) else items.add(index, candidate)
    }

    fun finish(candidateId: String) {
        inFlight.remove(candidateId)
    }
}

/** User-visible source strings for ignore outcomes; translated at the UI edge. */
object CaptureReviewText {
    const val IGNORED = "扫描结果已忽略"
    const val IGNORE_FAILED = "候选状态未能更新，请重试"

    fun ignore(outcome: IgnoreOutcome): String = when (outcome) {
        IgnoreOutcome.IGNORED -> IGNORED
        IgnoreOutcome.MARK_FAILED -> IGNORE_FAILED
    }
}
