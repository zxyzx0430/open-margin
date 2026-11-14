package com.bookkeeping.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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

    /** Optional success-page capture. It is intentionally off until the user opts in. */
    fun observeSuccessPageCaptureEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_SUCCESS_PAGE_CAPTURE] ?: false }

    suspend fun setSuccessPageCaptureEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_SUCCESS_PAGE_CAPTURE] = enabled }
    }

    suspend fun successPageCaptureEnabledNow(): Boolean =
        context.settingsDataStore.data.first()[KEY_SUCCESS_PAGE_CAPTURE] ?: false

    /** The compatibility accessibility entry is separately opt-in. */
    fun observeCompatibilityAccessibilityEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_COMPATIBILITY_ACCESSIBILITY] ?: false }

    suspend fun setCompatibilityAccessibilityEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_COMPATIBILITY_ACCESSIBILITY] = enabled }
    }

    suspend fun compatibilityAccessibilityEnabledNow(): Boolean =
        context.settingsDataStore.data.first()[KEY_COMPATIBILITY_ACCESSIBILITY] ?: false

    // Synchronous snapshot for service startup; the value is tiny and local.
    fun successPageCaptureEnabledBlocking(): Boolean = runBlocking {
        context.settingsDataStore.data.first()[KEY_SUCCESS_PAGE_CAPTURE] ?: false
    }

    fun compatibilityAccessibilityEnabledBlocking(): Boolean = runBlocking {
        context.settingsDataStore.data.first()[KEY_COMPATIBILITY_ACCESSIBILITY] ?: false
    }

    // 同步读快照值。在 MainActivity.onCreate 里只需要"当前值"决定是否启动 Service，
    // 不需要 observe；用 runBlocking 拿一下，DataStore 本地读很快不会卡 UI。
    fun keepAliveEnabledBlocking(): Boolean = runBlocking {
        context.settingsDataStore.data.first()[KEY_KEEP_ALIVE] ?: false
    }

    companion object {
        private val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive_enabled")
        private val KEY_SUCCESS_NOTIFICATIONS =
            booleanPreferencesKey("success_notifications_enabled")
        private val KEY_SUCCESS_PAGE_CAPTURE =
            booleanPreferencesKey("success_page_capture_enabled")
        private val KEY_COMPATIBILITY_ACCESSIBILITY =
            booleanPreferencesKey("compatibility_accessibility_enabled")
    }
}
