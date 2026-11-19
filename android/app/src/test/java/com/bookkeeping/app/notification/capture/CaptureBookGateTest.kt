package com.bookkeeping.app.notification.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hard gate: preview / allowAuto=false must never reach the ledger writer. */
class CaptureBookGateTest {

    @Test
    fun `auto capture may write the ledger`() {
        assertEquals(
            CaptureBookGate.Decision.WRITE_LEDGER,
            CaptureBookGate.decide(allowAuto = true, previewOnly = false),
        )
        assertTrue(CaptureBookGate.mayWriteLedger(allowAuto = true))
    }

    @Test
    fun `allowAuto false holds the candidate back`() {
        assertEquals(
            CaptureBookGate.Decision.HOLD_FOR_REVIEW,
            CaptureBookGate.decide(allowAuto = false, previewOnly = false),
        )
        assertFalse(CaptureBookGate.mayWriteLedger(allowAuto = false))
    }

    @Test
    fun `preview only never writes even when auto is allowed`() {
        assertEquals(
            CaptureBookGate.Decision.HOLD_FOR_REVIEW,
            CaptureBookGate.decide(allowAuto = true, previewOnly = true),
        )
        assertFalse(CaptureBookGate.mayWriteLedger(allowAuto = true, previewOnly = true))
    }
}
