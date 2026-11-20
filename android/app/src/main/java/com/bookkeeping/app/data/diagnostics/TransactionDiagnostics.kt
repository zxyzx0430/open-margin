package com.bookkeeping.app.data.diagnostics

import com.bookkeeping.app.data.local.dao.TransactionDiagnosticDao
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import kotlinx.coroutines.CancellationException
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only writer for per-bill diagnostics. It has no HTTP client, preferences
 * or Logcat dependency; a write failure never changes bookkeeping behavior.
 *
 * Every row may carry a dotted [callSite] plus structured [inputSummary] and
 * [outputSummary] strings. Those summaries are counts, amounts, directions,
 * merchant names and hit/miss facts — never raw OCR text, screenshot bytes,
 * audio, notification bodies or a whole import file. [DiagnosticSummary] clips
 * them so one bad caller cannot turn a row into a text dump.
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
        callSite: String? = null,
        inputSummary: String? = null,
        outputSummary: String? = null,
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
                    callSite = DiagnosticSummary.clip(callSite),
                    inputSummary = DiagnosticSummary.clip(inputSummary),
                    outputSummary = DiagnosticSummary.clip(outputSummary),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostics are best-effort and must never block or roll back a bill operation.
        }
    }

    /** Typed variant so a capture pipeline can hand over one whole step at once. */
    suspend fun record(step: DiagnosticStep) = record(
        attemptId = step.attemptId,
        stage = step.stage,
        result = step.result,
        details = step.details,
        transactionId = step.transactionId,
        callSite = step.callSite,
        inputSummary = step.inputSummary,
        outputSummary = step.outputSummary,
    )

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

/**
 * One pipeline step. [callSite] answers "where did this happen", the two
 * summaries answer "what went in / what came out" without storing the payload.
 */
data class DiagnosticStep(
    val attemptId: String,
    val stage: String,
    val result: String,
    val details: String = "",
    val transactionId: Long? = null,
    val callSite: String? = null,
    val inputSummary: String? = null,
    val outputSummary: String? = null,
)

data class TransactionDiagnosticInput(
    val attemptId: String = TransactionDiagnostics.newAttemptId(),
    /** Dotted entry point of this chain; null keeps the receiver's default. */
    val callSite: String? = null,
)

/**
 * Builds the short, structured strings that make a chain readable.
 *
 * Rules: money is rendered from integer cents (never a Double), the merchant is
 * the only free-text field, and every string is clipped to [MAX_LENGTH] before
 * it reaches the database. No helper here accepts OCR text, a notification body
 * or a file path.
 */
object DiagnosticSummary {
    const val MAX_LENGTH = 160

    fun clip(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= MAX_LENGTH) trimmed else trimmed.take(MAX_LENGTH - 1) + "…"
    }

    /** "-¥8.00" for an expense, "+¥5.00" for income, "¥5.00" when unknown. */
    fun amount(type: String?, amountCents: Long, currency: String? = null): String {
        val sign = when (type?.trim()?.uppercase(Locale.ROOT)) {
            "EXPENSE" -> "-"
            "INCOME" -> "+"
            else -> ""
        }
        val cents = if (amountCents == Long.MIN_VALUE) 0L else kotlin.math.abs(amountCents)
        val body = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
        val symbol = when (val code = currency?.trim()?.uppercase(Locale.ROOT).orEmpty()) {
            "", "CNY" -> "¥"
            else -> "$code "
        }
        return "$sign$symbol$body"
    }

    /** "-¥8.00 · 祐迎迎生鲜超市"; a missing merchant is left out, never invented. */
    fun amountWithMerchant(amount: String, merchant: String?): String {
        val name = merchant?.trim().orEmpty()
        return if (name.isEmpty()) amount else "$amount · $name"
    }

    /** Stage counters, e.g. "自动入账 1 · 待确认 0 · 跳过 1". */
    fun counts(vararg pairs: Pair<String, Int>): String =
        pairs.joinToString(" · ") { (label, value) -> "$label $value" }
}
