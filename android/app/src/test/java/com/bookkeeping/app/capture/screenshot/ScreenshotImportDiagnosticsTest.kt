package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed import may only record an attempt id, a stage and a failure code.
 * OCR text, merchant, amount and image bytes stay out of diagnostics, so the
 * note builder is pinned here instead of relying on the Android coordinator.
 */
class ScreenshotImportDiagnosticsTest {

    @Test
    fun `failure note records the attempt id and the reason code only`() {
        val note = ScreenshotImportDiagnostics.failureNote("attempt-42", FailureReason.OCR_EMPTY)

        assertEquals("attempt-42", note.attemptId)
        assertEquals(TransactionDiagnostics.STAGE_IMPORT, note.stage)
        assertEquals(TransactionDiagnostics.RESULT_FAILURE, note.result)
        assertEquals("OCR_EMPTY", note.details)
        assertEquals("import", note.stage)
        assertEquals("failure", note.result)
    }

    @Test
    fun `every failure reason maps to a stable non sensitive code`() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val code = Regex("[A-Z_]+")

        FailureReason.entries.forEach { reason ->
            val note = ScreenshotImportDiagnostics.failureNote("attempt", reason)

            assertEquals(reason.name, note.details)
            assertTrue("${reason.name} must be a code", note.details.matches(code))
            assertFalse("${reason.name} must not carry page text", cjk.containsMatchIn(note.details))
            assertTrue(note.details.length <= 32)
            assertEquals("attempt", note.attemptId)
        }
    }
}
