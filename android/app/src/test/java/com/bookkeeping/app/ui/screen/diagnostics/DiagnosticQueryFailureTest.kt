package com.bookkeeping.app.ui.screen.diagnostics

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class DiagnosticQueryFailureTest {
    @Test fun cancellationIsRethrownWithoutPublishingAnOldQueryError() {
        val cancellation = CancellationException("superseded query")
        var stateWasUpdated = false

        try {
            handleDiagnosticQueryFailure(cancellation, isCurrentRequest = false) {
                stateWasUpdated = true
            }
            fail("Expected cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertFalse(stateWasUpdated)
    }

    @Test fun failureFromSupersededQueryDoesNotReplaceCurrentState() {
        var stateWasUpdated = false

        handleDiagnosticQueryFailure(
            failure = IllegalStateException("obsolete query"),
            isCurrentRequest = false,
        ) { stateWasUpdated = true }

        assertFalse(stateWasUpdated)
    }
}
