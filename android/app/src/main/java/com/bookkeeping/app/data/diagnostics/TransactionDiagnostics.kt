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
 * merchant names and hit/miss facts. The raw input travels separately in
 * [TransactionDiagnosticEventEntity.rawText], bounded by [DiagnosticRawText] —
 * never by the 160-char summary clip, and never a screenshot byte, audio clip
 * or whole import file.
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
        rawText: String? = null,
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
                    // Independent bound: the summary clip is 160 chars and would
                    // silently destroy the payload this column exists for.
                    rawText = DiagnosticRawText.store(rawText),
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
        rawText = step.rawText,
    )

    /** Null every stored raw input; the summaries and the 7-day window stay. */
    suspend fun clearRawText(): Int = dao.clearRawText()

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
 * summaries answer "what went in / what came out" without storing the payload,
 * and [rawText] carries the actual input (bounded by [DiagnosticRawText]).
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
    val rawText: String? = null,
)

data class TransactionDiagnosticInput(
    val attemptId: String = TransactionDiagnostics.newAttemptId(),
    /** Dotted entry point of this chain; null keeps the receiver's default. */
    val callSite: String? = null,
    /**
     * Verbatim input when the caller still holds it — the ASR transcript for a
     * voice entry, for example. Null lets the repository derive the raw text
     * from the merchant and note the user actually submitted.
     */
    val rawText: String? = null,
)

/**
 * Builds the short, structured strings that make a chain readable.
 *
 * Rules: money is rendered from integer cents (never a Double), the merchant is
 * the only free-text field, and every string is clipped to [MAX_LENGTH] before
 * it reaches the database.
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

/**
 * The only bound raw input is subject to.
 *
 * It cannot reuse [DiagnosticSummary.clip]: 160 characters is shorter than one
 * payment notification, so clipping raw text there would store a truncated body
 * and make bug reports unreproducible. A screenshot page is typically 200–800
 * characters, so 4000 leaves ample room while still preventing a caller bug from
 * writing an unbounded dump.
 *
 * This object also owns the reading-order join used by the screenshot chain so
 * the stored text keeps the OCR order instead of a re-assembled structure.
 */
object DiagnosticRawText {
    const val MAX_LENGTH = 4_000
    const val TRUNCATED_MARK = "…（已截断）"

    /** Trim, drop blank input (NULL, not an empty string) and bound the length. */
    fun store(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length <= MAX_LENGTH) return trimmed
        // Reserve room for the mark so the result is never longer than MAX_LENGTH.
        return trimmed.take(MAX_LENGTH - TRUNCATED_MARK.length) + TRUNCATED_MARK
    }

    /**
     * "标题\n正文" for a notification or SMS. A missing half is dropped; both
     * missing yields null so an empty row never claims to carry a payload.
     */
    fun titleAndBody(title: String?, body: String?): String? {
        val parts = listOfNotNull(
            title?.trim()?.takeIf { it.isNotEmpty() },
            body?.trim()?.takeIf { it.isNotEmpty() },
        )
        return store(parts.joinToString("\n"))
    }

    /**
     * A hand-typed bill has no single "original sentence": the two free-text
     * fields the user filled are the closest thing. Blank fields are dropped so
     * a bill with only a merchant stores just the merchant.
     */
    fun manualEntry(merchant: String?, note: String?): String? = store(
        listOfNotNull(merchant, note)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n"),
    )

    /**
     * One OCR block per line, ordered top-to-bottom then left-to-right. Blocks
     * without bounds sink to the end in their original order rather than being
     * given a fake coordinate at the top of the page.
     */
    fun ocrLines(blocks: List<RawLine>): String? {
        val ordered = blocks
            .filter { it.text.isNotBlank() }
            .sortedWith(
                compareBy<RawLine> { if (it.hasBounds) 0 else 1 }
                    .thenBy { it.top }
                    .thenBy { it.left },
            )
        return store(ordered.joinToString("\n") { it.text.trim() })
    }
}

/** One recognised line with its position; the data layer's copy of a TextBlock. */
data class RawLine(
    val text: String,
    val top: Int = 0,
    val left: Int = 0,
    val hasBounds: Boolean = true,
)

/** 7×24-hour rolling retention. Events exactly at the cutoff remain available. */
object TransactionDiagnosticRetention {
    const val RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1_000L
    fun cutoffMs(nowMs: Long): Long = nowMs - RETENTION_MS
    fun isExpired(occurredAtMs: Long, nowMs: Long): Boolean = occurredAtMs < cutoffMs(nowMs)
}
