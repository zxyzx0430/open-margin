package com.bookkeeping.app

import com.bookkeeping.app.sync.PairingFlowPolicy
import com.bookkeeping.app.sync.PairingParseResult
import com.bookkeeping.app.sync.PairingPayload
import com.bookkeeping.app.sync.WindowsConnectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingFlowPolicyTest {
    private val url = "https://pc.tailnet.ts.net/finance"
    private val otherUrl = "https://other.tailnet.ts.net/finance"
    private val serverId = "ledger-identity-123456"
    private val otherServerId = "ledger-identity-654321"

    private fun history(
        url: String = "",
        serverId: String? = null,
        uploaded: Long = 0L,
        pulled: Long = 0L,
        successMs: Long = 0L,
        resultCode: String? = null,
    ) = PairingFlowPolicy.History(url, serverId, uploaded, pulled, successMs, resultCode)

    @Test
    fun firstPairingHasNoPinnedLedger() {
        assertEquals(
            PairingFlowPolicy.PreProbe.ALLOWED,
            PairingFlowPolicy.evaluatePreProbe(history(), url, serverId),
        )
        assertEquals(
            PairingFlowPolicy.PreProbe.ALLOWED,
            PairingFlowPolicy.evaluatePreProbe(history(), otherUrl, null),
        )
    }

    @Test
    fun anyHistoricalSuccessBlocksADifferentUrl() {
        listOf(
            history(url = url, uploaded = 1L),
            history(url = url, pulled = 1L),
            history(url = url, successMs = 1L),
            history(url = url, serverId = serverId),
            history(url = url, resultCode = "ok"),
        ).forEachIndexed { index, old ->
            assertEquals(
                "history case $index must block a different URL",
                PairingFlowPolicy.PreProbe.MIGRATION_REQUIRED,
                PairingFlowPolicy.evaluatePreProbe(old, otherUrl, serverId),
            )
        }
    }

    @Test
    fun sameUrlButDifferentServerIdRequiresMigration() {
        val old = history(url = url, serverId = serverId, successMs = 1L)
        assertEquals(
            PairingFlowPolicy.PreProbe.MIGRATION_REQUIRED,
            PairingFlowPolicy.evaluatePreProbe(old, url, otherServerId),
        )
        assertEquals(
            PairingFlowPolicy.PreProbe.ALLOWED,
            PairingFlowPolicy.evaluatePreProbe(old, "$url/", serverId),
        )
    }

    @Test
    fun scannedPairingWithUnknownStoredIdentityRequiresMigration() {
        val old = history(url = url, uploaded = 3L)
        assertEquals(
            PairingFlowPolicy.PreProbe.MIGRATION_REQUIRED,
            PairingFlowPolicy.evaluatePreProbe(old, url, serverId),
        )
        // Typed credentials have no QR identity to compare; the probe's stored
        // identity/cursor checks remain responsible for that path.
        assertEquals(
            PairingFlowPolicy.PreProbe.ALLOWED,
            PairingFlowPolicy.evaluatePreProbe(old, url, null),
        )
    }

    @Test
    fun clearedStoredUrlCannotLaunderHistory() {
        assertEquals(
            PairingFlowPolicy.PreProbe.MIGRATION_REQUIRED,
            PairingFlowPolicy.evaluatePreProbe(history(uploaded = 1L), url, serverId),
        )
        assertEquals(
            PairingFlowPolicy.PreProbe.MIGRATION_REQUIRED,
            PairingFlowPolicy.evaluatePreProbe(history(successMs = 1L), url, null),
        )
    }

    @Test
    fun probeMustMatchTheScannedServerId() {
        assertEquals(
            WindowsConnectionPolicy.Result.READY,
            PairingFlowPolicy.evaluateProbe(
                serverId, WindowsConnectionPolicy.Result.READY, serverId,
            ),
        )
        assertEquals(
            WindowsConnectionPolicy.Result.WRONG_LEDGER,
            PairingFlowPolicy.evaluateProbe(
                serverId, WindowsConnectionPolicy.Result.READY, otherServerId,
            ),
        )
        assertEquals(
            WindowsConnectionPolicy.Result.READY,
            PairingFlowPolicy.evaluateProbe(
                null, WindowsConnectionPolicy.Result.READY, otherServerId,
            ),
        )
        assertEquals(
            WindowsConnectionPolicy.Result.UNAUTHORIZED,
            PairingFlowPolicy.evaluateProbe(
                serverId, WindowsConnectionPolicy.Result.UNAUTHORIZED, "",
            ),
        )
        assertEquals(
            WindowsConnectionPolicy.Result.INVALID_RESPONSE,
            PairingFlowPolicy.evaluateProbe(
                serverId, WindowsConnectionPolicy.Result.INVALID_RESPONSE, "",
            ),
        )
    }

    @Test
    fun scannedAndManualInputsReachTheSameFlowDecision() {
        val raw =
            """{"v":1,"kind":"open-margin-pair","url":"$url","token":"device-token-1234567890","server_id":"$serverId"}"""
        val scanned = (PairingPayload.parse(raw) as PairingParseResult.Success).payload
        val manual = PairingPayload.fromInput(
            " ${scanned.url}/ ", " ${scanned.token} ", scanned.serverId,
        )!!
        assertEquals(scanned, manual)

        val old = history(url = url, serverId = serverId, successMs = 1L)
        assertEquals(
            PairingFlowPolicy.evaluatePreProbe(old, manual.url, null),
            PairingFlowPolicy.evaluatePreProbe(old, scanned.url, scanned.serverId),
        )
    }
}
