package com.bookkeeping.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the ongoing-notification dedupe: the 1s uptime ticker must
 * not call notify() again while the visible text is unchanged, but the
 * "已断开 N 秒" countdown must keep repainting every second.
 */
class CaptureStatusNotifierDedupeTest {

    private val connectedText = "自动记账服务正常运行中"

    /** Same shape as CaptureStatusNotifier.disconnectedText, minus the Android lookups. */
    private fun disconnectedLine(seconds: Long): String {
        val downtime = CaptureUptime.formatDowntime(seconds * 1_000L)
        return "17:33:50 断开 · 已断开 $downtime · 点击检查权限"
    }

    @Test
    fun identicalTextIsNotReposted() {
        assertFalse(shouldPostStatus(connectedText, connectedText, true, true))
    }

    @Test
    fun changedTextIsReposted() {
        assertTrue(shouldPostStatus(connectedText, "正在确认运行状态", true, true))
    }

    @Test
    fun losingConnectionIsRepostedEvenWhenTheTextMatches() {
        // Losing the connection attaches the "去检查权限" action, so the visual
        // changes even if the text happened to be identical.
        assertTrue(shouldPostStatus(connectedText, connectedText, true, false))
    }

    @Test
    fun regainingConnectionIsRepostedSoTheActionDisappears() {
        assertTrue(shouldPostStatus(connectedText, connectedText, false, true))
    }

    @Test
    fun firstPostAlwaysGoesThrough() {
        assertTrue(shouldPostStatus(null, connectedText, false, true))
    }

    @Test
    fun gateRepostsAfterForget() {
        val gate = CaptureStatusPostGate()
        assertTrue(gate.takeIfChanged(connectedText, true))
        assertFalse(gate.takeIfChanged(connectedText, true))
        gate.forget()
        assertTrue(gate.takeIfChanged(connectedText, true))
    }

    @Test
    fun disconnectionCountdownStillAdvancesEverySecond() {
        val first = disconnectedLine(5)
        val second = disconnectedLine(6)
        assertNotEquals(first, second)
        assertTrue(shouldPostStatus(first, second, false, false))
    }

    @Test
    fun repeatedCallWithinTheSameSecondIsNotReposted() {
        val gate = CaptureStatusPostGate()
        val line = disconnectedLine(5)
        assertTrue(gate.takeIfChanged(line, false))
        assertFalse(gate.takeIfChanged(line, false))
        assertTrue(gate.takeIfChanged(disconnectedLine(6), false))
    }

    @Test
    fun healthyServicePostsOnceAndThenStaysQuiet() {
        val gate = CaptureStatusPostGate()
        assertTrue(gate.takeIfChanged(connectedText, true))
        // The ticker keeps calling once per second; every repeat is a no-op.
        repeat(15) {
            assertFalse(gate.takeIfChanged(connectedText, true))
        }
    }
}
