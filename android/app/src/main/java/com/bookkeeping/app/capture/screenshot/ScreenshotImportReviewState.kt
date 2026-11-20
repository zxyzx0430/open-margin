package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate

/**
 * Review state for one screenshot import.
 *
 * One screenshot can hold several transactions, so the import screen lists every
 * candidate at once and the user scrolls through them, instead of being asked one
 * transaction at a time. The state is immutable: every edit returns a new
 * snapshot, which keeps Compose recomposition trivial and lets the rules
 * (idempotency, what counts as done, which rows may reach the ledger) be tested
 * without Android or Compose.
 */
data class ScreenshotImportReviewState(
    val rows: List<Row> = emptyList(),
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

    val pendingCount: Int get() = rows.count { it.status == Status.PENDING }
    val savedCount: Int get() = rows.count { it.status == Status.SAVED }
    val skippedCount: Int get() = rows.count { it.status == Status.SKIPPED }

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

    /** PENDING -> SAVING. Anything else is a no-op, so a double tap cannot book twice. */
    fun begin(candidateId: String): ScreenshotImportReviewState =
        transition(candidateId, from = setOf(Status.PENDING), to = Status.SAVING)

    /** SAVING -> SAVED. Only a claimed row can be saved, and only once. */
    fun save(candidateId: String): ScreenshotImportReviewState =
        transition(candidateId, from = setOf(Status.SAVING), to = Status.SAVED)

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
