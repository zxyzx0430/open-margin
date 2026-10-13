package com.bookkeeping.app

import com.bookkeeping.app.sync.SyncEndpointPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEndpointPolicyTest {
    private val windows = "https://pc.example.ts.net/finance"
    private val vps = "https://my-vps.example/finance"

    @Test fun firstTimeSetupAcceptsWindowsOrVps() {
        assertTrue(SyncEndpointPolicy.canChangeServer("", windows, 0, 0, 0))
        assertTrue(SyncEndpointPolicy.canChangeServer("", vps, 0, 0, 0))
    }

    @Test fun existingServerPermitsTokenRotationAndTrailingSlash() {
        assertTrue(SyncEndpointPolicy.canChangeServer(windows, "$windows/", 8, 61, 100))
    }

    @Test fun switchingAfterAnyUploadOrDownloadIsBlocked() {
        assertFalse(SyncEndpointPolicy.canChangeServer(windows, vps, 1, 0, 0))
        assertFalse(SyncEndpointPolicy.canChangeServer(windows, vps, 0, 100, 0))
        assertFalse(SyncEndpointPolicy.canChangeServer(windows, vps, 0, 0, 100))
    }

    @Test fun changingBeforeAnySyncIsSafe() {
        assertTrue(SyncEndpointPolicy.canChangeServer(windows, vps, 0, 0, 0))
    }
}
