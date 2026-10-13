package com.bookkeeping.app.update

/**
 * GitHub Release tags are the authoritative public version identifiers.
 * The Android versionCode is used separately by PackageManager for APK upgrades.
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

data class PublishedRelease(
    val tag: String,
    val version: ReleaseVersion,
    val prerelease: Boolean,
    val hasSignedApk: Boolean,
)

object UpdateVersionPolicy {
    // The build is published under this tag, tied to versionCode 10. Raise both
    // when shipping a newer RC; RC4 is code 9 and must never be offered to RC5.
    const val CURRENT_TAG = "v1.2.0-rc.5"

    fun latestNewer(
        releases: List<PublishedRelease>,
        current: ReleaseVersion,
        includePrereleases: Boolean,
    ): PublishedRelease? = releases.asSequence()
        .filter { it.hasSignedApk && (includePrereleases || !it.prerelease) }
        .filter { it.version > current }
        .maxByOrNull { it.version }

    fun isSignedApkName(name: String): Boolean =
        name.endsWith("-signed.apk") || name.endsWith("-original-signature.apk")
}
