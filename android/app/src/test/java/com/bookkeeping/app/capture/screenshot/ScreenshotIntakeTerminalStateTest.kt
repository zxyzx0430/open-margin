package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.DuplicateReason
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0：重复 ingest 不能把一次成功的入账改写成「已忽略」，也不能写第二行账。
 *
 * 真机证据（files/page_capture/candidates.jsonl）：
 *   cid=2a6f61d6 770 PENDING→PENDING→PENDING→APPROVED→IGNORED→IGNORED
 * 8.00 已经在账本里，候选却被标成 IGNORED，出现在「已忽略的截图 (3)」里。
 * 根因是同一张截图被 MediaStore 多次回调 / 兜底扫描重复 ingest，第二次命中
 * 自己刚写入的行，走 Duplicate 分支又 mark 了一遍 IGNORED。
 */
class ScreenshotIntakeTerminalStateTest {

    private val capturedAt = System.currentTimeMillis()

    private class CountingWriter(private val result: LedgerInsertResult) : ScreenshotLedgerWriter {
        var inserts = 0

        override suspend fun insert(entity: TransactionEntity, attemptId: String): LedgerInsertResult {
            inserts++
            return result
        }

        override suspend fun confirm(entity: TransactionEntity, attemptId: String): LedgerInsertResult =
            error("ingest must never call confirm")
    }

    private fun blockAt(text: String, left: Int, top: Int) =
        TextBlock(text = text, left = left, top = top, right = left + 240, bottom = top + 20)

    /** 已完成的转账气泡：AUTO_BOOK，会真正走到 ledger writer。 */
    private fun completedTransferPage() = listOf(
        blockAt("在吗", 60, 120),
        blockAt("晚上一起吃饭", 420, 120),
        blockAt("¥200.00", 80, 200),
        blockAt("已被接收", 90, 240),
        blockAt("转账", 90, 280),
    )

    private fun request() = ScreenshotIntakeRequest(
        source = IntakeSource.MEDIASTORE,
        uri = "content://media/external/images/media/9",
        mimeType = "image/jpeg",
        capturedAtMs = capturedAt,
        byteSize = 400_000L,
    )

    private fun filler(index: Int) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:PAYMENT:100:filler-$index",
        amountCents = 100L + index,
        merchantCandidates = listOf("填充商户"),
        orderId = null,
        capturedAtMs = capturedAt + index,
        screenshotPath = null,
        confidence = 0.6f,
        reason = "filler",
        paymentPlatform = CaptureCandidate.PLATFORM_WECHAT,
        imageFingerprint = index.toString(16).padStart(64, '0'),
        candidateId = "filler-$index",
    )

    @Test
    fun `re-ingesting an approved candidate writes no second row and keeps it approved`() = runBlocking {
        val dir: File = Files.createTempDirectory("intake-approved").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val writer = CountingWriter(LedgerInsertResult.Inserted(7L))
            val coordinator = ScreenshotIntakeCoordinator(store, ledgerWriter = writer)
            val fingerprint = "a".repeat(64)

            val first = coordinator.ingestBlocks(
                request = request(),
                blocks = completedTransferPage(),
                bytes = null,
                fingerprint = fingerprint,
                attemptId = "attempt-1",
                allowAuto = true,
                previewOnly = false,
            )
            assertEquals(1, first.booked)
            val approved = store.latest().single()
            assertEquals(CaptureCandidate.STATUS_APPROVED, approved.status)

            // 让这张卡滑出「最近 80 个指纹」窗口——真实 inbox 每天都在发生。
            // 这样 plan 不会再靠 repeatImage 提前短路，必须由 candidateId 的
            // 终态检查兜住，才是真的防住重复 ingest。
            (1..81).forEach { index -> store.save(filler(index), null) }
            assertTrue(
                "the fixture must evict the card from the recent-fingerprint window",
                store.latest(80).none { it.candidateId == approved.candidateId },
            )

            val second = coordinator.ingestBlocks(
                request = request().copy(capturedAtMs = capturedAt + 47 * 60_000L),
                blocks = completedTransferPage(),
                bytes = null,
                fingerprint = fingerprint,
                attemptId = "attempt-2",
                allowAuto = true,
                previewOnly = false,
            )

            assertEquals("the ledger must not receive a second row", 1, writer.inserts)
            assertEquals(0, second.booked)
            assertTrue("a settled image is a repeat ingest", second.repeatImage)
            assertEquals(
                CaptureCandidate.STATUS_APPROVED,
                store.findByCandidateId(approved.candidateId)?.status,
            )
            assertFalse(
                "an approved card must never appear in the ignored list",
                store.ignored().any { it.candidateId == approved.candidateId },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a new candidate is still ignored on its first pass`() = runBlocking {
        val dir: File = Files.createTempDirectory("intake-first-ignore").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            // Two ¥20 rows with no OCR boxes: the page cannot be split, the single
            // draft is ambiguous, and the policy says IGNORE. That path must keep
            // working — the terminal guard may only block an existing APPROVED card.
            val page = listOf(
                TextBlock(text = "微信支付"),
                TextBlock(text = "收款方 店铺A"),
                TextBlock(text = "-¥20.00"),
                TextBlock(text = "支付成功"),
                TextBlock(text = "店铺B"),
                TextBlock(text = "-¥20.00"),
                TextBlock(text = "支付成功"),
            )

            val outcome = ScreenshotIntakeCoordinator(store).ingestBlocks(
                request = request(),
                blocks = page,
                bytes = null,
                fingerprint = "b".repeat(64),
                attemptId = "attempt-ignore",
                allowAuto = true,
                previewOnly = false,
            )

            assertEquals(1, outcome.ignored)
            assertEquals(CaptureCandidate.STATUS_IGNORED, store.latest().single().status)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a same page identity duplicate closes the card as a duplicate`() = runBlocking {
        val dir: File = Files.createTempDirectory("intake-identity").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val writer = CountingWriter(
                LedgerInsertResult.Duplicate(
                    existingTransactionId = 42L,
                    reason = DuplicateReason.SAME_PAGE_IDENTITY,
                ),
            )
            val coordinator = ScreenshotIntakeCoordinator(store, ledgerWriter = writer)

            val outcome = coordinator.ingestBlocks(
                request = request(),
                blocks = completedTransferPage(),
                bytes = null,
                fingerprint = "c".repeat(64),
                attemptId = "attempt-identity",
                allowAuto = true,
                previewOnly = false,
            )

            assertEquals(1, writer.inserts)
            assertEquals(1, outcome.duplicates)
            assertEquals(1, outcome.ignored)
            assertEquals(0, outcome.pending)
            assertEquals(CaptureCandidate.STATUS_IGNORED, store.latest().single().status)
        } finally {
            dir.deleteRecursively()
        }
    }
}
