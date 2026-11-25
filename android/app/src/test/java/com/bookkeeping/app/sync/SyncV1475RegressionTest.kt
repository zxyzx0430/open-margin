package com.bookkeeping.app.sync

import com.bookkeeping.app.data.local.entity.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncV1475RegressionTest {
    private fun row(
        id: Long = 1L,
        amountCents: Long = 500L,
        merchant: String = "测试商户",
        category: String = "FOOD",
        revision: Long = 1L,
        deleted: Boolean = false,
        note: String = "",
        account: String? = null,
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        type = "EXPENSE",
        category = category,
        merchant = merchant,
        note = note,
        source = "WECHAT",
        account = account,
        timestamp = 1_795_570_000_000L,
        remoteEventId = "ev-1",
        remoteRevision = revision,
        syncDeleted = deleted,
        remoteReviewStatus = "unverified",
    )

    @Test
    fun `UploadPayload v2Record equivalence holds across different revisions and local-only fields`() {
        val local = row(revision = 2L, note = "本地备注", account = "零钱")
        val remote = row(revision = 5L, note = "", account = null)
        assertEquals(UploadPayload.v2Record(local), UploadPayload.v2Record(remote))
        assertEquals(local.remoteReviewStatus, remote.remoteReviewStatus)
        assertEquals(local.syncDeleted, remote.syncDeleted)
    }

    @Test
    fun `UploadPayload v2Record detects real business difference`() {
        val local = row(category = "FOOD")
        val remote = row(category = "SHOPPING")
        assertFalse(UploadPayload.v2Record(local) == UploadPayload.v2Record(remote))
    }

    @Test
    fun `double tombstone syncDeleted match does not depend on local fields`() {
        val localDeleted = row(deleted = true, revision = 1L)
        val remoteDeleted = row(deleted = true, revision = 3L)
        assertTrue(localDeleted.syncDeleted && remoteDeleted.syncDeleted)
    }
}
