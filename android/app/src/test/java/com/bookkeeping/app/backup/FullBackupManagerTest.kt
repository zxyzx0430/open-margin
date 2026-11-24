package com.bookkeeping.app.backup

import com.bookkeeping.app.data.budget.BudgetSettings
import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.merchant.MerchantRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FullBackupManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val tz = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun roundTripExportInspectAndRestoreIntoEmptyStorage() = runBlocking {
        val sourceEvidenceDir = tempFolder.newFolder("source_evidence")
        val evidenceName = "ev_${"a".repeat(64)}.webp"
        val evidenceFile = File(sourceEvidenceDir, evidenceName).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        }

        val sourceStorage = FakeBackupStorage(evidenceDir = sourceEvidenceDir).apply {
            transactions += listOf(
                TransactionEntity(
                    id = 1L,
                    amountCents = 3_650L,
                    type = TransactionType.EXPENSE.name,
                    category = TransactionCategory.FOOD.name,
                    merchant = "瑞幸咖啡",
                    note = "生椰拿铁",
                    source = TransactionSource.WECHAT.name,
                    account = "微信",
                    timestamp = 1_732_420_000_000L,
                    evidenceImageName = evidenceName,
                ),
                TransactionEntity(
                    id = 2L,
                    amountCents = 800_000L,
                    type = TransactionType.INCOME.name,
                    category = TransactionCategory.SALARY.name,
                    merchant = "某科技公司",
                    note = "11月工资",
                    source = TransactionSource.BANK_SMS.name,
                    account = "招商银行",
                    timestamp = 1_732_330_000_000L,
                ),
            )
            subscriptions += SubscriptionEntity(
                id = 10L,
                syncUuid = "sub-uuid-1",
                wallosId = 42L,
                name = "ChatGPT Plus",
                amountCents = 1999L,
                currency = "USD",
                cnyEstimateCents = 14_500L,
                cadence = SubscriptionCadence.MONTHLY,
                nextDueMs = 1_733_000_000_000L,
                paymentChannel = "银行卡",
                autoRenew = true,
                pendingCancel = false,
                notifyEnabled = true,
                notifyDaysBefore = 3,
                active = true,
                note = "AI 订阅",
                updatedAtMs = 1_732_400_000_000L,
            )
            budgets["2026-11"] = BudgetSettings(
                monthlyTotalCents = 500_000L,
                perCategory = mapOf(TransactionCategory.FOOD to 150_000L),
            )
            merchantRules = MerchantRulesBackup(
                rules = mapOf("瑞幸咖啡" to TransactionCategory.FOOD.name),
                learningCounts = mapOf("麦当劳" to mapOf(TransactionCategory.FOOD.name to 3)),
                learningDisabled = listOf("滴滴"),
            )
            settings = mutableMapOf(
                FullBackupManager.SETTING_LANGUAGE to "zh",
                FullBackupManager.SETTING_KEEP_ALIVE to true,
                FullBackupManager.SETTING_SUCCESS_NOTIFICATIONS to false,
                FullBackupManager.SETTING_EVIDENCE_RETENTION_MODE to "forever",
                FullBackupManager.SETTING_CALENDAR_HEATMAP to "NET",
                FullBackupManager.SETTING_CALENDAR_WEEK_START_MONDAY to true,
                FullBackupManager.SETTING_CALENDAR_SHOW_LUNAR to false,
                FullBackupManager.SETTING_CALENDAR_NUMBER_MODE to "EXPENSE",
            )
            evidenceFiles[evidenceName] = evidenceFile
        }

        val exporter = FullBackupManager(
            storage = sourceStorage,
            appVersion = "1.46",
            timeZone = tz,
            nowProvider = { 1_732_425_000_000L },
        )

        val zipOut = ByteArrayOutputStream()
        val manifest = exporter.exportToZip(zipOut, includeEvidence = true)
        val zipBytes = zipOut.toByteArray()

        assertEquals(1, manifest.schemaVersion)
        assertEquals("1.46", manifest.appVersion)
        assertTrue(manifest.includesEvidence)
        assertEquals(2, manifest.transactionCount)
        assertEquals(1, manifest.subscriptionCount)
        assertEquals(1, manifest.budgetMonthCount)
        assertEquals(1, manifest.merchantRuleCount)
        assertEquals(8, manifest.settingsCount)
        assertEquals(1, manifest.evidenceCount)

        val targetEvidenceDir = tempFolder.newFolder("target_evidence")
        val targetStorage = FakeBackupStorage(evidenceDir = targetEvidenceDir)
        val restorer = FullBackupManager(
            storage = targetStorage,
            appVersion = "1.46",
            timeZone = tz,
        )

        val preview = restorer.inspectZip(ByteArrayInputStream(zipBytes))
        assertEquals(2, preview.newTransactionCount)
        assertEquals(0, preview.duplicateTransactionCount)
        assertEquals(0, preview.errorTransactionCount)
        assertEquals(1, preview.newSubscriptionCount)
        assertEquals(0, preview.duplicateSubscriptionCount)
        assertEquals(1, preview.budgetMonthCount)
        assertEquals(1, preview.merchantRuleCount)
        assertEquals(8, preview.settingsCount)
        assertEquals(1, preview.evidenceFileCount)
        assertEquals(1, preview.newEvidenceFileCount)

        val result = restorer.restoreFromZip(ByteArrayInputStream(zipBytes), BackupRestoreOptions())
        assertEquals(2, result.insertedTransactions)
        assertEquals(0, result.skippedDuplicateTransactions)
        assertEquals(1, result.insertedSubscriptions)
        assertEquals(1, result.restoredBudgetMonths)
        assertEquals(1, result.restoredMerchantRules)
        assertEquals(8, result.restoredSettingsCount)
        assertEquals(1, result.restoredEvidenceCount)

        assertEquals(2, targetStorage.transactions.size)
        val coffeeTx = targetStorage.transactions.first { it.merchant == "瑞幸咖啡" }
        assertEquals(3_650L, coffeeTx.amountCents)
        assertEquals(TransactionCategory.FOOD.name, coffeeTx.category)
        assertEquals("微信", coffeeTx.account)
        assertEquals(evidenceName, coffeeTx.evidenceImageName)

        val restoredSub = targetStorage.subscriptions.single()
        assertEquals("sub-uuid-1", restoredSub.syncUuid)
        assertEquals("ChatGPT Plus", restoredSub.name)
        assertEquals(42L, restoredSub.wallosId)
        assertEquals(1999L, restoredSub.amountCents)

        assertEquals(500_000L, targetStorage.budgets["2026-11"]?.monthlyTotalCents)
        assertEquals(150_000L, targetStorage.budgets["2026-11"]?.perCategory?.get(TransactionCategory.FOOD))
        assertEquals(TransactionCategory.FOOD.name, targetStorage.merchantRules.rules["瑞幸咖啡"])
        assertEquals(true, targetStorage.settings[FullBackupManager.SETTING_KEEP_ALIVE])
        assertTrue(File(targetEvidenceDir, evidenceName).isFile)
    }

    @Test
    fun exportWithoutEvidenceOmitsEvidenceDirectoryByDefault() = runBlocking {
        val sourceEvidenceDir = tempFolder.newFolder("ev_src")
        val evidenceName = "ev_${"b".repeat(64)}.webp"
        val evidenceFile = File(sourceEvidenceDir, evidenceName).apply {
            writeBytes(byteArrayOf(7, 8, 9))
        }
        val storage = FakeBackupStorage(evidenceDir = sourceEvidenceDir).apply {
            evidenceFiles[evidenceName] = evidenceFile
        }
        val manager = FullBackupManager(storage = storage, appVersion = "1.46", timeZone = tz)

        val out = ByteArrayOutputStream()
        val manifest = manager.exportToZip(out, includeEvidence = false)
        assertFalse(manifest.includesEvidence)
        assertEquals(0, manifest.evidenceCount)

        val entryNames = readZipEntryNames(out.toByteArray())
        assertTrue(entryNames.none { it.startsWith("evidence/") })

        // Empty ledger backup should inspect with 0 error rows
        val preview = manager.inspectZip(ByteArrayInputStream(out.toByteArray()))
        assertEquals(0, preview.newTransactionCount)
        assertEquals(0, preview.errorTransactionCount)
    }

    @Test
    fun safeMergeRestorePreservesExistingDataAndMergesByTimestampAndOptions() = runBlocking {
        val sourceEvidenceDir = tempFolder.newFolder("merge_src_ev")
        val sharedEvidence = "ev_${"c".repeat(64)}.webp"
        val newEvidence = "ev_${"d".repeat(64)}.webp"
        File(sourceEvidenceDir, sharedEvidence).writeBytes(byteArrayOf(10, 20, 30))
        File(sourceEvidenceDir, newEvidence).writeBytes(byteArrayOf(40, 50, 60))

        val sourceStorage = FakeBackupStorage(evidenceDir = sourceEvidenceDir).apply {
            transactions += listOf(
                TransactionEntity(
                    id = 1L,
                    amountCents = 2_500L,
                    type = TransactionType.EXPENSE.name,
                    category = TransactionCategory.FOOD.name,
                    merchant = "肯德基",
                    note = "午餐",
                    source = TransactionSource.ALIPAY.name,
                    timestamp = 1_732_420_000_000L,
                ),
                TransactionEntity(
                    id = 2L,
                    amountCents = 5_000L,
                    type = TransactionType.EXPENSE.name,
                    category = TransactionCategory.TRANSPORT.name,
                    merchant = "滴滴出行",
                    note = "打车",
                    source = TransactionSource.WECHAT.name,
                    timestamp = 1_732_430_000_000L,
                ),
            )
            subscriptions += listOf(
                // Newer than target -> should update
                SubscriptionEntity(
                    id = 1L,
                    syncUuid = "sub-update-me",
                    name = "iCloud+ 200GB",
                    amountCents = 2_100L,
                    cadence = SubscriptionCadence.MONTHLY,
                    nextDueMs = 1_733_000_000_000L,
                    updatedAtMs = 2_000L,
                ),
                // Older than target -> should NOT overwrite target
                SubscriptionEntity(
                    id = 2L,
                    syncUuid = "sub-keep-local",
                    name = "旧名字",
                    amountCents = 1_000L,
                    cadence = SubscriptionCadence.MONTHLY,
                    nextDueMs = 1_733_000_000_000L,
                    updatedAtMs = 500L,
                ),
                // Missing on target -> should insert
                SubscriptionEntity(
                    id = 3L,
                    syncUuid = "sub-new",
                    name = "Spotify",
                    amountCents = 1_500L,
                    cadence = SubscriptionCadence.MONTHLY,
                    nextDueMs = 1_733_000_000_000L,
                    updatedAtMs = 1_000L,
                ),
            )
            budgets["2026-11"] = BudgetSettings(
                monthlyTotalCents = 999_900L,
                perCategory = mapOf(
                    TransactionCategory.FOOD to 88_800L,
                    TransactionCategory.TRANSPORT to 30_000L,
                ),
            )
            merchantRules = MerchantRulesBackup(
                rules = mapOf(
                    "肯德基" to TransactionCategory.SHOPPING.name,
                    "星巴克" to TransactionCategory.FOOD.name,
                ),
            )
            evidenceFiles[sharedEvidence] = File(sourceEvidenceDir, sharedEvidence)
            evidenceFiles[newEvidence] = File(sourceEvidenceDir, newEvidence)
        }

        val zipOut = ByteArrayOutputStream()
        FullBackupManager(sourceStorage, "1.46", tz).exportToZip(zipOut, includeEvidence = true)
        val zipBytes = zipOut.toByteArray()

        val targetEvidenceDir = tempFolder.newFolder("merge_tgt_ev")
        val existingEvidenceFile = File(targetEvidenceDir, sharedEvidence).apply {
            writeBytes(byteArrayOf(99, 99, 99))
        }

        val targetStorage = FakeBackupStorage(evidenceDir = targetEvidenceDir).apply {
            // Existing matching KFC tx + an unrelated local tx that must NEVER be deleted
            transactions += listOf(
                TransactionEntity(
                    id = 100L,
                    amountCents = 2_500L,
                    type = TransactionType.EXPENSE.name,
                    category = TransactionCategory.FOOD.name,
                    merchant = "肯德基",
                    note = "午餐",
                    source = TransactionSource.ALIPAY.name,
                    timestamp = 1_732_420_000_000L,
                ),
                TransactionEntity(
                    id = 101L,
                    amountCents = 12_300L,
                    type = TransactionType.EXPENSE.name,
                    category = TransactionCategory.SHOPPING.name,
                    merchant = "本地独有记录",
                    note = "绝不能被删除",
                    source = TransactionSource.MANUAL.name,
                    timestamp = 1_732_450_000_000L,
                ),
            )
            subscriptions += listOf(
                SubscriptionEntity(
                    id = 20L,
                    syncUuid = "sub-update-me",
                    name = "iCloud+ 50GB",
                    amountCents = 600L,
                    cadence = SubscriptionCadence.MONTHLY,
                    nextDueMs = 1_733_000_000_000L,
                    updatedAtMs = 1_000L,
                ),
                SubscriptionEntity(
                    id = 21L,
                    syncUuid = "sub-keep-local",
                    name = "本地新名字",
                    amountCents = 1_200L,
                    cadence = SubscriptionCadence.MONTHLY,
                    nextDueMs = 1_733_000_000_000L,
                    updatedAtMs = 3_000L,
                ),
                SubscriptionEntity(
                    id = 22L,
                    syncUuid = "sub-local-only",
                    name = "本地独有订阅",
                    amountCents = 900L,
                    cadence = SubscriptionCadence.YEARLY,
                    nextDueMs = 1_735_000_000_000L,
                    updatedAtMs = 1_000L,
                ),
            )
            budgets["2026-11"] = BudgetSettings(
                monthlyTotalCents = 400_000L,
                perCategory = mapOf(TransactionCategory.FOOD to 100_000L),
            )
            merchantRules = MerchantRulesBackup(
                rules = mapOf("肯德基" to TransactionCategory.FOOD.name),
            )
        }

        val restorer = FullBackupManager(targetStorage, "1.46", tz)
        val preview = restorer.inspectZip(ByteArrayInputStream(zipBytes))
        assertEquals(1, preview.newTransactionCount)
        assertEquals(1, preview.duplicateTransactionCount)
        assertEquals(1, preview.newSubscriptionCount)
        assertEquals(1, preview.updatedSubscriptionCount)
        assertEquals(1, preview.duplicateSubscriptionCount)
        assertEquals(2, preview.evidenceFileCount)
        assertEquals(1, preview.newEvidenceFileCount)

        val result = restorer.restoreFromZip(
            ByteArrayInputStream(zipBytes),
            BackupRestoreOptions(overwriteExistingConfigs = false),
        )
        assertEquals(1, result.insertedTransactions)
        assertEquals(1, result.skippedDuplicateTransactions)
        assertEquals(3, targetStorage.transactions.size)
        assertTrue(targetStorage.transactions.any { it.merchant == "本地独有记录" })
        assertTrue(targetStorage.transactions.any { it.merchant == "滴滴出行" })

        assertEquals(1, result.insertedSubscriptions)
        assertEquals(1, result.updatedSubscriptions)
        assertEquals(1, result.skippedSubscriptions)
        assertEquals(4, targetStorage.subscriptions.size)
        assertEquals("iCloud+ 200GB", targetStorage.subscriptions.first { it.syncUuid == "sub-update-me" }.name)
        assertEquals("本地新名字", targetStorage.subscriptions.first { it.syncUuid == "sub-keep-local" }.name)
        assertTrue(targetStorage.subscriptions.any { it.syncUuid == "sub-local-only" })

        // Non-destructive budget merge: kept 400_000 total & 100_000 food, filled missing 30_000 transport
        val mergedBudget = targetStorage.budgets["2026-11"]!!
        assertEquals(400_000L, mergedBudget.monthlyTotalCents)
        assertEquals(100_000L, mergedBudget.perCategory[TransactionCategory.FOOD])
        assertEquals(30_000L, mergedBudget.perCategory[TransactionCategory.TRANSPORT])

        // Non-destructive merchant rule merge: kept KFC=FOOD, added Starbucks=FOOD
        assertEquals(TransactionCategory.FOOD.name, targetStorage.merchantRules.rules["肯德基"])
        assertEquals(TransactionCategory.FOOD.name, targetStorage.merchantRules.rules["星巴克"])

        // Existing evidence file untouched; missing evidence file copied
        assertEquals(1, result.restoredEvidenceCount)
        assertEquals(1, result.skippedEvidenceCount)
        assertEquals(listOf<Byte>(99, 99, 99), existingEvidenceFile.readBytes().toList())
        assertTrue(File(targetEvidenceDir, newEvidence).isFile)
    }

    @Test
    fun neverExportsSensitiveSyncTokensOrServerCredentials() = runBlocking {
        val evidenceDir = tempFolder.newFolder("sec_ev")
        val storage = FakeBackupStorage(evidenceDir = evidenceDir).apply {
            settings = mutableMapOf(
                FullBackupManager.SETTING_LANGUAGE to "en",
                FullBackupManager.SETTING_KEEP_ALIVE to false,
                "token" to "super-secret-device-token-123456",
                "X-Device-Token" to "super-secret-header-token",
                "url" to "https://private.example.ts.net/finance",
                "serverUrl" to "https://private.example.ts.net/finance",
                "password" to "hunter2",
                "secret" to "topsecret",
                "installation_id" to "inst-abc",
            )
        }
        val manager = FullBackupManager(storage, "1.46", tz)
        val out = ByteArrayOutputStream()
        val manifest = manager.exportToZip(out, includeEvidence = false)
        assertEquals(2, manifest.settingsCount)

        val settingsJson = readZipEntryText(out.toByteArray(), FullBackupManager.ENTRY_SETTINGS)
        assertNotNull(settingsJson)
        assertTrue(settingsJson!!.contains("language"))
        assertTrue(settingsJson.contains("keepAliveEnabled"))
        listOf(
            "super-secret-device-token-123456",
            "super-secret-header-token",
            "private.example.ts.net",
            "hunter2",
            "topsecret",
            "X-Device-Token",
            "password",
            "installation_id",
        ).forEach { forbidden ->
            assertFalse("Sensitive string leaked in settings.json: $forbidden", settingsJson.contains(forbidden))
        }
    }

    @Test
    fun rejectsZipSlipPathTraversalEntriesAndProtectsParentDirs() = runBlocking {
        val parentDir = tempFolder.newFolder("sandbox_root")
        val targetEvidenceDir = File(parentDir, "receipt_evidence").apply { mkdirs() }
        val storage = FakeBackupStorage(evidenceDir = targetEvidenceDir)
        val manager = FullBackupManager(storage, "1.46", tz)

        val safeName = "ev_${"e".repeat(64)}.webp"
        val maliciousZip = buildRawZip(
            mapOf(
                FullBackupManager.ENTRY_MANIFEST to """
                    {
                      "schemaVersion": 1,
                      "appVersion": "1.46",
                      "exportedAtMs": 1732425000000,
                      "includesEvidence": true,
                      "counts": { "transactions": 0, "subscriptions": 0, "budgetMonths": 0, "merchantRules": 0, "settings": 0, "evidence": 1 },
                      "checksums": {}
                    }
                """.trimIndent().toByteArray(Charsets.UTF_8),
                "../../evil.txt" to "pwned".toByteArray(),
                "evidence/../../outside.webp" to "pwned".toByteArray(),
                "evidence/nested/sub.webp" to "pwned".toByteArray(),
                "evidence\\backslash.webp" to "pwned".toByteArray(),
                "/etc/passwd" to "pwned".toByteArray(),
                "evidence/$safeName" to byteArrayOf(1, 3, 5, 7),
            ),
        )

        val preview = manager.inspectZip(ByteArrayInputStream(maliciousZip))
        assertEquals(1, preview.evidenceFileCount)
        assertTrue(preview.rejectedUnsafeEntries >= 4)

        val result = manager.restoreFromZip(ByteArrayInputStream(maliciousZip), BackupRestoreOptions())
        assertEquals(1, result.restoredEvidenceCount)
        assertTrue(result.rejectedUnsafeEntries >= 4)
        assertTrue(File(targetEvidenceDir, safeName).isFile)
        assertFalse(File(parentDir, "outside.webp").exists())
        assertFalse(File(parentDir, "evil.txt").exists())
        assertFalse(File(targetEvidenceDir, "nested/sub.webp").exists())
    }

    private fun readZipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                names += entry.name
                zis.closeEntry()
            }
        }
        return names
    }

    private fun readZipEntryText(bytes: ByteArray, entryName: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == entryName) {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
            }
        }
        return null
    }

    private fun buildRawZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
            zos.finish()
        }
        return out.toByteArray()
    }

    private class FakeBackupStorage(
        private val evidenceDir: File,
    ) : BackupStorage {
        val transactions = mutableListOf<TransactionEntity>()
        val subscriptions = mutableListOf<SubscriptionEntity>()
        val budgets = mutableMapOf<String, BudgetSettings>()
        var merchantRules = MerchantRulesBackup()
        var settings = mutableMapOf<String, Any?>()
        val evidenceFiles = mutableMapOf<String, File>()
        private var nextTxId = 1000L
        private var nextSubId = 1000L

        override suspend fun readTransactions(): List<TransactionEntity> =
            transactions.filter { !it.syncDeleted }

        override suspend fun insertTransactions(inserts: List<TransactionEntity>): Int {
            inserts.forEach { tx ->
                transactions += tx.copy(id = nextTxId++)
            }
            return inserts.size
        }

        override suspend fun updateTransactions(updates: List<TransactionEntity>): Int {
            var count = 0
            updates.forEach { updated ->
                val idx = transactions.indexOfFirst { it.id == updated.id }
                if (idx >= 0) {
                    transactions[idx] = updated
                    count++
                }
            }
            return count
        }

        override suspend fun readSubscriptions(): List<SubscriptionEntity> =
            subscriptions.filter { it.deletedAtMs == null }

        override suspend fun upsertSubscription(subscription: SubscriptionEntity) {
            val idx = subscriptions.indexOfFirst { it.syncUuid == subscription.syncUuid }
            if (idx >= 0) {
                subscriptions[idx] = subscription.copy(id = subscriptions[idx].id)
            } else {
                subscriptions += subscription.copy(id = nextSubId++)
            }
        }

        override suspend fun readBudgets(transactionMonths: Set<String>): List<BudgetMonthBackup> =
            budgets.entries.map { (month, s) ->
                BudgetMonthBackup(
                    month = month,
                    monthlyTotalCents = s.monthlyTotalCents,
                    perCategory = s.perCategory.mapKeys { it.key.name },
                )
            }

        override suspend fun writeBudget(
            month: String,
            settings: BudgetSettings,
            overwrite: Boolean,
        ): Boolean {
            val existing = budgets[month]
            if (existing == null || overwrite) {
                budgets[month] = settings
                return true
            }
            var changed = false
            var total = existing.monthlyTotalCents
            if (total == 0L && settings.monthlyTotalCents > 0L) {
                total = settings.monthlyTotalCents
                changed = true
            }
            val cats = existing.perCategory.toMutableMap()
            settings.perCategory.forEach { (cat, cents) ->
                if ((cats[cat] ?: 0L) == 0L && cents > 0L) {
                    cats[cat] = cents
                    changed = true
                }
            }
            if (changed) {
                budgets[month] = BudgetSettings(total, cats)
            }
            return changed
        }

        override suspend fun readMerchantRules(): MerchantRulesBackup = merchantRules

        override suspend fun writeMerchantRules(
            data: MerchantRulesBackup,
            overwrite: Boolean,
        ): Pair<Int, Int> {
            val rules = merchantRules.rules.toMutableMap()
            var restored = 0
            var skipped = 0
            data.rules.forEach { (k, v) ->
                val key = MerchantRules.normalize(k)
                if (!overwrite && rules.containsKey(key)) {
                    skipped++
                } else {
                    rules[key] = v
                    restored++
                }
            }
            merchantRules = MerchantRulesBackup(
                rules = rules,
                learningCounts = if (overwrite) data.learningCounts else merchantRules.learningCounts + data.learningCounts,
                learningDisabled = (merchantRules.learningDisabled + data.learningDisabled).distinct(),
            )
            return restored to skipped
        }

        override suspend fun readNonSensitiveSettings(): Map<String, Any?> = settings.toMap()

        override suspend fun writeNonSensitiveSettings(
            settings: Map<String, Any?>,
            overwrite: Boolean,
        ): SettingsWriteResult {
            var applied = 0
            var skipped = 0
            settings.forEach { (k, v) ->
                if (overwrite || !this.settings.containsKey(k)) {
                    this.settings[k] = v
                    applied++
                } else {
                    skipped++
                }
            }
            return SettingsWriteResult(applied, skipped)
        }

        override fun listEvidenceFiles(): Map<String, File> = evidenceFiles.toMap()

        override fun evidenceTargetDir(): File = evidenceDir
    }
}
