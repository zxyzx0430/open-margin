package com.bookkeeping.app.capture.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两件真机问题：
 *  - 详情页右上角的「未同步」被 OCR 当成商户名，因为截的是本 app 自己的界面：
 *    文件名里带着 `com.bookkeeping.app[.sync2]`，必须在 OCR 之前就跳过；
 *  - 观察者被 MIUI 静默丢掉回调后永久失聪：兜底扫描按 MediaStore `_ID` 水位推进，
 *    首轮只记基线不回溯，每次最多处理 5 张，同一 _ID 不重复。
 *
 * 这里只测纯函数，不搭 MediaStore。
 */
class ScreenshotWatchCatchUpTest {

    // ---- 本 app 自己的截图 ----

    @Test
    fun `own app screenshot is skipped before OCR`() {
        val name = "Screenshot_2026-11-21-17-18-00-590_com.bookkeeping.app.sync2.jpg"

        // 名字本身确实像截图：挡住它的是包名，不是截图规则。
        assertTrue(ScreenshotWatchController.looksLikeScreenshot(name, "Pictures/Screenshots/"))
        assertTrue(
            "sync2 包名带后缀，也必须按固定前缀挡住",
            isOwnAppScreenshot(name, "com.bookkeeping.app"),
        )
        assertTrue(
            "普通包名同样要挡住",
            isOwnAppScreenshot("Screenshot_2026-11-21-17-18-00-590_com.bookkeeping.app.jpg", "com.bookkeeping.app"),
        )
    }

    @Test
    fun `wechat screenshot is not skipped`() {
        val name = "Screenshot_2026-11-21-17-38-55-330_com.tencent.mm.jpg"

        assertTrue(ScreenshotWatchController.looksLikeScreenshot(name, "Pictures/Screenshots/"))
        assertFalse(isOwnAppScreenshot(name, "com.bookkeeping.app"))
    }

    // ---- 兜底扫描的水位 ----

    @Test
    fun `first scan takes the current maximum as baseline and never backfills`() {
        val baseline = resolveBaselineId(stored = null, maxAvailableId = 900L)

        assertEquals(900L, baseline)
        assertEquals(
            "首轮不回溯：已有的 900 张一张都不扫",
            emptyList<Long>(),
            nextIdsToScan(baseline, (1L..900L).toList()),
        )
    }

    @Test
    fun `watermark above the album is re-baselined instead of going deaf`() {
        assertEquals(120L, resolveBaselineId(stored = 5000L, maxAvailableId = 120L))
    }

    @Test
    fun `stored watermark below the maximum is kept as is`() {
        assertEquals(900L, resolveBaselineId(stored = 900L, maxAvailableId = 1200L))
    }

    @Test
    fun `only ids above the watermark are scanned, oldest first`() {
        assertEquals(
            listOf(6L, 7L, 9L),
            nextIdsToScan(lastScannedId = 5L, availableIds = listOf(9L, 3L, 6L, 5L, 7L, 7L)),
        )
    }

    @Test
    fun `one scan handles at most five images`() {
        assertEquals(
            listOf(11L, 12L, 13L, 14L, 15L),
            nextIdsToScan(lastScannedId = 10L, availableIds = (11L..30L).toList()),
        )
    }

    @Test
    fun `a handled id moves the watermark so it is never scanned again`() {
        val available = (11L..30L).toList()
        val first = nextIdsToScan(lastScannedId = 10L, availableIds = available)

        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), first)
        assertTrue("同一批 _ID 不能第二次进队列", nextIdsToScan(first.last(), first).isEmpty())
        assertEquals(
            "水位推进后从下一张继续，不跳过也不回头",
            listOf(16L, 17L, 18L, 19L, 20L),
            nextIdsToScan(first.last(), available),
        )
    }
}
