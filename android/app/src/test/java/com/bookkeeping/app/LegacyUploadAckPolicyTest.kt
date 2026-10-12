package com.bookkeeping.app

import com.bookkeeping.app.sync.LegacyUploadAckPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyUploadAckPolicyTest {
    @Test fun successfulUploadAdvancesCursor() {
        assertEquals(LegacyUploadAckPolicy.Decision.ADVANCE_CURSOR,
            LegacyUploadAckPolicy.decide(3,3,0))
    }

    @Test fun rejectedEventMustNeverAdvanceCursor() {
        assertEquals(LegacyUploadAckPolicy.Decision.REJECTED_BY_LEDGER_START,
            LegacyUploadAckPolicy.decide(3,3,1))
        assertEquals(LegacyUploadAckPolicy.Decision.REJECTED_BY_LEDGER_START,
            LegacyUploadAckPolicy.decide(1,1,1))
    }

    @Test fun partialOrMalformedAckMustRetry() {
        assertEquals(LegacyUploadAckPolicy.Decision.RETRY,
            LegacyUploadAckPolicy.decide(3,2,0))
        assertEquals(LegacyUploadAckPolicy.Decision.RETRY,
            LegacyUploadAckPolicy.decide(2,2,-1))
    }
}
