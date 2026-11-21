package com.bookkeeping.app.update

import android.content.Context

/**
 * Local state for the self-hosted update channel. SharedPreferences (not
 * DataStore) because the download worker and the banner need a synchronous
 * snapshot and none of these values belong to the ledger.
 */
class SiteUpdatePrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCheckMs(): Long = prefs.getLong(KEY_LAST_CHECK_MS, 0L)

    fun setLastCheckMs(value: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK_MS, value).apply()
    }

    /** Default off: downloading an APK automatically is opt-in. */
    fun autoDownloadEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_DOWNLOAD, false)

    fun setAutoDownloadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, enabled).apply()
    }

    /** Default on: a new stable version is worth one unobtrusive row. */
    fun bannerEnabled(): Boolean = prefs.getBoolean(KEY_BANNER, true)

    fun setBannerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BANNER, enabled).apply()
    }

    /** The last version the user closed; it must never be shown again. */
    fun dismissedVersionCode(): Int = prefs.getInt(KEY_DISMISSED_VERSION, 0)

    fun dismissVersion(versionCode: Int) {
        prefs.edit().putInt(KEY_DISMISSED_VERSION, versionCode).apply()
    }

    fun cachedManifestJson(): String? = prefs.getString(KEY_CACHED_MANIFEST, null)

    fun setCachedManifestJson(json: String?) {
        prefs.edit().putString(KEY_CACHED_MANIFEST, json).apply()
    }

    fun pendingDownloadId(): Long = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, 0L)

    fun pendingVersionCode(): Int = prefs.getInt(KEY_PENDING_VERSION, 0)

    fun setPendingDownload(downloadId: Long, versionCode: Int) {
        prefs.edit()
            .putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
            .putInt(KEY_PENDING_VERSION, versionCode)
            .apply()
    }

    fun clearPendingDownload() {
        prefs.edit()
            .remove(KEY_PENDING_DOWNLOAD_ID)
            .remove(KEY_PENDING_VERSION)
            .apply()
    }

    /** Avoid re-posting the "downloaded" notification for the same version. */
    fun notifiedVersionCode(): Int = prefs.getInt(KEY_NOTIFIED_VERSION, 0)

    fun setNotifiedVersionCode(versionCode: Int) {
        prefs.edit().putInt(KEY_NOTIFIED_VERSION, versionCode).apply()
    }

    /**
     * Debug/test builds only. Renders a sample banner so the interaction can be
     * verified even when the published manifest matches the installed build.
     */
    fun previewBannerEnabled(): Boolean = prefs.getBoolean(KEY_PREVIEW_BANNER, false)

    fun setPreviewBannerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_BANNER, enabled).apply()
        if (enabled) {
            // Re-enabling the preview is an explicit request to see the sample
            // banner again, so a previous preview dismissal is cleared.
            prefs.edit().remove(KEY_DISMISSED_VERSION).apply()
        }
    }

    private companion object {
        const val PREFS = "open_margin_site_update"
        const val KEY_LAST_CHECK_MS = "last_check_ms"
        const val KEY_AUTO_DOWNLOAD = "auto_download"
        const val KEY_BANNER = "banner_enabled"
        const val KEY_DISMISSED_VERSION = "dismissed_version_code"
        const val KEY_CACHED_MANIFEST = "cached_manifest"
        const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
        const val KEY_PENDING_VERSION = "pending_version_code"
        const val KEY_NOTIFIED_VERSION = "notified_version_code"
        const val KEY_PREVIEW_BANNER = "preview_banner"
    }
}
