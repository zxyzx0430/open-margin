package com.bookkeeping.app.ui.screen.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.capture.screenshot.ScreenshotWatchController
import com.bookkeeping.app.capture.screenshot.ScreenshotWatchSettings
import com.bookkeeping.app.data.diagnostics.DiagnosticSummary
import com.bookkeeping.app.data.diagnostics.ServiceHealthEntry
import com.bookkeeping.app.data.diagnostics.ServiceHealthLog
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.notification.CaptureHealth
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.notification.SmsPermissionHelper
import com.bookkeeping.app.notification.accessibility.AccessibilityCaptureHealth
import com.bookkeeping.app.rom.DeviceRomDetector
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.sync.TransactionSyncWorker
import com.bookkeeping.app.ui.components.AutomationStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class DiagnosticExportContext(
    val appVersion: String,
    val packageName: String,
    val deviceInfo: String,
    val healthItems: List<Pair<String, String>>,
    val crashReport: String? = null,
    val selectedTransactionId: Long? = null,
    val query: String = "",
    val romDisplay: String = "",
    val batteryOptimizationStatus: String = "未知",
    val albumWatcherSummary: String = "未采集",
    val syncSummary: String = "未开启",
    val channelFilter: DiagnosticChannelFilter = DiagnosticChannelFilter.ALL,
    val outcomeFilter: DiagnosticOutcomeFilter = DiagnosticOutcomeFilter.ALL,
    val exportAll: Boolean = false,
    val serviceHealthEntries: List<ServiceHealthEntry> = emptyList(),
    val pendingCandidates: List<CaptureCandidate> = emptyList(),
    val ledgerPossibleDuplicateCount: Int = 0,
    val romManufacturer: String = "",
    val romBrand: String = "",
    val romFamily: String = "",
    val romVersion: String = "",
    val captureStrategy: String = "",
    val notificationReconnect24h: Int = 0,
    val screenshotThrottled24h: Boolean = false,
)

internal object DiagnosticExportFormatter {
    const val RAW_TEXT_EXPORT_LIMIT = 2_000
    private const val MAX_PENDING_SNAPSHOT_ROWS = 30

