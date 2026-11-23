package com.bookkeeping.app.ui.screen.diagnostics

import android.content.Context
import android.os.Build
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.notification.CaptureHealth
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.notification.SmsPermissionHelper
import com.bookkeeping.app.notification.accessibility.AccessibilityCaptureHealth
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
)

internal object DiagnosticExportFormatter {
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
            appendLine("应用版本: ${meta.appVersion} (${meta.packageName})")
            appendLine("设备系统: ${meta.deviceInfo}")
            appendLine("包含原始正文: ${if (includeRawText) "是" else "否（已脱敏）"}")
            if (meta.selectedTransactionId != null || meta.query.isNotBlank()) {
                appendLine(
                    "筛选条件: transactionId=${meta.selectedTransactionId ?: "全部"} query=${meta.query.ifBlank { "无" }}",
                )
            }
            appendLine()
            appendLine("---------- 权限与采集健康 ----------")
            meta.healthItems.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            if (!meta.crashReport.isNullOrBlank()) {
                appendLine()
                appendLine("---------- 最近崩溃堆栈 ----------")
                appendLine(meta.crashReport.trimEnd())
            }
            appendLine()
            appendLine("---------- 诊断链路 (${attempts.size} 条 / ${eventsNewestFirst.size} 步) ----------")
            if (attempts.isEmpty()) {
                appendLine("(当前筛选条件下暂无诊断事件)")
            } else {
                attempts.forEach { attempt ->
                    appendLine(
                        "[${timeFmt.format(Date(attempt.startedAtMs))}] attempt=${attempt.attemptId} " +
                            "entry=${attempt.entry.name} outcome=${attempt.outcome.name} " +
                            "duration=${DiagnosticTimeline.formatDuration(attempt.durationMs)} " +
                            "txId=${attempt.transactionId ?: "-"}",
                    )
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
                                appendLine("    原始正文: $raw")
                            } else {
                                appendLine("    原始正文: [已脱敏，共 ${raw.length} 字]")
                            }
                        }
                    }
                }
            }
        }
    }

    fun collectMeta(
        context: Context,
        selectedTransactionId: Long?,
        query: String,
    ): DiagnosticExportContext {
        val captureHealth = CaptureHealth.snapshot(context, serviceStartedAtMs = 0L)
        val accHealth = AccessibilityCaptureHealth.snapshot(context)
        val healthItems = listOf(
            "系统通知权限" to if (AutomationStatus.areNotificationsGranted(context)) "已允许" else "未允许",
            "通知使用权" to if (NotificationListenerHelper.isEnabled(context)) "已授权" else "未授权",
            "页面识别服务" to if (AutomationStatus.isPageRecognitionEnabled(context)) "已开启" else "未开启",
            "无障碍兜底(可选)" to accHealth.state.name,
            "短信权限(可选)" to if (SmsPermissionHelper.isGranted(context)) "已授权" else "未开启",
            "通知采集状态" to "${captureHealth.state.name} (reason=${captureHealth.disconnectedReason.ifBlank { "none" }})",
        )
        val crashReport = runCatching {
            File(context.filesDir, "crash-last.txt").takeIf { it.isFile }?.readText()
        }.getOrNull()
        return DiagnosticExportContext(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            packageName = context.packageName,
            deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            healthItems = healthItems,
            crashReport = crashReport,
            selectedTransactionId = selectedTransactionId,
            query = query,
        )
    }
}
