package com.bookkeeping.app

import com.bookkeeping.app.i18n.EnglishUiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishUiTextDiagnosticsTest {
    @Test fun billDiagnosticsLabelsHaveEnglishTranslations() {
        val labels = listOf(
            "诊断日志",
            "账单诊断日志",
            "本机保存 7 天",
            "仅保存在本机，7 天后自动清理",
            "搜索账单 ID 或日志",
            "暂无诊断日志",
            "没有匹配的日志",
            "加载更多",
            "尝试 ID",
            "查看详情",
            "采集",
            "录入",
            "解析",
            "去重",
            "开始",
            "成功",
            "警告",
            "失败",
            "采集运行概览",
            "最近系统运行轨迹",
            "全部来源",
            "全部结果",
            "待确认/去重/跳过/失败",
            "仅导出当前筛选范围",
            "导出全部 7 天",
            "已自动记账",
            "转入待确认",
            "已去重忽略",
            "已跳过",
            "处理失败",
        )
        labels.forEach { label ->
            assertTrue("Missing English translation: $label", EnglishUiText.hasTranslation(label))
            assertNotEquals(label, EnglishUiText.translate(label))
        }
        assertTrue(EnglishUiText.translate("账单诊断日志").contains("Bill"))
        assertTrue(EnglishUiText.translate("仅保存在本机，7 天后自动清理").contains("7 days"))
    }

    @Test fun everyFixedDiagnosticDetailHasChineseAndEnglishPresentationText() {
        val details = listOf(
            "Payment notification capture started",
            "Payment notification operation failed",
            "Notification did not parse as a completed bill",
            "Payment notification parsed",
            "Bank SMS capture started",
            "Bank SMS operation failed",
            "Bank SMS did not parse as a transaction",
            "Bank SMS parsed",
            "Accessibility page capture started",
            "Accessibility capture failed",
            "Accessibility page parsed",
            "Accessibility fingerprint duplicate suppressed",
            "OCR payment-page capture started",
            "Screenshot could not be decoded",
            "OCR did not identify one completed bill",
            "OCR page contained multiple bill candidates",
            "Payment page processing failed",
            "Screenshot capture failed",
            "Payment-page candidate capture started",
            "Screenshot unavailable; parsed text will be used",
            "Payment page capture started",
            "Payment page parsed",
            "Capture candidate could not be stored",
            "Recent page-capture fingerprint suppressed",
            "Transaction save requested",
            "Transaction row inserted",
            "Transaction saved but success notification failed",
            "Existing source transaction ID; candidate not inserted",
            "Exact duplicate candidate skipped",
            "Candidate merged into existing transaction",
            "Merged transaction notification failed",
            "Transaction edit requested",
            "Transaction row updated",
            "Imported transaction row inserted",
            "Imported fields added to existing transaction",
            "Transaction deletion requested",
            "Transaction tombstone saved",
            "Possible duplicate retained for review",
            "No duplicate found",
            "Sync work requested",
            "Sync is disabled; transaction remains local",
            "Sync work queued",
            "No network upload scheduled",
            "Database operation failed",
            "Operation failed",
            "Sync blocked by security state",
            "Bidirectional sync failed",
            "Transaction sync operation failed",
            "Transaction is not uploadable; batch stopped",
            "Upload not attempted because another transaction is invalid",
            "Transaction upload started",
            "Upload request failed or returned no response",
            "Upload authorization failed",
            "Upload rejected by server",
            "Server acknowledgement count did not match upload",
            "Server acknowledged upload, but local sync cursor save failed",
            "Upload batch acknowledged; historical rows were ignored by the remote server",
            "Server acknowledged transaction upload",
            "Sync operation failed",
            "Stale server change ignored",
            "Remote change held because the local transaction has unsent edits",
            "Remote change could not be applied to a local transaction",
            "Server transaction change applied locally",
            "Transaction sync push started",
            "Transaction sync push failed",
            "Transaction was removed while sync push was in progress",
            "Local transaction changed during sync; stale response ignored",
            "Server applied transaction change",
            "Server reports a conflict and no current record",
            "User-approved local transaction change applied",
            "User-approved local change was not applied",
            "User-approved server transaction change applied",
            "Server conflict resolution could not be applied",
            "Transaction sync conflict awaits resolution",
            "Older server did not accept this transaction before its start boundary",
            "Unknown transaction sync result",
            "Duplicate precheck started",
            "Duplicate precheck failed",
            "No similar bill found",
            "Possible duplicate requires confirmation",
            "Unable to start voice transcription",
            "Voice transcription attempt started",
            "Microphone permission denied",
            "Voice transcription completed",
            "Voice transcription failed",
            "Voice model preparation failed",
            "Voice transcription final result timed out",
            "Quick-entry parsing started",
            "Quick-entry parsing failed",
            "Amount not recognized",
            "Parsed with review warnings",
            "Quick-entry text parsed",
            "Import preview contains no rows",
            "Import preview parsed",
            "Import file parsing started",
            "Import file parsing failed",
            "User confirmed bill import",
            "Confirmed import failed",
            "Confirmed import committed",
        )
        details.forEach { storedDetail ->
            val chinese = EnglishUiText.diagnosticDetailTranslationKey(storedDetail)
            assertNotEquals("Missing Chinese diagnostic detail: $storedDetail", storedDetail, chinese)
            assertTrue("Missing English UI mapping for: $chinese", EnglishUiText.hasTranslation(chinese))
            assertEquals(storedDetail, EnglishUiText.translate(chinese))
        }
    }

    @Test fun diagnosticQueryAndLoadErrorsHaveChineseAndEnglishText() {
        val errors = mapOf(
            "无法加载账单筛选项" to "Unable to load bill filters",
            "无法加载诊断日志" to "Unable to load diagnostic events",
        )
        errors.forEach { (chinese, english) ->
            assertTrue("Missing English UI mapping for: $chinese", EnglishUiText.hasTranslation(chinese))
            assertEquals(english, EnglishUiText.translate(chinese))
            assertEquals(chinese, EnglishUiText.diagnosticDetailTranslationKey(chinese))
        }
    }
}
