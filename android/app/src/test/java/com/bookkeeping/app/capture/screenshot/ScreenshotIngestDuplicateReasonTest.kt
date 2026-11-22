package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.data.diagnostics.DiagnosticStep
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.DuplicateReason
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ingest auto-book path must never close a candidate on a cross-channel hit.
 *
 * [DuplicateReason.CROSS_SOURCE_WEAK] used to fall into the same `else` branch as
 * a definite same-channel re-report and became IGNORED: the card disappeared with
 * no prompt. This pins the reason split:
 * - CROSS_SOURCE / CROSS_SOURCE_WEAK -> PENDING, diagnostic written;
 * - SOURCE_ORDER_ID -> IGNORED, because an exact platform order ID proves the re-report.
 */
class ScreenshotIngestDuplicateReasonTest {

    private val capturedAt = System.currentTimeMillis()

    private class FakeWriter(private val result: LedgerInsertResult) : ScreenshotLedgerWriter {
        var insertCalls = 0

        override suspend fun insert(entity: TransactionEntity, attemptId: String): LedgerInsertResult {
            insertCalls++
            return result
        }

        override suspend fun confirm(entity: TransactionEntity, attemptId: String): LedgerInsertResult =
            error("ingest must never call confirm")
    }

    private fun blockAt(text: String, left: Int, top: Int, right: Int) =
        TextBlock(text = text, left = left, top = top, right = right, bottom = top + 20)

    /**
     * A settled WeChat transfer bubble: this is AUTO_BOOK (the same shape pinned
     * by ScreenshotAuditMerchantTransferTest), so the fake writer is reached.
     */
    private fun completedTransferPage() = listOf(
        blockAt("在吗", 60, 120, 220),
        blockAt("晚上一起吃饭", 420, 120, 760),
        blockAt("¥200.00", 80, 200, 300),
        blockAt("已被接收", 90, 240, 200),
        blockAt("转账", 90, 280, 160),
    )

    private fun request() = ScreenshotIntakeRequest(
        source = IntakeSource.SHARE,
        uri = "content://media/external/images/media/7",
        mimeType = "image/jpeg",
        capturedAtMs = capturedAt,
        byteSize = 400_000L,
    )

    private fun withIngest(
        reason: DuplicateReason,
        block: suspend (CaptureCandidateStore, ScreenshotIntakeCoordinator.Outcome, List<DiagnosticStep>) -> Unit,
    ) = runBlocking {
        val dir: File = Files.createTempDirectory("ingest-dup-${reason.name}").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val notes = mutableListOf<DiagnosticStep>()
            val writer = FakeWriter(
                LedgerInsertResult.Duplicate(
                    existingTransactionId = 42L,
                    reason = reason,
                ),
            )
            val coordinator = ScreenshotIntakeCoordinator(
                store = store,
                diagnosticSink = { notes += it },
                ledgerWriter = writer,
            )
            val outcome = coordinator.ingestBlocks(
                request = request(),
                blocks = completedTransferPage(),
                bytes = null,
                fingerprint = (reason.ordinal + 1).toString(16).repeat(64).take(64),
                attemptId = "attempt-${reason.name}",
                allowAuto = true,
                previewOnly = false,
            )
            assertEquals("the page must reach the auto-book writer", 1, writer.insertCalls)
            block(store, outcome, notes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `weak cross channel match keeps the candidate pending and writes a diagnostic`() =
        withIngest(DuplicateReason.CROSS_SOURCE_WEAK) { store, outcome, notes ->
            val stored = store.latest().single()
            assertEquals(CaptureCandidate.STATUS_PENDING, stored.status)
            assertEquals(1, outcome.pending)
            assertEquals(0, outcome.ignored)
            assertEquals(0, outcome.duplicates)

            val note = notes.single { it.details.startsWith("Cross-channel match kept") }
            assertEquals(TransactionDiagnostics.STAGE_DEDUP, note.stage)
            assertEquals(TransactionDiagnostics.RESULT_WARNING, note.result)
            assertTrue("diagnostic must name the weak reason", note.details.contains("CROSS_SOURCE_WEAK"))
            assertTrue("diagnostic must say it stays for review", note.outputSummary.orEmpty().contains("保留待确认"))
        }

    @Test
    fun `strong cross channel match also keeps the candidate pending`() =
        withIngest(DuplicateReason.CROSS_SOURCE) { store, outcome, notes ->
            val stored = store.latest().single()
            assertEquals(CaptureCandidate.STATUS_PENDING, stored.status)
            assertEquals(1, outcome.pending)
            assertEquals(0, outcome.ignored)

            val note = notes.single { it.details.startsWith("Cross-channel match kept") }
            assertTrue(note.details.contains("CROSS_SOURCE"))
            assertFalse("strong and weak must remain distinguishable", note.details.contains("CROSS_SOURCE_WEAK"))
        }

    @Test
    fun `exact source order id duplicate is ignored`() =
        withIngest(DuplicateReason.SOURCE_ORDER_ID) { store, outcome, notes ->
            val stored = store.latest().single()
            assertEquals(CaptureCandidate.STATUS_IGNORED, stored.status)
            assertEquals(0, outcome.pending)
            assertEquals(1, outcome.ignored)
            assertEquals(1, outcome.duplicates)

            val note = notes.single { it.details.startsWith("Duplicate confirmed by ledger") }
            assertTrue(note.details.contains("SOURCE_ORDER_ID"))
        }
}
