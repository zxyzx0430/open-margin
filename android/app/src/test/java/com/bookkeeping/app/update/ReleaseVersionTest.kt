package com.bookkeeping.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test fun officialTagsCompareInCorrectOrder() {
        val old = ReleaseVersion.parse("v1.1.3")!!
        val rc4 = ReleaseVersion.parse("v1.2.0-rc.4")!!
        val rc5 = ReleaseVersion.parse("v1.2.0-rc.5")!!
        val stable = ReleaseVersion.parse("v1.2.0")!!
        assertTrue(old < rc4)
        assertTrue(rc4 < rc5)
        assertTrue(rc5 < stable)
        assertEquals(rc5, ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG))
        assertNull(ReleaseVersion.parse("v1.2.0-rc.test"))
        assertNull(ReleaseVersion.parse("v1.2.0-beta.8"))
        assertNull(ReleaseVersion.parse("https://malicious.example"))
    }

    @Test fun currentRc5DoesNotOfferRc4OrOldStable() {
        val current = ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG)!!
        val releases = listOf(
            release("v1.1.3", false),
            release("v1.2.0-rc.4", true),
        )
        assertNull(UpdateVersionPolicy.latestNewer(releases, current, true))
        assertNull(UpdateVersionPolicy.latestNewer(releases, current, false))
    }

    @Test fun prereleaseChannelFiltersAndRequiresSignedAsset() {
        val current = ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG)!!
        val releases = listOf(
            release("v1.2.0-rc.6", true, false),
            release("v1.2.0-rc.7", true),
            release("v1.1.3", false),
        )
        assertEquals("v1.2.0-rc.7",
            UpdateVersionPolicy.latestNewer(releases, current, true)?.tag)
        assertNull(UpdateVersionPolicy.latestNewer(releases, current, false))
        assertEquals("v1.2.0",
            UpdateVersionPolicy.latestNewer(
                releases + release("v1.2.0", false), current, false
            )?.tag)
    }

    @Test fun updateAssetMustBeSignedNotUnsigned() {
        assertTrue(UpdateVersionPolicy.isSignedApkName("open-margin-v1.2.0-rc5-signed.apk"))
        assertTrue(UpdateVersionPolicy.isSignedApkName("open-margin-v1.2.0-original-signature.apk"))
        assertFalse(UpdateVersionPolicy.isSignedApkName("app-release-unsigned.apk"))
        assertFalse(UpdateVersionPolicy.isSignedApkName("open-margin-v1.2.0.zip"))
    }

    private fun release(tag: String, prerelease: Boolean, signed: Boolean = true) =
        PublishedRelease(tag, ReleaseVersion.parse(tag)!!, prerelease, signed)
}
