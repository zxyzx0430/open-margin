package com.bookkeeping.app

import com.bookkeeping.app.notification.CaptureConnectionState
import com.bookkeeping.app.notification.CaptureHealthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHealthPolicyTest {

    @Test fun startupGraceEndsAtExactlyFifteenSeconds() {
        val startedAt = 1_000L
        assertTrue(
            CaptureHealthPolicy.withinStartupGrace(
                startedAt,
                startedAt + CaptureHealthPolicy.STARTUP_GRACE_MS - 1,
            )
        )
        assertFalse(
            CaptureHealthPolicy.withinStartupGrace(
                startedAt,
                startedAt + CaptureHealthPolicy.STARTUP_GRACE_MS,
            )
        )
        assertFalse(CaptureHealthPolicy.withinStartupGrace(0L, 60_000L))
    }

    @Test fun clockRollbackDoesNotExtendStartupGrace() {
        assertFalse(CaptureHealthPolicy.withinStartupGrace(10_000L, 9_000L))
    }

    @Test fun permissionOffWinsEvenIfAnOldProcessFlagSaysConnected() {
        assertEquals(
            CaptureConnectionState.PERMISSION_OFF,
            CaptureHealthPolicy.resolve(
                authorized = false,
                connected = true,
                hasDisconnectedRecord = false,
                withinStartupGrace = false,
            ),
        )
    }

    @Test fun connectedWinsOverAnOldDisconnectRecord() {
        assertEquals(
            CaptureConnectionState.CONNECTED,
            CaptureHealthPolicy.resolve(
                authorized = true,
                connected = true,
                hasDisconnectedRecord = true,
                withinStartupGrace = true,
            ),
        )
    }

    @Test fun disconnectRecordShowsDisconnectedImmediately() {
        assertEquals(
            CaptureConnectionState.DISCONNECTED,
            CaptureHealthPolicy.resolve(
                authorized = true,
                connected = false,
                hasDisconnectedRecord = true,
                withinStartupGrace = true,
            ),
        )
    }

    @Test fun authorizedWithoutCallbackIsUnknownDuringStartupGrace() {
        assertEquals(
            CaptureConnectionState.UNKNOWN,
            CaptureHealthPolicy.resolve(
                authorized = true,
                connected = false,
                hasDisconnectedRecord = false,
                withinStartupGrace = true,
            ),
        )
    }

    @Test fun authorizedWithoutCallbackTurnsDisconnectedAfterGrace() {
        assertEquals(
            CaptureConnectionState.DISCONNECTED,
            CaptureHealthPolicy.resolve(
                authorized = true,
                connected = false,
                hasDisconnectedRecord = false,
                withinStartupGrace = false,
            ),
        )
    }
}
