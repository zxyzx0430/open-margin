package com.bookkeeping.app

import com.bookkeeping.app.sync.SyncConflictApproval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConflictApprovalTest {
    @Test fun mustMatchBothSnapshotsToApplyUserChoice() {
        assertTrue(SyncConflictApproval.valid("keep_phone",20,20,1000,1000,"mutation-A","mutation-A"))
        assertTrue(SyncConflictApproval.valid("keep_server",20,20,1000,1000,"mutation-A","mutation-A"))
        assertFalse(SyncConflictApproval.valid("keep_phone",19,20,1000,1000,"mutation-A","mutation-A"))
        assertFalse(SyncConflictApproval.valid("keep_server",20,20,1000,1001,"mutation-A","mutation-A"))
        assertFalse(SyncConflictApproval.valid(null,20,20,1000,1000,"mutation-A","mutation-A"))
        assertFalse(SyncConflictApproval.valid("auto",20,20,1000,1000,"mutation-A","mutation-A"))
        assertFalse(SyncConflictApproval.valid("keep_phone",20,20,1000,1000,"mutation-A","mutation-B"))
        assertFalse(SyncConflictApproval.valid("keep_phone",20,20,1000,1000,null,null))
    }
}
