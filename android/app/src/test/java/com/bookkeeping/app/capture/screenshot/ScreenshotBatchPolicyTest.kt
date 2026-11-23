package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.CaptureMergePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotBatchPolicyTest {

    @Test
    fun capSelection_keepsUpToTenAndMarksOverflow() {
        val exact = ScreenshotBatchPolicy.capSelection((1..10).toList())
        assertEquals(10, exact.accepted.size)
        assertEquals(0, exact.droppedCount)
        assertFalse(exact.truncated)

        val overflow = ScreenshotBatchPolicy.capSelection((1..13).toList())
        assertEquals((1..10).toList(), overflow.accepted)
        assertEquals(3, overflow.droppedCount)
        assertTrue(overflow.truncated)
    }

    @Test
    fun reviewState_tracksBookedTransactionsAndNotices() {
        val state = ScreenshotImportReviewState()
            .withBookedTransactions(listOf(101L, 102L, 101L, 0L))
            .withNotice("第 3 张：这张截图已经处理过")

        assertEquals(listOf(101L, 102L), state.bookedTransactionIds)
        assertEquals(listOf("第 3 张：这张截图已经处理过"), state.notices)
        assertTrue(state.hasAnyContent)
    }

    @Test
    fun captureMergePolicy_preservesOrFillsEvidenceImageName() {
        val existing = TransactionEntity(
            id = 1L,
            amountCents = 1000L,
            type = "EXPENSE",
            category = "FOOD",
            merchant = "微信支付",
            source = "WECHAT",
            timestamp = 1_700_000_000_000L,
            sourceTransactionId = "order-123",
            evidenceImageName = null,
        )
        val incoming = existing.copy(
            id = 0L,
            merchant = "新奥尔良烤鸡腿",
            captureKind = "PAYMENT",
            evidenceImageName = "ev_${"a".repeat(64)}.webp",
        )

        val merged = CaptureMergePolicy.planConfirmedMerge(existing, incoming)
        assertEquals(incoming.evidenceImageName, merged?.evidenceImageName)
    }
}
