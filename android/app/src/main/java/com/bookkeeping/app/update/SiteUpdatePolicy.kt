package com.bookkeeping.app.update

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Rules for the self-hosted stable update manifest at
 * `https://zxyzx.me/open-margin/installer/latest.json`.
 *
 * The manifest is fetched from a plain static file instead of the GitHub API:
 * `api.github.com` is routinely unreachable from mainland China, while the
 * project site stays reachable. Everything here is pure Kotlin so every hard
 * rule can be covered by JVM unit tests.
 */
object SiteUpdatePolicy {
    const val MANIFEST_URL = "https://zxyzx.me/open-margin/installer/latest.json"
    const val SITE_HOST = "zxyzx.me"
    const val STABLE_CHANNEL = "stable"
    const val OFFICIAL_PACKAGE = "com.bookkeeping.app"
    const val TEST_PACKAGE = "com.bookkeeping.app.sync3"
    private const val LEGACY_TEST_PACKAGE = "com.bookkeeping.app.sync2"

    /** Minimum cooldown between silent checks on cold start / foreground resume. */
    const val CHECK_INTERVAL_MS = 5L * 60L * 1000L

    private const val APK_PREFIX = "https://zxyzx.me/open-margin/installer/"
    private const val MAX_NOTES = 5

    data class SiteUpdateManifest(
        val channel: String,
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String,
        val notes: List<String>,
    )

    /** What the ledger banner needs; the debug preview can supply it directly. */
    data class BannerState(
        val versionCode: Int,
        val versionName: String,
        val summaryNote: String? = null,
        val isPreview: Boolean = false,
    )

    fun isOfficialPackage(packageName: String?): Boolean = packageName == OFFICIAL_PACKAGE

    /** Test builds may render the banner for UI verification, nothing else. */
    fun isTestPackage(packageName: String?): Boolean =
        packageName == TEST_PACKAGE || packageName == LEGACY_TEST_PACKAGE

    fun canShowBanner(packageName: String?): Boolean =
        isOfficialPackage(packageName) || isTestPackage(packageName)

    /**
     * Auto download + installer hand-off is reserved for the production
     * application id: a test build installing the production APK would create
     * a second app (and a second ledger).
     */
    fun canAutoDownload(packageName: String?): Boolean = isOfficialPackage(packageName)

    /** Manifest URLs must be HTTPS on the exact project host, no subdomains. */
    fun isTrustedSiteUrl(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return false
        if (value.any { it.isWhitespace() }) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host ?: return false
        if (!host.equals(SITE_HOST, ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        return true
    }

    /** Release candidates are never downloaded, however they are spelled. */
    fun isReleaseCandidate(versionName: String?): Boolean {
        val lower = versionName?.lowercase().orEmpty()
        return lower.contains("-rc") || lower.contains("rc.")
    }

    /** Bare 64-character hex digest; the manifest has no `sha256:` prefix. */
    fun normalizeSha256(raw: String?): String? {
        val hex = raw?.trim()?.lowercase() ?: return null
        if (hex.length != 64) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return hex
    }

    /**
     * Validates every hard rule except "newer than installed". Returning null
     * means the manifest must be discarded silently — no error dialog.
     */
    fun parseManifest(json: String?): SiteUpdateManifest? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

        if (root.optString("channel").trim() != STABLE_CHANNEL) return null

        val versionName = root.optString("versionName").trim()
        if (versionName.isEmpty()) return null
        if (isReleaseCandidate(versionName)) return null

        val versionCode = root.optInt("versionCode", -1)
        if (versionCode <= 0) return null

        val sha256 = normalizeSha256(root.optString("sha256")) ?: return null

        val size = root.optLong("size", 0L)
        if (size <= 0L) return null

        val apkUrl = root.optString("apkUrl").trim()
        if (!isTrustedSiteUrl(apkUrl)) return null
        // Defence in depth: the URL must stay inside the project's own
        // installer directory and end in a boring `.apk` file name.
        if (!apkUrl.startsWith(APK_PREFIX)) return null
        if (apkUrl.contains('?') || apkUrl.contains('#')) return null
        if (apkUrl.contains("..")) return null
        val fileName = apkUrl.substringAfterLast('/')
        if (!UpdateUrlPolicy.isSafeApkName(fileName)) return null

        return SiteUpdateManifest(
            channel = STABLE_CHANNEL,
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            size = size,
            sha256 = sha256,
            notes = notes(root.optJSONArray("notes")),
        )
    }

    /** Full gate used by the banner, the cached manifest and the downloader. */
    fun parse(json: String?, currentVersionCode: Int): SiteUpdateManifest? {
        val manifest = parseManifest(json) ?: return null
        return manifest.takeIf { it.versionCode > currentVersionCode }
    }

    /** A dismissed version stays dismissed; a higher version reminds again. */
    fun shouldShowBanner(
        manifestVersionCode: Int,
        installedVersionCode: Int,
        dismissedVersionCode: Int,
        bannerEnabled: Boolean,
        snoozedInSession: Boolean = false,
    ): Boolean =
        bannerEnabled &&
            !snoozedInSession &&
            manifestVersionCode > installedVersionCode &&
            manifestVersionCode > dismissedVersionCode

    /** A clock rollback also counts as due, so a broken clock cannot pin the check. */
    fun isCheckDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    /** Test-only banner sample: next version code and next minor version. */
    fun previewBanner(currentVersionCode: Int, currentVersionName: String): BannerState =
        BannerState(
            versionCode = if (currentVersionCode == Int.MAX_VALUE) currentVersionCode else currentVersionCode + 1,
            versionName = nextMinorVersion(currentVersionName),
            summaryNote = "示例更新摘要：点击「立即更新」可在测试环境预览入口",
            isPreview = true,
        )

    fun nextMinorVersion(versionName: String): String {
        val parts = versionName.trim().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return "1.0"
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return "$major.${minor + 1}"
    }

    private fun notes(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val line = array.optString(index).trim()
            if (line.isNotEmpty()) result += line
            if (result.size == MAX_NOTES) break
        }
        return result
    }
}

/** Adapts a site manifest to the existing download/install dialog types. */
fun SiteUpdatePolicy.SiteUpdateManifest.asCheckResult():
    GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable =
    GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable(
        tag = "v$versionName",
        releaseUrl = "https://zxyzx.me/open-margin/installer/",
        componentScope = UpdateComponentScope.ANDROID_ONLY,
        apkUrl = apkUrl,
        apkTag = "v$versionName",
        apkSizeBytes = size,
        apkSha256 = sha256,
        notes = notes,
    )

