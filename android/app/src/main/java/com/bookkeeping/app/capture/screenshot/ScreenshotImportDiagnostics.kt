package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics

/**
 * The one diagnostic row a failed screenshot import writes.
 *
 * Diagnostics exist so a failed attempt can be traced, not so the screenshot can
 * be re-read: the row carries an attempt id, a stage and the failure code only.
 * OCR text, image bytes, merchant names, amounts and page identity never leave
 * the local candidate store (and that store is still bounded by the seven-day
 * retention rule).
 */
object ScreenshotImportDiagnostics {

    data class FailureNote(
        val attemptId: String,
        val stage: String,
        val result: String,
        /** A [FailureReason] name: a stable code, never page content. */
        val details: String,
    )

    fun failureNote(attemptId: String, failure: FailureReason): FailureNote = FailureNote(
        attemptId = attemptId,
        stage = TransactionDiagnostics.STAGE_IMPORT,
        result = TransactionDiagnostics.RESULT_FAILURE,
        details = failure.name,
    )
}
