package com.bookkeeping.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteUpdatePolicyTest {
    private val sha = "52e84095cb9e3e63f6d9f87f011374ecae7a90caaa1295482802609d3dd0d91a"

    private fun manifest(
        channel: String = "stable",
        versionCode: Int = 43,
        versionName: String = "1.42",
        apkUrl: String = "https://zxyzx.me/open-margin/installer/open-margin-1.42-52e84095.apk",
        size: Long = 125697647L,
        sha256: String? = sha,
    ): String = """
        {
          "channel": "$channel",
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "apkUrl": "$apkUrl",
          "size": $size,
          "sha256": ${if (sha256 == null) "null" else "\"$sha256\""},
          "notes": ["修复账单页横幅", "允许移动网络下载"]
        }
    """.trimIndent()

    @Test
    fun validStableManifestParsesAndPassesEveryGate() {
        val parsed = SiteUpdatePolicy.parse(manifest(), currentVersionCode = 42)
        assertNotNull(parsed)
        parsed!!
        assertEquals("stable", parsed.channel)
        assertEquals(43, parsed.versionCode)
        assertEquals("1.42", parsed.versionName)
        assertEquals(
            "https://zxyzx.me/open-margin/installer/open-margin-1.42-52e84095.apk",
            parsed.apkUrl,
        )
        assertEquals(125697647L, parsed.size)
        assertEquals(sha, parsed.sha256)
        assertEquals(listOf("修复账单页横幅", "允许移动网络下载"), parsed.notes)
    }

    @Test
    fun httpManifestUrlIsRejected() {
        assertFalse(SiteUpdatePolicy.isTrustedSiteUrl("http://zxyzx.me/open-margin/installer/latest.json"))
        assertFalse(
            SiteUpdatePolicy.isTrustedSiteUrl(
                "http://zxyzx.me/open-margin/installer/open-margin-1.42-52e84095.apk"
            )
        )
    }

    @Test
    fun foreignAndSubdomainHostsAreRejected() {
        assertFalse(SiteUpdatePolicy.isTrustedSiteUrl("https://example.com/open-margin/installer/latest.json"))
        assertFalse(SiteUpdatePolicy.isTrustedSiteUrl("https://evil.zxyzx.me/x.apk"))
        assertFalse(SiteUpdatePolicy.isTrustedSiteUrl("https://zxyzx.me.evil.com/x.apk"))
        assertFalse(SiteUpdatePolicy.isTrustedSiteUrl("https://zxyzx.me@evil.com/x.apk"))
    }

    @Test
    fun nonStableChannelIsRejected() {
        assertNull(SiteUpdatePolicy.parse(manifest(channel = "beta"), 42))
        assertNull(SiteUpdatePolicy.parse(manifest(channel = "rc"), 42))
        assertNull(SiteUpdatePolicy.parse(manifest(channel = ""), 42))
    }

    @Test
    fun releaseCandidatesAreNeverAccepted() {
        assertNull(SiteUpdatePolicy.parse(manifest(versionName = "1.42-rc.1"), 42))
        assertNull(SiteUpdatePolicy.parse(manifest(versionName = "1.42-RC.2"), 42))
        assertNull(SiteUpdatePolicy.parse(manifest(versionName = "1.42rc.1"), 42))
        assertTrue(SiteUpdatePolicy.isReleaseCandidate("1.42-rc.1"))
        assertTrue(SiteUpdatePolicy.isReleaseCandidate("1.42rc.1"))
        assertFalse(SiteUpdatePolicy.isReleaseCandidate("1.42"))
    }

    @Test
    fun sha256MustBePresentAndExactly64HexChars() {
        assertNull(SiteUpdatePolicy.parse(manifest(sha256 = null), 42))
        assertNull(SiteUpdatePolicy.parse(manifest(sha256 = ""), 42))
        assertNull(SiteUpdatePolicy.parse(manifest(sha256 = "abc"), 42))
        assertNull(SiteUpdatePolicy.parse(manifest(sha256 = "z".repeat(64)), 42))
        assertEquals(sha, SiteUpdatePolicy.normalizeSha256(sha.uppercase()))
    }

    @Test
    fun sizeMustBePositive() {
        assertNull(SiteUpdatePolicy.parse(manifest(size = 0L), 42))
        assertNull(SiteUpdatePolicy.parse(manifest(size = -1L), 42))
    }

    @Test
    fun versionCodeMustBeStrictlyGreaterThanInstalled() {
        assertNull(SiteUpdatePolicy.parse(manifest(versionCode = 42), 42))
        assertNull(SiteUpdatePolicy.parse(manifest(versionCode = 41), 42))
        assertNotNull(SiteUpdatePolicy.parse(manifest(versionCode = 43), 42))
        // The structural parser still describes the file; the version gate is
        // applied by parse()/callers.
        assertNotNull(SiteUpdatePolicy.parseManifest(manifest(versionCode = 42)))
    }

    @Test
    fun apkUrlMustStayOnTheProjectHttpsInstallerPath() {
        assertNull(
            SiteUpdatePolicy.parse(
                manifest(apkUrl = "http://zxyzx.me/open-margin/installer/open-margin-1.42-52e84095.apk"),
                42,
            )
        )
        assertNull(
            SiteUpdatePolicy.parse(
                manifest(apkUrl = "https://zxyzx.me.evil.com/open-margin/installer/open-margin-1.42-52e84095.apk"),
                42,
            )
        )
        assertNull(
            SiteUpdatePolicy.parse(
                manifest(apkUrl = "https://evil.com/open-margin-1.42-52e84095.apk"),
                42,
            )
        )
        assertNull(
            SiteUpdatePolicy.parse(
                manifest(apkUrl = "https://zxyzx.me/other/open-margin-1.42-52e84095.apk"),
                42,
            )
        )
        assertNull(
            SiteUpdatePolicy.parse(
                manifest(apkUrl = "https://zxyzx.me/open-margin/installer/../evil.apk"),
                42,
            )
        )
        assertNull(
            SiteUpdatePolicy.parse(
                manifest(apkUrl = "https://zxyzx.me/open-margin/installer/open-margin.apk?x=1"),
                42,
            )
        )
    }

    @Test
    fun malformedJsonIsDiscardedSilently() {
        assertNull(SiteUpdatePolicy.parse(null, 42))
        assertNull(SiteUpdatePolicy.parse("", 42))
        assertNull(SiteUpdatePolicy.parse("not json", 42))
        assertNull(SiteUpdatePolicy.parse("{}", 42))
    }

    @Test
    fun checkIsDueEveryTwentyFourHours() {
        val hour = 60L * 60L * 1000L
        assertTrue(SiteUpdatePolicy.isCheckDue(0L, 1_000L))
        assertFalse(SiteUpdatePolicy.isCheckDue(1_000L, 1_000L + 23 * hour))
        assertTrue(SiteUpdatePolicy.isCheckDue(1_000L, 1_000L + 24 * hour))
        // Clock rollback must not freeze the check forever.
        assertTrue(SiteUpdatePolicy.isCheckDue(10_000L, 5_000L))
    }

    @Test
    fun dismissedVersionNeverRemindsButHigherVersionDoes() {
        assertTrue(
            SiteUpdatePolicy.shouldShowBanner(
                manifestVersionCode = 43,
                installedVersionCode = 42,
                dismissedVersionCode = 0,
                bannerEnabled = true,
            )
        )
        assertFalse(
            SiteUpdatePolicy.shouldShowBanner(
                manifestVersionCode = 43,
                installedVersionCode = 42,
                dismissedVersionCode = 43,
                bannerEnabled = true,
            )
        )
        assertTrue(
            SiteUpdatePolicy.shouldShowBanner(
                manifestVersionCode = 44,
                installedVersionCode = 42,
                dismissedVersionCode = 43,
                bannerEnabled = true,
            )
        )
        assertFalse(
            SiteUpdatePolicy.shouldShowBanner(
                manifestVersionCode = 44,
                installedVersionCode = 42,
                dismissedVersionCode = 0,
                bannerEnabled = false,
            )
        )
        assertFalse(
            SiteUpdatePolicy.shouldShowBanner(
                manifestVersionCode = 42,
                installedVersionCode = 42,
                dismissedVersionCode = 0,
                bannerEnabled = true,
            )
        )
    }

    @Test
    fun packageGateSeparatesProductionAndTestBuilds() {
        assertTrue(SiteUpdatePolicy.isOfficialPackage("com.bookkeeping.app"))
        assertFalse(SiteUpdatePolicy.isOfficialPackage("com.bookkeeping.app.sync2"))
        assertFalse(SiteUpdatePolicy.isOfficialPackage("com.bookkeeping.app.sync3"))
        assertTrue(SiteUpdatePolicy.isTestPackage("com.bookkeeping.app.sync2"))
        assertTrue(SiteUpdatePolicy.isTestPackage("com.bookkeeping.app.sync3"))
        assertFalse(SiteUpdatePolicy.isTestPackage("com.bookkeeping.app"))

        assertTrue(SiteUpdatePolicy.canShowBanner("com.bookkeeping.app"))
        assertTrue(SiteUpdatePolicy.canShowBanner("com.bookkeeping.app.sync2"))
        assertTrue(SiteUpdatePolicy.canShowBanner("com.bookkeeping.app.sync3"))
        assertFalse(SiteUpdatePolicy.canShowBanner("com.example.other"))

        // Only the production package may download or open the installer.
        assertTrue(SiteUpdatePolicy.canAutoDownload("com.bookkeeping.app"))
        assertFalse(SiteUpdatePolicy.canAutoDownload("com.bookkeeping.app.sync2"))
        assertFalse(SiteUpdatePolicy.canAutoDownload("com.bookkeeping.app.sync3"))
        assertFalse(SiteUpdatePolicy.canAutoDownload("com.example.other"))
    }

    @Test
    fun previewBannerDerivesFromInstalledVersion() {
        val preview = SiteUpdatePolicy.previewBanner(42, "1.41")
        assertEquals(43, preview.versionCode)
        assertEquals("1.42", preview.versionName)
        assertEquals("1.10", SiteUpdatePolicy.nextMinorVersion("1.9"))
        assertEquals("1.0", SiteUpdatePolicy.nextMinorVersion("broken"))
    }

    @Test
    fun siteManifestConvertsToTheExistingDialogType() {
        val manifest = SiteUpdatePolicy.parse(manifest(), 42)!!
        val result = manifest.asCheckResult()
        assertEquals("v1.42", result.apkTag)
        assertEquals(manifest.apkUrl, result.apkUrl)
        assertEquals(manifest.size, result.apkSizeBytes)
        assertEquals(manifest.sha256, result.apkSha256)
        assertEquals(manifest.notes, result.notes)
        assertEquals(UpdateComponentScope.ANDROID_ONLY, result.componentScope)
    }
}
