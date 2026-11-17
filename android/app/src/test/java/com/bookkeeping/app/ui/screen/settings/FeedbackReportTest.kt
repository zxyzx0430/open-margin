package com.bookkeeping.app.ui.screen.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportTest {
    private val diagnostics = FeedbackReport.diagnostics(
        versionName = "1.3.1",
        versionCode = 28,
        device = "Xiaomi 23127PN0CC",
        androidRelease = "15",
        sdkInt = 35,
        language = "zh_CN",
    )

    @Test
    fun diagnosticsCarryBuildAndDeviceFacts() {
        assertTrue(diagnostics.contains("1.3.1"))
        assertTrue(diagnostics.contains("28"))
        assertTrue(diagnostics.contains("Xiaomi 23127PN0CC"))
        assertTrue(diagnostics.contains("API 35"))
        assertTrue(diagnostics.contains("zh_CN"))
        assertTrue(diagnostics.contains("版本: 1.3.1"))
        assertTrue(diagnostics.contains("机型: Xiaomi 23127PN0CC"))
        assertTrue(diagnostics.contains("Android: 15 (API 35)"))
        assertTrue(diagnostics.contains("语言: zh_CN"))
    }

    @Test
    fun diagnosticsSayNoLedgerDataIsIncluded() {
        assertTrue(diagnostics.contains("不含账单金额"))
    }

    @Test
    fun mailtoTargetsTheDeveloperAddress() {
        val uri = FeedbackReport.mailtoUri("清衡反馈 1.3.1", "语音记账分类不对")
        assertTrue(uri.startsWith("mailto:zxyzx0430@outlook.com?subject="))
        assertTrue(uri.contains("body="))
        assertFalse(uri.contains(" "))
    }

    @Test
    fun issueUrlPointsAtNewIssueWithEncodedFields() {
        val url = FeedbackReport.issueUrl("清衡反馈 1.3.1", "语音记账分类不对\n第二行")
        assertTrue(url.startsWith(FeedbackReport.ISSUE_BASE + "?title="))
        assertTrue(url.contains("body="))
        assertFalse(url.contains("\n"))
        assertFalse(url.contains(" "))
    }
}
