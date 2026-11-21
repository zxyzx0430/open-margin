package com.bookkeeping.app.update

import android.content.Context
import com.bookkeeping.app.BuildConfig

/**
 * Glue between the ledger banner, the settings card and the background worker.
 *
 * Only the production package may check/download. Test builds get the banner
 * (preview or, in the future, a real manifest) purely as UI and never touch the
 * network or the installer.
 */
object SiteUpdateCoordinator {
    private val lock = Any()

    /** Cached manifest for the settings row; ignores banner dismissal. */
    fun cachedManifest(context: Context): SiteUpdatePolicy.SiteUpdateManifest? {
        if (!SiteUpdatePolicy.isOfficialPackage(context.packageName)) return null
        val prefs = SiteUpdatePrefs(context)
        return prefs.cachedManifestJson()?.let {
            SiteUpdatePolicy.parse(it, BuildConfig.VERSION_CODE)
        }
    }

    /**
     * Banner state for the ledger page. Test builds only ever see the explicit
     * preview sample; production builds see the cached manifest unless the
     * version was dismissed or the banner was disabled.
     */
    fun bannerFor(context: Context): SiteUpdatePolicy.BannerState? {
        val packageName = context.packageName
        if (SiteUpdatePolicy.isTestPackage(packageName)) {
            val prefs = SiteUpdatePrefs(context)
            if (!prefs.previewBannerEnabled()) return null
            val preview = SiteUpdatePolicy.previewBanner(
                BuildConfig.VERSION_CODE,
                BuildConfig.VERSION_NAME,
            )
            if (prefs.dismissedVersionCode() >= preview.versionCode) return null
            return preview
        }
        if (!SiteUpdatePolicy.isOfficialPackage(packageName)) return null
        val prefs = SiteUpdatePrefs(context)
        val manifest = cachedManifest(context) ?: return null
        if (!SiteUpdatePolicy.shouldShowBanner(
                manifestVersionCode = manifest.versionCode,
                installedVersionCode = BuildConfig.VERSION_CODE,
                dismissedVersionCode = prefs.dismissedVersionCode(),
                bannerEnabled = prefs.bannerEnabled(),
            )
        ) {
            return null
        }
        return SiteUpdatePolicy.BannerState(manifest.versionCode, manifest.versionName)
    }

    /** 24-hour gate: opening the ledger must not query the site on every visit. */
    fun refreshIfDue(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): SiteUpdatePolicy.SiteUpdateManifest? {
        if (!SiteUpdatePolicy.isOfficialPackage(context.packageName)) return null
        val prefs = SiteUpdatePrefs(context)
        if (!prefs.bannerEnabled() && !prefs.autoDownloadEnabled()) return null
        if (!SiteUpdatePolicy.isCheckDue(prefs.lastCheckMs(), nowMs)) {
            return cachedManifest(context)
        }
        return refresh(context, nowMs)
    }

    /**
     * Fetches the manifest, caches it, and starts the background download when
     * the user opted in. Network/parse failures keep the previous cache.
     */
    fun refresh(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): SiteUpdatePolicy.SiteUpdateManifest? {
        return synchronized(lock) {
            if (!SiteUpdatePolicy.isOfficialPackage(context.packageName)) {
                return@synchronized null
            }
            val prefs = SiteUpdatePrefs(context)
            if (!prefs.bannerEnabled() && !prefs.autoDownloadEnabled()) {
                return@synchronized null
            }
            if (!force && !SiteUpdatePolicy.isCheckDue(prefs.lastCheckMs(), nowMs)) {
                return@synchronized cachedManifest(context)
            }
            when (val result = SiteUpdateChecker.check()) {
                is SiteUpdateChecker.Result.Available -> {
                    prefs.setLastCheckMs(nowMs)
                    prefs.setCachedManifestJson(result.rawJson)
                    if (prefs.autoDownloadEnabled()) {
                        SiteUpdateWorker.enqueue(context, result.rawJson)
                    }
                    result.manifest
                }
                SiteUpdateChecker.Result.UpToDate -> {
                    prefs.setLastCheckMs(nowMs)
                    prefs.setCachedManifestJson(null)
                    null
                }
                // Leave the timestamp untouched so the next entry retries
                // instead of hiding updates for another 24 hours.
                SiteUpdateChecker.Result.Unavailable -> cachedManifest(context)
            }
        }
    }

    /** Called when the settings switch is flipped on: check and download now. */
    fun onAutoDownloadEnabled(context: Context) {
        if (!SiteUpdatePolicy.canAutoDownload(context.packageName)) return
        refresh(context, System.currentTimeMillis(), force = true)
    }

    /** Manual settings check found a manifest: keep it for the banner later. */
    fun rememberFoundManifest(
        context: Context,
        manifest: SiteUpdatePolicy.SiteUpdateManifest,
        rawJson: String,
    ) {
        if (!SiteUpdatePolicy.isOfficialPackage(context.packageName)) return
        if (manifest.versionCode <= BuildConfig.VERSION_CODE) return
        val prefs = SiteUpdatePrefs(context)
        prefs.setLastCheckMs(System.currentTimeMillis())
        prefs.setCachedManifestJson(rawJson)
    }

    fun dismissBanner(context: Context, versionCode: Int) {
        SiteUpdatePrefs(context).dismissVersion(versionCode)
    }
}
