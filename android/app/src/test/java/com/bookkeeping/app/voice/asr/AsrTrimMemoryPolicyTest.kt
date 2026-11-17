package com.bookkeeping.app.voice.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内存回调 → ASR 常驻策略。
 *
 * 产品要求「记账要快」：只有真的内存紧张才释放模型，`RUNNING_LOW` 只记日志。
 * 这里把每个级别钉死，防止以后有人顺手把 LOW 也加进释放分支。
 */
class AsrTrimMemoryPolicyTest {

    @Test
    fun runningLowOnlyLogsAndKeepsTheModel() {
        assertEquals(AsrMemoryAction.LOG_ONLY, AsrTrimMemoryPolicy.actionFor(AsrTrimMemoryPolicy.RUNNING_LOW))
    }

    @Test
    fun runningCriticalReleases() {
        assertEquals(
            AsrMemoryAction.RELEASE,
            AsrTrimMemoryPolicy.actionFor(AsrTrimMemoryPolicy.RUNNING_CRITICAL),
        )
    }

    @Test
    fun completeReleases() {
        assertEquals(AsrMemoryAction.RELEASE, AsrTrimMemoryPolicy.actionFor(AsrTrimMemoryPolicy.COMPLETE))
    }

    @Test
    fun backgroundAndModerateLevelsDoNothing() {
        // UI_HIDDEN(20) / BACKGROUND(40) / MODERATE(60) / RUNNING_MODERATE(5)：切后台不释放。
        listOf(0, 5, 20, 40, 60).forEach { level ->
            assertEquals("level=$level 不该动模型", AsrMemoryAction.NONE, AsrTrimMemoryPolicy.actionFor(level))
        }
    }

    @Test
    fun constantsMirrorThePlatformValues() {
        assertEquals(10, AsrTrimMemoryPolicy.RUNNING_LOW)
        assertEquals(15, AsrTrimMemoryPolicy.RUNNING_CRITICAL)
        assertEquals(80, AsrTrimMemoryPolicy.COMPLETE)
    }
}
