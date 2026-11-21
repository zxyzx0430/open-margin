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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户显式确认后的结果映射。
 *
 * 现场：截图里商户比通知行更完整，用户已经点了「确认」，旧实现只回一句
 * 「这笔交易已在账本中」，候选保持 PENDING，截图里的商户/订单号没有回填。
 *
 * 这里钉死确认路径：
 * - 仓库合并成功（[LedgerInsertResult.Merged]）→ SAVED，卡片 APPROVED；
 * - 无法安全合并（CROSS_SOURCE / CROSS_SOURCE_WEAK）→ ALREADY_EXISTS，卡片仍 PENDING；
 * - 同一通道的精确重发 → ALREADY_EXISTS，卡片 IGNORED（原有行为）。
 *
 * 真正的 Room 写入需要设备；这里用 coordinator 自带的 writer seam 驱动三种结果。
 */
class ScreenshotConfirmCandidateTest {

    private val capturedAt = System.currentTimeMillis()

    private class FakeWriter(private val result: LedgerInsertResult) : ScreenshotLedgerWriter {
        var insertCalls = 0
        var confirmCalls = 0
        var lastEntity: TransactionEntity? = null

        override suspend fun insert(entity: TransactionEntity, attemptId: String): LedgerInsertResult {
            insertCalls++
            return LedgerInsertResult.Failed(IllegalStateException("confirm must not use insert"))
        }

        override suspend fun confirm(entity: TransactionEntity, attemptId: String): LedgerInsertResult {
            confirmCalls++
            lastEntity = entity
            return result
        }
    }

    private fun candidate(capturedAtMs: Long = capturedAt) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:payment",
        amountCents = 2000L,
        merchantCandidates = listOf("麦当劳"),
        orderId = "4200001234567890",
        capturedAtMs = capturedAtMs,
        screenshotPath = null,
        confidence = 0.9f,
        reason = "test",
        status = CaptureCandidate.STATUS_PENDING,
        kind = CaptureCandidate.KIND_PAYMENT,
        direction = CaptureCandidate.DIRECTION_EXPENSE,
        paymentPlatform = CaptureCandidate.PLATFORM_WECHAT,
        candidateId = "confirm-candidate",
    )

    private fun withStore(block: suspend (CaptureCandidateStore, File) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("confirm-merge").toFile()
        try {
            block(CaptureCandidateStore(dir), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun statusOf(store: CaptureCandidateStore, id: String): String? =
        store.latest().firstOrNull { it.candidateId == id }?.status

    @Test
    fun `confirmed merge saves the card as approved and never inserts`() = withStore { store, _ ->
        val stored = candidate()
        assertNotNull(store.save(stored, null))
        val writer = FakeWriter(LedgerInsertResult.Merged(42L))
        val coordinator = ScreenshotIntakeCoordinator(store, ledgerWriter = writer)

        val result = coordinator.confirmCandidate(stored)

        assertEquals(ScreenshotIntakeCoordinator.ConfirmationResult.SAVED, result)
        assertEquals(1, writer.confirmCalls)
        assertEquals(0, writer.insertCalls)
        assertEquals(CaptureCandidate.STATUS_APPROVED, statusOf(store, stored.candidateId))
        // 截图里更完整的商户与订单号就是交给仓库合并的输入。
        assertEquals("麦当劳", writer.lastEntity?.merchant)
        assertEquals("4200001234567890", writer.lastEntity?.sourceTransactionId)
    }

    @Test
    fun `unmergeable cross channel match keeps the card pending`() = withStore { store, _ ->
        val stored = candidate()
        assertNotNull(store.save(stored, null))
        val writer = FakeWriter(
            LedgerInsertResult.Duplicate(
                existingTransactionId = 42L,
                reason = DuplicateReason.CROSS_SOURCE,
            ),
        )
        val coordinator = ScreenshotIntakeCoordinator(store, ledgerWriter = writer)

        val result = coordinator.confirmCandidate(stored)

        assertEquals(ScreenshotIntakeCoordinator.ConfirmationResult.ALREADY_EXISTS, result)
        assertEquals(1, writer.confirmCalls)
        assertEquals("still waiting for the user", CaptureCandidate.STATUS_PENDING, statusOf(store, stored.candidateId))
    }

    @Test
    fun `weak cross channel match keeps the card pending too`() = withStore { store, _ ->
        val stored = candidate()
        assertNotNull(store.save(stored, null))
        val writer = FakeWriter(
            LedgerInsertResult.Duplicate(
                existingTransactionId = 42L,
                reason = DuplicateReason.CROSS_SOURCE_WEAK,
            ),
        )
        val coordinator = ScreenshotIntakeCoordinator(store, ledgerWriter = writer)

        val result = coordinator.confirmCandidate(stored)

        assertEquals(ScreenshotIntakeCoordinator.ConfirmationResult.ALREADY_EXISTS, result)
        assertEquals(CaptureCandidate.STATUS_PENDING, statusOf(store, stored.candidateId))
    }

    @Test
    fun `exact same channel duplicate still closes the card`() = withStore { store, _ ->
        val stored = candidate()
        assertNotNull(store.save(stored, null))
        val writer = FakeWriter(
            LedgerInsertResult.Duplicate(
                existingTransactionId = 42L,
                reason = DuplicateReason.SOURCE_ORDER_ID,
            ),
        )
        val coordinator = ScreenshotIntakeCoordinator(store, ledgerWriter = writer)

        val result = coordinator.confirmCandidate(stored)

        assertEquals(ScreenshotIntakeCoordinator.ConfirmationResult.ALREADY_EXISTS, result)
        assertEquals(CaptureCandidate.STATUS_IGNORED, statusOf(store, stored.candidateId))
    }

    @Test
    fun `preview candidates never reach the writer`() = withStore { store, _ ->
        val stored = candidate().copy(ledgerWriteAllowed = false)
        assertNotNull(store.save(stored, null))
        val writer = FakeWriter(LedgerInsertResult.Merged(42L))
        val coordinator = ScreenshotIntakeCoordinator(store, ledgerWriter = writer)

        val result = coordinator.confirmCandidate(stored)

        assertEquals(ScreenshotIntakeCoordinator.ConfirmationResult.NOT_WRITABLE, result)
        assertEquals(0, writer.confirmCalls)
        assertEquals(0, writer.insertCalls)
        assertTrue(statusOf(store, stored.candidateId) == CaptureCandidate.STATUS_PENDING)
    }
}
