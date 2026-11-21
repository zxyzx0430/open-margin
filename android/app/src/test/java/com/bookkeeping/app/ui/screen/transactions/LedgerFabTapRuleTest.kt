package com.bookkeeping.app.ui.screen.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记账 FAB 的单击 / 双击判定。
 *
 * 手势本身在 VoiceFab（按住 0.75 秒是语音），这里只覆盖「一次点按算单击还是双击」，
 * 不驱动真实手势：[SimulatedFab] 复刻页面里 onTap + LaunchedEffect 的调用顺序，
 * 断言最终落到哪个动作上。
 */
class LedgerFabTapRuleTest {

    /** 复刻页面里的判定流程：挂起的单击 + 到点后的复核，记录最后一次真正执行的动作。 */
    private class SimulatedFab {
        var pendingTapAt: Long? = null
        var action: String? = null

        /** LaunchedEffect 启动时抓到的那个时间；为 null 表示没有在跑的单击计时。 */
        private var singleScheduledFor: Long? = null

        fun tap(nowMs: Long) {
            action = null
            when (LedgerFabTapRule.actionFor(pendingTapAt, nowMs)) {
                FabTapAction.DOUBLE_TAP -> {
                    pendingTapAt = null
                    singleScheduledFor = null
                    action = "screenshot"
                }
                FabTapAction.PENDING_SINGLE -> {
                    pendingTapAt = nowMs
                    singleScheduledFor = nowMs
                }
            }
        }

        /** 双击窗口到点：与页面里 `singleStillPending(pendingFabTapAt, tappedAt)` 同一判断。 */
        fun windowElapsed() {
            val scheduled = singleScheduledFor ?: return
            if (!LedgerFabTapRule.singleStillPending(pendingTapAt, scheduled)) return
            pendingTapAt = null
            singleScheduledFor = null
            action = "manual"
        }
    }

    @Test
    fun windowIsShortEnoughForASingleTap() {
        assertEquals(250L, LedgerFabTapRule.DOUBLE_TAP_WINDOW_MS)
        assertTrue(LedgerFabTapRule.DOUBLE_TAP_WINDOW_MS in 200L..300L)
    }

    @Test
    fun firstTapIsPendingNotImmediatelyADoubleTap() {
        assertEquals(
            FabTapAction.PENDING_SINGLE,
            LedgerFabTapRule.actionFor(firstTapAtMs = null, nowMs = 1_000L),
        )
    }

    @Test
    fun secondTapInsideWindowIsDoubleTap() {
        assertEquals(
            FabTapAction.DOUBLE_TAP,
            LedgerFabTapRule.actionFor(firstTapAtMs = 1_000L, nowMs = 1_100L),
        )
        // 边界：正好 250ms 仍算双击，不会漏掉手速慢一点的第二下。
        assertEquals(
            FabTapAction.DOUBLE_TAP,
            LedgerFabTapRule.actionFor(
                firstTapAtMs = 1_000L,
                nowMs = 1_000L + LedgerFabTapRule.DOUBLE_TAP_WINDOW_MS,
            ),
        )
    }

    @Test
    fun secondTapOutsideWindowStartsANewSingle() {
        assertEquals(
            FabTapAction.PENDING_SINGLE,
            LedgerFabTapRule.actionFor(
                firstTapAtMs = 1_000L,
                nowMs = 1_001L + LedgerFabTapRule.DOUBLE_TAP_WINDOW_MS,
            ),
        )
    }

    @Test
    fun singleTapStillOpensManualEntry() {
        val fab = SimulatedFab()
        fab.tap(nowMs = 5_000L)
        assertNull("单击在窗口内不该立刻执行", fab.action)
        fab.windowElapsed()
        assertEquals("manual", fab.action)
    }

    @Test
    fun doubleTapOpensScreenshotImportAndSkipsManualEntry() {
        val fab = SimulatedFab()
        fab.tap(nowMs = 5_000L)
        fab.tap(nowMs = 5_150L)
        assertEquals("screenshot", fab.action)
        // 第一下的单击计时到点后不能再补开手动记账（这就是「先关面板再导航」的闪回来源）。
        fab.windowElapsed()
        assertEquals("screenshot", fab.action)
        assertNull(fab.pendingTapAt)
    }

    @Test
    fun slowSecondTapIsTreatedAsANewSingleTap() {
        val fab = SimulatedFab()
        fab.tap(nowMs = 5_000L)
        fab.windowElapsed()
        assertEquals("manual", fab.action)

        // 超过窗口的第二下开启新一轮：两次慢速单击不会被拼成截图记账。
        fab.tap(nowMs = 5_400L)
        assertNull(fab.action)
        fab.windowElapsed()
        assertEquals("manual", fab.action)
    }

    @Test
    fun windowCallbackWithoutPendingTapDoesNothing() {
        val fab = SimulatedFab()
        fab.windowElapsed()
        assertNull(fab.action)
        assertFalse(LedgerFabTapRule.singleStillPending(null, 5_000L))
    }
}
