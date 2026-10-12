package com.bookkeeping.app

import com.bookkeeping.app.sync.SyncServerIdentityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncServerIdentityPolicyTest {
    private val first="7f118eccfd364933a217a1a33f57d110"
    private val second="7f118eccfd364933a217a1a33f57d111"
    private val p=SyncServerIdentityPolicy.Decision

    @Test fun newServerCanBeBoundBeforeAnyDownload() {
        assertEquals(p.ACCEPT,SyncServerIdentityPolicy.evaluate(null,0,first,0))
        assertEquals(p.ACCEPT,SyncServerIdentityPolicy.evaluate(first,12,first,40))
    }

    @Test fun sameDomainButDifferentDatabaseMustBlock() {
        assertEquals(p.DIFFERENT_SERVER,SyncServerIdentityPolicy.evaluate(first,12,second,40))
        assertEquals(p.CURSOR_AHEAD,SyncServerIdentityPolicy.evaluate(first,30,first,18))
    }

    @Test fun missingMetadataOrUnknownLegacyCursorCannotSilentlyAdvance() {
        assertEquals(p.MISSING_ID,SyncServerIdentityPolicy.evaluate(first,10,"",40))
        assertEquals(p.UNKNOWN_OLD_CURSOR,SyncServerIdentityPolicy.evaluate(null,10,first,40))
    }
}
