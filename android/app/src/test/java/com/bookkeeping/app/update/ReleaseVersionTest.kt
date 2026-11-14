package com.bookkeeping.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test fun versionsCompareRcAndStableCorrectly() {
        val old = ReleaseVersion.parse("v1.1.3")!!
        val rc5 = ReleaseVersion.parse("v1.2.0-rc.5")!!
        val rc6 = ReleaseVersion.parse("v1.2.0-rc.6")!!
        val rc7 = ReleaseVersion.parse("v1.2.0-rc.7")!!
        val rc8 = ReleaseVersion.parse("v1.2.0-rc.8")!!
        val stable = ReleaseVersion.parse("v1.2.0")!!
        assertTrue(old < rc5)
        assertTrue(rc5 < rc6)
        assertTrue(rc6 < rc7)
        assertTrue(rc7 < rc8)
        assertTrue(rc8 < stable)
        assertEquals(rc8, ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG))
        assertNull(ReleaseVersion.parse("v1.2.0-rc.test"))
        assertNull(ReleaseVersion.parse("v1.2.0-beta.8"))
        assertNull(ReleaseVersion.parse("https://other.example"))
    }

    @Test fun releaseNotesDeclareExactlyOneComponentScope() {
        val parser = UpdateComponentScope
        assertEquals(UpdateComponentScope.ANDROID_ONLY,
            parser.fromReleaseNotes("# Notes\n<!-- open-margin-components: android -->\n"))
        assertEquals(UpdateComponentScope.SERVER_ONLY,
            parser.fromReleaseNotes("<!-- open-margin-components: server -->"))
        assertEquals(UpdateComponentScope.BOTH,
            parser.fromReleaseNotes("<!-- open-margin-components: android,server -->\r\n## Changes"))
        assertEquals(UpdateComponentScope.UNSPECIFIED,
            parser.fromReleaseNotes("# Unmarked old release\nImproved server sync"))
        assertEquals(UpdateComponentScope.UNSPECIFIED,
            parser.fromReleaseNotes("<!-- open-margin-components: android, server -->"))
        assertEquals(UpdateComponentScope.UNSPECIFIED,
            parser.fromReleaseNotes("<!-- open-margin-components: android -->\n<!-- open-margin-components: server -->"))
    }

    @Test fun serverOnlyReleaseWithoutApkMustStillAppear() {
        val current = ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG)!!
        val releases = listOf(
            release("v1.2.0-rc.9", true, false, UpdateComponentScope.SERVER_ONLY),
            release("v1.2.0-rc.10", true, false, UpdateComponentScope.ANDROID_ONLY),
        )
        val latest = UpdateVersionPolicy.latestNewer(releases, current, true)
        assertEquals("v1.2.0-rc.9", latest?.tag)
        assertEquals(UpdateComponentScope.SERVER_ONLY, latest?.componentScope)
        assertNull(UpdateVersionPolicy.latestNewer(releases, current, false))
    }

    @Test fun appOrBothReleaseRequiresSignedApkAndOldBuildCannotBeSuggested() {
        val current = ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG)!!
        val releases = listOf(
            release("v1.1.3", false),
            release("v1.2.0-rc.5", true),
            release("v1.2.0-rc.9", true, false, UpdateComponentScope.BOTH),
            release("v1.2.0-rc.10", true, true, UpdateComponentScope.BOTH),
        )
        assertEquals("v1.2.0-rc.10",
            UpdateVersionPolicy.latestNewer(releases, current, true)?.tag)
        assertNull(UpdateVersionPolicy.latestNewer(releases, current, false))
        assertEquals("v1.2.0",
            UpdateVersionPolicy.latestNewer(
                releases + release("v1.2.0", false, true, UpdateComponentScope.ANDROID_ONLY),
                current, false
            )?.tag)
    }

    @Test fun legacyUnmarkedReleaseCanBeFoundButScopeMustStayUnknown() {
        val current = ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG)!!
        val candidate = release("v1.2.0-rc.9", true, true)
        assertEquals(UpdateComponentScope.UNSPECIFIED,
            UpdateVersionPolicy.latestNewer(listOf(candidate), current, true)?.componentScope)
        assertNull(UpdateVersionPolicy.latestNewer(
            listOf(candidate.copy(hasSignedApk = false)), current, true))
    }

    @Test fun androidAndServerReleasesCombineIntoBothAcrossMultipleTags() {
        val current = ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG)!!
        val announcements = listOf(
            release("v1.2.0-rc.9", true, true, UpdateComponentScope.ANDROID_ONLY),
            release("v1.2.0-rc.10", true, false, UpdateComponentScope.SERVER_ONLY),
        )
        val result = UpdateVersionPolicy.latestNewer(announcements, current, true)
        assertEquals("v1.2.0-rc.10", result?.tag)
        assertEquals(UpdateComponentScope.BOTH, result?.componentScope)
    }

    @Test fun unknownLegacyScopePreventsOverconfidentComponentAdvice() {
        val current = ReleaseVersion.parse(UpdateVersionPolicy.CURRENT_TAG)!!
        val announcements = listOf(
            release("v1.2.0-rc.9", true, true, UpdateComponentScope.ANDROID_ONLY),
            release("v1.2.0-rc.10", true, true, UpdateComponentScope.UNSPECIFIED),
        )
        assertEquals(UpdateComponentScope.UNSPECIFIED,
            UpdateVersionPolicy.latestNewer(announcements, current, true)?.componentScope)
    }

    @Test fun onlySignedApkNamesQualify() {
        assertTrue(UpdateVersionPolicy.isSignedApkName("open-margin-v1.2.0-signed.apk"))
        assertTrue(UpdateVersionPolicy.isSignedApkName("open-margin-v1.2.0-original-signature.apk"))
        assertFalse(UpdateVersionPolicy.isSignedApkName("app-release-unsigned.apk"))
        assertFalse(UpdateVersionPolicy.isSignedApkName("finance-server.zip"))
    }

    private fun release(
        tag: String,
        prerelease: Boolean,
        signed: Boolean = true,
        scope: UpdateComponentScope = UpdateComponentScope.UNSPECIFIED
    ) = PublishedRelease(tag, ReleaseVersion.parse(tag)!!, prerelease, signed, scope)
}
