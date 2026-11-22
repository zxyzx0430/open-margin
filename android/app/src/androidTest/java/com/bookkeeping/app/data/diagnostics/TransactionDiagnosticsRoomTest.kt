package com.bookkeeping.app.data.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.ProcessedNoticeEntity
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.data.repository.NotificationInsertResult
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeCoordinator
import com.bookkeeping.app.capture.screenshot.CandidateStorageWarningQueue
import com.bookkeeping.app.capture.screenshot.CandidateStorageIntakeKind
import com.bookkeeping.app.capture.screenshot.CandidateStorageWarningCopy
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeFailureNotice
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeFailureNoticeQueue
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeFailureNoticeCopy
import com.bookkeeping.app.capture.screenshot.IntakeSource
import com.bookkeeping.app.capture.screenshot.FailureReason
import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.notification.NoticeFingerprint
import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.notification.accessibility.PageCaptureReviewPolicy
import com.bookkeeping.app.notification.accessibility.PageCaptureOutcome
import com.bookkeeping.app.notification.accessibility.PageCaptureResultPolicy
import com.bookkeeping.app.data.repository.DuplicateReason
import com.bookkeeping.app.data.repository.CrossSourceDuplicatePolicy
import com.bookkeeping.app.ui.screen.capture.CaptureReviewMapping
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import com.bookkeeping.app.sync.TransactionSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TransactionDiagnosticsRoomTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun candidateStorageOverflowWarningPersistsUntilAcknowledged() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        CandidateStorageWarningQueue.acknowledge(testContext)
        try {
            assertTrue(CandidateStorageWarningQueue.markPending(testContext, CandidateStorageIntakeKind.SCREENSHOT_PAGE))
            assertTrue("repeated overflow remains one bounded warning slot", CandidateStorageWarningQueue.markPending(testContext, CandidateStorageIntakeKind.SCREENSHOT_PAGE))
            assertTrue(CandidateStorageWarningQueue.markPending(testContext, CandidateStorageIntakeKind.NOTIFICATION))
            assertTrue(CandidateStorageWarningQueue.markPending(testContext, CandidateStorageIntakeKind.SMS))
            assertTrue(CandidateStorageWarningQueue.isPending(testContext))
            assertEquals(
                CandidateStorageIntakeKind.entries.toSet(),
                CandidateStorageWarningQueue.pendingKinds(testContext),
            )
            assertTrue(
                CandidateStorageWarningCopy.guidance(
                    setOf(CandidateStorageIntakeKind.SCREENSHOT_PAGE),
                    { EnglishUiText.translate(it) },
                ).contains("reselect the screenshot or reopen the payment page"),
            )
            assertTrue(
                CandidateStorageWarningCopy.guidance(
                    setOf(CandidateStorageIntakeKind.NOTIFICATION),
                    { EnglishUiText.translate(it) },
                ).contains("open the payment app's notifications or bill history"),
            )
            assertTrue(
                CandidateStorageWarningCopy.guidance(
                    setOf(CandidateStorageIntakeKind.SMS),
                    { EnglishUiText.translate(it) },
                ).contains("find the original message in the SMS app"),
            )
            assertTrue(
                CandidateStorageWarningCopy.acknowledgement { EnglishUiText.translate(it) }
                    .contains("does not recover the unsaved items"),
            )
            val preferences = testContext.getSharedPreferences("candidate_storage_warning", Context.MODE_PRIVATE)
            assertEquals(setOf(CandidateStorageWarningQueue.PENDING_MASK_KEY), preferences.all.keys)

            preferences.edit().putString(CandidateStorageWarningQueue.PENDING_MASK_KEY, "corrupt fixture").commit()
            assertEquals("malformed warning state remains visible", CandidateStorageIntakeKind.entries.toSet(), CandidateStorageWarningQueue.pendingKinds(testContext))
            preferences.edit().clear().putBoolean(CandidateStorageWarningQueue.LEGACY_PENDING_KEY, true).commit()
            assertEquals("legacy warning state remains visible", CandidateStorageIntakeKind.entries.toSet(), CandidateStorageWarningQueue.pendingKinds(testContext))
            assertTrue(CandidateStorageWarningQueue.acknowledge(testContext))
            assertFalse(CandidateStorageWarningQueue.isPending(testContext))
        } finally {
            CandidateStorageWarningQueue.acknowledge(testContext)
        }
    }

    @Test fun backgroundScreenshotFailureNoticePersistsOnlyIntakeAndReasonCodes() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        ScreenshotIntakeFailureNoticeQueue.acknowledge(testContext)
        try {
            assertTrue(
                ScreenshotIntakeFailureNoticeQueue.markPending(
                    testContext,
                    IntakeSource.MEDIASTORE,
                    FailureReason.DECODE_FAILED,
                ),
            )
            val reopenedNotice = ScreenshotIntakeFailureNoticeQueue.pending(testContext)
            assertEquals(ScreenshotIntakeFailureNotice(IntakeSource.MEDIASTORE, FailureReason.DECODE_FAILED), reopenedNotice)
            assertEquals(
                "Album watcher import failed: The image could not be decoded. Select the original image or capture again from the payment app.",
                ScreenshotIntakeFailureNoticeCopy.message(requireNotNull(reopenedNotice)) {
                    EnglishUiText.translate(it)
                },
            )
            val values = testContext.getSharedPreferences("screenshot_intake_failure_notice", Context.MODE_PRIVATE).all
            assertEquals(setOf(ScreenshotIntakeFailureNoticeQueue.SOURCE_KEY, ScreenshotIntakeFailureNoticeQueue.REASON_KEY), values.keys)
            assertTrue(values.values.all { it is String })
            assertTrue(ScreenshotIntakeFailureNoticeQueue.acknowledge(testContext))
            assertEquals(null, ScreenshotIntakeFailureNoticeQueue.pending(testContext))
        } finally {
            ScreenshotIntakeFailureNoticeQueue.acknowledge(testContext)
        }
    }

    @Test fun daoRetentionKeepsTheSevenDayBoundaryAndUnlinkedAttempts() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val dao = database.transactionDiagnosticDao()
            val cutoff = 1_800_000_000_000L - TransactionDiagnosticRetention.RETENTION_MS
            dao.insert(event("expired", "attempt-expired", null, cutoff - 1))
            dao.insert(event("boundary", "attempt-before-insert", null, cutoff))
            dao.insert(event("recent", "attempt-recent", 77L, cutoff + 1))

            assertEquals(1, dao.pruneBefore(cutoff))
            val all = dao.observeEvents(cutoff, null, "", 100, 0).first()
            assertEquals(listOf("recent", "boundary"), all.map { it.eventId })
            assertEquals("attempt-before-insert", all.last().attemptId)
            assertEquals(null, all.last().transactionId)
            assertEquals(listOf(77L), dao.observeRecentTransactionIds(cutoff, 20).first())
            assertEquals(
                listOf("recent"),
                dao.observeEvents(cutoff, 77L, "", 100, 0).first().map { it.eventId },
            )
        } finally {
            database.close()
        }
    }

    @Test fun exactOrderDuplicatesAreSuppressedAndSimilarTransactionsAreSaved() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            val diagnosticDao = database.transactionDiagnosticDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = database.processedNoticeDao(),
                database = database,
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(diagnosticDao),
                context = context,
            )
            val timestamp = 1_800_000_000_000L

            val sourceMatchedId = transactionDao.insert(
                bill(
                    source = "WECHAT",
                    amountCents = 1_000L,
                    merchant = "Fixture shop",
                    timestamp = timestamp,
                    sourceTransactionId = "source-id-1",
                ),
            )
            assertNull(
                repository.insertIfNotDuplicate(
                    bill(
                        source = "WECHAT",
                        amountCents = 1_000L,
                        merchant = "Fixture shop",
                        timestamp = timestamp,
                        sourceTransactionId = "source-id-1",
                    ),
                    diagnosticInput = TransactionDiagnosticInput("source-match-attempt"),
                ),
            )
            val sourceEvents = diagnosticDao.observeEvents(0L, sourceMatchedId, "", 100, 0).first()
            val sourceSuppression = sourceEvents.single {
                it.attemptId == "source-match-attempt" &&
                    it.details == "Existing source transaction ID; candidate not inserted"
            }
            assertEquals(sourceMatchedId, sourceSuppression.transactionId)

            val sameAmountFirstId = transactionDao.insert(
                bill(
                    source = "ALIPAY",
                    amountCents = 2_500L,
                    merchant = "Fixture cafe",
                    timestamp = timestamp + 20_000L,
                ),
            )
            val independentSameAmount = repository.insertIfNotDuplicateResult(
                    bill(
                        source = "ALIPAY",
                        amountCents = 2_500L,
                        merchant = "Fixture cafe",
                        timestamp = timestamp + 20_001L,
                    ),
                    diagnosticInput = TransactionDiagnosticInput("independent-same-amount-attempt"),
            )
            assertTrue(
                "same-source/same-merchant/same-amount within 8 seconds is not proof of one event",
                independentSameAmount is LedgerInsertResult.Inserted,
            )
            val insertedId = (independentSameAmount as LedgerInsertResult.Inserted).transactionId
            assertTrue(insertedId != sameAmountFirstId)
            assertEquals(
                "both independent rows remain in the ledger",
                2,
                transactionDao.recentForScreenshotDedup(100)
                    .count { it.source == "ALIPAY" && it.merchant == "Fixture cafe" },
            )
            val savedEvents = diagnosticDao.observeEvents(0L, insertedId, "", 100, 0).first()
            assertTrue(savedEvents.any {
                it.attemptId == "independent-same-amount-attempt" && it.details == "Transaction row inserted"
            })
        } finally {
            database.close()
        }
    }

    @Test fun exactPlatformOrderIdDiagnosticPointsToTheMatchedLedgerRow() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            val diagnosticDao = database.transactionDiagnosticDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = database.processedNoticeDao(),
                database = database,
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(diagnosticDao),
                context = context,
            )
            val timestamp = 1_800_000_000_000L
            val orderId = "fixture-platform-order-7001"
            val matchedId = transactionDao.insert(
                bill(
                    source = "WECHAT",
                    amountCents = 1_200L,
                    merchant = "Fixture shop",
                    timestamp = timestamp,
                    sourceTransactionId = orderId,
                ),
            )

            val duplicate = repository.insertIfNotDuplicateResult(
                bill(
                    source = "WECHAT",
                    amountCents = 1_200L,
                    merchant = "Fixture shop",
                    timestamp = timestamp + 1L,
                    sourceTransactionId = orderId,
                ),
                diagnosticInput = TransactionDiagnosticInput("exact-order-id-room-attempt"),
            )

            assertTrue(duplicate is LedgerInsertResult.Duplicate)
            assertEquals(DuplicateReason.SOURCE_ORDER_ID, (duplicate as LedgerInsertResult.Duplicate).reason)
            val linkedEvent = diagnosticDao.observeEvents(0L, matchedId, "", 100, 0).first().single {
                it.attemptId == "exact-order-id-room-attempt"
            }
            assertEquals("Existing source transaction ID; candidate not inserted", linkedEvent.details)
            assertEquals("exact-order-id-room-attempt", linkedEvent.attemptId)
            assertEquals(matchedId, linkedEvent.transactionId)
            assertEquals(1, transactionDao.recentForScreenshotDedup(100).size)
        } finally {
            database.close()
        }
    }

    @Test fun concurrentSameKeyWithoutOrderIdProducesOneWriteAndOneAmbiguity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            fun repository() = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = database.processedNoticeDao(),
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val repositories = listOf(repository(), repository())
            val timestamp = 1_800_000_000_100L
            val fingerprint = requireNotNull(
                NoticeFingerprint.forSourceEvent("com.tencent.mm", "fixture-notice-key"),
            )
            val event = bill(
                source = "WECHAT",
                amountCents = 1_234L,
                merchant = "Fixture shop",
                timestamp = timestamp,
            )

            val outcomes = coroutineScope {
                listOf("notice-attempt-1", "notice-attempt-2").mapIndexed { index, attemptId ->
                    async {
                        repositories[index].insertNotificationIfNotReplayed(
                            fingerprint = fingerprint,
                            source = "com.tencent.mm",
                            seenMs = timestamp + 500L,
                            transaction = event,
                            notifySuccess = false,
                            diagnosticInput = TransactionDiagnosticInput(attemptId),
                        )
                    }
                }.awaitAll()
            }

            assertEquals(1, outcomes.count { it is NotificationInsertResult.Processed })
            assertEquals(1, outcomes.count { it is NotificationInsertResult.NeedsReview })
            assertEquals(0, outcomes.count { it is NotificationInsertResult.Replayed })
            val processed = outcomes.filterIsInstance<NotificationInsertResult.Processed>().single()
            assertTrue(processed.ledgerResult is LedgerInsertResult.Inserted)
            assertEquals(1, transactionDao.recentForScreenshotDedup(100).size)
            assertEquals(
                "the idempotency key is linked to the committed ledger row",
                (processed.ledgerResult as LedgerInsertResult.Inserted).transactionId,
                database.processedNoticeDao().find(fingerprint)?.transactionId,
            )
        } finally {
            database.close()
        }
    }

    @Test fun failedLedgerWriteRollsBackTheNotificationFingerprintClaim() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = database.processedNoticeDao(),
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val timestamp = 1_800_000_001_000L
            val fingerprint = requireNotNull(
                NoticeFingerprint.forSourceEvent("com.eg.android.AlipayGphone", "fixture-notice-key"),
            )
            val event = bill("ALIPAY", 2_500L, "Fixture cafe", timestamp)
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_fixture_transaction BEFORE INSERT ON transactions " +
                    "BEGIN SELECT RAISE(FAIL, 'fixture write failure'); END",
            )

            val failed = repository.insertNotificationIfNotReplayed(
                fingerprint = fingerprint,
                source = "com.eg.android.AlipayGphone",
                seenMs = timestamp + 100L,
                transaction = event,
                notifySuccess = false,
                diagnosticInput = TransactionDiagnosticInput("failed-notice-attempt"),
            )
            assertTrue(failed is NotificationInsertResult.Failed)
            assertNull("a failed row write must not leave an idempotency claim", database.processedNoticeDao().find(fingerprint))

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_fixture_transaction")
            val retried = repository.insertNotificationIfNotReplayed(
                fingerprint = fingerprint,
                source = "com.eg.android.AlipayGphone",
                seenMs = timestamp + 200L,
                transaction = event,
                notifySuccess = false,
                diagnosticInput = TransactionDiagnosticInput("retried-notice-attempt"),
            )
            assertTrue(retried is NotificationInsertResult.Processed)
            assertTrue(
                (retried as NotificationInsertResult.Processed).ledgerResult is LedgerInsertResult.Inserted,
            )
            assertEquals(1, transactionDao.recentForScreenshotDedup(100).size)
            assertNotNull(database.processedNoticeDao().find(fingerprint)?.transactionId)
        } finally {
            database.close()
        }
    }

    @Test fun unlinkedLegacyNoticeFingerprintIsPreservedAndHeldForReview() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            val noticeDao = database.processedNoticeDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = noticeDao,
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val timestamp = 1_800_000_001_500L
            val legacyFingerprint = NoticeFingerprint.of(
                packageName = "com.eg.android.AlipayGphone",
                title = "Fixture payment",
                body = "Fixture kiosk charge 4.20",
                originalTimeMs = timestamp,
            )
            val fingerprint = requireNotNull(
                NoticeFingerprint.forSourceEvent("com.eg.android.AlipayGphone", "legacy-event-key"),
            )
            // The previous implementation stored the notice fingerprint without
            // linking it to a ledger row, including when its amount heuristic
            // suppressed a distinct transaction.
            noticeDao.insertIgnore(
                ProcessedNoticeEntity(
                    fingerprint = legacyFingerprint,
                    source = "com.eg.android.AlipayGphone",
                    firstSeenMs = timestamp,
                    lastSeenMs = timestamp,
                    seenCount = 1,
                ),
            )

            val result = repository.insertNotificationIfNotReplayed(
                fingerprint = fingerprint,
                source = "com.eg.android.AlipayGphone",
                seenMs = timestamp + 100L,
                transaction = bill("ALIPAY", 420L, "Fixture kiosk", timestamp),
                notifySuccess = false,
                diagnosticInput = TransactionDiagnosticInput("legacy-unlinked-notice"),
                legacyFingerprints = setOf(legacyFingerprint),
            )

            assertTrue("an unlinked content fingerprint is ambiguous", result is NotificationInsertResult.NeedsReview)
            assertEquals("an ambiguous old attempt must not be silently reinserted", 0, transactionDao.recentForScreenshotDedup(100).size)
            assertEquals(1, noticeDao.find(legacyFingerprint)?.seenCount)
            assertNull(noticeDao.find(legacyFingerprint)?.transactionId)
            assertNull("do not create a new claim over the unresolved legacy row", noticeDao.find(fingerprint))
        } finally {
            database.close()
        }
    }

    @Test fun differentNotificationKeysStayIndependentAndSameIdlessKeyNeedsReview() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        val reviewStore = CaptureCandidateStore(context).apply { clear() }
        try {
            val transactionDao = database.transactionDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = database.processedNoticeDao(),
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val timestamp = 1_800_000_002_000L
            val firstFingerprint = requireNotNull(
                NoticeFingerprint.forSourceEvent("com.tencent.mm", "notification-key-a"),
            )
            val secondFingerprint = requireNotNull(
                NoticeFingerprint.forSourceEvent("com.tencent.mm", "notification-key-b"),
            )
            val first = repository.insertNotificationIfNotReplayed(
                fingerprint = firstFingerprint,
                source = "com.tencent.mm",
                seenMs = timestamp,
                transaction = bill("WECHAT", 1_250L, "Fixture snack shop", timestamp)
                    .copy(currency = "USD"),
                diagnosticInput = TransactionDiagnosticInput("same-visible-first"),
            )
            val second = repository.insertNotificationIfNotReplayed(
                fingerprint = secondFingerprint,
                source = "com.tencent.mm",
                seenMs = timestamp + 1L,
                transaction = bill("WECHAT", 1_250L, "Fixture snack shop", timestamp),
                diagnosticInput = TransactionDiagnosticInput("same-visible-second"),
            )
            assertTrue(first is NotificationInsertResult.Processed)
            assertTrue(second is NotificationInsertResult.Processed)
            val firstId = (first as NotificationInsertResult.Processed).let {
                (it.ledgerResult as LedgerInsertResult.Inserted).transactionId
            }
            assertEquals("different Android notification keys must not collapse", 2, transactionDao.recentForScreenshotDedup(100).size)

            val refreshedSameEvent = repository.insertNotificationIfNotReplayed(
                fingerprint = requireNotNull(
                    NoticeFingerprint.forSourceEvent("com.tencent.mm", "notification-key-a"),
                ),
                source = "com.tencent.mm",
                seenMs = timestamp + 2L,
                // The same Android key could be a refresh or a new transaction.
                transaction = bill("WECHAT", 1_250L, "Refreshed visible body", timestamp + 2L)
                    .copy(currency = "USD"),
                diagnosticInput = TransactionDiagnosticInput("same-key-body-refresh"),
            )
            assertTrue("a reused key without an order ID must remain ambiguous", refreshedSameEvent is NotificationInsertResult.NeedsReview)
            val ambiguous = refreshedSameEvent as NotificationInsertResult.NeedsReview
            assertTrue(ambiguous.reason.contains("no trustworthy transaction ID"))
            assertEquals("the diagnostic points back to the first linked ledger row", firstId, ambiguous.candidateTransactionId)
            assertEquals(2, transactionDao.recentForScreenshotDedup(100).size)
            val pending = reviewStore.pending().single()
            assertEquals("NOTIFICATION", pending.intakeMethod)
            assertEquals(1_250L, pending.amountCents)
            assertEquals("USD", pending.transactionCurrency)
            assertEquals(null, pending.orderId)
            assertEquals(firstId, pending.matchedTransactionId)
            assertTrue(CaptureBookGate.mayConfirm(pending))
            assertEquals(
                "WECHAT",
                CaptureReviewMapping.toTransactionEntity(
                    pending,
                    pending.merchantCandidates.single(),
                    CaptureReviewMapping.defaultCategory(pending),
                ).source,
            )
            assertEquals(
                "USD",
                CaptureReviewMapping.toTransactionEntity(
                    pending,
                    pending.merchantCandidates.single(),
                    CaptureReviewMapping.defaultCategory(pending),
                ).currency,
            )
        } finally {
            reviewStore.clear()
            database.close()
        }
    }

    @Test fun reusedIdlessSmsLegacyKeyCreatesAnActionableReviewCandidate() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        val reviewStore = CaptureCandidateStore(context).apply { clear() }
        try {
            val transactionDao = database.transactionDao()
            val noticeDao = database.processedNoticeDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = noticeDao,
                database = database,
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
            )
            val timestamp = 1_800_000_002_350L
            val reusedLegacyKey = NoticeFingerprint.of(
                packageName = "BANK_SMS",
                title = "Fixture bank sender",
                body = "Fixture debit 18.25",
                originalTimeMs = timestamp,
            )
            noticeDao.insertIgnore(
                ProcessedNoticeEntity(
                    fingerprint = reusedLegacyKey,
                    source = "BANK_SMS",
                    firstSeenMs = timestamp,
                    lastSeenMs = timestamp,
                    seenCount = 1,
                ),
            )
            val transaction = bill(
                source = "BANK_SMS",
                amountCents = 1_825L,
                merchant = "",
                timestamp = timestamp,
            ).copy(account = "Fixture bank account", category = "TRANSPORT", currency = "EUR")

            val result = repository.insertNotificationIfNotReplayed(
                fingerprint = null,
                source = "BANK_SMS",
                seenMs = timestamp + 100L,
                transaction = transaction,
                diagnosticInput = TransactionDiagnosticInput("fixture-sms-key-reuse"),
                legacyFingerprints = setOf(reusedLegacyKey),
            )

            assertTrue(result is NotificationInsertResult.NeedsReview)
            assertEquals(0, transactionDao.recentForScreenshotDedup(100).size)
            val pending = reviewStore.pending().single()
            assertEquals("SMS", pending.intakeMethod)
            assertEquals("BANK_SMS", pending.transactionSource)
            assertEquals("Fixture bank account", pending.transactionAccount)
            assertEquals("EUR", pending.transactionCurrency)
            assertNull(pending.orderId)
            assertEquals(1_825L, pending.amountCents)
            assertTrue(CaptureBookGate.mayConfirm(pending))
            val mapped = CaptureReviewMapping.toTransactionEntity(
                pending,
                pending.merchantCandidates.single(),
                CaptureReviewMapping.defaultCategory(pending),
            )
            assertEquals("BANK_SMS", mapped.source)
            assertEquals("Fixture bank account", mapped.account)
            assertEquals("TRANSPORT", mapped.category)
            assertEquals("EUR", mapped.currency)
        } finally {
            reviewStore.clear()
            database.close()
        }
    }

    @Test fun weakSameAmountTimeIsHeldButDistinctOrderIdsRemainSeparateLedgerRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        val reviewStore = CaptureCandidateStore(context).apply { clear() }
        try {
            val transactionDao = database.transactionDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = database.processedNoticeDao(),
                database = database,
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
            )
            val timestamp = 1_800_000_002_450L
            val first = repository.insertIfNotDuplicateResult(
                transaction = bill("WECHAT", 1_000L, "微信支付", timestamp),
                notifySuccess = false,
            )
            assertTrue(first is LedgerInsertResult.Inserted)
            val firstId = (first as LedgerInsertResult.Inserted).transactionId
            val pageCandidate = CaptureCandidate(
                packageName = CaptureCandidate.WECHAT,
                pageIdentity = "fixture-weak-page-candidate",
                amountCents = 1_000L,
                merchantCandidates = listOf("Fixture shop"),
                orderId = null,
                capturedAtMs = timestamp + 1_000L,
                screenshotPath = null,
                confidence = 0.9f,
                reason = "fixture page candidate",
                eventTimeMs = timestamp + 1_000L,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                candidateId = "fixture-weak-page-candidate-id",
            )
            assertTrue(reviewStore.save(pageCandidate, null) != null)

            val weak = repository.insertIfNotDuplicateResult(
                transaction = bill("WECHAT", 1_000L, "Fixture shop", timestamp + 1_000L)
                    .copy(captureObservedAtMs = timestamp + 1_000L),
                notifySuccess = false,
                crossSourceWindowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                holdWeakCrossSourceMatch = true,
            )

            assertTrue(weak is LedgerInsertResult.Duplicate)
            assertEquals(DuplicateReason.CROSS_SOURCE_WEAK, (weak as LedgerInsertResult.Duplicate).reason)
            assertEquals(PageCaptureOutcome.HOLD_FOR_REVIEW, PageCaptureResultPolicy.of(weak))
            assertNotNull(PageCaptureReviewPolicy.holdPossibleDuplicate(weak, pageCandidate, reviewStore))
            assertEquals(1, transactionDao.recentForScreenshotDedup(100).count { it.amountCents == 1_000L })
            val pending = reviewStore.pending().single()
            assertEquals(CaptureCandidate.REASON_LIKELY_DUPLICATE, pending.reason)
            assertEquals(firstId, pending.matchedTransactionId)
            assertTrue(CaptureBookGate.mayConfirm(pending))

            val confirmed = ScreenshotIntakeCoordinator(
                store = reviewStore,
                repositoryProvider = { repository },
            ).confirmCandidate(pending)
            assertEquals(ScreenshotIntakeCoordinator.ConfirmationResult.SAVED, confirmed)
            assertEquals(
                "explicit confirmation records a weakly similar no-ID payment as a separate row",
                2,
                transactionDao.recentForScreenshotDedup(100).count { it.amountCents == 1_000L },
            )
            assertEquals(CaptureCandidate.STATUS_APPROVED, reviewStore.latest().single().status)

            val idA = repository.insertIfNotDuplicateResult(
                transaction = bill(
                    "WECHAT", 2_000L, "微信支付", timestamp,
                    sourceTransactionId = "FIXTURE-ORDER-A",
                ),
                notifySuccess = false,
            ) as LedgerInsertResult.Inserted
            val idB = repository.insertIfNotDuplicateResult(
                transaction = bill(
                    "WECHAT", 2_000L, "Fixture shop", timestamp + 1_000L,
                    sourceTransactionId = "FIXTURE-ORDER-B",
                ).copy(captureObservedAtMs = timestamp + 1_000L),
                notifySuccess = false,
                crossSourceWindowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                holdWeakCrossSourceMatch = true,
            )

            assertTrue("different exact IDs are retained as distinct ledger rows", idB is LedgerInsertResult.Inserted)
            assertTrue(idA.transactionId != (idB as LedgerInsertResult.Inserted).transactionId)
            val distinctRows = transactionDao.recentForScreenshotDedup(100)
                .filter { it.amountCents == 2_000L }
            assertEquals(2, distinctRows.size)
            assertEquals(setOf("FIXTURE-ORDER-A", "FIXTURE-ORDER-B"), distinctRows.mapNotNull { it.sourceTransactionId }.toSet())
        } finally {
            reviewStore.clear()
            database.close()
        }
    }

    @Test fun sameNotificationKeyWithDifferentOrderIdsWritesBothAndPreservesFirstLink() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            val noticeDao = database.processedNoticeDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = noticeDao,
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val timestamp = 1_800_000_002_100L
            val recordFingerprint = requireNotNull(
                NoticeFingerprint.forSourceEvent("com.tencent.mm", "reused-fixture-key"),
            )
            val first = repository.insertNotificationIfNotReplayed(
                fingerprint = recordFingerprint,
                source = "com.tencent.mm",
                seenMs = timestamp,
                transaction = bill(
                    "WECHAT", 1_250L, "Fixture shop A", timestamp,
                    sourceTransactionId = "FIXTURE-ORDER-A",
                ),
                diagnosticInput = TransactionDiagnosticInput("key-reuse-order-a"),
            )
            val firstId = (first as NotificationInsertResult.Processed).let {
                (it.ledgerResult as LedgerInsertResult.Inserted).transactionId
            }
            val second = repository.insertNotificationIfNotReplayed(
                fingerprint = recordFingerprint,
                source = "com.tencent.mm",
                seenMs = timestamp + 1L,
                transaction = bill(
                    "WECHAT", 1_250L, "Fixture shop A", timestamp,
                    sourceTransactionId = "FIXTURE-ORDER-B",
                ),
                diagnosticInput = TransactionDiagnosticInput("key-reuse-order-b"),
            )

            assertTrue("a distinct exact order ID must not replay-suppress", second is NotificationInsertResult.Processed)
            val secondId = (second as NotificationInsertResult.Processed).let {
                (it.ledgerResult as LedgerInsertResult.Inserted).transactionId
            }
            assertEquals(2, transactionDao.recentForScreenshotDedup(100).size)
            assertEquals("the notification record keeps its first ledger link", firstId, noticeDao.find(recordFingerprint)?.transactionId)
            assertEquals(
                secondId,
                noticeDao.find(requireNotNull(NoticeFingerprint.forPlatformTransaction("WECHAT", "FIXTURE-ORDER-B")))?.transactionId,
            )
        } finally {
            database.close()
        }
    }

    @Test fun exactPlatformOrderIdRemainsIdempotentAcrossNotificationBodyRefresh() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            val noticeDao = database.processedNoticeDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = noticeDao,
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val timestamp = 1_800_000_002_200L
            val first = repository.insertNotificationIfNotReplayed(
                fingerprint = requireNotNull(
                    NoticeFingerprint.forSourceEvent("com.tencent.mm", "refresh-fixture-key"),
                ),
                source = "com.tencent.mm",
                seenMs = timestamp,
                transaction = bill(
                    "WECHAT", 1_250L, "Fixture shop", timestamp,
                    sourceTransactionId = "FIXTURE-ORDER-REFRESH",
                ),
                diagnosticInput = TransactionDiagnosticInput("order-refresh-original"),
            )
            val insertedId = (first as NotificationInsertResult.Processed).let {
                (it.ledgerResult as LedgerInsertResult.Inserted).transactionId
            }
            val refreshed = repository.insertNotificationIfNotReplayed(
                fingerprint = requireNotNull(
                    NoticeFingerprint.forSourceEvent("com.tencent.mm", "refresh-fixture-key"),
                ),
                source = "com.tencent.mm",
                seenMs = timestamp + 10_000L,
                transaction = bill(
                    "WECHAT", 1_250L, "Refreshed fixture body", timestamp + 10_000L,
                    sourceTransactionId = "FIXTURE-ORDER-REFRESH",
                ),
                diagnosticInput = TransactionDiagnosticInput("order-refresh-updated"),
            )

            assertEquals(NotificationInsertResult.Replayed(insertedId), refreshed)
            assertEquals(1, transactionDao.recentForScreenshotDedup(100).size)
            assertEquals(
                insertedId,
                noticeDao.find(requireNotNull(NoticeFingerprint.forPlatformTransaction("WECHAT", "FIXTURE-ORDER-REFRESH")))?.transactionId,
            )
        } finally {
            database.close()
        }
    }

    @Test fun idlessSmsUsesNonLossyWritesInsteadOfContentTupleReplaySuppression() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            val noticeDao = database.processedNoticeDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = noticeDao,
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val timestamp = 1_800_000_002_500L
            val weakLegacyTuple = NoticeFingerprint.of("BANK_SMS", "fixture-bank", "扣款 12.00", timestamp)
            repeat(2) { index ->
                val result = repository.insertNotificationIfNotReplayed(
                    fingerprint = null,
                    source = "BANK_SMS",
                    seenMs = timestamp + index,
                    transaction = bill("BANK_SMS", 1_200L, "Fixture store", timestamp),
                    diagnosticInput = TransactionDiagnosticInput("idless-sms-$index"),
                    legacyFingerprints = setOf(weakLegacyTuple),
                )
                assertTrue(result is NotificationInsertResult.Processed)
                assertTrue((result as NotificationInsertResult.Processed).ledgerResult is LedgerInsertResult.Inserted)
            }
            assertEquals("without a trustworthy message ID, similar events stay reviewable rows", 2, transactionDao.recentForScreenshotDedup(100).size)
            assertNull(noticeDao.find(weakLegacyTuple))
        } finally {
            database.close()
        }
    }

    @Test fun exactOrderIdCanProveReplayWithoutRewritingAmbiguousLegacyLink() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            val noticeDao = database.processedNoticeDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = noticeDao,
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val timestamp = 1_800_000_002_800L
            val legacy = NoticeFingerprint.of("BANK_SMS", "fixture-bank", "order 123", timestamp)
            noticeDao.insertIgnore(
                ProcessedNoticeEntity(legacy, "BANK_SMS", timestamp, timestamp, 1),
            )
            val existingId = transactionDao.insert(
                bill("BANK_SMS", 2_200L, "Fixture store", timestamp, sourceTransactionId = "exact-order-123"),
            )

            val result = repository.insertNotificationIfNotReplayed(
                fingerprint = requireNotNull(NoticeFingerprint.forSourceEvent("BANK_SMS", "sms-id-123")),
                source = "BANK_SMS",
                seenMs = timestamp + 10L,
                transaction = bill(
                    "BANK_SMS", 2_200L, "Fixture store", timestamp,
                    sourceTransactionId = "exact-order-123",
                ),
                legacyFingerprints = setOf(legacy),
            )

            assertEquals(NotificationInsertResult.Replayed(existingId), result)
            assertNull("the weak legacy tuple remains ambiguous", noticeDao.find(legacy)?.transactionId)
        } finally {
            database.close()
        }
    }

    @Test fun notificationAndOrdinaryWritesUseMutexBeforeRoomWithoutDeadlocking() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val transactionDao = database.transactionDao()
            val ordinaryReachedDao = CompletableDeferred<Unit>()
            val releaseOrdinaryDao = CompletableDeferred<Unit>()
            val gatedDao = object : TransactionDao by transactionDao {
                override suspend fun findBySourceTransactionId(
                    source: String,
                    sourceTransactionId: String,
                ): TransactionEntity? {
                    if (sourceTransactionId == "ordinary-lock-gate") {
                        ordinaryReachedDao.complete(Unit)
                        releaseOrdinaryDao.await()
                    }
                    return transactionDao.findBySourceTransactionId(source, sourceTransactionId)
                }
            }
            val repository = TransactionRepository(
                dao = gatedDao,
                processedNoticeDao = database.processedNoticeDao(),
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val timestamp = 1_800_000_003_000L
            val ordinary = async(Dispatchers.IO) {
                repository.insertIfNotDuplicateResult(
                    bill(
                        "MANUAL", 20_000L, "Fixture manual", timestamp,
                        sourceTransactionId = "ordinary-lock-gate",
                    ),
                    diagnosticInput = TransactionDiagnosticInput("lock-ordinary"),
                )
            }
            withTimeout(5_000L) { ordinaryReachedDao.await() }
            val notificationStarted = CompletableDeferred<Unit>()
            val notification = async(Dispatchers.IO) {
                notificationStarted.complete(Unit)
                repository.insertNotificationIfNotReplayed(
                    fingerprint = requireNotNull(
                        NoticeFingerprint.forSourceEvent("com.tencent.mm", "lock-event"),
                    ),
                    source = "com.tencent.mm",
                    seenMs = timestamp,
                    transaction = bill("WECHAT", 10_000L, "Fixture notice", timestamp),
                    diagnosticInput = TransactionDiagnosticInput("lock-notice"),
                )
            }
            notificationStarted.await()
            // Let the notification attempt to enter its transaction while the
            // ordinary insert owns the mutex at the DAO boundary.
            delay(100L)
            releaseOrdinaryDao.complete(Unit)

            val ordinaryResult = withTimeout(5_000L) { ordinary.await() }
            val notificationResult = withTimeout(5_000L) { notification.await() }
            assertTrue(ordinaryResult is LedgerInsertResult.Inserted)
            assertTrue(notificationResult is NotificationInsertResult.Processed)
            assertEquals(2, transactionDao.recentForScreenshotDedup(100).size)
        } finally {
            database.close()
        }
    }

    @Test fun notificationSyncQueuesOnlyAfterCommitAndOnceForInsertedOrMergedRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        val syncPrefs = context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE)
        val previousEnabled = syncPrefs.getBoolean("enabled", false)
        val previousUrl = syncPrefs.getString("url", null)
        val previousToken = syncPrefs.getString("token", null)
        val workManager = WorkManager.getInstance(context)
        try {
            workManager.cancelUniqueWork(TransactionSyncWorker.IMMEDIATE_WORK_NAME)
                .result.get(5, TimeUnit.SECONDS)
            workManager.pruneWork().result.get(5, TimeUnit.SECONDS)
            syncPrefs.edit()
                .putBoolean("enabled", true)
                .putString("url", "")
                .putString("token", "")
                .commit()
            val transactionDao = database.transactionDao()
            val repository = TransactionRepository(
                dao = transactionDao,
                processedNoticeDao = database.processedNoticeDao(),
                settings = SettingsPreferences(context),
                diagnostics = TransactionDiagnostics(database.transactionDiagnosticDao()),
                context = context,
                database = database,
            )
            val timestamp = 1_800_000_003_500L
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_notice_link BEFORE UPDATE OF transactionId ON processed_notices " +
                    "WHEN NEW.transactionId IS NOT NULL BEGIN SELECT RAISE(ABORT, 'fixture link failure'); END",
            )
            val rolledBack = repository.insertNotificationIfNotReplayed(
                fingerprint = requireNotNull(NoticeFingerprint.forSourceEvent("com.tencent.mm", "rollback-key")),
                source = "com.tencent.mm",
                seenMs = timestamp,
                transaction = bill("WECHAT", 3_500L, "Fixture rollback", timestamp),
                diagnosticInput = TransactionDiagnosticInput("post-commit-rollback"),
            )
            assertTrue(rolledBack is NotificationInsertResult.Failed)
            val afterRollback = workManager.getWorkInfosForUniqueWork(TransactionSyncWorker.IMMEDIATE_WORK_NAME)
                .get(5, TimeUnit.SECONDS)
            assertTrue("a rollback must not leave sync queued", afterRollback.none { it.state != WorkInfo.State.CANCELLED })
            assertEquals(0, transactionDao.recentForScreenshotDedup(100).size)

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_notice_link")
            val committed = repository.insertNotificationIfNotReplayed(
                fingerprint = requireNotNull(NoticeFingerprint.forSourceEvent("com.tencent.mm", "committed-key")),
                source = "com.tencent.mm",
                seenMs = timestamp + 1L,
                transaction = bill("WECHAT", 3_600L, "Fixture committed", timestamp + 1L),
                diagnosticInput = TransactionDiagnosticInput("post-commit-success"),
            )
            assertTrue(committed is NotificationInsertResult.Processed)
            val afterCommit = workManager.getWorkInfosForUniqueWork(TransactionSyncWorker.IMMEDIATE_WORK_NAME)
                .get(5, TimeUnit.SECONDS)
            assertEquals(
                "one committed insert queues exactly one live sync job",
                1,
                afterCommit.count { it.state != WorkInfo.State.CANCELLED },
            )

            workManager.cancelUniqueWork(TransactionSyncWorker.IMMEDIATE_WORK_NAME)
                .result.get(5, TimeUnit.SECONDS)
            workManager.pruneWork().result.get(5, TimeUnit.SECONDS)
            transactionDao.insert(
                bill(
                    "WECHAT", 3_700L, "Fixture merge", timestamp + 2L,
                    sourceTransactionId = " order-for-merge ",
                ),
            )
            val merged = repository.insertNotificationIfNotReplayed(
                fingerprint = requireNotNull(NoticeFingerprint.forSourceEvent("com.tencent.mm", "merged-key")),
                source = "com.tencent.mm",
                seenMs = timestamp + 2L,
                transaction = bill(
                    "WECHAT", 3_700L, "Fixture merge", timestamp + 2L,
                    sourceTransactionId = "order-for-merge",
                ),
                diagnosticInput = TransactionDiagnosticInput("post-commit-merge"),
            )
            assertTrue(
                "a committed merge also requests exactly one sync",
                merged is NotificationInsertResult.Processed && merged.ledgerResult is LedgerInsertResult.Merged,
            )
            val afterMerge = workManager.getWorkInfosForUniqueWork(TransactionSyncWorker.IMMEDIATE_WORK_NAME)
                .get(5, TimeUnit.SECONDS)
            assertEquals(1, afterMerge.count { it.state != WorkInfo.State.CANCELLED })
        } finally {
            workManager.cancelUniqueWork(TransactionSyncWorker.IMMEDIATE_WORK_NAME)
                .result.get(5, TimeUnit.SECONDS)
            workManager.pruneWork().result.get(5, TimeUnit.SECONDS)
            syncPrefs.edit()
                .putBoolean("enabled", previousEnabled)
                .putString("url", previousUrl)
                .putString("token", previousToken)
                .commit()
            database.close()
        }
    }

    @Test fun billFilterIncludesRetainedEventsSharingTheLinkedAttemptId() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val dao = database.transactionDiagnosticDao()
            val cutoff = 100L
            dao.insert(event("pre-save", "attempt-77", null, 101L))
            dao.insert(event("saved", "attempt-77", 77L, 102L))
            dao.insert(event("other-unlinked", "attempt-other", null, 103L))
            dao.insert(event("other-bill", "attempt-88", 88L, 104L))
            dao.insert(event("expired-link", "attempt-expired", 77L, cutoff - 1))
            dao.insert(event("expired-only-attempt", "attempt-expired", null, 105L))

            assertEquals(
                listOf("saved", "pre-save"),
                dao.observeEvents(cutoff, 77L, "", 100, 0).first().map { it.eventId },
            )
        } finally {
            database.close()
        }
    }

    @Test fun numericBillSearchFindsIdsBeyondChipLimitAndTheirUnlinkedAttemptEvents() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val dao = database.transactionDiagnosticDao()
            val cutoff = 100L
            dao.insert(event("pre-save-old-id", "attempt-1", null, 101L))
            dao.insert(event("saved-old-id", "attempt-1", 1L, 102L))
            (2L..202L).forEach { id ->
                dao.insert(event("saved-$id", "attempt-$id", id, 100L + id))
            }

            assertFalse(dao.observeRecentTransactionIds(cutoff, 200).first().contains(1L))
            assertEquals(
                listOf("saved-old-id", "pre-save-old-id"),
                dao.observeEvents(cutoff, null, "1", 100, 0).first()
                    .filter { it.attemptId == "attempt-1" }
                    .map { it.eventId },
            )
        } finally {
            database.close()
        }
    }

    @Test fun sameMillisecondEventsAreOrderedByInsertionNotRandomEventId() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            BookkeepingDatabase::class.java,
        ).build()
        try {
            val dao = database.transactionDiagnosticDao()
            val sameTime = 1_700_000_000_000L
            dao.insert(event("z-id", "attempt", null, sameTime))
            dao.insert(event("a-id", "attempt", null, sameTime))
            dao.insert(event("m-id", "attempt", null, sameTime))

            assertEquals(
                listOf("m-id", "a-id", "z-id"),
                dao.observeEvents(0L, null, "", 100, 0).first().map { it.eventId },
            )
        } finally {
            database.close()
        }
    }

    @Test fun migration8To9PreservesTransactionsNoticesAndSyncPreferencesWithoutBackfill() = runBlocking {
        val databaseName = "diagnostic-migration-${UUID.randomUUID()}.db"
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        val syncPreferences = context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE)
        val syncPreferencesBefore = syncPreferences.all.toMap()

        val oldDatabase = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            oldDatabase.execSQL("""CREATE TABLE transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                amountCents INTEGER NOT NULL,
                type TEXT NOT NULL,
                category TEXT NOT NULL,
                merchant TEXT NOT NULL,
                note TEXT NOT NULL,
                source TEXT NOT NULL,
                account TEXT,
                timestamp INTEGER NOT NULL,
                currency TEXT NOT NULL DEFAULT 'CNY',
                remoteEventId TEXT,
                remoteRevision INTEGER NOT NULL DEFAULT 0,
                syncDirty INTEGER NOT NULL DEFAULT 0,
                syncDeleted INTEGER NOT NULL DEFAULT 0,
                syncModifiedMs INTEGER NOT NULL DEFAULT 0,
                remoteReviewStatus TEXT NOT NULL DEFAULT 'unverified',
                syncMutationId TEXT,
                duplicateOfId INTEGER,
                merchantIconKey TEXT,
                merchantIconPath TEXT,
                sourceTransactionId TEXT,
                captureConfidence REAL,
                captureKind TEXT,
                captureObservedAtMs INTEGER
            )""")
            oldDatabase.execSQL("CREATE UNIQUE INDEX index_transactions_remoteEventId ON transactions(remoteEventId)")
            oldDatabase.execSQL("CREATE INDEX index_transactions_sourceTransactionId ON transactions(sourceTransactionId)")
            oldDatabase.execSQL("""CREATE TABLE processed_notices (
                fingerprint TEXT NOT NULL PRIMARY KEY,
                source TEXT NOT NULL,
                firstSeenMs INTEGER NOT NULL,
                lastSeenMs INTEGER NOT NULL,
                seenCount INTEGER NOT NULL,
                transactionId INTEGER
            )""")
            oldDatabase.execSQL("""INSERT INTO transactions (
                id, amountCents, type, category, merchant, note, source, account, timestamp,
                currency, remoteEventId, remoteRevision, syncDirty, syncDeleted,
                syncModifiedMs, remoteReviewStatus, syncMutationId, sourceTransactionId
            ) VALUES (
                501, 2599, 'EXPENSE', 'FOOD', 'Migration Test Shop', 'local note', 'WECHAT',
                'WeChat', 1700000000000, 'CNY', 'remote-event-501', 13, 1, 0,
                1700000000123, 'confirmed', 'mutation-501', 'order-501'
            )""")
            oldDatabase.execSQL("""INSERT INTO processed_notices
                (fingerprint, source, firstSeenMs, lastSeenMs, seenCount, transactionId)
                VALUES ('notice-hash', 'WECHAT', 100, 200, 3, 501)""")
            oldDatabase.version = 8
        } finally {
            oldDatabase.close()
        }

        val database = Room.databaseBuilder(context, BookkeepingDatabase::class.java, databaseName)
            .addMigrations(BookkeepingDatabase.MIGRATION_8_9)
            .build()
        try {
            val transaction = database.transactionDao().getById(501L)
            assertNotNull(transaction)
            assertEquals("remote-event-501", transaction?.remoteEventId)
            assertEquals(13L, transaction?.remoteRevision)
            assertTrue(transaction?.syncDirty == true)
            assertEquals("mutation-501", transaction?.syncMutationId)

            val migrated = database.openHelper.readableDatabase
            migrated.query("SELECT fingerprint, seenCount, transactionId FROM processed_notices").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("notice-hash", cursor.getString(0))
                assertEquals(3, cursor.getInt(1))
                assertEquals(501L, cursor.getLong(2))
            }
            migrated.query("SELECT COUNT(*) FROM transaction_diagnostic_events").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("PRAGMA table_info(transaction_diagnostic_events)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(1)
                assertEquals(
                    setOf("eventId", "attemptId", "transactionId", "occurredAtMs", "stage", "result", "details"),
                    columns,
                )
            }
            migrated.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(9, cursor.getInt(0))
            }
            assertEquals(syncPreferencesBefore, syncPreferences.all.toMap())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun bill(
        source: String,
        amountCents: Long,
        merchant: String,
        timestamp: Long,
        sourceTransactionId: String? = null,
    ) = TransactionEntity(
        amountCents = amountCents,
        type = "EXPENSE",
        category = "FOOD",
        merchant = merchant,
        source = source,
        timestamp = timestamp,
        sourceTransactionId = sourceTransactionId,
    )

    private fun event(
        eventId: String,
        attemptId: String,
        transactionId: Long?,
        occurredAtMs: Long,
    ) = TransactionDiagnosticEventEntity(
        eventId = eventId,
        attemptId = attemptId,
        transactionId = transactionId,
        occurredAtMs = occurredAtMs,
        stage = TransactionDiagnostics.STAGE_PARSE,
        result = TransactionDiagnostics.RESULT_FAILURE,
        details = "parse failed",
    )
}
