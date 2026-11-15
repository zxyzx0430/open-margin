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

    // 同步读快照值。在 MainActivity.onCreate 里只需要"当前值"决定是否启动 Service，
    // 不需要 observe；用 runBlocking 拿一下，DataStore 本地读很快不会卡 UI。
    fun keepAliveEnabledBlocking(): Boolean = runBlocking {
        context.settingsDataStore.data.first()[KEY_KEEP_ALIVE] ?: false
    }

    fun observeMerchantRules(): Flow<Map<String, TransactionCategory>> =
        context.settingsDataStore.data.map { parseMerchantRules(it[KEY_MERCHANT_RULES]) }

    fun merchantRulesBlocking(): Map<String, TransactionCategory> = runBlocking {
        parseMerchantRules(context.settingsDataStore.data.first()[KEY_MERCHANT_RULES])
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
        context.settingsDataStore.edit { it.remove(KEY_MERCHANT_RULES) }
        MerchantRules.clear()
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
        }
        MerchantRules.remove(key)
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
        // Legacy success_page_capture_enabled and compatibility_accessibility_enabled
        // keys are intentionally ignored: page recognition now runs with the service.
    }
}
