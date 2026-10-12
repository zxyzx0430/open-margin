package com.bookkeeping.app

import com.bookkeeping.app.sync.SyncServerIdentityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncServerIdentityPolicyTest {
    private val first="7f118eccfd364933a217a1a33f57d110"
    private val second="7f118eccfd364933a217a1a33f57d111"

    @Test fun newServerCanBeBoundBeforeAnyDownload() {
        assertEquals(SyncServerIdentityPolicy.Decision.ACCEPT,SyncServerIdentityPolicy.evaluate(null,0,first,0))
        assertEquals(SyncServerIdentityPolicy.Decision.ACCEPT,SyncServerIdentityPolicy.evaluate(first,12,first,40))
    }

    @Test fun sameDomainButDifferentDatabaseMustBlock() {
        assertEquals(SyncServerIdentityPolicy.Decision.DIFFERENT_SERVER,SyncServerIdentityPolicy.evaluate(first,12,second,40))
        assertEquals(SyncServerIdentityPolicy.Decision.CURSOR_AHEAD,SyncServerIdentityPolicy.evaluate(first,30,first,18))
    }

    @Test fun missingMetadataOrUnknownLegacyCursorCannotSilentlyAdvance() {
        assertEquals(SyncServerIdentityPolicy.Decision.MISSING_ID,SyncServerIdentityPolicy.evaluate(first,10,"",40))
        assertEquals(SyncServerIdentityPolicy.Decision.UNKNOWN_OLD_CURSOR,SyncServerIdentityPolicy.evaluate(null,10,first,40))
    }
}
