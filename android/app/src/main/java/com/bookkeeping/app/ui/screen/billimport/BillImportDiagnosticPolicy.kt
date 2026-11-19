package com.bookkeeping.app.ui.screen.billimport

import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics

/** A fixed-size metadata-only diagnostic plan for import previews. */
internal data class BillImportDiagnosticEvent(
    val result: String,
    val details: String,
)

internal object BillImportDiagnosticPolicy {
    /** Preview diagnostics stay constant-size even for imports with 50,000 rows. */
    fun previewEvents(rowCount: Int): List<BillImportDiagnosticEvent> {
        require(rowCount >= 0)
        return listOf(
            if (rowCount == 0) {
                BillImportDiagnosticEvent(
                    result = TransactionDiagnostics.RESULT_WARNING,
                    details = "Import preview contains no rows",
                )
            } else {
                BillImportDiagnosticEvent(
                    result = TransactionDiagnostics.RESULT_SUCCESS,
                    details = "Import preview parsed",
                )
            },
        )
    }
}
