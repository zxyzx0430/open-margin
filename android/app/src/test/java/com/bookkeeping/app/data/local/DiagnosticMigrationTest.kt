package com.bookkeeping.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v10 -> v11 adds three nullable TEXT columns to the diagnostics table;
 * v11 -> v12 adds one more (rawText, the verbatim input).
 *
 * Room's own migration runner (`MigrationTestHelper`) needs an instrumented
 * device, and this project's CI only runs `testDebugUnitTest`, so the migration
 * statement list is executed here against a recording [SupportSQLiteDatabase]
 * proxy instead. That proves the exact SQL and that nothing destructive is in
 * it; the real Room upgrade path stays an on-device (androidTest) check.
 */
class DiagnosticMigrationTest {

    private fun execSqlOf(migration: Migration): List<String> {
        val statements = mutableListOf<String>()
        val recorder = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    statements += args?.firstOrNull() as? String ?: ""
                    null
                }
                "toString" -> "RecordingSupportSQLiteDatabase"
                "hashCode" -> System.identityHashCode(statements)
                "equals" -> false
                else -> null
            }
        } as SupportSQLiteDatabase
        migration.migrate(recorder)
        return statements
    }

    @Test
    fun `migration 10 to 11 adds the three readable columns as nullable text`() {
        val statements = execSqlOf(BookkeepingDatabase.MIGRATION_10_11)

        assertEquals(
            listOf(
                "ALTER TABLE transaction_diagnostic_events ADD COLUMN callSite TEXT",
                "ALTER TABLE transaction_diagnostic_events ADD COLUMN inputSummary TEXT",
                "ALTER TABLE transaction_diagnostic_events ADD COLUMN outputSummary TEXT",
            ),
            statements,
        )
        // Additive only: no "NOT NULL DEFAULT ''" backfill, so old rows stay NULL
        // and the screen can tell "written before v11" apart from an empty string.
        statements.forEach { sql ->
            assertFalse(sql.contains("NOT NULL"))
            assertFalse(sql.contains("DEFAULT"))
        }
    }

    @Test
    fun `migration never drops rewrites or clears existing diagnostics`() {
        val statements = execSqlOf(BookkeepingDatabase.MIGRATION_10_11).map { it.uppercase() }

        statements.forEach { sql ->
            assertFalse(sql.contains("DROP"))
            assertFalse(sql.contains("DELETE"))
            assertFalse(sql.contains("CREATE TABLE"))
            assertFalse(sql.contains("RENAME"))
        }
        assertTrue(statements.all { it.startsWith("ALTER TABLE TRANSACTION_DIAGNOSTIC_EVENTS ADD COLUMN") })
    }

    @Test
    fun `the shared builder declares version 12 and registers the migration`() {
        val source = databaseSource()
        assertTrue(
            "BookkeepingDatabase must declare version = 12",
            Regex("""version\s*=\s*12""").containsMatchIn(source),
        )
        assertTrue(
            "MIGRATION_11_12 must be part of the addMigrations chain",
            Regex("""addMigrations\([^)]*MIGRATION_11_12""").containsMatchIn(source),
        )
        assertTrue(
            "MIGRATION_11_12 must exist",
            Regex("""val MIGRATION_11_12\s*=\s*object\s*:\s*Migration\(11,\s*12\)""").containsMatchIn(source),
        )
        assertTrue(
            "MIGRATION_10_11 must stay registered (users may still be on v10)",
            Regex("""addMigrations\([^)]*MIGRATION_10_11[^)]*MIGRATION_11_12""").containsMatchIn(source),
        )
        // A destructive fallback would erase the user's unsynced ledger rows.
        assertFalse(source.contains("fallbackToDestructiveMigration"))
    }

    @Test
    fun `migration 11 to 12 adds one nullable raw text column`() {
        val statements = execSqlOf(BookkeepingDatabase.MIGRATION_11_12)

        assertEquals(
            listOf("ALTER TABLE transaction_diagnostic_events ADD COLUMN rawText TEXT"),
            statements,
        )
        statements.forEach { sql ->
            assertFalse("rawText must stay nullable", sql.contains("NOT NULL"))
            assertFalse("old rows must not be backfilled with a placeholder", sql.contains("DEFAULT"))
        }
    }

    @Test
    fun `migration 11 to 12 never drops or rewrites rows`() {
        val statements = execSqlOf(BookkeepingDatabase.MIGRATION_11_12).map { it.uppercase() }

        statements.forEach { sql ->
            assertFalse(sql.contains("DROP"))
            assertFalse(sql.contains("DELETE"))
            assertFalse(sql.contains("CREATE TABLE"))
            assertFalse(sql.contains("RENAME"))
            assertFalse(sql.contains("UPDATE"))
        }
        assertTrue(statements.all { it.startsWith("ALTER TABLE TRANSACTION_DIAGNOSTIC_EVENTS ADD COLUMN") })
    }

    @Test
    fun `rows written before v11 keep their seven fields and leave the new ones null`() {
        val legacy = TransactionDiagnosticEventEntity(
            eventId = "event-1",
            attemptId = "attempt-1",
            transactionId = 42L,
            occurredAtMs = 1_800_000_000_000L,
            stage = "save",
            result = "success",
            details = "Transaction row inserted",
        )

        assertEquals("save", legacy.stage)
        assertEquals(42L, legacy.transactionId)
        assertNull(legacy.callSite)
        assertNull(legacy.inputSummary)
        assertNull(legacy.outputSummary)
    }

    /** Unit tests run with the module directory as CWD, but walk up if they don't. */
    private fun databaseSource(): String {
        val relative = "src/main/java/com/bookkeeping/app/data/local/BookkeepingDatabase.kt"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("BookkeepingDatabase.kt not found from ${File(".").absolutePath}")
        return file.readText()
    }
}
