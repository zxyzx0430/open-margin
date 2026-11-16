package com.bookkeeping.app.update

import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Read-only public GitHub Releases query. Version update scope is declared by
 * release maintainers, never guessed from the presence of an APK or a title.
 * This does not inspect the user's installed Windows/VPS server version.
 *
 * `api.github.com` is frequently DNS-poisoned or blocked on Chinese networks
 * while `github.com` still resolves, so a failed API call is retried once
 * against the same host's Atom feed.
 */
object GitHubReleaseUpdateChecker {
    private const val RELEASES_API =
        "https://api.github.com/repos/zxyzx0430/open-margin/releases?per_page=30"

    /** Same host as the release page, so it survives API-only blocking. */
    private const val RELEASES_ATOM =
        "https://github.com/zxyzx0430/open-margin/releases.atom"
    private const val RELEASE_DOWNLOAD =
        "https://github.com/zxyzx0430/open-margin/releases/download/"
    private const val USER_AGENT = "OpenMargin-Android-Version-Check"
    private const val ATOM_TIMEOUT_MS = 10_000

    /** A missing APK answer must not block the dialog for half a minute. */
    private const val APK_PROBE_TIMEOUT_MS = 8_000

    /** Only the newest announcements are worth probing; older ones lose the race anyway. */
    private const val MAX_APK_PROBES = 3

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

