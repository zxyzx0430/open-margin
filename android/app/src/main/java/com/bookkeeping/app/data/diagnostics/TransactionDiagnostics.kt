package com.bookkeeping.app.data.diagnostics

import com.bookkeeping.app.data.local.dao.TransactionDiagnosticDao
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only writer for per-bill diagnostics. It has no HTTP client, preferences
 * or Logcat dependency; a write failure never changes bookkeeping behavior.
 */
@Singleton
class TransactionDiagnostics @Inject constructor(
    private val dao: TransactionDiagnosticDao,
) {
    suspend fun record(
        attemptId: String = newAttemptId(),
        stage: String,
        result: String,
        details: String = "",
        transactionId: Long? = null,
        occurredAtMs: Long = System.currentTimeMillis(),
    ) {
        try {
            try {
                pruneExpired(occurredAtMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retention is best-effort; a prune failure must not skip this event.
            }
            dao.insert(
                TransactionDiagnosticEventEntity(
                    eventId = UUID.randomUUID().toString(),
                    attemptId = attemptId,
                    transactionId = transactionId,
                    occurredAtMs = occurredAtMs,
                    stage = stage,
                    result = result,
                    details = details,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostics are best-effort and must never block or roll back a bill operation.
        }
    }

    suspend fun pruneExpired(nowMs: Long = System.currentTimeMillis()): Int =
        dao.pruneBefore(TransactionDiagnosticRetention.cutoffMs(nowMs))

    companion object {
        const val STAGE_CAPTURE = "capture"
        const val STAGE_IMPORT = "import"
        const val STAGE_ENTRY = "entry"
        const val STAGE_PARSE = "parse"
        const val STAGE_DEDUP = "dedup"
        const val STAGE_SAVE = "save"
        const val STAGE_SYNC = "sync"
        const val STAGE_EDIT = "edit"
        const val STAGE_DELETE = "delete"

        const val RESULT_STARTED = "started"
        const val RESULT_SUCCESS = "success"
        const val RESULT_WARNING = "warning"
        const val RESULT_FAILURE = "failure"

        fun newAttemptId(): String = UUID.randomUUID().toString()
    }
}

data class TransactionDiagnosticInput(
    val attemptId: String = TransactionDiagnostics.newAttemptId(),
)

/** 7×24-hour rolling retention. Events exactly at the cutoff remain available. */
object TransactionDiagnosticRetention {
    const val RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1_000L
    fun cutoffMs(nowMs: Long): Long = nowMs - RETENTION_MS
    fun isExpired(occurredAtMs: Long, nowMs: Long): Boolean = occurredAtMs < cutoffMs(nowMs)
}
