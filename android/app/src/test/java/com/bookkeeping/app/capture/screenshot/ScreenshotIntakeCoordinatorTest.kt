package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.data.diagnostics.DiagnosticStep
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.ImageFingerprintLookup
import com.bookkeeping.app.ui.screen.capture.CaptureReviewMapping
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intake coordinator's pre-OCR half: every rejection has to leave one local
 * diagnostic row, and a diagnostics failure may never change the outcome.
 *
 * The store and the sink are the only dependencies here, so this runs on the
 * JVM; URI access, bitmap decoding and OCR stay unverified without a device.
 */
class ScreenshotIntakeCoordinatorTest {

    // The candidate store prunes captures against the current clock.
    private val capturedAt = System.currentTimeMillis()

    private fun request(mime: String? = "image/jpeg") = ScreenshotIntakeRequest(
        source = IntakeSource.SHARE,
        uri = "content://media/external/images/media/7",
        mimeType = mime,
        capturedAtMs = capturedAt,
        byteSize = 400_000L,
    )

    private fun coordinator(
        dir: File,
        sink: suspend (DiagnosticStep) -> Unit,
    ) = ScreenshotIntakeCoordinator(CaptureCandidateStore(dir), diagnosticSink = sink)

    @Test
    fun `a rejected request still writes its failure diagnostic`() = runBlocking {
        val dir = Files.createTempDirectory("intake-rejected").toFile()
        try {
            val notes = mutableListOf<DiagnosticStep>()
            val outcome = coordinator(dir) { notes += it }.ingestRequest(
                request = request(mime = "application/pdf"),
                allowAuto = true,
                previewOnly = false,
                openStream = { error("a rejected request must never be read") },
            )

            assertEquals(FailureReason.UNSUPPORTED_MIME, outcome.failure)
            // Only the attempt id, the failure code and a structured input summary:
            // no page text or money.
            val note = notes.single()
            assertEquals(TransactionDiagnostics.STAGE_IMPORT, note.stage)
            assertEquals(TransactionDiagnostics.RESULT_FAILURE, note.result)
            assertEquals("UNSUPPORTED_MIME", note.details)
            assertEquals("ScreenshotIntakeCoordinator.ingest", note.callSite)
            assertEquals("截图 1 张 · 390 KB · application/pdf", note.inputSummary)
            assertTrue(note.attemptId.isNotBlank())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable stream is recorded before any parsing happens`() = runBlocking {
        val dir = Files.createTempDirectory("intake-unreadable").toFile()
        try {
            val notes = mutableListOf<DiagnosticStep>()
            val outcome = coordinator(dir) { notes += it }.ingestRequest(
                request = request(),
                allowAuto = true,
                previewOnly = false,
                openStream = { null },
            )

            assertEquals(FailureReason.UNREADABLE, outcome.failure)
            assertEquals("UNREADABLE", notes.single().details)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `decode failure reaches the persistent notice consumer without creating a candidate or writing ledger`() = runBlocking {
        val dir = Files.createTempDirectory("intake-decode-failure-notice").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            var ledgerWrites = 0
            val writer = object : ScreenshotLedgerWriter {
                override suspend fun insert(entity: TransactionEntity, attemptId: String): LedgerInsertResult {
                    ledgerWrites++
                    return LedgerInsertResult.Inserted(1L)
                }

                override suspend fun confirm(entity: TransactionEntity, attemptId: String): LedgerInsertResult =
                    insert(entity, attemptId)
            }
            val notices = mutableListOf<ScreenshotIntakeFailureNotice>()
            val outcome = ScreenshotIntakeCoordinator(
                store = store,
                ledgerWriter = writer,
                decodeOverride = { null },
                failureNoticeSink = { source, reason -> notices += ScreenshotIntakeFailureNotice(source, reason) },
            ).ingestRequest(
                request = request().copy(source = IntakeSource.MEDIASTORE),
                allowAuto = true,
                previewOnly = false,
                openStream = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) },
            )

            assertEquals(FailureReason.DECODE_FAILED, outcome.failure)
            assertEquals(
                listOf(ScreenshotIntakeFailureNotice(IntakeSource.MEDIASTORE, FailureReason.DECODE_FAILED)),
                notices,
            )
            assertEquals(
                "Album watcher import failed: The image could not be decoded. Select the original image or capture again from the payment app.",
                ScreenshotIntakeFailureNoticeCopy.message(notices.single()) { EnglishUiText.translate(it) },
            )
            assertEquals(
                "The image could not be decoded. Check the image, then reselect or share the original image.",
                ScreenshotIntakeFailureNoticeCopy.activityMessage(outcome.failure!!) { EnglishUiText.translate(it) },
            )
            assertTrue(store.latest().isEmpty())
            assertEquals(0, ledgerWrites)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a broken diagnostics sink cannot break the import`() = runBlocking {
        val dir = Files.createTempDirectory("intake-sink-down").toFile()
        try {
            val outcome = coordinator(dir) { throw IllegalStateException("diagnostics down") }
                .ingestRequest(
                    request = request(mime = "application/pdf"),
                    allowAuto = false,
                    previewOnly = false,
                    openStream = { null },
                )

            assertEquals(FailureReason.UNSUPPORTED_MIME, outcome.failure)
            assertFalse(outcome.autoBookAllowed)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun block(text: String, top: Int) = TextBlock(text = text, top = top, right = 300, bottom = top + 20)

    private fun transferPage(status: String) = listOf(
        block("微信支付", 10),
        block("转账给 张三", 40),
        block("-¥200.00", 70),
        block(status, 100),
    )

    private fun autoBookablePaymentPage() = listOf(
        block("微信支付", 10),
        block("收款方 Fixture convenience store", 40),
        block("-¥36.50", 80),
        block("当前状态", 120),
        block("交易成功", 140),
        block("2026-11-21 12:30", 180),
        block("订单号 9000000000000001", 220),
    )

    private fun waitingTransfer(capturedAtMs: Long) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:TRANSFER:20000:张三",
        amountCents = 20_000L,
        merchantCandidates = listOf("张三"),
        orderId = null,
        capturedAtMs = capturedAtMs,
        screenshotPath = null,
        confidence = 0.6f,
        reason = "waiting",
        status = CaptureCandidate.STATUS_WAITING,
        kind = CaptureCandidate.KIND_TRANSFER,
        eventTimeMs = capturedAtMs,
        direction = CaptureCandidate.DIRECTION_EXPENSE,
        paymentPlatform = CaptureCandidate.PLATFORM_WECHAT,
        completionStatus = CaptureCandidate.COMPLETION_PENDING,
        candidateId = "waiting-transfer",
    )

    private suspend fun ingestPage(
        coordinator: ScreenshotIntakeCoordinator,
        capturedAtMs: Long,
        blocks: List<TextBlock>,
        bytes: ByteArray? = null,
        fingerprint: String = capturedAtMs.toString(16).padStart(64, '0'),
        allowAuto: Boolean = true,
        previewOnly: Boolean = false,
    ) = coordinator.ingestBlocks(
        request = request().copy(capturedAtMs = capturedAtMs),
        blocks = blocks,
        bytes = bytes,
        fingerprint = fingerprint,
        attemptId = "attempt-$capturedAtMs",
        allowAuto = allowAuto,
        previewOnly = previewOnly,
    )

    private fun fingerprintCandidate(
        id: String,
        capturedAtMs: Long,
        fingerprint: String,
        status: String = CaptureCandidate.STATUS_PENDING,
    ) = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:test:$id",
        amountCents = 1_234L,
        merchantCandidates = listOf("Fixture shop"),
        orderId = null,
        capturedAtMs = capturedAtMs,
        screenshotPath = null,
        confidence = 0.9f,
        reason = "fixture",
        status = status,
        imageFingerprint = fingerprint,
        candidateId = id,
    )

    @Test
    fun `auto booking requires the exact screenshot bytes to be durably retained`() = runBlocking {
        val failedRoot = Files.createTempDirectory("intake-missing-screenshot").toFile()
        val successRoot = Files.createTempDirectory("intake-retained-screenshot").toFile()
        try {
            val bytes = ByteArray(256) { (it % 29).toByte() }
            val failedStore = CaptureCandidateStore(failedRoot, screenshotStorageQuotaBytes = 0L)
            val failedSteps = mutableListOf<DiagnosticStep>()
            var failedLedgerWrites = 0
            val failedWriter = object : ScreenshotLedgerWriter {
                override suspend fun insert(
                    entity: TransactionEntity,
                    attemptId: String,
                ): LedgerInsertResult {
                    failedLedgerWrites++
                    return LedgerInsertResult.Inserted(701L)
                }

                override suspend fun confirm(
                    entity: TransactionEntity,
                    attemptId: String,
                ): LedgerInsertResult = insert(entity, attemptId)
            }
            val failedOutcome = ScreenshotIntakeCoordinator(
                store = failedStore,
                diagnosticSink = { failedSteps += it },
                ledgerWriter = failedWriter,
            ).ingestBlocks(
                request = request().copy(capturedAtMs = capturedAt),
                blocks = autoBookablePaymentPage(),
                bytes = bytes,
                fingerprint = "a".repeat(64),
                attemptId = "screenshot-quota-failure",
                allowAuto = true,
                previewOnly = false,
            )

            assertEquals(0, failedOutcome.booked)
            assertEquals(1, failedOutcome.pending)
            assertEquals(0, failedLedgerWrites)
            assertEquals(1, failedOutcome.confirmationCandidates.size)
            val retainedForReview = failedStore.pending().single()
            assertEquals(CaptureCandidate.STATUS_PENDING, retainedForReview.status)
            assertEquals(CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_QUOTA, retainedForReview.screenshotUnavailableReason)
            assertEquals(null, retainedForReview.screenshotPath)
            assertEquals(retainedForReview, failedOutcome.confirmationCandidates.single())
            assertEquals(
                "截图未保留（空间不足）；释放空间后重新导入。",
                CaptureReviewMapping.screenshotUnavailableNotice(failedOutcome.confirmationCandidates.single()),
            )
            assertTrue("the stored item remains available for explicit review", CaptureBookGate.mayConfirm(retainedForReview))
            assertTrue(
                failedSteps.any {
                    it.stage == TransactionDiagnostics.STAGE_SAVE &&
                        it.result == TransactionDiagnostics.RESULT_WARNING &&
                        it.details == "Screenshot evidence unavailable; candidate kept pending"
                },
            )

            // A deliberately user-confirmed action must receive the same guard:
            // missing screenshot evidence cannot route to any terminal status.
            val askUserOutcome = ScreenshotIntakeCoordinator(
                store = failedStore,
                ledgerWriter = failedWriter,
            ).ingestBlocks(
                request = request().copy(capturedAtMs = capturedAt + 2L),
                blocks = autoBookablePaymentPage(),
                bytes = bytes,
                fingerprint = "c".repeat(64),
                attemptId = "screenshot-ask-user-image-failure",
                allowAuto = false,
                previewOnly = false,
            )
            val askUserCandidate = failedStore.pending().single { it.diagnosticAttemptId == "screenshot-ask-user-image-failure" }
            assertEquals(1, askUserOutcome.pending)
            assertEquals(0, askUserOutcome.booked)
            assertEquals(0, failedLedgerWrites)
            assertEquals(askUserCandidate, askUserOutcome.confirmationCandidates.single())
            assertEquals(CaptureCandidate.STATUS_PENDING, askUserCandidate.status)
            assertEquals(CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_QUOTA, askUserCandidate.screenshotUnavailableReason)

            val successStore = CaptureCandidateStore(successRoot)
            var successLedgerWrites = 0
            val successWriter = object : ScreenshotLedgerWriter {
                override suspend fun insert(
                    entity: TransactionEntity,
                    attemptId: String,
                ): LedgerInsertResult {
                    successLedgerWrites++
                    return LedgerInsertResult.Inserted(702L)
                }

                override suspend fun confirm(
                    entity: TransactionEntity,
                    attemptId: String,
                ): LedgerInsertResult = insert(entity, attemptId)
            }
            val successOutcome = ScreenshotIntakeCoordinator(
                store = successStore,
                ledgerWriter = successWriter,
            ).ingestBlocks(
                request = request().copy(capturedAtMs = capturedAt + 1L),
                blocks = autoBookablePaymentPage(),
                bytes = bytes,
                fingerprint = "b".repeat(64),
                attemptId = "screenshot-image-retained",
                allowAuto = true,
                previewOnly = false,
            )

            assertEquals(1, successOutcome.booked)
            assertEquals(0, successOutcome.pending)
            assertEquals(1, successLedgerWrites)
            val bookedCandidate = successStore.latest().single()
            assertEquals(CaptureCandidate.STATUS_APPROVED, bookedCandidate.status)
            assertTrue(successStore.hasReadableScreenshot(bookedCandidate, bytes))
        } finally {
            failedRoot.deleteRecursively()
            successRoot.deleteRecursively()
        }
    }

    @Test
    fun `same image stays suppressed after more than eighty candidates and restore`() = runBlocking {
        val dir = Files.createTempDirectory("intake-image-retention").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val targetFingerprint = "a".repeat(64)
            val original = fingerprintCandidate("original-image", capturedAt, targetFingerprint)
            assertTrue(store.save(original, null) != null)
            assertTrue(store.mark(original, CaptureCandidate.STATUS_IGNORED))
            assertEquals(original.candidateId, store.findByImageFingerprint(targetFingerprint)?.candidateId)
            assertEquals(
                com.bookkeeping.app.notification.capture.RestoreOutcome.RESTORED,
                store.restore(original.candidateId),
            )
            assertEquals(CaptureCandidate.STATUS_PENDING, store.latest().single().status)

            // The store retains these rows, but the former 80-item lookup skipped
            // the older image and admitted the replay as a second import.
            repeat(81) { index ->
                val otherFingerprint = index.toString(16).padStart(64, '0')
                assertTrue(
                    store.save(
                        fingerprintCandidate(
                            id = "other-$index",
                            capturedAtMs = capturedAt + (index + 1) * 1_000L,
                            fingerprint = otherFingerprint,
                        ),
                        null,
                    ) != null,
                )
            }

            var ledgerWrites = 0
            val writer = object : ScreenshotLedgerWriter {
                override suspend fun insert(
                    entity: TransactionEntity,
                    attemptId: String,
                ): LedgerInsertResult {
                    ledgerWrites++
                    return LedgerInsertResult.Inserted(99L)
                }

                override suspend fun confirm(
                    entity: TransactionEntity,
                    attemptId: String,
                ): LedgerInsertResult = insert(entity, attemptId)
            }
            val outcome = ingestPage(
                coordinator = ScreenshotIntakeCoordinator(store, ledgerWriter = writer),
                capturedAtMs = capturedAt + 90_000L,
                blocks = transferPage("交易成功"),
                fingerprint = targetFingerprint,
            )

            assertTrue("the original image fingerprint must still be recognized", outcome.repeatImage)
            assertEquals("replaying a retained image must never reach the ledger writer", 0, ledgerWrites)
            assertEquals(
                "a restored candidate still reserves its image fingerprint until expiry",
                1,
                store.latest(200).count { it.imageFingerprint == targetFingerprint },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a completed card settles the earlier waiting placeholder and counts it`() = runBlocking {
        val dir = Files.createTempDirectory("intake-settle").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val waiting = waitingTransfer(capturedAtMs = capturedAt - 60_000)
            store.save(waiting, null)
            val coordinator = ScreenshotIntakeCoordinator(store)

            val outcome = ingestPage(
                coordinator,
                capturedAt,
                transferPage("交易成功"),
                bytes = ByteArray(64) { (it % 17).toByte() },
            )

            assertEquals(1, outcome.superseded)
            assertEquals(1, outcome.pending)
            assertEquals(
                CaptureCandidate.STATUS_LINKED,
                store.latest().single { it.candidateId == waiting.candidateId }.status,
            )
            // One actionable item, not two: the placeholder is closed.
            assertEquals(1, store.pending().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable image cannot leave a not-yet-completed candidate in waiting status`() = runBlocking {
        val dir = Files.createTempDirectory("intake-unreadable-waiting").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val outcome = ingestPage(
                ScreenshotIntakeCoordinator(store),
                capturedAt,
                transferPage("交易处理中"),
            )

            assertEquals(1, outcome.pending)
            assertEquals(0, outcome.waiting)
            assertEquals(0, outcome.ignored)
            val pending = store.pending().single()
            assertEquals(CaptureCandidate.STATUS_PENDING, pending.status)
            assertEquals(CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_MISSING, pending.screenshotUnavailableReason)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `preview only and allowAuto false change nothing at all`() = runBlocking {
        val dir = Files.createTempDirectory("intake-preview").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val waiting = waitingTransfer(capturedAtMs = capturedAt - 60_000)
            store.save(waiting, null)
            val coordinator = ScreenshotIntakeCoordinator(store)

            val preview = ingestPage(coordinator, capturedAt, transferPage("交易成功"), previewOnly = true)
            val noAuto = ingestPage(
                coordinator,
                capturedAt + 1_000,
                transferPage("交易成功"),
                allowAuto = false,
            )

            assertEquals(0, preview.superseded)
            assertEquals(0, noAuto.superseded)
            assertEquals(
                CaptureCandidate.STATUS_WAITING,
                store.latest().single { it.candidateId == waiting.candidateId }.status,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a card the app refuses to act on never settles a placeholder`() = runBlocking {
        val dir = Files.createTempDirectory("intake-ignored").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            // An earlier ¥20 purchase at 店铺A is waiting for evidence.
            val waiting = waitingTransfer(capturedAtMs = capturedAt - 60_000).copy(
                merchantCandidates = listOf("店铺A"),
                amountCents = 2_000L,
                kind = CaptureCandidate.KIND_PAYMENT,
                pageIdentity = "screenshot:wechat:PAYMENT:2000:店铺A",
            )
            store.save(waiting, null)
            // Two ¥20 cards with no OCR boxes: the page cannot be split, so its
            // single ambiguous draft is IGNORE. An ignored card may not close the
            // placeholder and may not book anything either.
            val page = listOf(
                TextBlock(text = "微信支付"),
                TextBlock(text = "收款方 店铺A"),
                TextBlock(text = "-¥20.00"),
                TextBlock(text = "支付成功"),
                TextBlock(text = "店铺B"),
                TextBlock(text = "-¥20.00"),
                TextBlock(text = "支付成功"),
            )

            val outcome = ingestPage(ScreenshotIntakeCoordinator(store), capturedAt, page)

            assertEquals(0, outcome.superseded)
            assertEquals(0, outcome.booked)
            assertEquals(1, outcome.pending)
            assertEquals(0, outcome.ignored)
            assertEquals(
                CaptureCandidate.STATUS_WAITING,
                store.latest().single { it.candidateId == waiting.candidateId }.status,
            )
            val unreadableIgnoredDraft = store.pending().single()
            assertEquals(CaptureCandidate.STATUS_PENDING, unreadableIgnoredDraft.status)
            assertEquals(
                CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_MISSING,
                unreadableIgnoredDraft.screenshotUnavailableReason,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt image fingerprint index holds an otherwise auto bookable card for review`() = runBlocking {
        val dir = Files.createTempDirectory("intake-corrupt-fingerprint").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val captureDir = File(dir, "page_capture").apply { mkdirs() }
            File(captureDir, "image-fingerprints.jsonl").writeText("{corrupt fixture index\n")
            assertTrue(store.lookupImageFingerprint("f".repeat(64)) is ImageFingerprintLookup.Unavailable)

            var ledgerWrites = 0
            val writer = object : ScreenshotLedgerWriter {
                override suspend fun insert(
                    entity: TransactionEntity,
                    attemptId: String,
                ): LedgerInsertResult {
                    ledgerWrites++
                    return LedgerInsertResult.Inserted(400L)
                }

                override suspend fun confirm(
                    entity: TransactionEntity,
                    attemptId: String,
                ): LedgerInsertResult = insert(entity, attemptId)
            }
            val highConfidencePayment = listOf(
                block("微信支付", 10),
                block("收款方 Fixture convenience store", 40),
                block("-¥36.50", 80),
                block("当前状态", 120),
                block("交易成功", 140),
                block("2026-11-19 12:30", 180),
                block("订单号 1000123456789012", 220),
            )

            val outcome = ingestPage(
                coordinator = ScreenshotIntakeCoordinator(store, ledgerWriter = writer),
                capturedAtMs = capturedAt,
                blocks = highConfidencePayment,
                fingerprint = "f".repeat(64),
            )

            assertEquals(0, outcome.booked)
            assertEquals(1, outcome.pending)
            assertEquals(0, ledgerWrites)
            val pending = store.pending().single()
            assertTrue(pending.reason.contains("fingerprint index unavailable"))
            assertTrue(pending.ledgerWriteAllowed)
            assertTrue("the shared review action remains available", CaptureBookGate.mayConfirm(pending))
            assertEquals("WECHAT", CaptureReviewMapping.toTransactionEntity(
                pending,
                pending.merchantCandidates.single(),
                CaptureReviewMapping.defaultCategory(pending),
            ).source)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a sibling card from the same import never settles the card this import created`() = runBlocking {
        val dir = Files.createTempDirectory("intake-sibling").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val page = listOf(
                block("微信支付", 10),
                block("转账给 张三", 40),
                block("-¥200.00", 70),
                block("等待对方确认", 100),
                block("转账给 张三", 140),
                block("-¥200.00", 170),
                block("交易成功", 200),
            )

            val outcome = ingestPage(
                ScreenshotIntakeCoordinator(store),
                capturedAt,
                page,
                bytes = ByteArray(64) { (it % 13).toByte() },
            )

            // Nothing was superseded: the only match was this import's own card.
            assertEquals(0, outcome.superseded)
            assertEquals(1, outcome.waiting)
            assertEquals(
                listOf(CaptureCandidate.STATUS_PENDING, CaptureCandidate.STATUS_WAITING),
                store.latest().map { it.status }.sorted(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
