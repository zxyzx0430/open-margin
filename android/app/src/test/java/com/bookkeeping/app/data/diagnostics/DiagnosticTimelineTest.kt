package com.bookkeeping.app.data.diagnostics

import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log screen's grouping rules, pinned as a pure function: one attempt id is
 * one capture chain, the duration is last minus first, the entry comes from the
 * caller and the final result has a fixed precedence.
 *
 * No Android or Room types are instantiated here — the entity is a plain data
 * class and [DiagnosticTimeline] never touches a database.
 */
class DiagnosticTimelineTest {

    private var sequence = 0

    private fun event(
        attemptId: String,
        at: Long,
        stage: String = TransactionDiagnostics.STAGE_CAPTURE,
        result: String = TransactionDiagnostics.RESULT_SUCCESS,
        details: String = "",
        transactionId: Long? = null,
        callSite: String? = null,
        inputSummary: String? = null,
        outputSummary: String? = null,
    ) = TransactionDiagnosticEventEntity(
        eventId = "event-${sequence++}",
        attemptId = attemptId,
        transactionId = transactionId,
        occurredAtMs = at,
        stage = stage,
        result = result,
        details = details,
        callSite = callSite,
        inputSummary = inputSummary,
        outputSummary = outputSummary,
    )

    @Test
    fun `rows of one attempt id become one chain and different ids stay apart`() {
        val attempts = DiagnosticTimeline.build(
            listOf(
                event("chain-a", 1_000, stage = TransactionDiagnostics.STAGE_CAPTURE),
                event("chain-a", 1_400, stage = TransactionDiagnostics.STAGE_SAVE),
                event("chain-b", 2_000, stage = TransactionDiagnostics.STAGE_CAPTURE),
            ),
        )

        assertEquals(2, attempts.size)
        // Newest chain first, so the screen shows the most recent capture on top.
        assertEquals("chain-b", attempts.first().attemptId)
        assertEquals(2, attempts.last().steps.size)
        assertEquals(
            listOf(TransactionDiagnostics.STAGE_CAPTURE, TransactionDiagnostics.STAGE_SAVE),
            attempts.last().steps.map { it.stage },
        )
    }

    @Test
    fun `duration is the last timestamp minus the first`() {
        val attempts = DiagnosticTimeline.build(
            listOf(
                event("chain", 10_000, stage = TransactionDiagnostics.STAGE_CAPTURE),
                event("chain", 10_400, stage = TransactionDiagnostics.STAGE_PARSE),
                event("chain", 11_860, stage = TransactionDiagnostics.STAGE_SAVE),
            ),
        )

        assertEquals(1_860L, attempts.single().durationMs)
        assertEquals(10_000L, attempts.single().startedAtMs)
        assertEquals(11_860L, attempts.single().endedAtMs)
    }

    @Test
    fun `sub second durations are shown in milliseconds`() {
        assertEquals("0 ms", DiagnosticTimeline.formatDuration(0L))
        assertEquals("860 ms", DiagnosticTimeline.formatDuration(860L))
        assertEquals("999 ms", DiagnosticTimeline.formatDuration(999L))
        assertEquals("1.4 s", DiagnosticTimeline.formatDuration(1_400L))
        assertEquals("59.9 s", DiagnosticTimeline.formatDuration(59_900L))
        assertEquals("2 min 10 s", DiagnosticTimeline.formatDuration(130_000L))
        // A clock that jumps backwards must not render a negative duration.
        assertEquals("0 ms", DiagnosticTimeline.formatDuration(-50L))
    }

    @Test
    fun `a failed step followed by success is partly failed not success`() {
        // Rule: failure anywhere but the last step -> PARTIAL. The chain
        // recovered, but the log must not claim a clean success.
        val attempts = DiagnosticTimeline.build(
            listOf(
                event("chain", 1_000, stage = TransactionDiagnostics.STAGE_DEDUP, result = TransactionDiagnostics.RESULT_STARTED),
                event("chain", 1_100, stage = TransactionDiagnostics.STAGE_DEDUP, result = TransactionDiagnostics.RESULT_FAILURE),
                event("chain", 1_200, stage = TransactionDiagnostics.STAGE_SAVE, result = TransactionDiagnostics.RESULT_SUCCESS),
            ),
        )

        assertEquals(DiagnosticTimeline.Outcome.PARTIAL, attempts.single().outcome)
    }

    @Test
    fun `a failing last step is a failure`() {
        val attempts = DiagnosticTimeline.build(
            listOf(
                event("chain", 1_000, stage = TransactionDiagnostics.STAGE_SAVE, result = TransactionDiagnostics.RESULT_STARTED),
                event("chain", 1_100, stage = TransactionDiagnostics.STAGE_SAVE, result = TransactionDiagnostics.RESULT_FAILURE),
            ),
        )

        assertEquals(DiagnosticTimeline.Outcome.FAILURE, attempts.single().outcome)
    }

