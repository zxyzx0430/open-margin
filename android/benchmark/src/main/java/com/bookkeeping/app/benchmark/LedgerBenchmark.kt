package com.bookkeeping.app.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET = "com.bookkeeping.app.sync3"

private fun UiDevice.requireLedger() {
    assertTrue("Complete onboarding and open the ledger in the dedicated test package before measuring.",
        wait(Until.hasObject(By.text("账单").pkg(TARGET)), 5_000) ||
            hasObject(By.text("Ledger").pkg(TARGET)))
}

private fun UiDevice.scrollLedger() {
    val x = displayWidth / 2
    val top = (displayHeight * 0.25f).toInt()
    val bottom = (displayHeight * 0.72f).toInt()
    repeat(5) {
        swipe(x, bottom, x, top, 24)
        waitForIdle(500)
    }
    repeat(5) {
        swipe(x, top, x, bottom, 24)
        waitForIdle(500)
    }
}

@RunWith(AndroidJUnit4::class)
class LedgerBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) { startActivityAndWait() }

    @Test fun ledgerScroll() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.requireLedger()
        },
    ) { device.scrollLedger() }
}

@RunWith(AndroidJUnit4::class)
class LedgerBaselineProfile {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun generate() = profile.collect(packageName = TARGET, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.requireLedger()
        device.scrollLedger()
    }
}
