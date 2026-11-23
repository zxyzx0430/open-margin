package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.CaptureMergePolicy
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticExportContext
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticExportFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P0FeatureRegressionTest {
    @Test
    fun `diagnostic export redacts raw text by default and includes it when requested`() {
        val now = 1_800_000_000_000L
        val meta = DiagnosticExportContext(
            appVersion = "1.41 (42)",
            packageName = "com.bookkeeping.app.sync3",
            deviceInfo = "OPPO PKR110 / Android 16 (SDK 36)",
            healthItems = listOf("通知使用权" to "已授权", "页面识别服务" to "已开启"),
            crashReport = "java.lang.IllegalStateException: test trace",
        )
        val events = listOf(
            TransactionDiagnosticEventEntity(
                eventId = "evt-1",
                attemptId = "attempt-1234",
                occurredAtMs = now,
                stage = "capture",
                result = "success",
                details = "Notification parsed",
                transactionId = 88L,
                callSite = "PaymentNotificationListenerService",
                inputSummary = "CNY 12.00",
                outputSummary = "Saved #88",
                rawText = "SECRET_RAW_NOTIFICATION_BODY",
            ),
        )

        val redacted = DiagnosticExportFormatter.format(meta, events, includeRawText = false, exportedAtMs = now)
        assertTrue(redacted.contains("1.41 (42)"))
        assertTrue(redacted.contains("OPPO PKR110"))
        assertTrue(redacted.contains("通知使用权: 已授权"))
        assertTrue(redacted.contains("IllegalStateException: test trace"))
        assertTrue(redacted.contains("attempt-1234"))
        assertFalse(redacted.contains("SECRET_RAW_NOTIFICATION_BODY"))
        assertTrue(redacted.contains("[已脱敏"))

        val withRaw = DiagnosticExportFormatter.format(meta, events, includeRawText = true, exportedAtMs = now)
        assertTrue(withRaw.contains("SECRET_RAW_NOTIFICATION_BODY"))
    }

    @Test
    fun `mergePendingDuplicate combines richer fields and soft deletes duplicate`() {
        val original = TransactionEntity(
            id = 10L,
            amountCents = 2500L,
            type = "EXPENSE",
            category = TransactionCategory.OTHER.name,
            merchant = "塔斯汀+",
            note = "[通知] 微信支付",
            source = "WECHAT",
            account = null,
            timestamp = 1_800_000_000_000L,
            remoteReviewStatus = "unverified",
        )
        val duplicate = TransactionEntity(
            id = 20L,
            amountCents = 2500L,
            type = "EXPENSE",
            category = TransactionCategory.FOOD.name,
            merchant = "塔斯汀中国汉堡",
            note = "[导入] 官方账单",
            source = "ALIPAY",
            account = "花呗",
            timestamp = 1_800_000_030_000L,
            sourceTransactionId = "202611230001",
            captureConfidence = 0.92f,
            captureKind = "SCREENSHOT",
            captureObservedAtMs = 1_800_000_020_000L,
            duplicateOfId = 10L,
            remoteReviewStatus = "possible_duplicate",
        )

        val plan = CaptureMergePolicy.mergePendingDuplicate(original, duplicate)

        assertEquals(10L, plan.keep.id)
        assertEquals("塔斯汀中国汉堡", plan.keep.merchant)
        assertEquals(TransactionCategory.FOOD.name, plan.keep.category)
        assertEquals("202611230001", plan.keep.sourceTransactionId)
        assertEquals("花呗", plan.keep.account)
        assertEquals(1_800_000_020_000L, plan.keep.captureObservedAtMs)
        assertEquals("confirmed", plan.keep.remoteReviewStatus)
        assertNull(plan.keep.duplicateOfId)
        assertTrue(plan.keep.note.contains("[通知] 微信支付"))
        assertTrue(plan.keep.note.contains("[导入] 官方账单"))

        assertEquals(20L, plan.absorbed.id)
        assertTrue(plan.absorbed.syncDeleted)
        assertNull(plan.absorbed.duplicateOfId)
    }
}
