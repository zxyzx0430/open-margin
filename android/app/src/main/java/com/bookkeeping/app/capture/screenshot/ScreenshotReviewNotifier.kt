package com.bookkeeping.app.capture.screenshot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bookkeeping.app.R
import com.bookkeeping.app.i18n.tr

/**
 * Medium-confidence screenshots ask through a notification with "record" and
 * "ignore" actions. It never starts an Activity from the background.
 */
object ScreenshotReviewNotifier {
    const val CHANNEL_ID = "screenshot_review_channel"
    private const val NOTIFICATION_ID = 2400

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            tr("截图待确认"),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = tr("识别到的截图交易，等你确认后入账") }
        manager.createNotificationChannel(channel)
    }

    fun notifyPending(context: Context, count: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)
        val book = PendingIntent.getBroadcast(
            context,
            2401,
            Intent(context, ScreenshotReviewReceiver::class.java)
                .setAction(ScreenshotReviewReceiver.ACTION_BOOK)
                .putExtra(ScreenshotReviewReceiver.EXTRA_NEXT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ignore = PendingIntent.getBroadcast(
            context,
            2402,
            Intent(context, ScreenshotReviewReceiver::class.java)
                .setAction(ScreenshotReviewReceiver.ACTION_IGNORE)
                .putExtra(ScreenshotReviewReceiver.EXTRA_NEXT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(tr("截图识别待确认"))
            .setContentText(
                if (count > 1) tr("有 %d 笔截图交易等你确认").format(count)
                else tr("有一笔截图交易等你确认"),
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(0, tr("记入"), book)
            .addAction(0, tr("忽略"), ignore)
        // No contentIntent: confirming must not pull the app to the foreground.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }
}
