package com.bookkeeping.app

import com.bookkeeping.app.sync.WindowsConnectionPolicy
import com.bookkeeping.app.sync.SyncServerIdentityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsConnectionPolicyTest {
    private val address = "https://my-pc.my-tailnet.ts.net/finance"

    @Test fun validPrivateHttpsAddress() {
        assertTrue(WindowsConnectionPolicy.validateAddress(address))
        assertTrue(WindowsConnectionPolicy.validateAddress("$address/"))
        assertEquals(WindowsConnectionPolicy.Result.READY,
            WindowsConnectionPolicy.preflight(address, "private-credential-123456"))
    }

    @Test fun rejectUntrustedEndpoints() {
        listOf(
            "http://my-pc.my-tailnet.ts.net/finance",
            "https://finance.example.com/finance",
            "https://my-pc.my-tailnet.ts.net/finance/extra",
            "https://my-pc.my-tailnet.ts.net/finance?token=exposed",
            "https://user:pass@my-pc.my-tailnet.ts.net/finance",
            "https://my-pc.my-tailnet.ts.net:8080/finance"
        ).forEach { assertFalse(WindowsConnectionPolicy.validateAddress(it)) }
    }

    @Test fun tokenRequiredAndErrorDiagnoses() {
        assertEquals(WindowsConnectionPolicy.Result.INVALID_TOKEN,
            WindowsConnectionPolicy.preflight(address, "bad"))
        assertEquals(WindowsConnectionPolicy.Result.UNAUTHORIZED,
            WindowsConnectionPolicy.response(401, null))
        assertEquals(WindowsConnectionPolicy.Result.ROUTE_MISSING,
            WindowsConnectionPolicy.response(404, null))
        assertEquals(WindowsConnectionPolicy.Result.UNAVAILABLE,
            WindowsConnectionPolicy.response(503, null))
    }

    @Test fun identityMustBeTrustedBeforeEnabling() {
        assertEquals(WindowsConnectionPolicy.Result.READY,
            WindowsConnectionPolicy.response(200, SyncServerIdentityPolicy.Decision.ACCEPT))
        assertEquals(WindowsConnectionPolicy.Result.WRONG_LEDGER,
            WindowsConnectionPolicy.response(200, SyncServerIdentityPolicy.Decision.DIFFERENT_SERVER))
        assertEquals(WindowsConnectionPolicy.Result.WRONG_LEDGER,
            WindowsConnectionPolicy.response(200, SyncServerIdentityPolicy.Decision.CURSOR_AHEAD))
        assertEquals(WindowsConnectionPolicy.Result.INVALID_RESPONSE,
            WindowsConnectionPolicy.response(200, SyncServerIdentityPolicy.Decision.MISSING_ID))
    }
}
