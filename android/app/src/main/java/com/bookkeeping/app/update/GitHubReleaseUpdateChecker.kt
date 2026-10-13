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

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class UpdateAvailable(
            val tag: String,
            val releaseUrl: String,
            val componentScope: UpdateComponentScope,
        ) : CheckResult
        data object Unavailable : CheckResult
    }

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
            for (index in 0 until releases.length()) {
                val entry = releases.optJSONObject(index) ?: continue
                if (entry.optBoolean("draft", false)) continue
                val tag = entry.optString("tag_name")
                val version = ReleaseVersion.parse(tag) ?: continue
                val assets = entry.optJSONArray("assets") ?: continue
                val signedApk = (0 until assets.length()).any { assetIndex ->
                    val asset = assets.optJSONObject(assetIndex) ?: return@any false
                    asset.optLong("size", 0) > 0 &&
                        UpdateVersionPolicy.isSignedApkName(asset.optString("name"))
                }
                val scope = UpdateComponentScope.fromReleaseNotes(entry.optString("body", ""))
                candidates.add(
                    PublishedRelease(
                        tag, version, entry.optBoolean("prerelease", false), signedApk, scope
                    )
                )
            }
            val newer = UpdateVersionPolicy.latestNewer(candidates, current, includePrereleases)
                ?: return CheckResult.UpToDate
            // Only strictly parsed version tags can be added to this fixed
            // GitHub URL. Never open a URL supplied by the JSON response.
            return CheckResult.UpdateAvailable(
                newer.tag,
                RELEASE_PAGE + newer.tag,
                newer.componentScope,
            )
        } catch (_: Exception) {
            return CheckResult.Unavailable
        } finally {
            connection.disconnect()
        }
    }
}
