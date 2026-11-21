package com.bookkeeping.app.update

/**
 * The update JSON (tag names, asset names, body, manifest URL) comes from the
 * network, so it is untrusted input. Only the project's own GitHub release
 * download path or its own installer directory may be handed to the installer;
 * anything else is refused instead of being "helpfully" opened.
 *
 * The site path exists because `api.github.com` is blocked on Chinese
 * networks; the manifest lives on the project's own host.
 */
object UpdateUrlPolicy {
    private const val DOWNLOAD_PREFIX =
        "https://github.com/zxyzx0430/open-margin/releases/download/"
    private const val SITE_PREFIX =
        "https://zxyzx.me/open-margin/installer/"

    fun isAllowedAssetUrl(url: String?): Boolean {
        if (url == null) return false
        return isAllowedGithubAssetUrl(url) || isAllowedSiteAssetUrl(url)
    }

    private fun isAllowedGithubAssetUrl(url: String): Boolean {
        if (!url.startsWith(DOWNLOAD_PREFIX)) return false
        val rest = url.removePrefix(DOWNLOAD_PREFIX)
        if (rest.isEmpty()) return false
        if (rest.any { it.isWhitespace() || it == '\\' || it == '?' || it == '#' }) return false
        if (rest.contains("..")) return false
        val segments = rest.split('/')
        if (segments.size != 2) return false
        if (segments[0].isEmpty() || !segments[0].startsWith("v")) return false
        return isSafeApkName(segments[1])
    }

    /**
     * Same origin, same path prefix, one flat file name. The host is pinned by
     * the literal prefix, so a URL claiming `zxyzx.me` in a query parameter or
     * user-info position cannot pass.
     */
    private fun isAllowedSiteAssetUrl(url: String): Boolean {
        if (!url.startsWith(SITE_PREFIX)) return false
        val name = url.removePrefix(SITE_PREFIX)
        if (name.isEmpty()) return false
        if (name.contains('/')) return false
        if (name.any { it.isWhitespace() || it == '\\' || it == '?' || it == '#' }) return false
        if (name.contains("..")) return false
        return isSafeApkName(name)
    }

    /** File names are also used as local paths, so keep them boring. */
    fun isSafeApkName(name: String?): Boolean {
        if (name == null) return false
        if (name.length !in 5..128) return false
        if (name.startsWith('.')) return false
        if (!name.endsWith(".apk")) return false
        return name.all { char ->
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
                char == '.' || char == '_' || char == '-'
        }
    }

    fun fileNameOf(apkUrl: String?): String? {
        if (!isAllowedAssetUrl(apkUrl)) return null
        return apkUrl?.substringAfterLast('/')
    }
}
