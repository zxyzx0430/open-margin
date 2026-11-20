package com.bookkeeping.app.capture.screenshot

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

    private val capturedAt = 1795064400000L // 2026-11-19 13:00 Asia/Shanghai

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
}
