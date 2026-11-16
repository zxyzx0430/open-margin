package com.bookkeeping.app.update

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

    sealed interface CheckResult {
        data object UpToDate : CheckResult

        /**
         * [tag] is the newest published release (what the release page shows).
         * [apkTag] is the release the APK comes from: a newer server-only tag
         * can coexist with an older Android build, and installing that build
         * must not be labelled with the newer tag.
         */
        data class UpdateAvailable(
            val tag: String,
            val releaseUrl: String,
            val componentScope: UpdateComponentScope,
            val apkUrl: String? = null,
            val apkTag: String? = null,
            val apkSizeBytes: Long = 0L,
            val apkSha256: String? = null,
            val publishedAt: String? = null,
            val notes: List<String> = emptyList(),
        ) : CheckResult

        data object Unavailable : CheckResult
    }

    private data class ApkAsset(val url: String, val size: Long, val sha256: String?)

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
            val apkAssets = mutableMapOf<String, ApkAsset>()
            val publishedAt = mutableMapOf<String, String>()
            val bodies = mutableMapOf<String, String>()
            for (index in 0 until releases.length()) {
                val entry = releases.optJSONObject(index) ?: continue
                if (entry.optBoolean("draft", false)) continue
                val tag = entry.optString("tag_name")
                val version = ReleaseVersion.parse(tag) ?: continue
                val assets = entry.optJSONArray("assets") ?: continue
                var signedApk = false
                var downloadName: String? = null
                var downloadSize = 0L
                var downloadDigest: String? = null
                for (assetIndex in 0 until assets.length()) {
                    val asset = assets.optJSONObject(assetIndex) ?: continue
                    val name = asset.optString("name")
                    val size = asset.optLong("size", 0L)
                    if (size <= 0L) continue
                    if (!UpdateVersionPolicy.isSignedApkName(name)) continue
                    signedApk = true
                    if (downloadName == null && UpdateUrlPolicy.isSafeApkName(name)) {
                        downloadName = name
                        downloadSize = size
                        downloadDigest = ApkIntegrity.normalizeSha256(asset.optString("digest"))
                    }
                }
                if (downloadName != null) {
                    // Fixed GitHub host plus a strict tag/asset-name allowlist.
                    // Never open a URL supplied by the JSON response.
                    val url = RELEASE_DOWNLOAD + tag + "/" + downloadName
                    if (UpdateUrlPolicy.isAllowedAssetUrl(url)) {
                        apkAssets[tag] = ApkAsset(url, downloadSize, downloadDigest)
                    }
                }
                val body = entry.optString("body", "")
                bodies[tag] = body
                entry.optString("published_at").takeIf { it.isNotBlank() }?.let {
                    publishedAt[tag] = it
                }
                val scope = UpdateComponentScope.fromReleaseNotes(body)
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
            val apkRelease = candidates.asSequence()
                .filter { it.version > current }
                .filter { includePrereleases || !it.prerelease }
                .filter { apkAssets.containsKey(it.tag) }
                .maxByOrNull { it.version }
            val asset = apkRelease?.let { apkAssets[it.tag] }
            return CheckResult.UpdateAvailable(
                tag = newer.tag,
                releaseUrl = RELEASE_PAGE + newer.tag,
                componentScope = newer.componentScope,
                apkUrl = asset?.url,
                apkTag = apkRelease?.tag,
                apkSizeBytes = asset?.size ?: 0L,
                apkSha256 = asset?.sha256,
                publishedAt = apkRelease?.let { publishedAt[it.tag] },
                notes = UpdatePresentation.notes(apkRelease?.let { bodies[it.tag] }),
            )
        } catch (_: Exception) {
            return CheckResult.Unavailable
        } finally {
            connection.disconnect()
        }
    }
}
