package com.bookkeeping.app.capture.screenshot

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
 * The intake coordinator's pre-OCR half: every rejection has to leave one local
 * diagnostic row, and a diagnostics failure may never change the outcome.
 *
 * The store and the sink are the only dependencies here, so this runs on the
 * JVM; URI access, bitmap decoding and OCR stay unverified without a device.
 */
class ScreenshotIntakeCoordinatorTest {

    // The candidate store prunes captures against the current clock.
    private val capturedAt = System.currentTimeMillis()

    private fun request(mime: String? = "image/jpeg") = ScreenshotIntakeRequest(
        source = IntakeSource.SHARE,
        uri = "content://media/external/images/media/7",
        mimeType = mime,
        capturedAtMs = capturedAt,
        byteSize = 400_000L,
    )

    private fun coordinator(
        dir: File,
        sink: suspend (ScreenshotImportDiagnostics.FailureNote) -> Unit,
    ) = ScreenshotIntakeCoordinator(CaptureCandidateStore(dir), diagnosticSink = sink)

    @Test
    fun `a rejected request still writes its failure diagnostic`() = runBlocking {
        val dir = Files.createTempDirectory("intake-rejected").toFile()
        try {
            val notes = mutableListOf<ScreenshotImportDiagnostics.FailureNote>()
            val outcome = coordinator(dir) { notes += it }.ingestRequest(
                request = request(mime = "application/pdf"),
                allowAuto = true,
                previewOnly = false,
                openStream = { error("a rejected request must never be read") },
            )

            assertEquals(FailureReason.UNSUPPORTED_MIME, outcome.failure)
            // Only the attempt id and the failure code: no page text or money.
            assertEquals(
                listOf(ScreenshotImportDiagnostics.failureNote(notes.single().attemptId, FailureReason.UNSUPPORTED_MIME)),
                notes,
            )
            assertTrue(notes.single().attemptId.isNotBlank())
            assertEquals("UNSUPPORTED_MIME", notes.single().details)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable stream is recorded before any parsing happens`() = runBlocking {
        val dir = Files.createTempDirectory("intake-unreadable").toFile()
        try {
            val notes = mutableListOf<ScreenshotImportDiagnostics.FailureNote>()
            val outcome = coordinator(dir) { notes += it }.ingestRequest(
                request = request(),
                allowAuto = true,
                previewOnly = false,
                openStream = { null },
            )

            assertEquals(FailureReason.UNREADABLE, outcome.failure)
            assertEquals("UNREADABLE", notes.single().details)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a broken diagnostics sink cannot break the import`() = runBlocking {
        val dir = Files.createTempDirectory("intake-sink-down").toFile()
        try {
            val outcome = coordinator(dir) { throw IllegalStateException("diagnostics down") }
                .ingestRequest(
                    request = request(mime = "application/pdf"),
                    allowAuto = false,
                    previewOnly = false,
                    openStream = { null },
                )

            assertEquals(FailureReason.UNSUPPORTED_MIME, outcome.failure)
            assertFalse(outcome.autoBookAllowed)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun block(text: String, top: Int) = TextBlock(text = text, top = top, right = 300, bottom = top + 20)

    private fun transferPage(status: String) = listOf(
        block("微信支付", 10),
        block("转账给 张三", 40),
        block("-¥200.00", 70),
        block(status, 100),
    )

    private fun waitingTransfer(capturedAtMs: Long) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:TRANSFER:20000:张三",
        amountCents = 20_000L,
        merchantCandidates = listOf("张三"),
        orderId = null,
        capturedAtMs = capturedAtMs,
        screenshotPath = null,
        confidence = 0.6f,
        reason = "waiting",
        status = CaptureCandidate.STATUS_WAITING,
        kind = CaptureCandidate.KIND_TRANSFER,
        eventTimeMs = capturedAtMs,
        direction = CaptureCandidate.DIRECTION_EXPENSE,
        paymentPlatform = CaptureCandidate.PLATFORM_WECHAT,
        completionStatus = CaptureCandidate.COMPLETION_PENDING,
        candidateId = "waiting-transfer",
    )

    private suspend fun ingestPage(
        coordinator: ScreenshotIntakeCoordinator,
        capturedAtMs: Long,
        blocks: List<TextBlock>,
        allowAuto: Boolean = true,
        previewOnly: Boolean = false,
    ) = coordinator.ingestBlocks(
        request = request().copy(capturedAtMs = capturedAtMs),
        blocks = blocks,
        bytes = null,
        fingerprint = capturedAtMs.toString(16).padStart(64, '0'),
        attemptId = "attempt-$capturedAtMs",
        allowAuto = allowAuto,
        previewOnly = previewOnly,
    )

    @Test
    fun `a completed card settles the earlier waiting placeholder and counts it`() = runBlocking {
        val dir = Files.createTempDirectory("intake-settle").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val waiting = waitingTransfer(capturedAtMs = capturedAt - 60_000)
            store.save(waiting, null)
            val coordinator = ScreenshotIntakeCoordinator(store)

            val outcome = ingestPage(coordinator, capturedAt, transferPage("交易成功"))

            assertEquals(1, outcome.superseded)
            assertEquals(1, outcome.pending)
            assertEquals(
                CaptureCandidate.STATUS_LINKED,
                store.latest().single { it.candidateId == waiting.candidateId }.status,
            )
            // One actionable item, not two: the placeholder is closed.
            assertEquals(1, store.pending().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `preview only and allowAuto false change nothing at all`() = runBlocking {
        val dir = Files.createTempDirectory("intake-preview").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val waiting = waitingTransfer(capturedAtMs = capturedAt - 60_000)
            store.save(waiting, null)
            val coordinator = ScreenshotIntakeCoordinator(store)

            val preview = ingestPage(coordinator, capturedAt, transferPage("交易成功"), previewOnly = true)
            val noAuto = ingestPage(
                coordinator,
                capturedAt + 1_000,
                transferPage("交易成功"),
                allowAuto = false,
            )

            assertEquals(0, preview.superseded)
            assertEquals(0, noAuto.superseded)
            assertEquals(
                CaptureCandidate.STATUS_WAITING,
                store.latest().single { it.candidateId == waiting.candidateId }.status,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a card the app refuses to act on never settles a placeholder`() = runBlocking {
        val dir = Files.createTempDirectory("intake-ignored").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            // An earlier ¥20 purchase at 店铺A is waiting for evidence.
            val waiting = waitingTransfer(capturedAtMs = capturedAt - 60_000).copy(
                merchantCandidates = listOf("店铺A"),
                amountCents = 2_000L,
                kind = CaptureCandidate.KIND_PAYMENT,
                pageIdentity = "screenshot:wechat:PAYMENT:2000:店铺A",
            )
            store.save(waiting, null)
            // Two ¥20 cards with no OCR boxes: the page cannot be split, so its
            // single ambiguous draft is IGNORE. An ignored card may not close the
            // placeholder and may not book anything either.
            val page = listOf(
                TextBlock(text = "微信支付"),
                TextBlock(text = "收款方 店铺A"),
                TextBlock(text = "-¥20.00"),
                TextBlock(text = "支付成功"),
                TextBlock(text = "店铺B"),
                TextBlock(text = "-¥20.00"),
                TextBlock(text = "支付成功"),
            )

            val outcome = ingestPage(ScreenshotIntakeCoordinator(store), capturedAt, page)

            assertEquals(0, outcome.superseded)
            assertEquals(0, outcome.booked)
            assertEquals(
                CaptureCandidate.STATUS_WAITING,
                store.latest().single { it.candidateId == waiting.candidateId }.status,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a sibling card from the same import never settles the card this import created`() = runBlocking {
        val dir = Files.createTempDirectory("intake-sibling").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val page = listOf(
                block("微信支付", 10),
                block("转账给 张三", 40),
                block("-¥200.00", 70),
                block("等待对方确认", 100),
                block("转账给 张三", 140),
                block("-¥200.00", 170),
                block("交易成功", 200),
            )

            val outcome = ingestPage(ScreenshotIntakeCoordinator(store), capturedAt, page)

            // Nothing was superseded: the only match was this import's own card.
            assertEquals(0, outcome.superseded)
            assertEquals(1, outcome.waiting)
            assertEquals(
                listOf(CaptureCandidate.STATUS_PENDING, CaptureCandidate.STATUS_WAITING),
                store.latest().map { it.status }.sorted(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
