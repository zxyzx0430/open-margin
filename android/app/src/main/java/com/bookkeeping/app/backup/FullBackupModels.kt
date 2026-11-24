package com.bookkeeping.app.backup

import com.bookkeeping.app.data.budget.BudgetSettings
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import java.io.File

data class EvidenceLink(
    val timestampMs: Long,
    val amountCents: Long,
    val type: String,
    val merchant: String,
    val evidenceImageName: String,
)

data class BackupManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String,
    val exportedAtMs: Long,
    val includesEvidence: Boolean,
    val transactionCount: Int,
    val subscriptionCount: Int,
    val budgetMonthCount: Int,
    val merchantRuleCount: Int,
    val settingsCount: Int,
    val evidenceCount: Int,
    val checksums: Map<String, String> = emptyMap(),
    val evidenceLinks: List<EvidenceLink> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class BackupPreview(
    val manifest: BackupManifest,
    val newTransactionCount: Int,
    val duplicateTransactionCount: Int,
    val skippedTransactionCount: Int = 0,
    val errorTransactionCount: Int = 0,
    val newSubscriptionCount: Int,
    val updatedSubscriptionCount: Int = 0,
    val duplicateSubscriptionCount: Int,
    val budgetMonthCount: Int,
    val merchantRuleCount: Int,
    val settingsCount: Int,
    val evidenceFileCount: Int,
    val newEvidenceFileCount: Int,
    val rejectedUnsafeEntries: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    val estimatedNewTotal: Int
        get() = newTransactionCount + newSubscriptionCount + updatedSubscriptionCount + newEvidenceFileCount

    val estimatedDuplicateOrSkippedTotal: Int
        get() = duplicateTransactionCount +
            skippedTransactionCount +
            duplicateSubscriptionCount +
            (evidenceFileCount - newEvidenceFileCount).coerceAtLeast(0)
}

data class BackupRestoreOptions(
    val restoreTransactions: Boolean = true,
    val restoreSubscriptions: Boolean = true,
    val restoreBudgets: Boolean = true,
    val restoreMerchantRules: Boolean = true,
    val restoreSettings: Boolean = true,
    val restoreEvidence: Boolean = true,
    val overwriteExistingConfigs: Boolean = false,
) {
    val hasAnySelected: Boolean
        get() = restoreTransactions ||
            restoreSubscriptions ||
            restoreBudgets ||
            restoreMerchantRules ||
            restoreSettings ||
            restoreEvidence
}

data class BackupRestoreResult(
    val insertedTransactions: Int = 0,
    val skippedDuplicateTransactions: Int = 0,
    val insertedSubscriptions: Int = 0,
    val updatedSubscriptions: Int = 0,
    val skippedSubscriptions: Int = 0,
    val restoredBudgetMonths: Int = 0,
    val skippedBudgetMonths: Int = 0,
    val restoredMerchantRules: Int = 0,
    val skippedMerchantRules: Int = 0,
    val restoredSettingsCount: Int = 0,
    val skippedSettingsCount: Int = 0,
    val restoredEvidenceCount: Int = 0,
    val skippedEvidenceCount: Int = 0,
    val rejectedUnsafeEntries: Int = 0,
)

data class BudgetMonthBackup(
    val month: String,
    val monthlyTotalCents: Long,
    val perCategory: Map<String, Long>,
)

data class MerchantRulesBackup(
    val rules: Map<String, String> = emptyMap(),
    val learningCounts: Map<String, Map<String, Int>> = emptyMap(),
    val learningDisabled: List<String> = emptyList(),
)

data class SettingsWriteResult(
    val appliedCount: Int,
    val skippedCount: Int,
)

/**
 * Storage abstraction so [FullBackupManager] can run against Room/DataStore on Android
 * and pure in-memory fakes in JVM unit tests.
 */
interface BackupStorage {
    suspend fun readTransactions(): List<TransactionEntity>
    suspend fun insertTransactions(inserts: List<TransactionEntity>): Int
    suspend fun updateTransactions(updates: List<TransactionEntity>): Int

    suspend fun readSubscriptions(): List<SubscriptionEntity>
    suspend fun upsertSubscription(subscription: SubscriptionEntity)

    suspend fun readBudgets(transactionMonths: Set<String>): List<BudgetMonthBackup>
    suspend fun writeBudget(month: String, settings: BudgetSettings, overwrite: Boolean): Boolean

    suspend fun readMerchantRules(): MerchantRulesBackup
    suspend fun writeMerchantRules(data: MerchantRulesBackup, overwrite: Boolean): Pair<Int, Int>

    suspend fun readNonSensitiveSettings(): Map<String, Any?>
    suspend fun writeNonSensitiveSettings(
        settings: Map<String, Any?>,
        overwrite: Boolean,
    ): SettingsWriteResult

    fun listEvidenceFiles(): Map<String, File>
    fun evidenceTargetDir(): File
}
