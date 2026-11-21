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

/** Visible content of the ongoing notification; everything a re-post would change. */
internal data class CaptureStatusContent(
    val contentText: String,
    val connected: Boolean,
)

/**
 * Pure rule for "is this notification worth another notify() call?".
 *
 * `connected` takes part because it decides whether the "去检查权限" action is
 * attached, so a connection change must repaint even when the text happens to
 * match. A null [lastText] means nothing was posted yet (first post or reset).
 */
internal fun shouldPostStatus(
    lastText: String?,
    newText: String,
    lastConnected: Boolean,
    newConnected: Boolean,
): Boolean = lastText == null || lastText != newText || lastConnected != newConnected

/**
 * Process-wide memory of the content last handed to NotificationManager.
 *
 * The 1s uptime ticker, the screen receiver and the listener callbacks all post
 * through here; a single notify() binder transaction can take ~1s on a loaded
 * device, so rebuilding an identical notification every second is pure waste.
 * The gate records a signature before the binder call and [forget]s it again if
 * the call never reached the shade, so a skipped post cannot stick.
 */
internal class CaptureStatusPostGate {

    private var lastText: String? = null
    private var lastConnected: Boolean = false

    /** Returns true only when the caller should hand this content to the system. */
    @Synchronized
    fun takeIfChanged(newText: String, newConnected: Boolean): Boolean {
        if (!shouldPostStatus(lastText, newText, lastConnected, newConnected)) return false
        lastText = newText
        lastConnected = newConnected
        return true
    }

    /** Drops the remembered content so the next post always goes through. */
    @Synchronized
    fun forget() {
        lastText = null
        lastConnected = false
    }
}

internal object CaptureStatusNotifier {
    const val CHANNEL_ID = "keep_alive_channel"
    const val NOTIFICATION_ID = 1001

    private val postGate = CaptureStatusPostGate()

    fun build(context: Context, serviceStartedAtMs: Long): Notification =
        buildNotification(context, statusContent(context, serviceStartedAtMs))

    /**
     * Builds the notification handed to startForeground and remembers exactly the
     * content it shows, so the ticker does not immediately post a duplicate.
     */
    fun buildForStartForeground(context: Context, serviceStartedAtMs: Long): Notification {
        val content = statusContent(context, serviceStartedAtMs)
        postGate.takeIfChanged(content.contentText, content.connected)
        return buildNotification(context, content)
    }

    /** Posts the status notification, skipping identical content. */
    fun post(context: Context, serviceStartedAtMs: Long): Boolean {
        val content = statusContent(context, serviceStartedAtMs)
        // Cheap in-process check first: no binder traffic at all when nothing changed.
        if (!postGate.takeIfChanged(content.contentText, content.connected)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null || !manager.areNotificationsEnabled()) {
            // Nothing reached the shade; let the next refresh try again.
            postGate.forget()
            return false
        }
        val posted = runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification(context, content))
        }
        if (posted.isFailure) postGate.forget()
        return posted.isSuccess
    }

    /** Forgets the last posted content (service start/stop) so a restart repaints. */
    fun forget() {
        postGate.forget()
    }

    private fun statusContent(
        context: Context,
        serviceStartedAtMs: Long,
    ): CaptureStatusContent {
        val health = CaptureHealth.snapshot(context, serviceStartedAtMs)
        val contentText = when (health.state) {
            CaptureConnectionState.CONNECTED ->
                AppLanguage.tr(context, "自动记账服务正常运行中")
            CaptureConnectionState.UNKNOWN ->
                AppLanguage.tr(context, "正在确认运行状态")
            CaptureConnectionState.PERMISSION_OFF ->
                AppLanguage.tr(context, "通知使用权已关闭 · 点击开启")
            CaptureConnectionState.DISCONNECTED -> disconnectedText(context, health)
        }
        return CaptureStatusContent(
            contentText = contentText,
            connected = health.state == CaptureConnectionState.CONNECTED,
        )
    }

    private fun buildNotification(
        context: Context,
        content: CaptureStatusContent,
    ): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(context, 0, openIntent, pendingFlags)

        // 常驻通知只保留「品牌名 + 一行状态」：展开式 BigTextStyle 会撑成三行，
        // 挤占通知栏又说不到重点；运行时长交给 App 内状态页显示。
        val title = AppLanguage.tr(context, "清衡 OpenMargin")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(content.contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (!content.connected) {
            val permissionIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val permissionPending =
                PendingIntent.getActivity(context, 1, permissionIntent, pendingFlags)
            builder.addAction(0, AppLanguage.tr(context, "去检查权限"), permissionPending)
        }
        return builder.build()
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

    private fun formatTime(context: Context, timestampMs: Long): String {
        if (timestampMs <= 0L) return AppLanguage.tr(context, "尚无记录")
        return DateFormat.getTimeFormat(context).format(Date(timestampMs))
    }
}
