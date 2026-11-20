package com.bookkeeping.app.data.diagnostics

import com.bookkeeping.app.data.local.dao.TransactionDiagnosticDao
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class TransactionDiagnosticRetentionTest {
    @Test fun eventsExactlySevenDaysOldAreRetainedAndOlderEventsExpire() {
        val nowMs = 1_800_000_000_000L
        val cutoff = nowMs - 7L * 24L * 60L * 60L * 1_000L

        assertEquals(cutoff, TransactionDiagnosticRetention.cutoffMs(nowMs))
        assertFalse(TransactionDiagnosticRetention.isExpired(cutoff, nowMs))
        assertFalse(TransactionDiagnosticRetention.isExpired(cutoff + 1, nowMs))
        assertTrue(TransactionDiagnosticRetention.isExpired(cutoff - 1, nowMs))
    }

    @Test fun pruneFailureDoesNotPreventWritingCurrentDiagnosticEvent() = runBlocking {
        val dao = PruneFailingDiagnosticDao()

        TransactionDiagnostics(dao).record(
            attemptId = "attempt-current",
            stage = TransactionDiagnostics.STAGE_PARSE,
            result = TransactionDiagnostics.RESULT_FAILURE,
        )

        assertEquals(1, dao.insertCalls)
        assertEquals("attempt-current", dao.insertedEvent?.attemptId)
    }

    @Test fun eventEntityContainsOnlyDiagnosticMetadataFields() {
        val fields = TransactionDiagnosticEventEntity::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        // v11 added three nullable explanation columns. The guard stays hard: the
        // table is still metadata-only, and nothing may add a raw payload column
        // (screenshot bytes, OCR text, audio, notification body, import file).
        assertEquals(
            setOf(
                "eventId", "attemptId", "transactionId", "occurredAtMs", "stage", "result", "details",
                "callSite", "inputSummary", "outputSummary",
            ),
            fields,
        )
        listOf("callSite", "inputSummary", "outputSummary").forEach { name ->
            val field = TransactionDiagnosticEventEntity::class.java.getDeclaredField(name)
            assertEquals("$name must be nullable text", String::class.java, field.type)
            assertFalse("$name must not be a primitive", field.type.isPrimitive)
        }
    }

    @Test fun summaryColumnsAreClippedAndNeverCarryRawPayloads() {
        // Nothing may be stored unbounded: one caller mistake must not turn a row
        // into a text dump of the page.
        val long = "x".repeat(400)
        assertEquals(DiagnosticSummary.MAX_LENGTH, DiagnosticSummary.clip(long)!!.length)
        assertEquals(null, DiagnosticSummary.clip("   "))
        assertEquals(null, DiagnosticSummary.clip(null))
    }
}

private class PruneFailingDiagnosticDao : TransactionDiagnosticDao {
    var insertCalls = 0
    var insertedEvent: TransactionDiagnosticEventEntity? = null

    override suspend fun insert(event: TransactionDiagnosticEventEntity) {
        insertCalls++
        insertedEvent = event
    }

    override suspend fun pruneBefore(cutoffMs: Long): Int =
        throw IllegalStateException("prune failed")

    override fun observeEvents(
        cutoffMs: Long,
        transactionId: Long?,
        query: String,
        limit: Int,
        offset: Int,
    ): Flow<List<TransactionDiagnosticEventEntity>> = emptyFlow()

    override fun observeRecentTransactionIds(cutoffMs: Long, limit: Int): Flow<List<Long>> = emptyFlow()
}