        /** [kind] lets the UI say what actually failed instead of a generic error. */
        data class Unavailable(
            val kind: FailureKind = FailureKind.UNKNOWN,
            val httpCode: Int = 0,
        ) : CheckResult
    }

    enum class FailureKind { NO_NETWORK, TIMEOUT, HTTP, PARSE, UNKNOWN }

    private data class ApkAsset(val url: String, val size: Long, val sha256: String?)

    /** An APK found by probing the Atom fallback, which has no asset list. */
    private data class AtomApk(val tag: String, val url: String, val size: Long)

    /** Atom has no asset list: HEAD on the fixed download path is the only proof. */
    private sealed interface ApkProbe {
        data class Found(val size: Long) : ApkProbe
        data object Missing : ApkProbe
        data object Unknown : ApkProbe
    }

    /** Checks stable GitHub releases only; prerelease entries are filtered out. */
    fun checkStable(): CheckResult = check(includePrereleases = false)

    fun check(includePrereleases: Boolean): CheckResult {
        val current = ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG)
            ?: return CheckResult.Unavailable()
        val apiResult = checkViaApi(current, includePrereleases)
        if (apiResult !is CheckResult.Unavailable) return apiResult
        // Keep the API failure as the reported reason: it is the one that
        // explains why the normal path did not work.
        val atomResult = checkViaAtom(current, includePrereleases)
        return if (atomResult is CheckResult.Unavailable) apiResult else atomResult
    }

    private fun checkViaApi(current: ReleaseVersion, includePrereleases: Boolean): CheckResult {
        val connection = try {
            URL(RELEASES_API).openConnection() as HttpsURLConnection
        } catch (error: Exception) {
            return classify(error)
        }
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val httpCode = connection.responseCode
            if (httpCode != 200) {
                return CheckResult.Unavailable(FailureKind.HTTP, httpCode)
            }

            val releases = try {
                JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (_: Exception) {
                return CheckResult.Unavailable(FailureKind.PARSE)
            }
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
                releaseUrl = RELEASE_TAG_URL_PREFIX + newer.tag,
                componentScope = newer.componentScope,
                apkUrl = asset?.url,
                apkTag = apkRelease?.tag,
                apkSizeBytes = asset?.size ?: 0L,
                apkSha256 = asset?.sha256,
                publishedAt = apkRelease?.let { publishedAt[it.tag] },
                notes = UpdatePresentation.notes(apkRelease?.let { bodies[it.tag] }),
            )
        } catch (error: Exception) {
            return classify(error)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fallback for blocked `api.github.com`. The feed is rendered HTML without
     * an asset list, so every entry claims a signed APK and the real answer
     * comes from a HEAD probe on the fixed download path; a 404 there means the
     * release is server-only.
     */
    private fun checkViaAtom(current: ReleaseVersion, includePrereleases: Boolean): CheckResult {
        val xml = try {
            fetchAtom()
        } catch (error: Exception) {
            return classify(error)
        }
        val entries = try {
            parseAtom(xml)
        } catch (_: Exception) {
            return CheckResult.Unavailable(FailureKind.PARSE)
        }
        if (entries.isEmpty()) return CheckResult.Unavailable(FailureKind.PARSE)
        val byTag = entries.associateBy { it.tag }
        val candidates = entries.mapNotNull { entry ->
            val version = ReleaseVersion.parse(entry.tag) ?: return@mapNotNull null
            PublishedRelease(
                tag = entry.tag,
                version = version,
                // The feed carries no prerelease flag; -rc.N in the tag is the
                // only signal, and it is the same rule the API path applies.
                prerelease = version.releaseCandidate != null,
                // Unknown from the feed: the HEAD probe below decides, and
                // guessing false would hide every Android update.
                hasSignedApk = true,
                componentScope = UpdateComponentScope.fromReleaseNotes(entry.notes),
            )
        }
        val newer = UpdateVersionPolicy.latestNewer(candidates, current, includePrereleases)
            ?: return CheckResult.UpToDate
        val apk = newestApk(candidates, current, includePrereleases)
        val noteTag = apk?.tag ?: newer.tag
        return CheckResult.UpdateAvailable(
            tag = newer.tag,
            releaseUrl = RELEASE_TAG_URL_PREFIX + newer.tag,
            componentScope = newer.componentScope,
            apkUrl = apk?.url,
            apkTag = apk?.tag,
            apkSizeBytes = apk?.size ?: 0L,
            // The digest only exists in the API payload; the installer then
            // verifies the downloaded size instead.
            apkSha256 = null,
            publishedAt = byTag[noteTag]?.updated ?: byTag[newer.tag]?.updated,
            notes = UpdatePresentation.notes(byTag[noteTag]?.notes),
        )
    }

    private fun fetchAtom(): String {
        val connection = URL(RELEASES_ATOM).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.connectTimeout = ATOM_TIMEOUT_MS
            connection.readTimeout = ATOM_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/atom+xml")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val httpCode = connection.responseCode
            if (httpCode != 200) throw HttpStatusException(httpCode)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun newestApk(
        candidates: List<PublishedRelease>,
        current: ReleaseVersion,
        includePrereleases: Boolean,
    ): AtomApk? {
        val ordered = candidates.asSequence()
            .filter { it.version > current }
            .filter { includePrereleases || !it.prerelease }
            .sortedByDescending { it.version }
            .take(MAX_APK_PROBES)
            .toList()
        for (release in ordered) {
            // Fixed path built from the tag we parsed, never from an Atom href.
            val url = RELEASE_DOWNLOAD + release.tag + "/" +
                "open-margin-" + release.tag + "-signed.apk"
            if (!UpdateUrlPolicy.isAllowedAssetUrl(url)) continue
            val probe = probeApk(url)
            if (probe is ApkProbe.Found) return AtomApk(release.tag, url, probe.size)
        }
        return null
    }

    private fun probeApk(url: String): ApkProbe = try {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.requestMethod = "HEAD"
            connection.connectTimeout = APK_PROBE_TIMEOUT_MS
            connection.readTimeout = APK_PROBE_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val httpCode = connection.responseCode
            val size = connection.getHeaderField("Content-Length")
                ?.trim()
                ?.toLongOrNull()
                ?: -1L
            when {
                httpCode == 404 -> ApkProbe.Missing
                (httpCode == 200 || httpCode == 302) && size > 0L -> ApkProbe.Found(size)
                else -> ApkProbe.Unknown
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        ApkProbe.Unknown
    }

    private class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")

    /**
     * 把网络异常归成一类，UI 才能给出可操作的提示。
     * 国内网络下 api.github.com 基本不可直连，表现多为超时或 UnknownHost。
     */
    private fun classify(error: Throwable): CheckResult.Unavailable = when (error) {
        is HttpStatusException -> CheckResult.Unavailable(FailureKind.HTTP, error.code)
        is java.net.SocketTimeoutException -> CheckResult.Unavailable(FailureKind.TIMEOUT)
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException ->
            CheckResult.Unavailable(FailureKind.NO_NETWORK)
        is javax.net.ssl.SSLException -> CheckResult.Unavailable(FailureKind.NO_NETWORK)
        is java.io.IOException -> CheckResult.Unavailable(FailureKind.NO_NETWORK)
        is org.json.JSONException -> CheckResult.Unavailable(FailureKind.PARSE)
        else -> CheckResult.Unavailable(FailureKind.UNKNOWN)
    }
}
