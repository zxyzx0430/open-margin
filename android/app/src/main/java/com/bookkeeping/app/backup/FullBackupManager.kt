package com.bookkeeping.app.backup

import com.bookkeeping.app.billexport.BillExport
import com.bookkeeping.app.billimport.BillDirection
import com.bookkeeping.app.billimport.BillImportParser
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillImportRow
import com.bookkeeping.app.billimport.BillParserOptions
import com.bookkeeping.app.billimport.BillPreviewKind
import com.bookkeeping.app.ui.screen.billimport.BillImportMapping
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.category.CategoryCatalog
import com.bookkeeping.app.data.budget.BudgetSettings
import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.merchant.MerchantRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * Pure & Android-friendly `.zip` full backup exporter, previewer, and safe-merge restorer.
 *
 * Security & safety invariants:
 * - Strictly allow-lists non-sensitive preferences in `settings.json`; never exports
 *   `X-Device-Token`, sync server URLs/credentials, or passwords.
 * - Protects against zip-slip path traversal and oversized/zip-bomb entries.
 * - Safe-merge restore never deletes existing local transactions, subscriptions, budgets,
 *   merchant rules, or evidence files.
 */
class FullBackupManager(
    private val storage: BackupStorage,
    private val appVersion: String,
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    suspend fun exportToZip(
        outputStream: OutputStream,
        includeEvidence: Boolean,
    ): BackupManifest {
        val transactions = storage.readTransactions()
            .filter { !it.syncDeleted }
            .sortedWith(compareBy<TransactionEntity>({ it.timestamp }, { it.id }))
        val subscriptions = storage.readSubscriptions()
            .filter { it.deletedAtMs == null }
            .sortedWith(compareBy<SubscriptionEntity>({ it.nextDueMs }, { it.syncUuid }))

        val txMonths = transactions.mapTo(linkedSetOf()) { monthKeyOf(it.timestamp) }
        val budgets = storage.readBudgets(txMonths)
            .filter { it.monthlyTotalCents > 0L || it.perCategory.isNotEmpty() }
            .sortedBy { it.month }
        val merchantRules = storage.readMerchantRules()
        val settings = sanitizeExportSettings(storage.readNonSensitiveSettings())

        val txCsvBytes = BillExport.csvBytes(
            records = transactions.map { tx ->
                BillExport.Record(
                    timestampMs = tx.timestamp,
                    type = tx.type,
                    amountCents = tx.amountCents,
                    category = tx.category,
                    merchant = tx.merchant,
                    account = tx.account,
                    source = tx.source,
                    note = tx.note,
                )
            },
            timeZone = timeZone,
        )
        val subscriptionsBytes = encodeSubscriptions(subscriptions).toByteArray(Charsets.UTF_8)
        val budgetsBytes = encodeBudgets(budgets).toByteArray(Charsets.UTF_8)
        val merchantRulesBytes = encodeMerchantRules(merchantRules).toByteArray(Charsets.UTF_8)
        val settingsBytes = encodeSettings(settings).toByteArray(Charsets.UTF_8)

        val evidenceEntries = linkedMapOf<String, ByteArray>()
        if (includeEvidence) {
            val files = storage.listEvidenceFiles()
            for ((rawName, file) in files.entries.sortedBy { it.key }) {
                val safeName = sanitizeEvidenceFileName(rawName) ?: continue
                if (!file.isFile || file.length() <= 0L || file.length() > MAX_ENTRY_BYTES) continue
                val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: continue
                evidenceEntries["$EVIDENCE_DIR_PREFIX$safeName"] = bytes
            }
        }
        val evidenceLinks = if (includeEvidence && evidenceEntries.isNotEmpty()) {
            transactions.mapNotNull { tx ->
                val safeName = sanitizeEvidenceFileName(tx.evidenceImageName) ?: return@mapNotNull null
                if (!evidenceEntries.containsKey("$EVIDENCE_DIR_PREFIX$safeName")) return@mapNotNull null
                EvidenceLink(
                    timestampMs = tx.timestamp,
                    amountCents = tx.amountCents,
                    type = tx.type,
                    merchant = tx.merchant,
                    evidenceImageName = safeName,
                )
            }
        } else {
            emptyList()
        }

        val payloadEntries = linkedMapOf<String, ByteArray>().apply {
            put(ENTRY_TRANSACTIONS, txCsvBytes)
            put(ENTRY_SUBSCRIPTIONS, subscriptionsBytes)
            put(ENTRY_BUDGETS, budgetsBytes)
            put(ENTRY_MERCHANT_RULES, merchantRulesBytes)
            put(ENTRY_SETTINGS, settingsBytes)
            CategoryCatalog.peek()?.exportJson()?.let { json ->
                put(ENTRY_CATEGORIES, json.toByteArray(Charsets.UTF_8))
            }
            putAll(evidenceEntries)
        }
        val checksums = payloadEntries.mapValues { (_, bytes) -> sha256Hex(bytes) }

        val manifest = BackupManifest(
            schemaVersion = BackupManifest.CURRENT_SCHEMA_VERSION,
            appVersion = appVersion,
            exportedAtMs = nowProvider(),
            includesEvidence = includeEvidence,
            transactionCount = transactions.size,
            subscriptionCount = subscriptions.size,
            budgetMonthCount = budgets.size,
            merchantRuleCount = merchantRules.rules.size,
            settingsCount = settings.size,
            evidenceCount = evidenceEntries.size,
            checksums = checksums,
            evidenceLinks = evidenceLinks,
        )
        val manifestBytes = encodeManifest(manifest).toByteArray(Charsets.UTF_8)

        ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
            writeZipEntry(zos, ENTRY_MANIFEST, manifestBytes)
            for ((name, bytes) in payloadEntries) {
                writeZipEntry(zos, name, bytes)
            }
            zos.finish()
        }
        return manifest
    }

    suspend fun inspectZip(inputStream: InputStream): BackupPreview {
        val archive = readZipArchive(inputStream)
        val manifest = parseManifest(archive)
        verifyChecksums(manifest, archive.entries)

        val warnings = mutableListOf<String>()
        if (archive.rejectedUnsafeEntries > 0) {
            warnings += "已忽略 ${archive.rejectedUnsafeEntries} 个不安全路径条目"
        }

        val existingTransactions = storage.readTransactions()
        val txPreview = parseTransactionsPreview(
            csvBytes = archive.entries[ENTRY_TRANSACTIONS],
            existingTransactions = existingTransactions,
        )
        val errorCount = if (txPreview == null) {
            0
        } else if (isEmptyHeaderOnlyPreview(txPreview)) {
            0
        } else {
            txPreview.errorRows.size
        }

        val subscriptions = archive.entries[ENTRY_SUBSCRIPTIONS]?.let(::parseSubscriptions).orEmpty()
        val existingSubsByUuid = storage.readSubscriptions()
            .filter { it.deletedAtMs == null }
            .associateBy { it.syncUuid }
        var newSubs = 0
        var updatedSubs = 0
        var dupSubs = 0
        for (sub in subscriptions) {
            val existing = existingSubsByUuid[sub.syncUuid]
            when {
                existing == null -> newSubs++
                sub.updatedAtMs > existing.updatedAtMs -> updatedSubs++
                else -> dupSubs++
            }
        }

        val budgets = archive.entries[ENTRY_BUDGETS]?.let(::parseBudgets).orEmpty()
        val merchantRules = archive.entries[ENTRY_MERCHANT_RULES]?.let(::parseMerchantRules)
            ?: MerchantRulesBackup()
        val settings = archive.entries[ENTRY_SETTINGS]?.let(::parseSettings).orEmpty()

        val evidenceDir = storage.evidenceTargetDir()
        var newEvidence = 0
        for ((name, _) in archive.evidenceEntries) {
            val target = safeTargetFile(evidenceDir, name)
            if (target != null && (!target.isFile || target.length() <= 0L)) {
                newEvidence++
            }
        }

        return BackupPreview(
            manifest = manifest,
            newTransactionCount = txPreview?.validRows?.size ?: 0,
            duplicateTransactionCount = txPreview?.duplicateRows?.size ?: 0,
            skippedTransactionCount = txPreview?.skippedRows?.size ?: 0,
            errorTransactionCount = errorCount,
            newSubscriptionCount = newSubs,
            updatedSubscriptionCount = updatedSubs,
            duplicateSubscriptionCount = dupSubs,
            budgetMonthCount = budgets.size,
            merchantRuleCount = merchantRules.rules.size,
            settingsCount = settings.size,
            evidenceFileCount = archive.evidenceEntries.size,
            newEvidenceFileCount = newEvidence,
            rejectedUnsafeEntries = archive.rejectedUnsafeEntries,
            warnings = warnings,
        )
    }

    suspend fun restoreFromZip(
        inputStream: InputStream,
        options: BackupRestoreOptions = BackupRestoreOptions(),
    ): BackupRestoreResult {
        val archive = readZipArchive(inputStream)
        val manifest = parseManifest(archive)
        verifyChecksums(manifest, archive.entries)

        val evidenceDir = storage.evidenceTargetDir()
        var restoredEvidence = 0
        var skippedEvidence = 0
        val availableEvidenceNames = linkedSetOf<String>()

        if (options.restoreEvidence && archive.evidenceEntries.isNotEmpty()) {
            if (!evidenceDir.exists()) {
                evidenceDir.mkdirs()
            }
            for ((fileName, bytes) in archive.evidenceEntries) {
                val target = safeTargetFile(evidenceDir, fileName) ?: continue
                if (target.isFile && target.length() > 0L) {
                    skippedEvidence++
                    availableEvidenceNames += fileName
                } else {
                    if (writeAtomically(target, bytes)) {
                        restoredEvidence++
                        availableEvidenceNames += fileName
                    }
                }
            }
        }

        var insertedTx = 0
        var skippedDupTx = 0
        if (options.restoreTransactions) {
            val csvBytes = archive.entries[ENTRY_TRANSACTIONS]
            if (csvBytes != null) {
                val existingTransactions = storage.readTransactions()
                val existingKeys = BillImportMapping.existingRowKeys(existingTransactions)
                val marked = parseTransactionsPreview(csvBytes, existingTransactions)
                if (marked != null) {
                    val linksByKey = manifest.evidenceLinks
                        .filter { it.evidenceImageName in availableEvidenceNames }
                        .groupBy { linkKey(it.amountCents, it.type, it.merchant) }

                    val toInsert = mutableListOf<TransactionEntity>()
                    val toUpdateEvidence = mutableListOf<TransactionEntity>()
                    val existingById = existingTransactions.associateBy { it.id }

                    for (row in marked.rows) {
                        val parsed = row.parsed ?: continue
                        when (row.kind) {
                            BillPreviewKind.VALID -> {
                                if (BillImportMapping.isAlreadyPresent(parsed, existingTransactions, existingKeys)) {
                                    skippedDupTx++
                                    continue
                                }
                                val entity = BillImportMapping.toTransactionEntity(parsed) ?: continue
                                val matchedEvidence = if (options.restoreEvidence) {
                                    findEvidenceForRow(parsed, entity.type, linksByKey)
                                } else {
                                    null
                                }
                                toInsert += if (matchedEvidence != null) {
                                    entity.copy(evidenceImageName = matchedEvidence)
                                } else {
                                    entity
                                }
                            }
                            BillPreviewKind.DUPLICATE -> {
                                skippedDupTx++
                                if (options.restoreEvidence) {
                                    val existing = row.matchedTransactionId?.let(existingById::get)
                                    if (existing != null && existing.evidenceImageName.isNullOrBlank()) {
                                        val matchedEvidence = findEvidenceForRow(parsed, existing.type, linksByKey)
                                        if (matchedEvidence != null) {
                                            toUpdateEvidence += existing.copy(evidenceImageName = matchedEvidence)
                                        }
                                    }
                                }
                            }
                            BillPreviewKind.SKIPPED, BillPreviewKind.ERROR -> Unit
                        }
                    }

                    if (toInsert.isNotEmpty()) {
                        insertedTx = storage.insertTransactions(toInsert)
                    }
                    if (toUpdateEvidence.isNotEmpty()) {
                        storage.updateTransactions(toUpdateEvidence.distinctBy { it.id })
                    }
                }
            }
        }

        var insertedSubs = 0
        var updatedSubs = 0
        var skippedSubs = 0
        if (options.restoreSubscriptions) {
            val subs = archive.entries[ENTRY_SUBSCRIPTIONS]?.let(::parseSubscriptions).orEmpty()
            val existingByUuid = storage.readSubscriptions().associateBy { it.syncUuid }
            for (sub in subs) {
                val existing = existingByUuid[sub.syncUuid]
                when {
                    existing == null -> {
                        storage.upsertSubscription(sub.copy(id = 0L, dirty = true))
                        insertedSubs++
                    }
                    sub.updatedAtMs > existing.updatedAtMs -> {
                        storage.upsertSubscription(
                            sub.copy(
                                id = existing.id,
                                wallosId = sub.wallosId ?: existing.wallosId,
                                dirty = true,
                            ),
                        )
                        updatedSubs++
                    }
                    else -> skippedSubs++
                }
            }
        }

        var restoredBudgets = 0
        var skippedBudgets = 0
        if (options.restoreBudgets) {
            val budgets = archive.entries[ENTRY_BUDGETS]?.let(::parseBudgets).orEmpty()
            for (entry in budgets) {
                if (!BudgetMonth.isValid(entry.month)) continue
                val categoryMap = entry.perCategory.mapNotNull { (catName, cents) ->
                    val category = runCatching { TransactionCategory.valueOf(catName) }.getOrNull()
                        ?: return@mapNotNull null
                    if (cents > 0L) category to cents else null
                }.toMap()
                val settings = BudgetSettings(
                    monthlyTotalCents = entry.monthlyTotalCents.coerceAtLeast(0L),
                    perCategory = categoryMap,
                )
                if (storage.writeBudget(entry.month, settings, options.overwriteExistingConfigs)) {
                    restoredBudgets++
                } else {
                    skippedBudgets++
                }
            }
        }

        var restoredRules = 0
        var skippedRules = 0
        if (options.restoreMerchantRules) {
            val rulesData = archive.entries[ENTRY_MERCHANT_RULES]?.let(::parseMerchantRules)
            if (rulesData != null) {
                val (restored, skipped) = storage.writeMerchantRules(
                    rulesData,
                    options.overwriteExistingConfigs,
                )
                restoredRules = restored
                skippedRules = skipped
            }
        }

        var restoredSettings = 0
        var skippedSettings = 0
        if (options.restoreSettings) {
            archive.entries[ENTRY_CATEGORIES]?.let { catBytes ->
                CategoryCatalog.peek()?.importMerge(
                    rawJson = catBytes.toString(Charsets.UTF_8),
                    overwrite = options.overwriteExistingConfigs,
                )
            }
        }
        if (options.restoreSettings) {
            val settingsMap = archive.entries[ENTRY_SETTINGS]?.let(::parseSettings)
            if (!settingsMap.isNullOrEmpty()) {
                val result = storage.writeNonSensitiveSettings(
                    settingsMap,
                    options.overwriteExistingConfigs,
                )
                restoredSettings = result.appliedCount
                skippedSettings = result.skippedCount
            }
        }

        return BackupRestoreResult(
            insertedTransactions = insertedTx,
            skippedDuplicateTransactions = skippedDupTx,
            insertedSubscriptions = insertedSubs,
            updatedSubscriptions = updatedSubs,
            skippedSubscriptions = skippedSubs,
            restoredBudgetMonths = restoredBudgets,
            skippedBudgetMonths = skippedBudgets,
            restoredMerchantRules = restoredRules,
            skippedMerchantRules = skippedRules,
            restoredSettingsCount = restoredSettings,
            skippedSettingsCount = skippedSettings,
            restoredEvidenceCount = restoredEvidence,
            skippedEvidenceCount = skippedEvidence,
            rejectedUnsafeEntries = archive.rejectedUnsafeEntries,
        )
    }

    private fun parseTransactionsPreview(
        csvBytes: ByteArray?,
        existingTransactions: List<TransactionEntity>,
    ): BillImportPreview? {
        if (csvBytes == null) return null
        val existingKeys = BillImportMapping.existingRowKeys(existingTransactions)
        val parsed = BillImportParser.parse(
            bytes = csvBytes,
            fileName = ENTRY_TRANSACTIONS,
            options = BillParserOptions(
                existingDuplicateKeys = existingKeys,
                timeZoneId = timeZone.id,
            ),
        )
        return BillImportMapping.markExistingRows(
            preview = parsed,
            transactions = existingTransactions,
            duplicateMessage = "账本已有相同记录",
        )
    }

    private fun isEmptyHeaderOnlyPreview(preview: BillImportPreview): Boolean =
        preview.validRows.isEmpty() &&
            preview.duplicateRows.isEmpty() &&
            preview.skippedRows.isEmpty() &&
            preview.errorRows.size == 1 &&
            preview.errorRows.first().message == "没有可导入的数据行"

    private fun findEvidenceForRow(
        row: BillImportRow,
        entityType: String,
        linksByKey: Map<String, List<EvidenceLink>>,
    ): String? {
        val candidates = linksByKey[linkKey(row.amountCents, entityType, row.merchant)]
            ?: return null
        return candidates
            .firstOrNull { abs(it.timestampMs - row.occurredAtMs) <= EVIDENCE_LINK_TIME_WINDOW_MS }
            ?.evidenceImageName
    }

    private fun linkKey(amountCents: Long, type: String, merchant: String): String =
        "$amountCents|$type|${MerchantRules.normalize(merchant)}"

    private fun monthKeyOf(timestampMs: Long): String {
        val cal = Calendar.getInstance(timeZone, Locale.ROOT).apply {
            timeInMillis = timestampMs
        }
        return BudgetMonth.keyOf(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    private data class ZipArchiveContents(
        val entries: Map<String, ByteArray>,
        val evidenceEntries: Map<String, ByteArray>,
        val rejectedUnsafeEntries: Int,
    )

    private fun readZipArchive(inputStream: InputStream): ZipArchiveContents {
        val entries = linkedMapOf<String, ByteArray>()
        val evidenceEntries = linkedMapOf<String, ByteArray>()
        var rejectedUnsafe = 0
        var totalBytes = 0L
        var entryCount = 0

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "备份压缩包条目过多" }
                val rawName = entry.name
                if (!isSafeZipEntryPath(rawName)) {
                    rejectedUnsafe++
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val bytes = readBoundedEntryBytes(zis, MAX_ENTRY_BYTES)
                totalBytes += bytes.size.toLong()
                require(totalBytes <= MAX_TOTAL_BYTES) { "备份文件体积过大" }
                entries[rawName] = bytes
                if (rawName.startsWith(EVIDENCE_DIR_PREFIX)) {
                    val fileName = rawName.removePrefix(EVIDENCE_DIR_PREFIX)
                    val safeName = sanitizeEvidenceFileName(fileName)
                    if (safeName != null && bytes.isNotEmpty()) {
                        evidenceEntries[safeName] = bytes
                    } else {
                        rejectedUnsafe++
                    }
                }
                zis.closeEntry()
            }
        }
        return ZipArchiveContents(entries, evidenceEntries, rejectedUnsafe)
    }

    private fun readBoundedEntryBytes(input: InputStream, maxBytes: Long): ByteArray {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var remaining = maxBytes
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            remaining -= read.toLong()
            require(remaining >= 0L) { "备份单个条目超过 20 MB 限制" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun parseManifest(archive: ZipArchiveContents): BackupManifest {
        val bytes = archive.entries[ENTRY_MANIFEST]
            ?: throw IllegalArgumentException("无效的备份文件（缺少 manifest.json）")
        val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("无法解析 manifest.json") }
        val schemaVersion = json.optInt("schemaVersion", -1)
        require(schemaVersion == BackupManifest.CURRENT_SCHEMA_VERSION) {
            "不支持的备份版本（schemaVersion=$schemaVersion）"
        }
        val countsJson = json.optJSONObject("counts") ?: JSONObject()
        val checksumsJson = json.optJSONObject("checksums") ?: JSONObject()
        val checksums = buildMap {
            checksumsJson.keys().forEach { key ->
                val value = checksumsJson.optString(key)
                if (value.isNotBlank()) put(key, value)
            }
        }
        val linksJson = json.optJSONArray("evidenceLinks") ?: JSONArray()
        val links = buildList {
            for (i in 0 until linksJson.length()) {
                val obj = linksJson.optJSONObject(i) ?: continue
                val safeName = sanitizeEvidenceFileName(obj.optString("evidenceImageName"))
                    ?: continue
                val amount = obj.optLong("amountCents", -1L)
                val type = obj.optString("type")
                if (amount <= 0L || type.isBlank()) continue
                add(
                    EvidenceLink(
                        timestampMs = obj.optLong("timestampMs", 0L),
                        amountCents = amount,
                        type = type,
                        merchant = obj.optString("merchant", ""),
                        evidenceImageName = safeName,
                    ),
                )
            }
        }
        return BackupManifest(
            schemaVersion = schemaVersion,
            appVersion = json.optString("appVersion", ""),
            exportedAtMs = json.optLong("exportedAtMs", 0L),
            includesEvidence = json.optBoolean("includesEvidence", false),
            transactionCount = countsJson.optInt("transactions", 0),
            subscriptionCount = countsJson.optInt("subscriptions", 0),
            budgetMonthCount = countsJson.optInt("budgetMonths", 0),
            merchantRuleCount = countsJson.optInt("merchantRules", 0),
            settingsCount = countsJson.optInt("settings", 0),
            evidenceCount = countsJson.optInt("evidence", 0),
            checksums = checksums,
            evidenceLinks = links,
        )
    }

    private fun verifyChecksums(manifest: BackupManifest, entries: Map<String, ByteArray>) {
        for ((name, expectedHash) in manifest.checksums) {
            val actualBytes = entries[name] ?: continue
            val actualHash = sha256Hex(actualBytes)
            require(actualHash.equals(expectedHash, ignoreCase = true)) {
                "备份文件校验失败：$name 内容已损坏"
            }
        }
    }

    private fun encodeManifest(manifest: BackupManifest): String {
        val counts = JSONObject().apply {
            put("transactions", manifest.transactionCount)
            put("subscriptions", manifest.subscriptionCount)
            put("budgetMonths", manifest.budgetMonthCount)
            put("merchantRules", manifest.merchantRuleCount)
            put("settings", manifest.settingsCount)
            put("evidence", manifest.evidenceCount)
        }
        val checksums = JSONObject().apply {
            manifest.checksums.forEach { (k, v) -> put(k, v) }
        }
        val links = JSONArray().apply {
            manifest.evidenceLinks.forEach { link ->
                put(
                    JSONObject().apply {
                        put("timestampMs", link.timestampMs)
                        put("amountCents", link.amountCents)
                        put("type", link.type)
                        put("merchant", link.merchant)
                        put("evidenceImageName", link.evidenceImageName)
                    },
                )
            }
        }
        return JSONObject().apply {
            put("schemaVersion", manifest.schemaVersion)
            put("appVersion", manifest.appVersion)
            put("exportedAtMs", manifest.exportedAtMs)
            put("includesEvidence", manifest.includesEvidence)
            put("counts", counts)
            put("checksums", checksums)
            if (manifest.evidenceLinks.isNotEmpty()) {
                put("evidenceLinks", links)
            }
        }.toString(2)
    }

    private fun encodeSubscriptions(subscriptions: List<SubscriptionEntity>): String {
        val array = JSONArray()
        for (sub in subscriptions) {
            array.put(
                JSONObject().apply {
                    put("syncUuid", sub.syncUuid)
                    put("wallosId", sub.wallosId ?: JSONObject.NULL)
                    put("name", sub.name)
                    put("amountCents", sub.amountCents)
                    put("currency", sub.currency)
                    put("cnyEstimateCents", sub.cnyEstimateCents ?: JSONObject.NULL)
                    put("cadence", sub.cadence)
                    put("nextDueMs", sub.nextDueMs)
                    put("startMs", sub.startMs ?: JSONObject.NULL)
                    put("paymentChannel", sub.paymentChannel)
                    put("autoRenew", sub.autoRenew)
                    put("pendingCancel", sub.pendingCancel)
                    put("notifyEnabled", sub.notifyEnabled)
                    put("notifyDaysBefore", sub.notifyDaysBefore)
                    put("active", sub.active)
                    put("note", sub.note)
                    put("updatedAtMs", sub.updatedAtMs)
                },
            )
        }
        return JSONObject().put("subscriptions", array).toString(2)
    }

    private fun parseSubscriptions(bytes: ByteArray): List<SubscriptionEntity> {
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("subscriptions") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val syncUuid = obj.optString("syncUuid").trim()
                val name = obj.optString("name").trim()
                val cadence = obj.optString("cadence").trim()
                val amountCents = obj.optLong("amountCents", -1L)
                val nextDueMs = obj.optLong("nextDueMs", 0L)
                if (syncUuid.isEmpty() || name.isEmpty() || cadence !in SubscriptionCadence.ALL || amountCents < 0L) {
                    continue
                }
                add(
                    SubscriptionEntity(
                        id = 0L,
                        syncUuid = syncUuid,
                        wallosId = if (obj.isNull("wallosId")) null else obj.optLong("wallosId"),
                        name = name,
                        amountCents = amountCents,
                        currency = obj.optString("currency", "CNY").ifBlank { "CNY" },
                        cnyEstimateCents = if (obj.isNull("cnyEstimateCents")) null else obj.optLong("cnyEstimateCents"),
                        cadence = cadence,
                        nextDueMs = nextDueMs,
                        startMs = if (obj.isNull("startMs")) null else obj.optLong("startMs"),
                        paymentChannel = obj.optString("paymentChannel", ""),
                        autoRenew = obj.optBoolean("autoRenew", true),
                        pendingCancel = obj.optBoolean("pendingCancel", false),
                        notifyEnabled = obj.optBoolean("notifyEnabled", true),
                        notifyDaysBefore = obj.optInt("notifyDaysBefore", 3).coerceIn(0, 365),
                        active = obj.optBoolean("active", true),
                        note = obj.optString("note", ""),
                        updatedAtMs = obj.optLong("updatedAtMs", 0L),
                        deletedAtMs = null,
                        dirty = true,
                    ),
                )
            }
        }
    }

    private fun encodeBudgets(budgets: List<BudgetMonthBackup>): String {
        val monthsArray = JSONArray()
        for (budget in budgets) {
            val catJson = JSONObject()
            budget.perCategory.entries.sortedBy { it.key }.forEach { (cat, cents) ->
                if (cents > 0L) catJson.put(cat, cents)
            }
            monthsArray.put(
                JSONObject().apply {
                    put("month", budget.month)
                    put("monthlyTotalCents", budget.monthlyTotalCents)
                    put("perCategory", catJson)
                },
            )
        }
        return JSONObject().put("months", monthsArray).toString(2)
    }

    private fun parseBudgets(bytes: ByteArray): List<BudgetMonthBackup> {
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("months") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val month = obj.optString("month").trim()
                if (!BudgetMonth.isValid(month)) continue
                val total = obj.optLong("monthlyTotalCents", 0L).coerceAtLeast(0L)
                val catObj = obj.optJSONObject("perCategory") ?: JSONObject()
                val perCategory = buildMap {
                    catObj.keys().forEach { key ->
                        val cents = catObj.optLong(key, 0L)
                        if (cents > 0L) put(key, cents)
                    }
                }
                if (total > 0L || perCategory.isNotEmpty()) {
                    add(BudgetMonthBackup(month = month, monthlyTotalCents = total, perCategory = perCategory))
                }
            }
        }
    }

    private fun encodeMerchantRules(data: MerchantRulesBackup): String {
        val rulesJson = JSONObject()
        data.rules.entries.sortedBy { it.key }.forEach { (k, v) -> rulesJson.put(k, v) }
        val countsJson = JSONObject()
        data.learningCounts.entries.sortedBy { it.key }.forEach { (merchant, catMap) ->
            val inner = JSONObject()
            catMap.entries.sortedBy { it.key }.forEach { (cat, count) ->
                if (count > 0) inner.put(cat, count)
            }
            if (inner.length() > 0) countsJson.put(merchant, inner)
        }
        val disabledArray = JSONArray()
        data.learningDisabled.sorted().forEach(disabledArray::put)
        return JSONObject().apply {
            put("rules", rulesJson)
            put("learningCounts", countsJson)
            put("learningDisabled", disabledArray)
        }.toString(2)
    }

    private fun parseMerchantRules(bytes: ByteArray): MerchantRulesBackup {
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return MerchantRulesBackup()
        val rulesJson = root.optJSONObject("rules") ?: JSONObject()
        val rules = buildMap {
            rulesJson.keys().forEach { key ->
                val cat = rulesJson.optString(key)
                if (key.isNotBlank() && cat.isNotBlank()) put(key, cat)
            }
        }
        val countsJson = root.optJSONObject("learningCounts") ?: JSONObject()
        val counts = buildMap {
            countsJson.keys().forEach { merchant ->
                val inner = countsJson.optJSONObject(merchant) ?: return@forEach
                val catMap = buildMap {
                    inner.keys().forEach { cat ->
                        val c = inner.optInt(cat, 0)
                        if (c > 0) put(cat, c)
                    }
                }
                if (catMap.isNotEmpty()) put(merchant, catMap)
            }
        }
        val disabledJson = root.optJSONArray("learningDisabled") ?: JSONArray()
        val disabled = buildList {
            for (i in 0 until disabledJson.length()) {
                disabledJson.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return MerchantRulesBackup(rules = rules, learningCounts = counts, learningDisabled = disabled)
    }

    private fun encodeSettings(settings: Map<String, Any?>): String {
        val json = JSONObject()
        settings.entries.sortedBy { it.key }.forEach { (key, value) ->
            if (value != null && key in ALLOWED_SETTINGS_KEYS) {
                json.put(key, value)
            }
        }
        return json.toString(2)
    }

    private fun parseSettings(bytes: ByteArray): Map<String, Any?> {
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return emptyMap()
        return buildMap {
            root.keys().forEach { key ->
                if (key in ALLOWED_SETTINGS_KEYS && !root.isNull(key)) {
                    put(key, root.get(key))
                }
            }
        }
    }

    private fun writeZipEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val tmp = File(parent, "${target.name}.tmp")
        return runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.getOrElse {
            runCatching { tmp.delete() }
            false
        }
    }

    companion object {
        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_TRANSACTIONS = "transactions.csv"
        const val ENTRY_SUBSCRIPTIONS = "subscriptions.json"
        const val ENTRY_BUDGETS = "budgets.json"
        const val ENTRY_MERCHANT_RULES = "merchant_rules.json"
        const val ENTRY_SETTINGS = "settings.json"
        const val ENTRY_CATEGORIES = "categories.json"
        const val EVIDENCE_DIR_PREFIX = "evidence/"

        const val SETTING_LANGUAGE = "language"
        const val SETTING_KEEP_ALIVE = "keepAliveEnabled"
        const val SETTING_SUCCESS_NOTIFICATIONS = "successNotificationsEnabled"
        const val SETTING_EVIDENCE_RETENTION_MODE = "evidenceRetentionMode"
        const val SETTING_CALENDAR_HEATMAP = "calendarHeatmapMode"
        const val SETTING_CALENDAR_WEEK_START_MONDAY = "calendarWeekStartsMonday"
        const val SETTING_CALENDAR_SHOW_LUNAR = "calendarShowLunar"
        const val SETTING_CALENDAR_NUMBER_MODE = "calendarNumberMode"

        /**
         * Explicit allow-list of non-sensitive preferences. Sensitive sync tokens, URLs,
         * device credentials, and passwords are never included here.
         */
        val ALLOWED_SETTINGS_KEYS = setOf(
            SETTING_LANGUAGE,
            SETTING_KEEP_ALIVE,
            SETTING_SUCCESS_NOTIFICATIONS,
            SETTING_EVIDENCE_RETENTION_MODE,
            SETTING_CALENDAR_HEATMAP,
            SETTING_CALENDAR_WEEK_START_MONDAY,
            SETTING_CALENDAR_SHOW_LUNAR,
            SETTING_CALENDAR_NUMBER_MODE,
        )

        private const val MAX_ENTRIES = 10_000
        private const val MAX_ENTRY_BYTES = 20L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
        private const val EVIDENCE_LINK_TIME_WINDOW_MS = 2_000L

        private val SAFE_EVIDENCE_NAME = Regex("^[A-Za-z0-9._-]{1,128}$")

        fun sanitizeExportSettings(raw: Map<String, Any?>): Map<String, Any?> =
            raw.filter { (key, value) -> key in ALLOWED_SETTINGS_KEYS && value != null }

        fun isSafeZipEntryPath(rawName: String?): Boolean {
            if (rawName.isNullOrBlank()) return false
            if (rawName.contains('\u0000') || rawName.contains('\\')) return false
            if (rawName.startsWith("/")) return false
            if (rawName.length >= 2 && rawName[1] == ':') return false
            val trimmed = rawName.removeSuffix("/")
            if (trimmed.isEmpty()) return false
            val segments = trimmed.split('/')
            return segments.none { it.isEmpty() || it == "." || it == ".." }
        }

        fun sanitizeEvidenceFileName(rawName: String?): String? {
            val trimmed = rawName?.trim() ?: return null
            if (trimmed == "." || trimmed == ".." || trimmed.contains("..")) return null
            return trimmed.takeIf { SAFE_EVIDENCE_NAME.matches(it) }
        }

        fun safeTargetFile(baseDir: File, fileName: String): File? {
            val cleanName = sanitizeEvidenceFileName(fileName) ?: return null
            val baseCanonical = baseDir.canonicalFile
            val targetCanonical = File(baseCanonical, cleanName).canonicalFile
            if (targetCanonical.parentFile != baseCanonical) return null
            return targetCanonical
        }

        fun defaultZipFileName(
            date: Date = Date(),
            timeZone: TimeZone = TimeZone.getDefault(),
        ): String {
            val format = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).apply {
                this.timeZone = timeZone
            }
            return "open-margin-backup-${format.format(date)}.zip"
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
