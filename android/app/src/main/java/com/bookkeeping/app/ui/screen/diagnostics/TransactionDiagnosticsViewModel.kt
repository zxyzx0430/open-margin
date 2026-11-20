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
        /** True while the bulk "clear every raw text" write is in flight. */
        val clearingRawText: Boolean = false,
        /** One-shot result line under the toolbar, e.g. "原始正文已清除". */
        val rawTextNotice: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var eventJob: Job? = null
    private var reloadGeneration = 0L
    private var clearJob: Job? = null

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

    /**
     * Erase every stored raw input after the user confirms in the dialog.
     *
     * Deliberately not reversible and deliberately separate from the retention
     * prune: the summaries, stages and results stay readable, only the payload
     * (notification bodies, SMS text, OCR text, ASR transcripts) is nulled.
     */
    fun clearRawText() {
        if (clearJob?.isActive == true) return
        _state.value = _state.value.copy(clearingRawText = true, rawTextNotice = null)
        clearJob = viewModelScope.launch {
            val notice = try {
                diagnostics.clearRawText()
                "原始正文已清除"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "清除失败，请重试"
            }
            _state.value = _state.value.copy(clearingRawText = false, rawTextNotice = notice)
        }
    }

    fun dismissRawTextNotice() {
        if (_state.value.rawTextNotice == null) return
        _state.value = _state.value.copy(rawTextNotice = null)
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

/**
 * Pure presentation model for the diagnostics screen. One attempt = one capture
 * chain: [build] groups rows, orders the steps, and derives duration, entry kind
 * and the final outcome. Nothing here touches Android or Room, so the whole
 * grouping rule is unit tested on the JVM.
 *
 * Pass events oldest-first (the DAO returns newest-first, so callers use
 * `events.asReversed()`); rows that share a millisecond then keep insertion
 * order, which is the order the pipeline actually wrote them.
 */
internal object DiagnosticTimeline {

    enum class Entry { SCREENSHOT, NOTIFICATION, SMS, ACCESSIBILITY, IMPORT, MANUAL, VOICE, SYNC, EDIT, DELETE, CAPTURE, UNKNOWN }

    enum class Outcome { SUCCESS, WARNING, FAILURE, PARTIAL, RUNNING, UNKNOWN }

    data class Attempt(
        val attemptId: String,
        val entry: Entry,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val durationMs: Long,
        val outcome: Outcome,
        val transactionId: Long?,
        val steps: List<TransactionDiagnosticEventEntity>,
    ) {
        /**
         * False for rows written before schema v11: the card then shows only the
         * fields it had (stage, result, time, details) and no "—" placeholders.
         */
        val hasReadableSummaries: Boolean
            get() = steps.any { it.callSite != null || it.inputSummary != null || it.outputSummary != null }
    }

    fun build(events: List<TransactionDiagnosticEventEntity>): List<Attempt> {
        if (events.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<TransactionDiagnosticEventEntity>>()
        events.forEach { event ->
            grouped.getOrPut(event.attemptId) { mutableListOf() } += event
        }
        return grouped.map { (attemptId, rows) ->
            // sortedBy is stable: equal timestamps keep the caller's order.
            val steps = rows.sortedBy { it.occurredAtMs }
            val first = steps.first()
            val last = steps.last()
            Attempt(
                attemptId = attemptId,
                entry = inferEntry(steps),
                startedAtMs = first.occurredAtMs,
                endedAtMs = last.occurredAtMs,
                durationMs = (last.occurredAtMs - first.occurredAtMs).coerceAtLeast(0L),
                outcome = inferOutcome(steps),
                transactionId = steps.mapNotNull { it.transactionId }.lastOrNull(),
                steps = steps,
            )
        }.sortedWith(
            compareByDescending<Attempt> { it.endedAtMs }
                .thenByDescending { it.startedAtMs }
                .thenBy { it.attemptId },
        )
    }

    /**
     * Entry kind, in this order of evidence:
     * 1. the first non-blank callSite (it names the component that owns the chain);
     * 2. the stages present (import / sync-only / entry / delete / edit / capture).
     * Rows written before v11 have no callSite, so they fall back to stage hints.
     */
    fun inferEntry(steps: List<TransactionDiagnosticEventEntity>): Entry {
        steps.mapNotNull { it.callSite?.trim()?.takeIf(String::isNotEmpty) }
            .firstNotNullOfOrNull { entryForCallSite(it) }
            ?.let { return it }
        val stages = steps.map { it.stage }.toSet()
        return when {
            TransactionDiagnostics.STAGE_IMPORT in stages -> Entry.IMPORT
            stages.isNotEmpty() && stages.all { it == TransactionDiagnostics.STAGE_SYNC } -> Entry.SYNC
            TransactionDiagnostics.STAGE_ENTRY in stages -> Entry.MANUAL
            TransactionDiagnostics.STAGE_DELETE in stages -> Entry.DELETE
            TransactionDiagnostics.STAGE_EDIT in stages -> Entry.EDIT
            TransactionDiagnostics.STAGE_CAPTURE in stages -> Entry.CAPTURE
            else -> Entry.UNKNOWN
        }
    }

    private fun entryForCallSite(callSite: String): Entry? = when {
        callSite.startsWith("ScreenshotIntakeCoordinator") ||
            callSite.startsWith("ScreenshotImportPipeline") -> Entry.SCREENSHOT
        callSite.startsWith("PaymentNotificationListenerService") -> Entry.NOTIFICATION
        callSite.startsWith("SmsBroadcastReceiver") -> Entry.SMS
        callSite.startsWith("AccessibilityCaptureService") ||
            callSite.startsWith("PageRecognitionService") -> Entry.ACCESSIBILITY
        callSite.startsWith("Voice") -> Entry.VOICE
        callSite.startsWith("BillImport") -> Entry.IMPORT
        callSite.startsWith("TransactionSyncWorker") ||
            callSite.startsWith("BidirectionalSyncClient") -> Entry.SYNC
        else -> null
    }

    /**
     * Final result of the whole chain:
     * 1. the last step failed -> FAILURE;
     * 2. an earlier step failed but the chain finished -> PARTIAL (the run
     *    recovered; still shown as a problem, never as a clean success);
     * 3. the last step is still "started" -> RUNNING;
     * 4. any warning (duplicate skipped, possible duplicate retained) -> WARNING;
     * 5. any success -> SUCCESS;
     * otherwise UNKNOWN.
     */
    fun inferOutcome(steps: List<TransactionDiagnosticEventEntity>): Outcome {
        if (steps.isEmpty()) return Outcome.UNKNOWN
        val last = steps.last()
        if (last.result == TransactionDiagnostics.RESULT_FAILURE) return Outcome.FAILURE
        if (steps.any { it.result == TransactionDiagnostics.RESULT_FAILURE }) return Outcome.PARTIAL
        if (last.result == TransactionDiagnostics.RESULT_STARTED) return Outcome.RUNNING
        if (steps.any { it.result == TransactionDiagnostics.RESULT_WARNING }) return Outcome.WARNING
        if (steps.any { it.result == TransactionDiagnostics.RESULT_SUCCESS }) return Outcome.SUCCESS
        return Outcome.UNKNOWN
    }

    /** First 8 characters of the attempt id, for the card's "链路 ID" line. */
    fun shortAttemptId(attemptId: String): String =
        if (attemptId.length <= 8) attemptId else attemptId.take(8) + "…"

    /** "860 ms" under a second, "1.4 s", "2 min 10 s" — units stay language-neutral. */
    fun formatDuration(durationMs: Long): String {
        val ms = durationMs.coerceAtLeast(0L)
        return when {
            ms < 1_000L -> "$ms ms"
            ms < 60_000L -> String.format(java.util.Locale.US, "%.1f s", ms / 1_000.0)
            else -> "${ms / 60_000L} min ${(ms % 60_000L) / 1_000L} s"
        }
    }
}
