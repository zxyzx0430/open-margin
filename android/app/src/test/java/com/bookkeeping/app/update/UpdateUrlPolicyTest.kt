package com.bookkeeping.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlPolicyTest {
    @Test fun onlyProjectReleaseDownloadsAreAccepted() {
        assertTrue(
            UpdateUrlPolicy.isAllowedAssetUrl(
                "https://github.com/zxyzx0430/open-margin/releases/download/v1.2.4/open-margin-v1.2.4-signed.apk"
            )
        )
        assertFalse(
            UpdateUrlPolicy.isAllowedAssetUrl(
                "https://github.com/attacker/open-margin/releases/download/v1.2.4/open-margin-v1.2.4-signed.apk"
            )
        )
        assertFalse(
            UpdateUrlPolicy.isAllowedAssetUrl(
                "http://github.com/zxyzx0430/open-margin/releases/download/v1.2.4/open-margin-v1.2.4-signed.apk"
            )
        )
        assertFalse(
            UpdateUrlPolicy.isAllowedAssetUrl(
                "https://github.com/zxyzx0430/open-margin/releases/download/v1.2.4/..%2F..%2Fevil.apk"
            )
        )
        assertFalse(
            UpdateUrlPolicy.isAllowedAssetUrl(
                "https://github.com/zxyzx0430/open-margin/releases/download/v1.2.4/evil.apk?x=1"
            )
        )
        assertFalse(
            UpdateUrlPolicy.isAllowedAssetUrl(
                "https://github.com/zxyzx0430/open-margin/releases/download/v1.2.4/../../etc/passwd"
            )
        )
        assertFalse(UpdateUrlPolicy.isAllowedAssetUrl(null))
        assertFalse(UpdateUrlPolicy.isAllowedAssetUrl(""))
    }

    @Test fun assetNamesStaySimpleAndApkOnly() {
        assertTrue(UpdateUrlPolicy.isSafeApkName("open-margin-v1.2.4-signed.apk"))
        assertFalse(UpdateUrlPolicy.isSafeApkName("open-margin-v1.2.4-signed.apk.exe"))
        assertFalse(UpdateUrlPolicy.isSafeApkName("../../open-margin.apk"))
        assertFalse(UpdateUrlPolicy.isSafeApkName(".hidden.apk"))
        assertFalse(UpdateUrlPolicy.isSafeApkName("apk"))
        assertFalse(UpdateUrlPolicy.isSafeApkName("open margin.apk"))
        assertEquals(
            "open-margin-v1.2.4-signed.apk",
            UpdateUrlPolicy.fileNameOf(
                "https://github.com/zxyzx0430/open-margin/releases/download/v1.2.4/open-margin-v1.2.4-signed.apk"
            ),
        )
        assertNull(UpdateUrlPolicy.fileNameOf("https://example.com/open-margin.apk"))
    }
}
