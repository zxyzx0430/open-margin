package com.bookkeeping.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.MerchantRules
import com.bookkeeping.app.notification.CategoryLearning
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// 顶层属性：app 全局只有一份 settings DataStore
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings_prefs")

// 全局设置项。目前只放前台保活开关，后续可以扩展。
@Singleton
class SettingsPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun observeKeepAliveEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_KEEP_ALIVE] ?: false }

    suspend fun setKeepAliveEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_KEEP_ALIVE] = enabled }
    }

    fun observeSuccessNotificationsEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_SUCCESS_NOTIFICATIONS] ?: true }

    suspend fun setSuccessNotificationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_SUCCESS_NOTIFICATIONS] = enabled }
    }

    suspend fun successNotificationsEnabledNow(): Boolean =
        context.settingsDataStore.data.first()[KEY_SUCCESS_NOTIFICATIONS] ?: true

    /**
     * 采集自动合并的一次性纠正标记。只写 prefs，不动数据库结构；版本号变化
     * （例如以后规则再收紧）会重新跑一遍纠正。
     */
    suspend fun captureDuplicateRepairDone(): Boolean =
        context.settingsDataStore.data.first()[KEY_CAPTURE_DUPLICATE_REPAIR] ==
            CAPTURE_DUPLICATE_REPAIR_VERSION

    suspend fun markCaptureDuplicateRepairDone() {
        context.settingsDataStore.edit {
            it[KEY_CAPTURE_DUPLICATE_REPAIR] = CAPTURE_DUPLICATE_REPAIR_VERSION
        }
    }

    // 同步读快照值。在 MainActivity.onCreate 里只需要"当前值"决定是否启动 Service，
    // 不需要 observe；用 runBlocking 拿一下，DataStore 本地读很快不会卡 UI。
    fun keepAliveEnabledBlocking(): Boolean = runBlocking {
        context.settingsDataStore.data.first()[KEY_KEEP_ALIVE] ?: false
    }

    fun observeMerchantRules(): Flow<Map<String, TransactionCategory>> =
        context.settingsDataStore.data.map { prefs ->
            val (counts, disabled) = parseCategoryLearning(prefs[KEY_CATEGORY_LEARNING])
            val learned = counts.mapNotNull { (merchant, categories) ->
                if (merchant in disabled) return@mapNotNull null
                val sorted = categories.entries.sortedByDescending { it.value }
                val first = sorted.firstOrNull() ?: return@mapNotNull null
                val second = sorted.getOrNull(1)?.value ?: 0
                if (first.value >= 2 && first.value > second) merchant to first.key else null
            }.toMap()
            learned + parseMerchantRules(prefs[KEY_MERCHANT_RULES])
        }

    fun merchantRulesBlocking(): Map<String, TransactionCategory> = runBlocking {
        parseMerchantRules(context.settingsDataStore.data.first()[KEY_MERCHANT_RULES])
    }

    fun observeLearningDisabled(): Flow<Set<String>> =
        context.settingsDataStore.data.map { prefs ->
            parseCategoryLearning(prefs[KEY_CATEGORY_LEARNING]).second
        }

    fun categoryLearningBlocking(): Pair<Map<String, Map<TransactionCategory, Int>>, Set<String>> =
        runBlocking {
            parseCategoryLearning(context.settingsDataStore.data.first()[KEY_CATEGORY_LEARNING])
        }

    suspend fun recordCategoryLearning(merchant: String, category: TransactionCategory) {
        val key = MerchantRules.normalize(merchant)
        if (key.isBlank()) return
        context.settingsDataStore.edit { prefs ->
            val (counts, disabled) = parseCategoryLearning(prefs[KEY_CATEGORY_LEARNING])
            val updated = counts.toMutableMap()
            val merchantCounts = updated[key].orEmpty().toMutableMap()
            merchantCounts[category] = (merchantCounts[category] ?: 0) + 1
            updated[key] = merchantCounts
            prefs[KEY_CATEGORY_LEARNING] = encodeCategoryLearning(updated, disabled)
        }
        CategoryLearning.record(key, category)
    }

    suspend fun removeCategoryLearning(merchant: String) {
        val key = MerchantRules.normalize(merchant)
        if (key.isBlank()) return
        context.settingsDataStore.edit { prefs ->
            val (counts, disabled) = parseCategoryLearning(prefs[KEY_CATEGORY_LEARNING])
            val updated = counts.toMutableMap()
            updated.remove(key)
            val updatedDisabled = disabled.toMutableSet().also { it.remove(key) }
            prefs[KEY_CATEGORY_LEARNING] = encodeCategoryLearning(updated, updatedDisabled)
        }
        CategoryLearning.remove(key)
    }

    suspend fun disableCategoryLearning(merchant: String, disabled: Boolean) {
        val key = MerchantRules.normalize(merchant)
        if (key.isBlank()) return
        context.settingsDataStore.edit { prefs ->
            val (counts, currentDisabled) = parseCategoryLearning(prefs[KEY_CATEGORY_LEARNING])
            val updatedDisabled = currentDisabled.toMutableSet()
            if (disabled) updatedDisabled.add(key) else updatedDisabled.remove(key)
            prefs[KEY_CATEGORY_LEARNING] = encodeCategoryLearning(counts, updatedDisabled)
        }
        if (disabled) CategoryLearning.disable(key) else CategoryLearning.enable(key)
    }

    suspend fun rememberMerchantCategory(merchant: String, category: TransactionCategory) {
        val key = MerchantRules.normalize(merchant)
        if (key.isBlank()) return
        context.settingsDataStore.edit { prefs ->
            val rules = parseMerchantRules(prefs[KEY_MERCHANT_RULES]).toMutableMap()
            rules[key] = category
            val json = JSONObject()
            rules.forEach { (merchant, value) -> json.put(merchant, value.name) }
            prefs[KEY_MERCHANT_RULES] = json.toString()
        }
        MerchantRules.put(key, category)
    }

    suspend fun clearMerchantRules() {
        context.settingsDataStore.edit {
            it.remove(KEY_MERCHANT_RULES)
            it.remove(KEY_CATEGORY_LEARNING)
        }
        MerchantRules.clear()
        CategoryLearning.clear()
    }

    suspend fun removeMerchantRule(merchant: String) {
        val key = MerchantRules.normalize(merchant)
        if (key.isBlank()) return
        context.settingsDataStore.edit { prefs ->
            val rules = parseMerchantRules(prefs[KEY_MERCHANT_RULES]).toMutableMap()
            rules.remove(key)
            val json = JSONObject()
            rules.forEach { (name, category) -> json.put(name, category.name) }
            prefs[KEY_MERCHANT_RULES] = json.toString()

            val (counts, disabled) = parseCategoryLearning(prefs[KEY_CATEGORY_LEARNING])
            val updatedCounts = counts.toMutableMap()
            updatedCounts.remove(key)
            val updatedDisabled = disabled.toMutableSet().also { it.remove(key) }
            prefs[KEY_CATEGORY_LEARNING] = encodeCategoryLearning(updatedCounts, updatedDisabled)
        }
        MerchantRules.remove(key)
        CategoryLearning.remove(key)
    }

    private fun parseCategoryLearning(
        raw: String?,
    ): Pair<Map<String, Map<TransactionCategory, Int>>, Set<String>> {
        if (raw.isNullOrBlank()) return emptyMap<String, Map<TransactionCategory, Int>>() to emptySet()
        return runCatching {
            val json = JSONObject(raw)
            val countsJson = json.optJSONObject("counts") ?: JSONObject()
            val counts = buildMap {
                countsJson.keys().forEach { merchant ->
                    val categoriesJson = countsJson.optJSONObject(merchant) ?: return@forEach
                    val values = buildMap {
                        categoriesJson.keys().forEach { category ->
                            runCatching {
                                TransactionCategory.valueOf(category) to categoriesJson.optInt(category)
                            }.getOrNull()
                                ?.takeIf { it.second > 0 }
                                ?.let { put(it.first, it.second) }
                        }
                    }
                    if (values.isNotEmpty()) put(merchant, values)
                }
            }
            val disabledJson = json.optJSONArray("disabled") ?: org.json.JSONArray()
            val disabled = buildSet {
                for (index in 0 until disabledJson.length()) {
                    disabledJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            counts to disabled
        }.getOrDefault(emptyMap<String, Map<TransactionCategory, Int>>() to emptySet())
    }

    private fun encodeCategoryLearning(
        counts: Map<String, Map<TransactionCategory, Int>>,
        disabled: Set<String>,
    ): String {
        val countsJson = JSONObject()
        counts.forEach { (merchant, categories) ->
            val merchantJson = JSONObject()
            categories.forEach { (category, count) -> merchantJson.put(category.name, count) }
            countsJson.put(merchant, merchantJson)
        }
        val disabledJson = org.json.JSONArray()
        disabled.sorted().forEach(disabledJson::put)
        return JSONObject().apply {
            put("counts", countsJson)
            put("disabled", disabledJson)
        }.toString()
    }

    private fun parseMerchantRules(raw: String?): Map<String, TransactionCategory> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { merchant ->
                    runCatching {
                        TransactionCategory.valueOf(json.getString(merchant))
                    }.getOrNull()?.let { put(merchant, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    companion object {
        private val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive_enabled")
        private val KEY_SUCCESS_NOTIFICATIONS =
            booleanPreferencesKey("success_notifications_enabled")
        private val KEY_MERCHANT_RULES = stringPreferencesKey("merchant_category_rules")
        private val KEY_CATEGORY_LEARNING = stringPreferencesKey("category_learning")
        private val KEY_CAPTURE_DUPLICATE_REPAIR =
            stringPreferencesKey("capture_duplicate_repair_version")
        private const val CAPTURE_DUPLICATE_REPAIR_VERSION = "1"
        // Legacy success_page_capture_enabled and compatibility_accessibility_enabled
        // keys are intentionally ignored: page recognition now runs with the service.
    }
}
