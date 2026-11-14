package com.bookkeeping.app

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.bookkeeping.app.data.budget.BudgetMonthPolicy
import com.bookkeeping.app.data.local.entity.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetMonthPolicyTest {

    @Test fun legacyFallbackOnlyBeforeMigration() {
        val prefs = mutablePreferencesOf()
        prefs[BudgetMonthPolicy.legacyTotalKey()] = 100_000L
        prefs[BudgetMonthPolicy.legacyCategoryKey(TransactionCategory.FOOD)] = 25_000L

        val before = BudgetMonthPolicy.settingsWithLegacyFallback(prefs, "2026-11", "2026-11")
        assertEquals(100_000L, before.monthlyTotalCents)
        assertEquals(25_000L, before.perCategory[TransactionCategory.FOOD])

        prefs[BudgetMonthPolicy.MIGRATED] = true
        val after = BudgetMonthPolicy.settingsWithLegacyFallback(prefs, "2026-11", "2026-11")
        assertEquals(0L, after.monthlyTotalCents)
        assertTrue(after.perCategory.isEmpty())
    }

    @Test fun scopedValuesWinAndMonthsAreIsolated() {
        val prefs = mutablePreferencesOf()
        prefs[BudgetMonthPolicy.totalKey("2026-11")] = 120_000L
        prefs[BudgetMonthPolicy.totalKey("2026-12")] = 220_000L
        prefs[BudgetMonthPolicy.categoryKey("2026-11", TransactionCategory.FOOD)] = 30_000L
        prefs[BudgetMonthPolicy.MIGRATED] = true

        val nov = BudgetMonthPolicy.settings(prefs, "2026-11")
        assertEquals(120_000L, nov.monthlyTotalCents)
        assertEquals(30_000L, nov.perCategory[TransactionCategory.FOOD])

        val dec = BudgetMonthPolicy.settings(prefs, "2026-12")
        assertEquals(220_000L, dec.monthlyTotalCents)
        assertTrue(dec.perCategory.isEmpty())
    }

    @Test fun migrationCopiesLegacyValuesWithoutSalary() {
        val prefs = mutablePreferencesOf()
        prefs[BudgetMonthPolicy.legacyTotalKey()] = 90_000L
        prefs[BudgetMonthPolicy.legacyCategoryKey(TransactionCategory.FOOD)] = 20_000L
        prefs[BudgetMonthPolicy.legacyCategoryKey(TransactionCategory.SALARY)] = 999_000L

        val migrated = BudgetMonthPolicy.migratedValues(prefs, "2026-11")
        assertEquals(90_000L, migrated[BudgetMonthPolicy.totalKey("2026-11")])
        assertEquals(20_000L, migrated[BudgetMonthPolicy.categoryKey("2026-11", TransactionCategory.FOOD)])
        assertFalse(migrated.containsKey(BudgetMonthPolicy.categoryKey("2026-11", TransactionCategory.SALARY)))
    }

    @Test fun migrationDoesNotOverwriteExistingScopedValues() {
        val prefs = mutablePreferencesOf()
        prefs[BudgetMonthPolicy.legacyTotalKey()] = 90_000L
        prefs[BudgetMonthPolicy.totalKey("2026-11")] = 50_000L

        val migrated = BudgetMonthPolicy.migratedValues(prefs, "2026-11")
        assertFalse(migrated.containsKey(BudgetMonthPolicy.totalKey("2026-11")))
    }

    @Test fun markerPreventsRepeatedMigration() {
        val prefs = mutablePreferencesOf()
        prefs[BudgetMonthPolicy.MIGRATED] = true
        prefs[BudgetMonthPolicy.legacyTotalKey()] = 90_000L
        assertTrue(BudgetMonthPolicy.migratedValues(prefs, "2026-11").isEmpty())
    }

    @Test fun zeroOrNegativeValuesAreTreatedAsUnset() {
        val prefs = mutablePreferencesOf()
        prefs[BudgetMonthPolicy.totalKey("2026-11")] = 0L
        prefs[BudgetMonthPolicy.categoryKey("2026-11", TransactionCategory.FOOD)] = -1L
        val settings = BudgetMonthPolicy.settings(prefs, "2026-11")
        assertEquals(0L, settings.monthlyTotalCents)
        assertTrue(settings.perCategory.isEmpty())
    }

    @Test fun intentionalClearDoesNotResurrectLegacyBeforeMarker() {
        val prefs = mutablePreferencesOf()
        prefs[BudgetMonthPolicy.legacyTotalKey()] = 90_000L
        prefs[BudgetMonthPolicy.totalKey("2026-11")] = 0L
        val settings = BudgetMonthPolicy.settingsWithLegacyFallback(prefs, "2026-11", "2026-11")
        assertEquals(0L, settings.monthlyTotalCents)
        assertTrue(settings.perCategory.isEmpty())
    }
}
