package com.bookkeeping.app.ui.screen.billimport

import org.junit.Assert.assertEquals
import org.junit.Test

class BillImportDiagnosticPolicyTest {
    @Test fun previewDiagnosticCountDoesNotScaleWithNumberOfRows() {
        listOf(0, 1, 50_000).forEach { rowCount ->
            assertEquals(1, BillImportDiagnosticPolicy.previewEvents(rowCount).size)
        }
    }

    @Test fun previewDiagnosticsContainOnlyFixedMetadataDescriptions() {
        val events = listOf(
            BillImportDiagnosticPolicy.previewEvents(0).single(),
            BillImportDiagnosticPolicy.previewEvents(50_000).single(),
        )

        assertEquals("Import preview contains no rows", events[0].details)
        assertEquals("Import preview parsed", events[1].details)
    }
}
