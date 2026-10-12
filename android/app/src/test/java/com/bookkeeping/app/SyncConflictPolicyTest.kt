package com.bookkeeping.app

import com.bookkeeping.app.sync.SyncConflictPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncConflictPolicyTest {
    private val now=1791791400000L

    @Test fun clearOrderingUsesLatestEditTime() {
        assertEquals(SyncConflictPolicy.Decision.KEEP_PHONE,
            SyncConflictPolicy.decide(now-10000,now-30000,now))
        assertEquals(SyncConflictPolicy.Decision.KEEP_SERVER,
            SyncConflictPolicy.decide(now-30000,now-10000,now))
    }

    @Test fun ambiguousOrUnknownTimesNeverSilentlyOverride() {
        assertEquals(SyncConflictPolicy.Decision.HOLD,
            SyncConflictPolicy.decide(now-1000,now-2000,now))
        assertEquals(SyncConflictPolicy.Decision.HOLD,
            SyncConflictPolicy.decide(now-10000,0,now))
        assertEquals(SyncConflictPolicy.Decision.HOLD,
            SyncConflictPolicy.decide(now+600000,now-30000,now))
        // A wrong server clock must not silently replace valid phone edits.
        assertEquals(SyncConflictPolicy.Decision.HOLD,
            SyncConflictPolicy.decide(now-30000,now+600000,now))
    }

    @Test fun newerOfflineEditDoesNotSilentlyResurrectServerDeletion() {
        assertEquals(SyncConflictPolicy.Decision.HOLD,
            SyncConflictPolicy.decide(now-1000,now-20000,now,
                serverDeleted=true,phoneDeleted=false))
        assertEquals(SyncConflictPolicy.Decision.KEEP_PHONE,
            SyncConflictPolicy.decide(now-1000,now-20000,now,
                choice="keep_phone",serverDeleted=true,phoneDeleted=false))
        assertEquals(SyncConflictPolicy.Decision.KEEP_SERVER,
            SyncConflictPolicy.decide(now-1000,now-20000,now,
                choice="keep_server",serverDeleted=true,phoneDeleted=false))
    }

    @Test fun concurrentPhoneDeletionVersusServerEditNeedsPerRecordApproval() {
        assertEquals(SyncConflictPolicy.Decision.HOLD,
            SyncConflictPolicy.decide(now-1000,now-20000,now,
                serverDeleted=false,phoneDeleted=true))
        assertEquals(SyncConflictPolicy.Decision.KEEP_PHONE,
            SyncConflictPolicy.decide(now-1000,now-20000,now,
                choice="keep_phone",serverDeleted=false,phoneDeleted=true))
    }

    @Test fun ExplicitUserChoiceOverridesClockAmbiguity() {
        assertEquals(SyncConflictPolicy.Decision.KEEP_PHONE,
            SyncConflictPolicy.decide(now,now,now,"keep_phone"))
        assertEquals(SyncConflictPolicy.Decision.KEEP_SERVER,
            SyncConflictPolicy.decide(now,now,now,"keep_server"))
    }
}