    @Test
    fun `a chain whose last step only started is in progress`() {
        val attempts = DiagnosticTimeline.build(
            listOf(
                event("chain", 1_000, stage = TransactionDiagnostics.STAGE_CAPTURE, result = TransactionDiagnostics.RESULT_SUCCESS),
                event("chain", 1_100, stage = TransactionDiagnostics.STAGE_SYNC, result = TransactionDiagnostics.RESULT_STARTED),
            ),
        )

        assertEquals(DiagnosticTimeline.Outcome.RUNNING, attempts.single().outcome)
    }

    @Test
    fun `warnings without failures need review`() {
        val attempts = DiagnosticTimeline.build(
            listOf(
                event("chain", 1_000, stage = TransactionDiagnostics.STAGE_DEDUP, result = TransactionDiagnostics.RESULT_WARNING),
                event("chain", 1_100, stage = TransactionDiagnostics.STAGE_SAVE, result = TransactionDiagnostics.RESULT_SUCCESS),
            ),
        )

        assertEquals(DiagnosticTimeline.Outcome.WARNING, attempts.single().outcome)
    }

    @Test
    fun `empty input does not crash and has no chains`() {
        assertTrue(DiagnosticTimeline.build(emptyList()).isEmpty())
        assertEquals(DiagnosticTimeline.Outcome.UNKNOWN, DiagnosticTimeline.inferOutcome(emptyList()))
    }

    @Test
    fun `pre v11 rows with null summaries group and keep their old fields`() {
        // Schema v11 added callSite/inputSummary/outputSummary as nullable; every
        // row written before the upgrade has NULL in all three.
        val attempts = DiagnosticTimeline.build(
            listOf(
                event("legacy", 1_000, stage = TransactionDiagnostics.STAGE_IMPORT, result = TransactionDiagnostics.RESULT_FAILURE, details = "OCR_EMPTY"),
            ),
        )

        val attempt = attempts.single()
        assertFalse(attempt.hasReadableSummaries)
        assertEquals(DiagnosticTimeline.Entry.IMPORT, attempt.entry)
        assertEquals(DiagnosticTimeline.Outcome.FAILURE, attempt.outcome)
        assertEquals("OCR_EMPTY", attempt.steps.single().details)
        assertNull(attempt.steps.single().callSite)
        assertNull(attempt.steps.single().inputSummary)
        assertNull(attempt.steps.single().outputSummary)
        assertNull(attempt.transactionId)
    }

    @Test
    fun `the call site names the entry even when the last stage is shared`() {
        val screenshot = DiagnosticTimeline.build(
            listOf(
                event("s", 1_000, callSite = "ScreenshotIntakeCoordinator.ingest"),
                event("s", 1_100, stage = TransactionDiagnostics.STAGE_SAVE, callSite = "ScreenshotIntakeCoordinator.ingest"),
            ),
        ).single()
        val notification = DiagnosticTimeline.build(
            listOf(event("n", 2_000, callSite = "PaymentNotificationListenerService.onNotificationPosted")),
        ).single()

        assertEquals(DiagnosticTimeline.Entry.SCREENSHOT, screenshot.entry)
        assertEquals(DiagnosticTimeline.Entry.NOTIFICATION, notification.entry)
    }

    @Test
    fun `manual entry is recognised from the entry stage`() {
        val attempt = DiagnosticTimeline.build(
            listOf(
                event("m", 1_000, stage = TransactionDiagnostics.STAGE_ENTRY, callSite = "TransactionRepository.insert"),
                event("m", 1_050, stage = TransactionDiagnostics.STAGE_SAVE, callSite = "TransactionRepository.insert"),
            ),
        ).single()

        assertEquals(DiagnosticTimeline.Entry.MANUAL, attempt.entry)
    }

    @Test
    fun `rows sharing one millisecond keep the order they were written in`() {
        val attempts = DiagnosticTimeline.build(
            listOf(
                event("chain", 5_000, stage = TransactionDiagnostics.STAGE_CAPTURE),
                event("chain", 5_000, stage = TransactionDiagnostics.STAGE_PARSE),
                event("chain", 5_000, stage = TransactionDiagnostics.STAGE_SAVE),
            ),
        )

        assertEquals(
            listOf(
                TransactionDiagnostics.STAGE_CAPTURE,
                TransactionDiagnostics.STAGE_PARSE,
                TransactionDiagnostics.STAGE_SAVE,
            ),
            attempts.single().steps.map { it.stage },
        )
    }

    @Test
    fun `the chain keeps the last bill id any step was linked to`() {
        val attempt = DiagnosticTimeline.build(
            listOf(
                event("chain", 1_000, stage = TransactionDiagnostics.STAGE_DEDUP),
                event("chain", 1_100, stage = TransactionDiagnostics.STAGE_SAVE, transactionId = 412L),
                event("chain", 1_200, stage = TransactionDiagnostics.STAGE_SYNC, transactionId = 412L),
            ),
        ).single()

        assertEquals(412L, attempt.transactionId)
    }

    @Test
    fun `short attempt id keeps eight characters and never shows the raw uuid`() {
        assertEquals("a3f2c1d8…", DiagnosticTimeline.shortAttemptId("a3f2c1d8-9d1e-4d3a-b0b2-1f2e3d4c5b6a"))
        assertEquals("short", DiagnosticTimeline.shortAttemptId("short"))
    }
}
