package com.bookkeeping.app.capture.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotWatchControllerTest {
    @Test fun detectsHuaweiScreenshotsAndSvid() {
        assertTrue(ScreenshotWatchController.looksLikeScreenshot("Screenshot_20261124_121500.jpg", "Pictures/Screenshots/"))
        assertTrue(ScreenshotWatchController.looksLikeScreenshot("SVID_20261124_121500.jpg", "DCIM/"))
    }

    @Test fun detectsColorOsRealmeVivoAndIqooWithoutForegroundPackage() {
        val fileName = "Screenshot_2026-11-24-12-35-40.jpg"
        for (path in listOf("Pictures/Screenshots/", "DCIM/Screenshots/", "DCIM/截屏/")) {
            assertTrue(ScreenshotWatchController.looksLikeScreenshot(fileName, path))
            assertFalse(isOwnAppScreenshot(fileName, "com.bookkeeping.app.sync2"))
        }
    }

    @Test fun detectsXiaomiPackageNamesButSkipsOwnApp() {
        val wechat = "Screenshot_2026-11-24-12-35-40_com.tencent.mm.jpg"
        assertTrue(ScreenshotWatchController.looksLikeScreenshot(wechat, "DCIM/Screenshots/"))
        assertFalse(isOwnAppScreenshot(wechat, "com.bookkeeping.app"))
        assertTrue(isOwnAppScreenshot("Screenshot_2026-11-24_com.bookkeeping.app.sync2.jpg", "com.bookkeeping.app"))
    }

    @Test fun cameraPhotosAreNotScreenshots() {
        assertFalse(ScreenshotWatchController.looksLikeScreenshot("IMG_1234.jpg", "DCIM/Camera/"))
    }

    @Test fun pendingImagesNeverQualifyForWatermark() {
        assertFalse(isMediaRowReady(1))
        assertTrue(isMediaRowReady(0))
        assertEquals(listOf(102L, 103L), nextIdsToScan(101L, listOf(100L, 102L, 103L)))
        // On a pending row, the sequential worker stops before 103 and keeps 101 as watermark.
        assertEquals(101L, watermarkAfterMediaRow(101L, 102L, false))
        assertEquals(102L, watermarkAfterMediaRow(101L, 102L, true))
        assertEquals(102L, watermarkAfterMediaRow(102L, 100L, true))
        assertEquals(101L, resolveBaselineId(101L, 103L))
    }
}
