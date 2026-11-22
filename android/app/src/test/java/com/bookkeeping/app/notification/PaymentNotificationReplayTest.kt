package com.bookkeeping.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure replay policy regressions using synthetic platform keys and order IDs. */
class PaymentNotificationReplayTest {

    @Test
    fun `same notification key with distinct exact order IDs is a new transaction`() {
        val recordKey = NoticeFingerprint.forSourceEvent("com.tencent.mm", "fixture-key-7")
        val firstOrder = NoticeFingerprint.forPlatformTransaction("WECHAT", "FIXTURE-ORDER-A")
        val secondOrder = NoticeFingerprint.forPlatformTransaction("WECHAT", "FIXTURE-ORDER-B")

        assertFalse(NoticeReplayPolicy.isSamePlatformTransaction(firstOrder, secondOrder, 101L))
        assertEquals(
            recordKey,
            NoticeFingerprint.forSourceEvent("com.tencent.mm", "fixture-key-7"),
        )
        assertEquals(
            NoticeReplayPolicy.RecordKeyDecision.DISTINCT_TRANSACTION,
            NoticeReplayPolicy.classifyRecordKey(
                previousRecordExists = true,
                hasTrustedTransactionId = true,
                previousLinkMatchesTransactionId = false,
            ),
        )
    }

    @Test
    fun `same exact platform transaction stays idempotent across body refresh`() {
        val originalIdentity = NoticeFingerprint.forPlatformTransaction("WECHAT", "FIXTURE-ORDER-42")
        // A refreshed title, body, event time, or Android record key does not enter this identity.
        val refreshedIdentity = NoticeFingerprint.forPlatformTransaction("WECHAT", "FIXTURE-ORDER-42")

        assertTrue(
            NoticeReplayPolicy.isSamePlatformTransaction(
                previousFingerprint = originalIdentity,
                incomingFingerprint = refreshedIdentity,
                linkedTransactionId = 202L,
            ),
        )
        assertEquals(
            NoticeReplayPolicy.RecordKeyDecision.EXACT_TRANSACTION,
            NoticeReplayPolicy.classifyRecordKey(
                previousRecordExists = true,
                hasTrustedTransactionId = true,
                previousLinkMatchesTransactionId = true,
            ),
        )
    }

    @Test
    fun `reused key without a trustworthy transaction ID is explicitly ambiguous`() {
        assertFalse(
            NoticeReplayPolicy.isSamePlatformTransaction(
                previousFingerprint = NoticeFingerprint.forSourceEvent("com.tencent.mm", "fixture-key-8"),
                incomingFingerprint = null,
                linkedTransactionId = 303L,
            ),
        )
        assertEquals(
            NoticeReplayPolicy.RecordKeyDecision.AMBIGUOUS_REUSE,
            NoticeReplayPolicy.classifyRecordKey(
                previousRecordExists = true,
                hasTrustedTransactionId = false,
                previousLinkMatchesTransactionId = false,
            ),
        )
    }

    @Test
    fun `different notification keys remain independent without order IDs`() {
        val firstKey = NoticeFingerprint.forSourceEvent("com.tencent.mm", "fixture-key-a")
        val secondKey = NoticeFingerprint.forSourceEvent("com.tencent.mm", "fixture-key-b")
        assertTrue(firstKey != secondKey)
        assertEquals(
            NoticeReplayPolicy.RecordKeyDecision.NEW_RECORD,
            NoticeReplayPolicy.classifyRecordKey(
                previousRecordExists = false,
                hasTrustedTransactionId = false,
                previousLinkMatchesTransactionId = false,
            ),
        )
    }
}
