package com.bookkeeping.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.R
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class SubscriptionReminderWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val timeZone = TimeZone.getDefault()
        val db = BookkeepingDatabase.build(applicationContext)
        val dao = db.subscriptionDao()

        // Advance overdue auto-renew subscriptions so schedules remain current.
        val todayStartMs = SubscriptionProjection.startOfDayMs(nowMs, timeZone)
        val activeSubs = dao.getActiveNonDeleted()
        for (sub in activeSubs) {
            if (!sub.autoRenew) continue
            val dueStartMs = SubscriptionProjection.startOfDayMs(sub.nextDueMs, timeZone)
            if (dueStartMs < todayStartMs) {
                val advancedDue = SubscriptionProjection.advancePast(
                    nextDueMs = dueStartMs,
                    cadence = sub.cadence,
                    referenceMs = todayStartMs,
                    startMs = sub.startMs,
                    timeZone = timeZone,
                )
                if (advancedDue > dueStartMs) {
                    dao.upsert(
                        sub.copy(
                            nextDueMs = advancedDue,
                            updatedAtMs = nowMs,
                            dirty = true,
                        ),
                    )
                }
            }
        }

        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            return@withContext Result.success()
        }
        ensureChannel(applicationContext)

        val reminderPrefs = applicationContext.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            OPEN_APP_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val currentActive = dao.getActiveNonDeleted()
        for (sub in currentActive) {
            if (!sub.notifyEnabled) continue
            val daysUntilDue = SubscriptionProjection.daysUntilDue(sub.nextDueMs, nowMs, timeZone)
            if (daysUntilDue !in 0..sub.notifyDaysBefore) continue

            val dueDayMs = SubscriptionProjection.startOfDayMs(sub.nextDueMs, timeZone)
            val dedupKey = "${sub.syncUuid}:$dueDayMs:${sub.pendingCancel}"
            if (reminderPrefs.getBoolean(dedupKey, false)) continue

            val (title, body) = buildReminderContent(sub, daysUntilDue)
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build()

            val notificationId = BASE_NOTIFICATION_ID + (sub.syncUuid.hashCode() and 0x7FFF)
            val posted = runCatching {
                NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
            }.isSuccess
            if (posted) {
                reminderPrefs.edit().putBoolean(dedupKey, true).apply()
            }
        }

        Result.success()
    }

    companion object {
        const val WORK_NAME = "subscription_due_reminder"
        private const val CHANNEL_ID = "subscription_reminder_channel"
        private const val REMINDER_PREFS = "subscription_reminders"
        private const val BASE_NOTIFICATION_ID = 4000
        private const val OPEN_APP_REQUEST_CODE = 4000

        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionReminderWorker>(24, TimeUnit.HOURS)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        internal fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "周期续费到期提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "在订阅扣款日前提醒待扣费用与准备取消的自动续费"
            }
            manager.createNotificationChannel(channel)
        }

        fun buildReminderContent(
            subscription: SubscriptionEntity,
            daysUntilDue: Int,
        ): Pair<String, String> {
            val amountText = formatCurrencyAmount(subscription.amountCents, subscription.currency)
            val whenText = if (daysUntilDue <= 0) "今天" else "$daysUntilDue 天后"
            return if (subscription.pendingCancel) {
                val title = "[准备取消] ${subscription.name}"
                val body = "[准备取消] ${subscription.name} 将在 $whenText 自动续费 $amountText，记得去关闭自动续费"
                title to body
            } else {
                val channelSuffix = subscription.paymentChannel
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { "（$it）" }
                    ?: ""
                val title = "[续费提醒] ${subscription.name}"
                val body = "[续费提醒] ${subscription.name} 将在 $whenText 扣费 $amountText$channelSuffix"
                title to body
            }
        }

        private fun formatCurrencyAmount(amountCents: Long, currency: String): String {
            val symbol = when (currency.uppercase(Locale.US)) {
                "CNY" -> "¥"
                "USD" -> "$"
                "HKD" -> "HK$"
                "EUR" -> "€"
                "JPY" -> "¥"
                "GBP" -> "£"
                else -> "${currency.uppercase(Locale.US)} "
            }
            val whole = amountCents / 100L
            val fraction = (amountCents % 100L).toInt()
            return if (fraction == 0) {
                "$symbol$whole"
            } else {
                "$symbol$whole.${String.format(Locale.US, "%02d", fraction)}"
            }
        }
    }
}
