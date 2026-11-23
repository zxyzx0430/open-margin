package com.bookkeeping.app

import com.bookkeeping.app.data.diagnostics.ServiceHealthEntry
import com.bookkeeping.app.data.diagnostics.ServiceHealthLog
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.CaptureMergePolicy
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticChannelFilter
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticExportContext
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticExportFormatter
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticHumanizer
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticOutcomeFilter
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticSemanticOutcome
import com.bookkeeping.app.ui.screen.diagnostics.DiagnosticTimeline
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
    fun `four part troubleshooting export includes env health pending duplicate summary and truncates long rawText`() {
        val now = 1_800_000_000_000L
        val pending = (1..35).map { idx ->
            CaptureCandidate(
                packageName = CaptureCandidate.WECHAT,
                pageIdentity = "page-$idx",
                amountCents = if (idx <= 3) 1800L else (1000L + idx),
                merchantCandidates = listOf("商户$idx"),
                orderId = null,
                capturedAtMs = now - idx * 1_000L,
                screenshotPath = null,
                confidence = 0.9f,
                reason = if (idx <= 3) CaptureCandidate.REASON_LIKELY_DUPLICATE else "待核对",
                intakeMethod = CaptureCandidate.INTAKE_ACCESSIBILITY,
                kind = CaptureCandidate.KIND_PAYMENT,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
            )
        }
        val meta = DiagnosticExportContext(
            appVersion = "1.46 (55)",
            packageName = "com.bookkeeping.app.sync3",
            deviceInfo = "Xiaomi 14 / Android 15 (SDK 35)",
            romDisplay = "OS2.0.10.0.VNCCNXM",
            batteryOptimizationStatus = "已加入白名单（忽略电池优化）",
            albumWatcherSummary = "state=ACTIVE, enabled=true, lastScannedId=420",
            syncSummary = "enabled=true, urlConfigured=true, lastCode=ok",
            healthItems = listOf("通知使用权" to "已授权"),
            serviceHealthEntries = listOf(
                ServiceHealthEntry(
                    occurredAtMs = now - 5_000L,
                    channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
                    event = ServiceHealthLog.EVENT_DISCONNECTED,
                    summary = "通知监听服务已断开",
                    detail = "listener_disconnected",
                ),
            ),
            pendingCandidates = pending,
            ledgerPossibleDuplicateCount = 2,
        )
        val longRaw = "X".repeat(2_500)
        val eventsNewestFirst = listOf(
            TransactionDiagnosticEventEntity(
                eventId = "e2",
                attemptId = "attempt-success",
                occurredAtMs = now,
                stage = TransactionDiagnostics.STAGE_SAVE,
                result = TransactionDiagnostics.RESULT_SUCCESS,
                details = "Transaction row inserted",
                transactionId = 99L,
                callSite = "PaymentNotificationListenerService.onNotificationPosted",
                outputSummary = "-¥12.00 · 瑞幸咖啡",
                rawText = longRaw,
            ),
        )

        val report = DiagnosticExportFormatter.format(
            meta = meta,
            eventsNewestFirst = eventsNewestFirst,
            includeRawText = true,
            exportedAtMs = now,
        )

        assertTrue(report.contains("[1/4] 环境与配置快照"))
        assertTrue(report.contains("OS2.0.10.0.VNCCNXM"))
        assertTrue(report.contains("已加入白名单"))
        assertTrue(report.contains("lastScannedId=420"))
        assertTrue(report.contains("enabled=true, urlConfigured=true"))

        assertTrue(report.contains("[2/4] 服务运行轨迹"))
        assertTrue(report.contains("通知监听服务已断开"))
        assertTrue(report.contains("listener_disconnected"))

        assertTrue(report.contains("[3/4] 待确认与重复现场快照"))
        assertTrue(report.contains("待确认队列总数: 35"))
        assertTrue(report.contains("账本疑似重复 (possible_duplicate) 笔数: 2"))
        assertTrue(report.contains("[疑似重复堆积 ×3] com.tencent.mm / PAYMENT / -¥18.00"))
        assertTrue(report.contains("page-1"))
        assertFalse(report.contains("page-35"))

        assertTrue(report.contains("[4/4] 完整诊断链路 (1 条 / 1 步)"))
        assertTrue(report.contains("attempt=attempt-success"))
        assertTrue(report.contains("已截断，原文共 2500 字"))
        assertFalse(report.contains(longRaw))

        val redactedReport = DiagnosticExportFormatter.format(
            meta = meta,
            eventsNewestFirst = eventsNewestFirst,
            includeRawText = false,
            exportedAtMs = now,
        )
        assertTrue(redactedReport.contains("[已脱敏，共 2500 字]"))
    }

    @Test
    fun `diagnostic humanizer classifies semantic outcomes and shared export filter matches`() {
        val now = 1_800_000_000_000L
        val eventsNewestFirst = listOf(
            // Attempt 1: Notification auto-booked (SUCCESS)
            TransactionDiagnosticEventEntity(
                eventId = "e1",
                attemptId = "a-notif-ok",
                occurredAtMs = now,
                stage = TransactionDiagnostics.STAGE_SAVE,
                result = TransactionDiagnostics.RESULT_SUCCESS,
                details = "Transaction row inserted",
                transactionId = 101L,
                callSite = "PaymentNotificationListenerService.onNotificationPosted",
                outputSummary = "-¥8.00 · 祐迎迎生鲜超市",
            ),
            // Attempt 2: Accessibility dedup suppressed
            TransactionDiagnosticEventEntity(
                eventId = "e2",
                attemptId = "a-acc-dedup",
                occurredAtMs = now - 1_000L,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_WARNING,
                details = "Recent platform order ID suppressed",
                callSite = "PageRecognitionService.processCandidates",
            ),
            // Attempt 3: Screenshot intake held for review despite RESULT_SUCCESS on save step
            TransactionDiagnosticEventEntity(
                eventId = "e3",
                attemptId = "a-shot-review",
                occurredAtMs = now - 2_000L,
                stage = TransactionDiagnostics.STAGE_SAVE,
                result = TransactionDiagnostics.RESULT_SUCCESS,
                details = "Screenshot intake applied",
                callSite = "ScreenshotIntakeCoordinator.ingest",
                outputSummary = "入账 0 · 待确认 1 · 等待 0 · 跳过 0",
            ),
            // Attempt 4: Notification parse skipped
            TransactionDiagnosticEventEntity(
                eventId = "e4",
                attemptId = "a-notif-skip",
                occurredAtMs = now - 3_000L,
                stage = TransactionDiagnostics.STAGE_PARSE,
                result = TransactionDiagnostics.RESULT_WARNING,
                details = "Notification did not parse as a completed bill",
                callSite = "PaymentNotificationListenerService.onNotificationPosted",
            ),
        )

        val attempts = DiagnosticTimeline.build(eventsNewestFirst.asReversed())
        val byId = attempts.associateBy { it.attemptId }

        assertEquals(DiagnosticSemanticOutcome.AUTO_BOOKED, DiagnosticHumanizer.semanticOutcome(byId["a-notif-ok"]!!))
        assertEquals("已自动记账", DiagnosticHumanizer.outcomePillKey(DiagnosticSemanticOutcome.AUTO_BOOKED))
        assertEquals("-¥8.00 · 祐迎迎生鲜超市", DiagnosticHumanizer.extractPrimarySummary(byId["a-notif-ok"]!!))

        assertEquals(DiagnosticSemanticOutcome.DEDUP_IGNORED, DiagnosticHumanizer.semanticOutcome(byId["a-acc-dedup"]!!))
        assertEquals("该订单号刚已抓取过，已自动跳过避免重复", DiagnosticHumanizer.explainAttemptKey(byId["a-acc-dedup"]!!))

        assertEquals(DiagnosticSemanticOutcome.HELD_FOR_REVIEW, DiagnosticHumanizer.semanticOutcome(byId["a-shot-review"]!!))
        assertEquals("截图已识别，已转入待确认等待你核对", DiagnosticHumanizer.explainAttemptKey(byId["a-shot-review"]!!))

        assertEquals(DiagnosticSemanticOutcome.SKIPPED, DiagnosticHumanizer.semanticOutcome(byId["a-notif-skip"]!!))

        // Filter by channel = NOTIFICATION and outcome = NON_AUTO_BOOKED
        val filteredEvents = DiagnosticTimeline.filterEventsForExport(
            eventsNewestFirst = eventsNewestFirst,
            channelFilter = DiagnosticChannelFilter.NOTIFICATION,
            outcomeFilter = DiagnosticOutcomeFilter.NON_AUTO_BOOKED,
        )
        assertEquals(listOf("a-notif-skip"), filteredEvents.map { it.attemptId })

        // Filter by outcome = NON_AUTO_BOOKED across ALL channels excludes a-notif-ok
        val nonAutoEvents = DiagnosticTimeline.filterEventsForExport(
            eventsNewestFirst = eventsNewestFirst,
            channelFilter = DiagnosticChannelFilter.ALL,
            outcomeFilter = DiagnosticOutcomeFilter.NON_AUTO_BOOKED,
        )
        assertEquals(setOf("a-acc-dedup", "a-shot-review", "a-notif-skip"), nonAutoEvents.map { it.attemptId }.toSet())
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
