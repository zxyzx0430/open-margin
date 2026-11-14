package com.bookkeeping.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.R
import com.bookkeeping.app.i18n.AppLanguage
import java.util.Date
import java.util.Locale

internal object CaptureStatusNotifier {
    const val CHANNEL_ID = "keep_alive_channel"
    const val NOTIFICATION_ID = 1001

    fun build(context: Context, serviceStartedAtMs: Long): Notification {
        val health = CaptureHealth.snapshot(context, serviceStartedAtMs)
        val connected = health.state == CaptureConnectionState.CONNECTED
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(context, 0, openIntent, pendingFlags)

        val title = when (health.state) {
            CaptureConnectionState.CONNECTED ->
                "🟢 " + AppLanguage.tr(context, "自动记账运行中")
            CaptureConnectionState.UNKNOWN ->
                "⚪ " + AppLanguage.tr(context, "正在确认运行状态")
            else -> "🔴 " + AppLanguage.tr(context, "自动记账已断开")
        }
        val connectedLine = if (connected) {
            AppLanguage.tr(context, "通知监听已连接，已稳定运行") + " " +
                CaptureUptime.format(
                    CaptureUptime.elapsed(
                        connectedAtMs = health.connectedAtMs,
                        connectedElapsedMs = health.connectedElapsedMs,
                        nowMs = System.currentTimeMillis(),
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    ),
                    AppLanguage.tr(context, CaptureUptime.DEFAULT_PATTERN),
                )
        } else {
            ""
        }
        val contentText = when (health.state) {
            CaptureConnectionState.CONNECTED -> connectedLine
            CaptureConnectionState.UNKNOWN ->
                AppLanguage.tr(context, "已授权，正在等待通知监听连接")
            CaptureConnectionState.PERMISSION_OFF ->
                AppLanguage.tr(context, "通知使用权已关闭 · 点击开启")
            CaptureConnectionState.DISCONNECTED -> disconnectedText(context, health)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(buildBigText(context, health, contentText))
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (!connected) {
            val permissionIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val permissionPending =
                PendingIntent.getActivity(context, 1, permissionIntent, pendingFlags)
            builder.addAction(0, AppLanguage.tr(context, "去检查权限"), permissionPending)
        }
        return builder.build()
    }

    fun post(context: Context, serviceStartedAtMs: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        runCatching {
            manager.notify(NOTIFICATION_ID, build(context, serviceStartedAtMs))
        }
    }

    private fun disconnectedText(
        context: Context,
        health: CaptureHealthSnapshot,
    ): String {
        if (health.disconnectedAtMs > 0L) {
            val downtime = CaptureUptime.formatDowntime(
                CaptureUptime.elapsed(
                    connectedAtMs = health.disconnectedAtMs,
                    connectedElapsedMs = health.disconnectedElapsedMs,
                    nowMs = System.currentTimeMillis(),
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                ),
                AppLanguage.tr(context, CaptureUptime.SECONDS_PATTERN),
                AppLanguage.tr(context, CaptureUptime.MINUTES_PATTERN),
                AppLanguage.tr(context, CaptureUptime.HOURS_PATTERN),
            )
            return String.format(
                Locale.getDefault(),
                AppLanguage.tr(context, "%s 断开 · 已断开 %s · 点击检查权限"),
                formatTime(context, health.disconnectedAtMs),
                downtime,
            )
        }
        if (health.connectedAtMs <= 0L) {
            return AppLanguage.tr(context, "尚未连接过 · 点击检查权限")
        }
        return String.format(
            Locale.getDefault(),
            AppLanguage.tr(context, "上次连接 %s，当前未连接 · 点击检查权限"),
            formatTime(context, health.connectedAtMs),
        )
    }

    private fun buildBigText(
        context: Context,
        health: CaptureHealthSnapshot,
        statusLine: String,
    ): String {
        val lines = mutableListOf(statusLine)
        lines += if (health.lastSuccessAtMs > 0L) {
            AppLanguage.tr(context, "最近成功入账：") +
                formatTime(context, health.lastSuccessAtMs)
        } else {
            AppLanguage.tr(context, "尚无成功入账")
        }
        if (health.state == CaptureConnectionState.PERMISSION_OFF) {
            lines += AppLanguage.tr(context, "原因：通知使用权已关闭")
        } else if (health.state == CaptureConnectionState.DISCONNECTED &&
            health.disconnectedReason == "listener_disconnected"
        ) {
            lines += AppLanguage.tr(context, "原因：系统已断开通知监听服务")
        }
        if (health.lastFailureAtMs > 0L &&
            health.lastFailureAtMs > health.lastSuccessAtMs &&
            health.failureStage.isNotBlank()
        ) {
            lines += String.format(
                Locale.getDefault(),
                AppLanguage.tr(context, "最近异常：%s（%d 次）"),
                stageLabel(context, health.failureStage),
                health.failureCount,
            )
        }
        lines += AppLanguage.tr(context, "仅在本机显示状态，不含金额和商户")
        return lines.joinToString("\n")
    }

    private fun stageLabel(context: Context, stage: String): String = when (stage) {
        "processing" -> AppLanguage.tr(context, "通知处理")
        "persist" -> AppLanguage.tr(context, "保存交易")
        else -> stage
    }

    private fun formatTime(context: Context, timestampMs: Long): String {
        if (timestampMs <= 0L) return AppLanguage.tr(context, "尚无记录")
        return DateFormat.getTimeFormat(context).format(Date(timestampMs))
    }
}
