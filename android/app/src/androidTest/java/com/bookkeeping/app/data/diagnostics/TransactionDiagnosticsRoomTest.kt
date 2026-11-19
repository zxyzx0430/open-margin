package com.bookkeeping.app.data.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.sync.TransactionSyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransactionDiagnosticsRoomTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

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

    @Test fun duplicateSuppressionsAreVisibleUnderTheMatchedBill() = runBlocking {
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

            val exactMatchedId = transactionDao.insert(
                bill(
                    source = "ALIPAY",
                    amountCents = 2_500L,
                    merchant = "Fixture cafe",
                    timestamp = timestamp + 20_000L,
                ),
            )
            assertNull(
                repository.insertIfNotDuplicate(
                    bill(
                        source = "ALIPAY",
                        amountCents = 2_500L,
                        merchant = "Fixture cafe",
                        timestamp = timestamp + 20_001L,
                    ),
                    diagnosticInput = TransactionDiagnosticInput("exact-match-attempt"),
                ),
            )
            val exactEvents = diagnosticDao.observeEvents(0L, exactMatchedId, "", 100, 0).first()
            val exactSuppression = exactEvents.single {
                it.attemptId == "exact-match-attempt" &&
                    it.details == "Exact duplicate candidate skipped"
            }
            assertEquals(exactMatchedId, exactSuppression.transactionId)
        } finally {
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
