package com.bookkeeping.app.update

import com.bookkeeping.app.BuildConfig
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Reads the static stable manifest from the project site. This is the primary
 * update channel: `zxyzx.me` stays reachable where `api.github.com` is blocked.
 * Any failure (network, TLS, HTTP, parse, rejected manifest) is silent.
 */
object SiteUpdateChecker {
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BODY_CHARS = 64 * 1024
    private const val USER_AGENT = "OpenMargin-Android-Site-Check"

    sealed interface Result {
        data class Available(
            val manifest: SiteUpdatePolicy.SiteUpdateManifest,
            val rawJson: String,
        ) : Result

        data object UpToDate : Result

        data object Unavailable : Result
    }

    fun check(currentVersionCode: Int = BuildConfig.VERSION_CODE): Result {
        val body = try {
            fetch()
        } catch (_: Exception) {
            return Result.Unavailable
        }
        val manifest = SiteUpdatePolicy.parseManifest(body) ?: return Result.Unavailable
        return if (manifest.versionCode > currentVersionCode) {
            Result.Available(manifest, body)
        } else {
            Result.UpToDate
        }
    }

    private fun fetch(): String {
        val connection = URL(SiteUpdatePolicy.MANIFEST_URL).openConnection() as HttpsURLConnection
        try {
            // No redirects: a redirect could downgrade to http or leave the host.
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8 * 1024)
                val body = StringBuilder()
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    if (body.length + read > MAX_BODY_CHARS) {
                        throw IllegalStateException("manifest too large")
                    }
                    body.append(buffer, 0, read)
                }
                return body.toString()
            }
        } finally {
            connection.disconnect()
        }
    }
}
