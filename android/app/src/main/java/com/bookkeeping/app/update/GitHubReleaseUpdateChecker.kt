package com.bookkeeping.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Read-only public GitHub Releases query. Version update scope is declared by
 * release maintainers, never guessed from the presence of an APK or a title.
 * This does not inspect the user's installed Windows/VPS server version.
 */
object GitHubReleaseUpdateChecker {
    private const val RELEASES_API =
        "https://api.github.com/repos/zxyzx0430/open-margin/releases?per_page=30"
    private const val RELEASE_PAGE =
        "https://github.com/zxyzx0430/open-margin/releases/tag/"
    private const val RELEASE_DOWNLOAD =
        "https://github.com/zxyzx0430/open-margin/releases/download/"
    private const val APK_MIME = "application/vnd.android.package-archive"

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class UpdateAvailable(
            val tag: String,
            val releaseUrl: String,
            val componentScope: UpdateComponentScope,
            val apkUrl: String? = null,
        ) : CheckResult
        data object Unavailable : CheckResult
    }

    enum class InstallResult {
        OPENED_INSTALLER,
        NEEDS_USER_ACTION,
        FAILED,
    }

    /** Checks stable GitHub releases only; prerelease entries are filtered out. */
    fun checkStable(): CheckResult = check(includePrereleases = false)

    fun check(includePrereleases: Boolean): CheckResult {
        val current = ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG)
            ?: return CheckResult.Unavailable
        val connection = try {
            URL(RELEASES_API).openConnection() as HttpsURLConnection
        } catch (_: Exception) {
            return CheckResult.Unavailable
        }
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "OpenMargin-Android-Version-Check")
            if (connection.responseCode != 200) return CheckResult.Unavailable

            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val candidates = mutableListOf<PublishedRelease>()
            val apkUrls = mutableMapOf<String, String>()
            for (index in 0 until releases.length()) {
                val entry = releases.optJSONObject(index) ?: continue
                if (entry.optBoolean("draft", false)) continue
                val tag = entry.optString("tag_name")
                val version = ReleaseVersion.parse(tag) ?: continue
                val assets = entry.optJSONArray("assets") ?: continue
                var signedApk = false
                var downloadName: String? = null
                for (assetIndex in 0 until assets.length()) {
                    val asset = assets.optJSONObject(assetIndex) ?: continue
                    val name = asset.optString("name")
                    if (asset.optLong("size", 0L) <= 0L) continue
                    if (!UpdateVersionPolicy.isSignedApkName(name)) continue
                    signedApk = true
                    if (downloadName == null && isSafeAssetName(name)) {
                        downloadName = name
                    }
                }
                if (downloadName != null) {
                    // Fixed GitHub host plus a strict tag/asset-name allowlist.
                    // Never open a URL supplied by the JSON response.
                    apkUrls[tag] = RELEASE_DOWNLOAD + tag + "/" + downloadName
                }
                val scope = UpdateComponentScope.fromReleaseNotes(entry.optString("body", ""))
                // A tag carrying -rc.N is a prerelease even if the GitHub flag
                // was forgotten; stable-only checks must not offer it.
                val prerelease = entry.optBoolean("prerelease", false) ||
                    version.releaseCandidate != null
                candidates.add(
                    PublishedRelease(
                        tag, version, prerelease, signedApk, scope
                    )
                )
            }
            val newer = UpdateVersionPolicy.latestNewer(candidates, current, includePrereleases)
                ?: return CheckResult.UpToDate
            val apkUrl = candidates.asSequence()
                .filter { it.version > current }
                .filter { includePrereleases || !it.prerelease }
                .filter { apkUrls.containsKey(it.tag) }
                .maxByOrNull { it.version }
                ?.let { apkUrls[it.tag] }
            return CheckResult.UpdateAvailable(
                newer.tag,
                RELEASE_PAGE + newer.tag,
                newer.componentScope,
                apkUrl,
            )
        } catch (_: Exception) {
            return CheckResult.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    /** Enqueues the stable APK in the system DownloadManager. */
    fun enqueueApkDownload(context: Context, update: CheckResult.UpdateAvailable): Long? {
        val url = update.apkUrl ?: return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return null
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Open Margin " + update.tag)
            .setMimeType(APK_MIME)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        return runCatching { manager.enqueue(request) }.getOrNull()
    }

    /**
     * Best-effort installer launch. Android requires REQUEST_INSTALL_PACKAGES
     * for this call; when it is unavailable the caller should send the user to
     * the Downloads app, which can install the completed APK itself.
     */
    fun openDownloadedApk(context: Context, downloadId: Long): InstallResult {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return InstallResult.FAILED
        val uri = runCatching { manager.getUriForDownloadedFile(downloadId) }.getOrNull()
            ?: return InstallResult.FAILED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallResult.NEEDS_USER_ACTION
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (runCatching { context.startActivity(intent) }.isSuccess) {
            InstallResult.OPENED_INSTALLER
        } else {
            InstallResult.NEEDS_USER_ACTION
        }
    }

    fun openSystemDownloads(context: Context) {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun isSafeAssetName(name: String): Boolean =
        name.length in 5..128 &&
            name.endsWith(".apk") &&
            name.all { char ->
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
                    char == '.' || char == '_' || char == '-'
            }
}
