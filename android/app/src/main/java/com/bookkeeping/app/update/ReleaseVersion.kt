package com.bookkeeping.app.update

/**
 * GitHub release tags sort release announcements, but do not prove which
 * server version is installed. Update scope is explicit release metadata.
 */
data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val releaseCandidate: Int? = null,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return when {
            releaseCandidate == null && other.releaseCandidate != null -> 1
            releaseCandidate != null && other.releaseCandidate == null -> -1
            releaseCandidate == null -> 0
            else -> releaseCandidate!!.compareTo(other.releaseCandidate!!)
        }
    }

    companion object {
        private val TAG = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)(?:-rc\\.(\\d+))?$")

        fun parse(tag: String): ReleaseVersion? {
            val groups = TAG.matchEntire(tag)?.groupValues ?: return null
            return ReleaseVersion(
                groups[1].toIntOrNull() ?: return null,
                groups[2].toIntOrNull() ?: return null,
                groups[3].toIntOrNull() ?: return null,
                if (groups[4].isNotEmpty()) (groups[4].toIntOrNull() ?: return null) else null,
            )
        }
    }
}

/** Components included by the *release*, not the installation status on a remote computer. */
enum class UpdateComponentScope {
    ANDROID_ONLY,
    SERVER_ONLY,
    BOTH,
    UNSPECIFIED;

    companion object {
        // Exactly one metadata line in the release body, not a guessed keyword
        // from free-form notes. Order is fixed so it is safe to parse.
        private val METADATA = Regex(
            "(?m)^<!-- open-margin-components: (android|server|android,server) -->\\r?$"
        )

        fun fromReleaseNotes(body: String): UpdateComponentScope {
            val matches = METADATA.findAll(body).toList()
            if (matches.size != 1) return UNSPECIFIED
            return when (matches.single().groupValues[1]) {
                "android" -> ANDROID_ONLY
                "server" -> SERVER_ONLY
                "android,server" -> BOTH
                else -> UNSPECIFIED
            }
        }
    }
}

data class PublishedRelease(
    val tag: String,
    val version: ReleaseVersion,
    val prerelease: Boolean,
    val hasSignedApk: Boolean,
    val componentScope: UpdateComponentScope = UpdateComponentScope.UNSPECIFIED,
)

object UpdateVersionPolicy {
    // RC6 is Android-only; the live v1.2.0 server does not need a redeploy for
    // this UI change. Keep both release tag and Android versionCode in sync.
    const val CURRENT_TAG = "v1.2.0-rc.6"

    fun latestNewer(
        releases: List<PublishedRelease>,
        current: ReleaseVersion,
        includePrereleases: Boolean,
    ): PublishedRelease? {
        val pending = releases.asSequence()
            .filter { !it.prerelease || includePrereleases }
            .filter { it.version > current }
            .filter {
                // Server-only releases legitimately have no APK. Android/both
                // releases must have a signed APK, and legacy unlabelled releases
                // are only discoverable if they contain a signed APK.
                it.componentScope == UpdateComponentScope.SERVER_ONLY || it.hasSignedApk
            }
            .toList()
        val latest = pending.maxByOrNull { it.version } ?: return null
        // Aggregate the scopes of ALL pending announcements, not just the
        // latest one. RC7 may be server-only while RC6 added an Android APK:
        // a phone on RC5 must show BOTH updates, not only RC7's server scope.
        val scopes = pending.map { it.componentScope }.toSet()
        val combined = when {
            UpdateComponentScope.UNSPECIFIED in scopes -> UpdateComponentScope.UNSPECIFIED
            UpdateComponentScope.BOTH in scopes -> UpdateComponentScope.BOTH
            UpdateComponentScope.ANDROID_ONLY in scopes &&
                UpdateComponentScope.SERVER_ONLY in scopes -> UpdateComponentScope.BOTH
            UpdateComponentScope.ANDROID_ONLY in scopes -> UpdateComponentScope.ANDROID_ONLY
            else -> UpdateComponentScope.SERVER_ONLY
        }
        return latest.copy(componentScope = combined)
    }

    fun isSignedApkName(name: String): Boolean =
        name.endsWith("-signed.apk") || name.endsWith("-original-signature.apk")
}