    fun defaultFileName(nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(nowMs))
        return "清衡诊断日志-$stamp.txt"
    }

    fun format(
        meta: DiagnosticExportContext,
        eventsNewestFirst: List<TransactionDiagnosticEventEntity>,
        includeRawText: Boolean,
        exportedAtMs: Long = System.currentTimeMillis(),
    ): String {
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val stepFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val attempts = DiagnosticTimeline.build(eventsNewestFirst.asReversed())
        return buildString {
            appendLine("========== 清衡诊断排障包 ==========")
            appendLine("导出时间: ${timeFmt.format(Date(exportedAtMs))}")
            appendLine("包含原始正文: ${if (includeRawText) "是（单条上限 $RAW_TEXT_EXPORT_LIMIT 字）" else "否（已脱敏）"}")
            appendLine(formatFilterLine(meta))
            appendLine()

            // Section 1: 环境与配置快照
            appendLine("---------- [1/4] 环境与配置快照 ----------")
            appendLine("应用版本: ${meta.appVersion} (${meta.packageName})")
            appendLine("设备系统: ${meta.deviceInfo}")
            if (meta.romDisplay.isNotBlank()) {
                appendLine("ROM 构建标识 (Build.DISPLAY): ${meta.romDisplay}")
            }
            if (meta.romFamily.isNotBlank()) {
                appendLine("Build.MANUFACTURER: " + meta.romManufacturer)
                appendLine("Build.BRAND: " + meta.romBrand)
                appendLine("ROM 家族: " + meta.romFamily)
                appendLine("ROM 版本: " + meta.romVersion)
                appendLine("当前采集策略: " + meta.captureStrategy)
                appendLine("通知监听最近24h重连请求次数: " + meta.notificationReconnect24h)
                appendLine("最近24h是否触发过限频: " + if (meta.screenshotThrottled24h) "是" else "否")
            }
            appendLine("电池优化白名单: ${meta.batteryOptimizationStatus}")
            appendLine("相册截图监听: ${meta.albumWatcherSummary}")
            appendLine("双向同步状态: ${meta.syncSummary}")
            meta.healthItems.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            appendLine()

            // Section 2: 服务运行轨迹
            val healthEntries = meta.serviceHealthEntries.take(ServiceHealthLog.MAX_ENTRIES)
            appendLine("---------- [2/4] 服务运行轨迹 (${healthEntries.size} 条) ----------")
            if (healthEntries.isEmpty()) {
                appendLine("(暂无服务运行轨迹)")
            } else {
                healthEntries.forEach { entry ->
                    append("${timeFmt.format(Date(entry.occurredAtMs))} [${entry.channel}/${entry.event}] ${entry.summary}")
                    if (!entry.detail.isNullOrBlank()) {
                        append(" | detail=${entry.detail}")
                    }
                    appendLine()
                }
            }
            appendLine()

            // Section 3: 待确认与重复现场快照
            appendLine("---------- [3/4] 待确认与重复现场快照 ----------")
            val pending = meta.pendingCandidates
            appendLine("待确认队列总数: ${pending.size}")
            appendLine("账本疑似重复 (possible_duplicate) 笔数: ${meta.ledgerPossibleDuplicateCount}")
            val duplicateGroups = groupDuplicatePending(pending)
            if (duplicateGroups.isNotEmpty()) {
                appendLine("重复分组统计 (count >= 2):")
                duplicateGroups.forEach { group ->
                    appendLine("  [疑似重复堆积 ×${group.count}] ${group.label}")
                }
            } else {
                appendLine("重复分组统计 (count >= 2): 无")
            }
            val latestPending = pending
                .sortedByDescending { it.capturedAtMs }
                .take(MAX_PENDING_SNAPSHOT_ROWS)
            if (latestPending.isEmpty()) {
                appendLine("待确认明细: (空)")
            } else {
                appendLine("最近待确认明细 (最多 $MAX_PENDING_SNAPSHOT_ROWS 条):")
                latestPending.forEach { candidate ->
                    val amountText = candidate.amountCents?.let { cents ->
                        DiagnosticSummary.amount(candidate.direction, cents, candidate.transactionCurrency)
                    } ?: "未知金额"
                    val sourceLabel = "${candidate.intakeMethod}/${candidate.packageName ?: candidate.paymentPlatform}"
                    appendLine(
                        "  ${timeFmt.format(Date(candidate.capturedAtMs))} | $sourceLabel | " +
                            "${candidate.kind} | ${candidate.direction} | $amountText | " +
                            "${candidate.reason} | ${candidate.pageIdentity}",
                    )
                }
            }
            if (!meta.crashReport.isNullOrBlank()) {
                appendLine()
                appendLine("---------- 最近崩溃堆栈 ----------")
                appendLine(meta.crashReport.trimEnd())
            }
            appendLine()

            // Section 4: 完整诊断链路
            appendLine("---------- [4/4] 完整诊断链路 (${attempts.size} 条 / ${eventsNewestFirst.size} 步) ----------")
            if (attempts.isEmpty()) {
                appendLine("(当前筛选条件下暂无诊断事件)")
            } else {
                attempts.forEach { attempt ->
                    val semantic = DiagnosticHumanizer.semanticOutcome(attempt)
                    val explanation = DiagnosticHumanizer.explainAttemptKey(attempt)
                    appendLine(
                        "[${timeFmt.format(Date(attempt.startedAtMs))}] attempt=${attempt.attemptId} " +
                            "entry=${attempt.entry.name} outcome=${attempt.outcome.name} " +
                            "semantic=${DiagnosticHumanizer.outcomePillKey(semantic)} " +
                            "duration=${DiagnosticTimeline.formatDuration(attempt.durationMs)} " +
                            "txId=${attempt.transactionId ?: "-"}",
                    )
                    appendLine("  摘要说明: $explanation")
                    attempt.steps.forEach { step ->
                        append("  - ${stepFmt.format(Date(step.occurredAtMs))} [${step.stage}/${step.result}]")
                        if (!step.callSite.isNullOrBlank()) append(" (${step.callSite})")
                        if (step.details.isNotBlank()) append(" ${step.details}")
                        appendLine()
                        if (!step.inputSummary.isNullOrBlank()) {
                            appendLine("    输入: ${step.inputSummary}")
                        }
                        if (!step.outputSummary.isNullOrBlank()) {
                            appendLine("    结果: ${step.outputSummary}")
                        }
                        val raw = step.rawText?.takeIf { it.isNotBlank() }
                        if (raw != null) {
                            if (includeRawText) {
                                appendLine("    原始正文: ${truncateRawTextForExport(raw)}")
                            } else {
                                appendLine("    原始正文: [已脱敏，共 ${raw.length} 字]")
                            }
                        }
                    }
                }
            }
        }
    }

    internal data class DuplicateGroupSummary(
        val key: String,
        val label: String,
        val count: Int,
    )

    internal fun groupDuplicatePending(candidates: List<CaptureCandidate>): List<DuplicateGroupSummary> {
        if (candidates.isEmpty()) return emptyList()
        return candidates
            .groupBy { "${it.packageName ?: "unknown"}|${it.kind}|${it.amountCents?.toString() ?: "null"}" }
            .mapNotNull { (key, group) ->
                if (group.size < 2) return@mapNotNull null
                val sample = group.first()
                val pkg = sample.packageName ?: sample.paymentPlatform
                val amountText = sample.amountCents?.let { DiagnosticSummary.amount(sample.direction, it) } ?: "未知金额"
                DuplicateGroupSummary(
                    key = key,
                    label = "$pkg / ${sample.kind} / $amountText",
                    count = group.size,
                )
            }
            .sortedByDescending { it.count }
    }

    internal fun truncateRawTextForExport(raw: String, maxChars: Int = RAW_TEXT_EXPORT_LIMIT): String {
        if (raw.length <= maxChars) return raw
        val suffix = "…（已截断，原文共 ${raw.length} 字）"
        val headLength = (maxChars - suffix.length).coerceAtLeast(0)
        return raw.take(headLength) + suffix
    }

    private fun formatFilterLine(meta: DiagnosticExportContext): String {
        if (meta.exportAll) {
            return "导出范围: 全部 7 天"
        }
        return "导出范围: 当前筛选范围 (channel=${meta.channelFilter.name}, " +
            "outcome=${meta.outcomeFilter.name}, " +
            "transactionId=${meta.selectedTransactionId ?: "全部"}, " +
            "query=${meta.query.ifBlank { "无" }})"
    }

    fun collectMeta(
        context: Context,
        selectedTransactionId: Long? = null,
        query: String = "",
        channelFilter: DiagnosticChannelFilter = DiagnosticChannelFilter.ALL,
        outcomeFilter: DiagnosticOutcomeFilter = DiagnosticOutcomeFilter.ALL,
        exportAll: Boolean = false,
        ledgerPossibleDuplicateCount: Int = 0,
    ): DiagnosticExportContext {
        val appContext = context.applicationContext
        val captureHealth = CaptureHealth.snapshot(appContext, serviceStartedAtMs = 0L)
        val accHealth = AccessibilityCaptureHealth.snapshot(appContext)
        val disconnect24h = ServiceHealthLog.disconnectCountLast24h(appContext)
        val healthItems = listOf(
            "系统通知权限" to if (AutomationStatus.areNotificationsGranted(appContext)) "已允许" else "未允许",
            "通知使用权" to if (NotificationListenerHelper.isEnabled(appContext)) "已授权" else "未授权",
            "页面识别服务" to if (AutomationStatus.isPageRecognitionEnabled(appContext)) "已开启" else "未开启",
            "无障碍兜底(可选)" to accHealth.state.name,
            "短信权限(可选)" to if (SmsPermissionHelper.isGranted(appContext)) "已授权" else "未开启",
            "通知采集状态" to "${captureHealth.state.name} (reason=${captureHealth.disconnectedReason.ifBlank { "none" }}, 近24h断开=${disconnect24h}次)",
        )
        val batteryStatus = runCatching {
            val pm = appContext.getSystemService(PowerManager::class.java)
            if (pm != null) {
                if (pm.isIgnoringBatteryOptimizations(appContext.packageName)) {
                    "已加入白名单（忽略电池优化）"
                } else {
                    "未加入白名单（系统后台策略可能限制运行）"
                }
            } else {
                "未知"
            }
        }.getOrDefault("未知")

        val albumState = ScreenshotWatchController.state(appContext)
        val albumEnabled = ScreenshotWatchSettings.isEnabled(appContext)
        val lastScannedId = ScreenshotWatchSettings.lastScannedId(appContext)
        val albumWatcherSummary =
            "state=${albumState.name}, enabled=$albumEnabled, lastScannedId=${lastScannedId ?: "未初始化"}"

        val syncSummary = runCatching {
            val prefs = appContext.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("enabled", false)
            val urlConfigured = !prefs.getString("url", "").isNullOrBlank()
            val lastCode = prefs.getString(TransactionSyncWorker.LAST_RESULT_CODE, null).orEmpty().ifBlank { "none" }
            val lastResult = prefs.getString("last_result", "").orEmpty().ifBlank { "none" }
            val lastSuccessMs = prefs.getLong("last_success_ms", 0L)
            val lastSuccessText = if (lastSuccessMs > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(lastSuccessMs))
            } else {
                "尚无"
            }
            val pendingUploads = prefs.getInt(TransactionSyncWorker.PENDING_UPLOADS, 0)
            val pendingConflicts = prefs.getInt(TransactionSyncWorker.PENDING_CONFLICTS, 0)
            "enabled=$enabled, urlConfigured=$urlConfigured, lastCode=$lastCode, " +
                "lastSuccess=$lastSuccessText, pendingUploads=$pendingUploads, pendingConflicts=$pendingConflicts, lastResult=$lastResult"
        }.getOrDefault("读取失败")

        val serviceHealthEntries = ServiceHealthLog.entries(appContext)
        val romProfile = DeviceRomDetector.current()
        val nowMs = System.currentTimeMillis()
        val recent = serviceHealthEntries.filter {
            it.occurredAtMs in (nowMs - ServiceHealthLog.DAY_MS)..nowMs
        }
        val reconnect24h = recent.count {
            it.channel == ServiceHealthLog.CHANNEL_NOTIFICATION &&
                it.event == ServiceHealthLog.EVENT_REBIND_REQUESTED
        }
        val throttled24h = recent.any {
            it.channel == ServiceHealthLog.CHANNEL_ACCESSIBILITY &&
                it.event == ServiceHealthLog.EVENT_THROTTLED
        }
        val pendingCandidates = runCatching { CaptureCandidateStore(appContext).pending() }.getOrDefault(emptyList())
        val crashReport = runCatching {
            File(appContext.filesDir, "crash-last.txt").takeIf { it.isFile }?.readText()
        }.getOrNull()

        return DiagnosticExportContext(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            packageName = appContext.packageName,
            deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            healthItems = healthItems,
            crashReport = crashReport,
            selectedTransactionId = selectedTransactionId,
            query = query,
            romDisplay = Build.DISPLAY.orEmpty(),
            batteryOptimizationStatus = batteryStatus,
            albumWatcherSummary = albumWatcherSummary,
            syncSummary = syncSummary,
            channelFilter = channelFilter,
            outcomeFilter = outcomeFilter,
            exportAll = exportAll,
            serviceHealthEntries = serviceHealthEntries,
            pendingCandidates = pendingCandidates,
            ledgerPossibleDuplicateCount = ledgerPossibleDuplicateCount,
            romManufacturer = Build.MANUFACTURER.orEmpty(),
            romBrand = Build.BRAND.orEmpty(),
            romFamily = romProfile.family.name,
            romVersion = romProfile.version,
            captureStrategy = romProfile.captureStrategy,
            notificationReconnect24h = reconnect24h,
            screenshotThrottled24h = throttled24h,
        )
    }
}
