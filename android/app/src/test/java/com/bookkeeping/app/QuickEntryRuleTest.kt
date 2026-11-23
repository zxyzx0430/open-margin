package com.bookkeeping.app

import com.bookkeeping.app.ui.components.HoldRelease
import com.bookkeeping.app.ui.components.QuickEntryRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按 0.75 秒的时序与「空白不提交」都是纯规则，直接测数值，不依赖 Composables。
 */
class QuickEntryRuleTest {
    @Test
    fun ringSweepsZeroToOneOverHoldTime() {
        assertEquals(0f, QuickEntryRule.ringProgress(0L), 0.0001f)
        assertEquals(1f / 3f, QuickEntryRule.ringProgress(250L), 0.0001f)
        assertEquals(0.5f, QuickEntryRule.ringProgress(375L), 0.0001f)
        assertEquals(1f, QuickEntryRule.ringProgress(QuickEntryRule.HOLD_TO_RECORD_MS), 0.0001f)
    }

    @Test
    fun ringNeverOverflowsAndIgnoresNegativeTime() {
        assertEquals(1f, QuickEntryRule.ringProgress(5_000L), 0.0001f)
        assertEquals(0f, QuickEntryRule.ringProgress(-40L), 0.0001f)
    }

    @Test
    fun voiceTriggersAt750msAndNotBefore() {
        assertFalse(QuickEntryRule.isLongEnough(749L))
        assertTrue(QuickEntryRule.isLongEnough(750L))
    }

    @Test
    fun visualFeedbackStartsAfter100msButVoiceStillStartsAt750ms() {
        assertEquals(0f, QuickEntryRule.visualRingProgress(99L), 0.0001f)
        assertEquals(0f, QuickEntryRule.visualRingProgress(100L), 0.0001f)
        assertTrue(QuickEntryRule.visualRingProgress(250L) > 0f)
        assertEquals(1f, QuickEntryRule.visualRingProgress(750L), 0.0001f)
        assertTrue(QuickEntryRule.isLongEnough(750L))
    }

    @Test
    fun secondTapNeeds280msAndNearbyPosition() {
        assertTrue(QuickEntryRule.isSecondTap(1000, 1280, 7f, 15f))
        assertFalse(QuickEntryRule.isSecondTap(1000, 1281, 7f, 15f))
        assertFalse(QuickEntryRule.isSecondTap(1000, 1080, 16f, 15f))
        assertFalse(QuickEntryRule.isSecondTap(1000, 999, 7f, 15f))
        assertFalse(QuickEntryRule.isSecondTap(-1, 1080, 7f, 15f))
    }

    @Test
    fun lightHapticComesAtHalfASecond() {
        assertFalse(QuickEntryRule.shouldTick(499L))
        assertTrue(QuickEntryRule.shouldTick(500L))
        // 轻震必须早于真正触发，否则用户感觉不到「快到了」。
        assertTrue(QuickEntryRule.HAPTIC_TICK_MS < QuickEntryRule.HOLD_TO_RECORD_MS)
    }

    @Test
    fun releaseDecidesBetweenTapRecordAndAbort() {
        // 不到 0.75 秒松手 = 普通点击（开表单，不录音）。
        assertEquals(HoldRelease.TAP, QuickEntryRule.releaseAction(armed = false, cancelled = false))
        // 没开录就滑走 = 什么都不做。
        assertEquals(HoldRelease.NOTHING, QuickEntryRule.releaseAction(armed = false, cancelled = true))
        // 满 0.75 秒后正常松手 = 什么也不做，录音继续，不能结束会话。
        assertEquals(
            HoldRelease.NOTHING,
            QuickEntryRule.releaseAction(armed = true, cancelled = false),
        )
        // 满 0.75 秒后仍按住并滑走 = 取消录音并关面板，绝不能退化成点击。
        assertEquals(
            HoldRelease.ABORT_RECORDING,
            QuickEntryRule.releaseAction(armed = true, cancelled = true),
        )
    }

    @Test
    fun blankInputIsNotSubmitted() {
        assertFalse(QuickEntryRule.canSubmit(""))
        assertFalse(QuickEntryRule.canSubmit("   "))
        assertFalse(QuickEntryRule.canSubmit("\n\t"))
        assertTrue(QuickEntryRule.canSubmit("麦当劳 36.5"))
    }

    @Test
    fun hintStaysVisibleForFourSeconds() {
        assertEquals(4_000L, QuickEntryRule.HINT_VISIBLE_MS)
    }
}
