package com.bookkeeping.app.backup

import android.content.Context
import com.bookkeeping.app.capture.screenshot.ReceiptEvidenceSettings
import com.bookkeeping.app.capture.screenshot.ReceiptEvidenceStore
import com.bookkeeping.app.data.budget.BudgetMonth
import com.bookkeeping.app.data.budget.BudgetPreferences
import com.bookkeeping.app.data.budget.BudgetSettings
import com.bookkeeping.app.data.local.dao.SubscriptionDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.merchant.MerchantRules
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.i18n.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidBackupStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: TransactionDao,
    private val transactionRepository: TransactionRepository,
    private val subscriptionDao: SubscriptionDao,
    private val budgetPreferences: BudgetPreferences,
    private val settingsPreferences: SettingsPreferences,
) : BackupStorage {

    override suspend fun readTransactions(): List<TransactionEntity> =
        transactionDao.observeAll().first()

    override suspend fun insertTransactions(inserts: List<TransactionEntity>): Int =
        if (inserts.isEmpty()) 0 else transactionRepository.insertImportedBatch(inserts)

    override suspend fun updateTransactions(updates: List<TransactionEntity>): Int =
        if (updates.isEmpty()) 0
        else transactionRepository.importBillBatch(inserts = emptyList(), updates = updates).second

    override suspend fun readSubscriptions(): List<SubscriptionEntity> =
        subscriptionDao.getAllNonDeleted()

    override suspend fun upsertSubscription(subscription: SubscriptionEntity) {
        subscriptionDao.upsert(subscription)
    }

    override suspend fun readBudgets(transactionMonths: Set<String>): List<BudgetMonthBackup> {
        val candidateMonths = linkedSetOf<String>()
        val cal = Calendar.getInstance(Locale.ROOT)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MONTH, -BUDGET_SCAN_PAST_MONTHS)
        repeat(BUDGET_SCAN_PAST_MONTHS + BUDGET_SCAN_FUTURE_MONTHS + 1) {
            candidateMonths += BudgetMonth.keyOf(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
            cal.add(Calendar.MONTH, 1)
        }
        transactionMonths.filterTo(candidateMonths) { BudgetMonth.isValid(it) }

        return candidateMonths.mapNotNull { month ->
            val settings = budgetPreferences.observe(month).first()
            if (settings.monthlyTotalCents > 0L || settings.perCategory.isNotEmpty()) {
                BudgetMonthBackup(
                    month = month,
                    monthlyTotalCents = settings.monthlyTotalCents,
                    perCategory = settings.perCategory.mapKeys { it.key.name },
                )
            } else {
                null
            }
        }
    }

    override suspend fun writeBudget(
        month: String,
        settings: BudgetSettings,
        overwrite: Boolean,
    ): Boolean {
        val current = budgetPreferences.observe(month).first()
        if (overwrite) {
            budgetPreferences.setMonthlyTotal(month, settings.monthlyTotalCents)
            current.perCategory.keys
                .filter { it !in settings.perCategory }
                .forEach { category -> budgetPreferences.setCategoryBudget(month, category, 0L) }
            settings.perCategory.forEach { (category, cents) ->
                budgetPreferences.setCategoryBudget(month, category, cents)
            }
            return true
        }

        var changed = false
        if (current.monthlyTotalCents == 0L && settings.monthlyTotalCents > 0L) {
            budgetPreferences.setMonthlyTotal(month, settings.monthlyTotalCents)
            changed = true
        }
        settings.perCategory.forEach { (category, cents) ->
            if ((current.perCategory[category] ?: 0L) == 0L && cents > 0L) {
                budgetPreferences.setCategoryBudget(month, category, cents)
                changed = true
            }
        }
        return changed
    }

    override suspend fun readMerchantRules(): MerchantRulesBackup {
        val explicit = settingsPreferences.merchantRulesBlocking()
            .mapValues { (_, cat) -> cat.name }
        val (counts, disabled) = settingsPreferences.categoryLearningBlocking()
        val encodedCounts = counts.mapValues { (_, catMap) ->
            catMap.mapKeys { (cat, _) -> cat.name }
        }
        return MerchantRulesBackup(
            rules = explicit,
            learningCounts = encodedCounts,
            learningDisabled = disabled.sorted(),
        )
    }

    override suspend fun writeMerchantRules(
        data: MerchantRulesBackup,
        overwrite: Boolean,
    ): Pair<Int, Int> {
        val existingExplicit = settingsPreferences.merchantRulesBlocking()
        val (existingCounts, existingDisabled) = settingsPreferences.categoryLearningBlocking()
        val incomingDisabled = data.learningDisabled.mapNotNull {
            MerchantRules.normalize(it).takeIf(String::isNotBlank)
        }.toSet()

        var restored = 0
        var skipped = 0
        data.rules.forEach { (rawMerchant, rawCategory) ->
            val key = MerchantRules.normalize(rawMerchant)
            val category = runCatching { TransactionCategory.valueOf(rawCategory) }.getOrNull()
                ?: return@forEach
            if (key.isBlank()) return@forEach
            if (!overwrite && existingExplicit.containsKey(key)) {
                skipped++
            } else {
                val paused = key in incomingDisabled || key in existingDisabled
                settingsPreferences.saveMerchantRule(
                    previousMerchant = null,
                    merchant = key,
                    category = category,
                    learningPaused = paused,
                )
                restored++
            }
        }

        data.learningCounts.forEach { (rawMerchant, catCounts) ->
            val key = MerchantRules.normalize(rawMerchant)
            if (key.isBlank()) return@forEach
            if (overwrite || !existingCounts.containsKey(key)) {
                catCounts.forEach { (rawCategory, count) ->
                    val category = runCatching { TransactionCategory.valueOf(rawCategory) }.getOrNull()
                        ?: return@forEach
                    repeat(count.coerceIn(0, MAX_LEARNING_COUNT_REPLAY)) {
                        settingsPreferences.recordCategoryLearning(key, category)
                    }
                }
            }
        }

        incomingDisabled.forEach { key ->
            if (overwrite || key !in existingDisabled) {
                settingsPreferences.disableCategoryLearning(key, true)
            }
        }

        return restored to skipped
    }

    override suspend fun readNonSensitiveSettings(): Map<String, Any?> {
        val calPrefs = context.applicationContext.getSharedPreferences(
            CALENDAR_PREFS,
            Context.MODE_PRIVATE,
        )
        return buildMap {
            if (AppLanguage.hasStoredChoice(context)) {
                put(FullBackupManager.SETTING_LANGUAGE, AppLanguage.read(context))
            }
            put(FullBackupManager.SETTING_KEEP_ALIVE, settingsPreferences.observeKeepAliveEnabled().first())
            put(
                FullBackupManager.SETTING_SUCCESS_NOTIFICATIONS,
                settingsPreferences.observeSuccessNotificationsEnabled().first(),
            )
            put(
                FullBackupManager.SETTING_EVIDENCE_RETENTION_MODE,
                ReceiptEvidenceSettings.retentionMode(context).key,
            )
            calPrefs.getString(KEY_HEATMAP, null)?.let {
                put(FullBackupManager.SETTING_CALENDAR_HEATMAP, it)
            }
            if (calPrefs.contains(KEY_WEEK_START_MONDAY)) {
                put(
                    FullBackupManager.SETTING_CALENDAR_WEEK_START_MONDAY,
                    calPrefs.getBoolean(KEY_WEEK_START_MONDAY, false),
                )
            }
            if (calPrefs.contains(KEY_LUNAR)) {
                put(
                    FullBackupManager.SETTING_CALENDAR_SHOW_LUNAR,
                    calPrefs.getBoolean(KEY_LUNAR, true),
                )
            }
            calPrefs.getString(KEY_CALENDAR_NUMBERS, null)?.let {
                put(FullBackupManager.SETTING_CALENDAR_NUMBER_MODE, it)
            }
        }
    }

    override suspend fun writeNonSensitiveSettings(
        settings: Map<String, Any?>,
        overwrite: Boolean,
    ): SettingsWriteResult {
        var applied = 0
        var skipped = 0

        (settings[FullBackupManager.SETTING_LANGUAGE] as? String)?.let { rawLang ->
            val lang = AppLanguage.normalize(rawLang)
            if (overwrite || !AppLanguage.hasStoredChoice(context)) {
                if (lang != AppLanguage.read(context)) {
                    withContext(Dispatchers.Main) {
                        AppLanguage.set(context, lang)
                    }
                } else if (!AppLanguage.hasStoredChoice(context)) {
                    withContext(Dispatchers.Main) {
                        AppLanguage.set(context, lang)
                    }
                }
                applied++
            } else {
                skipped++
            }
        }

        (settings[FullBackupManager.SETTING_KEEP_ALIVE] as? Boolean)?.let { enabled ->
            val current = settingsPreferences.observeKeepAliveEnabled().first()
            if (overwrite || (!current && enabled)) {
                settingsPreferences.setKeepAliveEnabled(enabled)
                applied++
            } else {
                skipped++
            }
        }

        (settings[FullBackupManager.SETTING_SUCCESS_NOTIFICATIONS] as? Boolean)?.let { enabled ->
            val current = settingsPreferences.observeSuccessNotificationsEnabled().first()
            if (overwrite || (current && !enabled)) {
                settingsPreferences.setSuccessNotificationsEnabled(enabled)
                applied++
            } else {
                skipped++
            }
        }

        (settings[FullBackupManager.SETTING_EVIDENCE_RETENTION_MODE] as? String)?.let { rawMode ->
            val evidencePrefs = context.applicationContext.getSharedPreferences(
                EVIDENCE_PREFS,
                Context.MODE_PRIVATE,
            )
            if (overwrite || !evidencePrefs.contains(KEY_RETENTION_MODE)) {
                ReceiptEvidenceSettings.setRetentionMode(
                    context,
                    ReceiptEvidenceSettings.RetentionMode.fromKey(rawMode),
                )
                applied++
            } else {
                skipped++
            }
        }

        val calPrefs = context.applicationContext.getSharedPreferences(
            CALENDAR_PREFS,
            Context.MODE_PRIVATE,
        )
        val calEditor = calPrefs.edit()
        var calDirty = false

        (settings[FullBackupManager.SETTING_CALENDAR_HEATMAP] as? String)?.takeIf {
            it in VALID_HEATMAP_MODES
        }?.let { mode ->
            if (overwrite || !calPrefs.contains(KEY_HEATMAP)) {
                calEditor.putString(KEY_HEATMAP, mode)
                calDirty = true
                applied++
            } else {
                skipped++
            }
        }

        (settings[FullBackupManager.SETTING_CALENDAR_WEEK_START_MONDAY] as? Boolean)?.let { monday ->
            if (overwrite || !calPrefs.contains(KEY_WEEK_START_MONDAY)) {
                calEditor.putBoolean(KEY_WEEK_START_MONDAY, monday)
                calDirty = true
                applied++
            } else {
                skipped++
            }
        }

        (settings[FullBackupManager.SETTING_CALENDAR_SHOW_LUNAR] as? Boolean)?.let { lunar ->
            if (overwrite || !calPrefs.contains(KEY_LUNAR)) {
                calEditor.putBoolean(KEY_LUNAR, lunar)
                calDirty = true
                applied++
            } else {
                skipped++
            }
        }

        (settings[FullBackupManager.SETTING_CALENDAR_NUMBER_MODE] as? String)?.takeIf {
            it in VALID_CALENDAR_NUMBER_MODES
        }?.let { mode ->
            if (overwrite || !calPrefs.contains(KEY_CALENDAR_NUMBERS)) {
                calEditor.putString(KEY_CALENDAR_NUMBERS, mode)
                calDirty = true
                applied++
            } else {
                skipped++
            }
        }

        if (calDirty) {
            calEditor.apply()
        }

        return SettingsWriteResult(appliedCount = applied, skippedCount = skipped)
    }

    override fun listEvidenceFiles(): Map<String, File> {
        val dir = evidenceTargetDir()
        val files = dir.listFiles() ?: return emptyMap()
        return files.mapNotNull { file ->
            val safe = ReceiptEvidenceStore.sanitizeFileName(file.name) ?: return@mapNotNull null
            if (file.isFile && file.length() > 0L) safe to file else null
        }.toMap()
    }

    override fun evidenceTargetDir(): File =
        File(context.applicationContext.filesDir, ReceiptEvidenceStore.DIR_NAME)

    companion object {
        private const val BUDGET_SCAN_PAST_MONTHS = 36
        private const val BUDGET_SCAN_FUTURE_MONTHS = 12
        private const val MAX_LEARNING_COUNT_REPLAY = 20

        private const val EVIDENCE_PREFS = "receipt_evidence_settings"
        private const val KEY_RETENTION_MODE = "retention_mode"

        private const val CALENDAR_PREFS = "calendar_view_prefs"
        private const val KEY_HEATMAP = "heatmap"
        private const val KEY_WEEK_START_MONDAY = "week_start_monday"
        private const val KEY_LUNAR = "lunar"
        private const val KEY_CALENDAR_NUMBERS = "calendar_numbers"

        private val VALID_HEATMAP_MODES = setOf("EXPENSE", "INCOME", "NET")
        private val VALID_CALENDAR_NUMBER_MODES = setOf("OFF", "EXPENSE", "NET")
    }
}
