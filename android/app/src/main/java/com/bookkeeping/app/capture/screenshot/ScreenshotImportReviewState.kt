package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate

/**
 * Review state for one screenshot import session (single or batch up to 10 images).
 *
 * Tracks three buckets cleanly:
 * - [rows]: candidates awaiting user confirmation (and their settled transitions);
 * - [bookedTransactionIds]: Room transaction IDs recorded in this session (both AUTO_BOOK
 *   and user-confirmed rows), rendered with the same card as the ledger home screen;
 * - [notices]: non-booked explanations (duplicates, non-transaction images, failures).
 */
data class ScreenshotImportReviewState(
    val rows: List<Row> = emptyList(),
    val bookedTransactionIds: List<Long> = emptyList(),
    val notices: List<String> = emptyList(),
) {

    enum class Status {
        /** Waiting for the user. */
        PENDING,

        /** A ledger write is in flight for this row. */
        SAVING,

        /** Written to the ledger. */
        SAVED,

        /** Deliberately dropped; nothing was written. */
        SKIPPED,
    }

    data class Row(
        val candidate: CaptureCandidate,
        val status: Status = Status.PENDING,
    )

    /** Candidates still waiting for the user, in import order. */
    val pending: List<CaptureCandidate>
        get() = rows.filter { it.status == Status.PENDING }.map { it.candidate }

    val pendingRows: List<Row>
        get() = rows.filter { it.status == Status.PENDING || it.status == Status.SAVING }

    val pendingCount: Int get() = rows.count { it.status == Status.PENDING }
    val savedCount: Int get() = rows.count { it.status == Status.SAVED }
    val skippedCount: Int get() = rows.count { it.status == Status.SKIPPED }

    val hasAnyContent: Boolean
        get() = rows.isNotEmpty() || bookedTransactionIds.isNotEmpty() || notices.isNotEmpty()

    /**
     * True once every row reached a final state. An import with no candidates is
     * not "done": the screen still has to show why nothing was recognised.
     */
    val isDone: Boolean
        get() = rows.isNotEmpty() &&
            rows.all { it.status == Status.SAVED || it.status == Status.SKIPPED }

    /**
     * Candidates whose confirmation was written to the ledger. Skipping a row
     * never adds to this list, which is what keeps 忽略 off the ledger.
     */
    val ledgerWrites: List<CaptureCandidate>
        get() = rows.filter { it.status == Status.SAVED }.map { it.candidate }

    fun statusOf(candidateId: String): Status? =
        rows.firstOrNull { it.candidate.candidateId == candidateId }?.status

    fun canConfirm(candidateId: String): Boolean = statusOf(candidateId) == Status.PENDING

    /** Appends newly recognised candidates, keeping rows already on screen. */
    fun withCandidates(candidates: List<CaptureCandidate>): ScreenshotImportReviewState {
        val known = rows.map { it.candidate.candidateId }.toMutableSet()
        val added = candidates.filter { known.add(it.candidateId) }.map { Row(it) }
        return if (added.isEmpty()) this else copy(rows = rows + added)
    }

    /** Appends newly booked transaction IDs while preserving order and uniqueness. */
    fun withBookedTransactions(ids: List<Long>): ScreenshotImportReviewState {
        val known = bookedTransactionIds.toMutableSet()
        val added = ids.filter { it > 0L && known.add(it) }
        return if (added.isEmpty()) this else copy(bookedTransactionIds = bookedTransactionIds + added)
    }

    /** Appends a non-booked notice (duplicate / unrecognized / error) for the collapsible section. */
    fun withNotice(notice: String): ScreenshotImportReviewState {
        val clean = notice.trim()
        return if (clean.isEmpty()) this else copy(notices = notices + clean)
    }

    /** PENDING -> SAVING. Anything else is a no-op, so a double tap cannot book twice. */
    fun begin(candidateId: String): ScreenshotImportReviewState =
        transition(candidateId, from = setOf(Status.PENDING), to = Status.SAVING)

    /** SAVING -> SAVED. Only a claimed row can be saved, and only once. */
    fun save(candidateId: String, transactionId: Long? = null): ScreenshotImportReviewState {
        val next = transition(candidateId, from = setOf(Status.SAVING), to = Status.SAVED)
        return if (next !== this && transactionId != null && transactionId > 0L) {
            next.withBookedTransactions(listOf(transactionId))
        } else {
            next
        }
    }

    /** PENDING/SAVING -> SKIPPED. Ignoring never writes the ledger. */
    fun skip(candidateId: String): ScreenshotImportReviewState =
        transition(candidateId, from = setOf(Status.PENDING, Status.SAVING), to = Status.SKIPPED)

    /** SAVING -> PENDING so a failed write can be retried; other rows untouched. */
    fun fail(candidateId: String): ScreenshotImportReviewState =
        transition(candidateId, from = setOf(Status.SAVING), to = Status.PENDING)

    private fun transition(
        candidateId: String,
        from: Set<Status>,
        to: Status,
    ): ScreenshotImportReviewState {
        val index = rows.indexOfFirst { it.candidate.candidateId == candidateId }
        if (index < 0 || rows[index].status !in from) return this
        return copy(rows = rows.toMutableList().also { it[index] = it[index].copy(status = to) })
    }
}
