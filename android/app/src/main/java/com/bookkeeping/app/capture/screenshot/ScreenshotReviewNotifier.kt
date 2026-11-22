package com.bookkeeping.app.capture.screenshot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bookkeeping.app.MainActivity
import com.bookkeeping.app.R
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.ui.screen.capture.CaptureReviewMapping
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Candidate-specific review alerts plus actionable candidate-storage warnings. */
object ScreenshotReviewNotifier {
    const val CHANNEL_ID = "screenshot_review_channel"
    private const val NOTIFICATION_ID = 2400
    private const val STORAGE_FULL_NOTIFICATION_ID = 2401
    private const val OPEN_REVIEW_REQUEST = 2402

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            tr("交易待确认"),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = tr("识别到的交易，等你确认后入账") }
        manager.createNotificationChannel(channel)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Actionable warning for a rejected candidate-index write. */
    fun notifyCandidateStorageFull(context: Context, intakeKind: CandidateStorageIntakeKind) {
        if (!CandidateStorageWarningQueue.markPending(context, intakeKind)) {
            Log.e(TAG, "Could not persist candidate-storage warning state")
        }
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        ensureChannel(context)
        val openPending = PendingIntent.getActivity(
            context,
            STORAGE_FULL_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val warningKinds = CandidateStorageWarningQueue.pendingKinds(context) + intakeKind
        val detail = CandidateStorageWarningCopy.guidance(warningKinds) { AppLanguage.tr(context, it) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(tr("待确认存储已满"))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openPending)
            .addAction(0, tr("打开待确认列表"), openPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { notifications.notify(STORAGE_FULL_NOTIFICATION_ID, notification) }
    }

    fun notifyPending(context: Context, candidates: List<CaptureCandidate>) {
        if (candidates.isEmpty() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)
        val candidate = candidates.first()
        val availableActions = ScreenshotReviewNotificationPolicy.availableActions(candidate)
        val title = when (candidate.intakeMethod) {
            CaptureCandidate.INTAKE_NOTIFICATION -> tr("支付通知待确认")
            CaptureCandidate.INTAKE_SMS -> tr("短信交易待确认")
            else -> tr("截图识别待确认")
        }
        val book = actionIntent(context, candidate, ScreenshotReviewReceiver.ACTION_BOOK)
        val ignore = actionIntent(context, candidate, ScreenshotReviewReceiver.ACTION_IGNORE)
        val review = if (ScreenshotReviewNotificationPolicy.Action.OPEN_REVIEW in availableActions) {
            PendingIntent.getActivity(
                context,
                candidate.candidateId.hashCode() xor OPEN_REVIEW_REQUEST,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }
        val detail = buildString {
            append(candidateDetails(candidate))
            if (candidates.size > 1) {
                append("\n")
                append(tr("还有 %d 笔待确认").format(candidates.size - 1))
            }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(detail.replace('\n', ' '))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (review != null) {
            builder.setContentIntent(review)
            builder.addAction(0, tr("打开待确认列表"), review)
        } else {
            if (ScreenshotReviewNotificationPolicy.Action.RECORD in availableActions) {
                builder.addAction(0, tr("记入"), book)
            }
            if (ScreenshotReviewNotificationPolicy.Action.IGNORE in availableActions) {
                builder.addAction(0, tr("忽略"), ignore)
            }
        }
        // Only an explicit tap opens review; image failures have no shortcut to book or ignore.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun actionIntent(context: Context, candidate: CaptureCandidate, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            candidate.candidateId.hashCode() xor action.hashCode(),
            Intent(context, ScreenshotReviewReceiver::class.java)
                .setAction(action)
                .putExtra(ScreenshotReviewReceiver.EXTRA_CANDIDATE_ID, candidate.candidateId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun candidateDetails(candidate: CaptureCandidate): String {
        val income = candidate.direction == CaptureCandidate.DIRECTION_INCOME
        val directionKnown = candidate.direction in setOf(
            CaptureCandidate.DIRECTION_INCOME, CaptureCandidate.DIRECTION_EXPENSE,
        )
        val currency = candidate.transactionCurrency ?: "CNY"
        val currencySymbol = if (currency == "CNY") "¥" else "$currency "
        val amount = candidate.amountCents?.let {
            (if (!directionKnown) currencySymbol else if (income) "+$currencySymbol" else "−$currencySymbol") +
                String.format(Locale.ROOT, "%.2f", it / 100.0)
        } ?: tr("金额待补")
        val merchant = candidate.merchantCandidates.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: tr("未知商户")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(candidate.eventTimeMs ?: candidate.capturedAtMs))
        val pendingTime = if (candidate.eventTimeNeedsConfirmation) " · ${tr("时间待确认")}" else ""
        val platform = when {
            candidate.transactionSource == "BANK_SMS" -> tr("银行短信")
            candidate.transactionSource == "WECHAT" ||
                candidate.paymentPlatform == CaptureCandidate.PLATFORM_WECHAT -> tr("微信")
            candidate.transactionSource == "ALIPAY" ||
                candidate.paymentPlatform == CaptureCandidate.PLATFORM_ALIPAY -> tr("支付宝")
            else -> tr("其他来源")
        }
        val status = when (candidate.completionStatus) {
            CaptureCandidate.COMPLETION_COMPLETED -> tr("交易已完成")
            CaptureCandidate.COMPLETION_PENDING -> tr("交易处理中")
            CaptureCandidate.COMPLETION_CREATED -> tr("尚未扣款")
            CaptureCandidate.COMPLETION_CANCELLED -> tr("交易已取消")
            CaptureCandidate.COMPLETION_FAILED -> tr("交易失败")
            else -> tr("交易状态未识别")
        }
        val details = listOf(
            "${tr("金额")}：$amount",
            "${tr("收支类型")}：${tr(if (!directionKnown) "方向待确认" else if (income) "收入" else "支出")}",
            "${tr("商户")}：$merchant · $platform",
            "${tr("发生时间")}：$time$pendingTime",
            "${tr("状态")}：$status",
        )
        val review = "${tr("确认原因")}：${reviewReason(candidate)}"
        val imageWarning = CaptureReviewMapping.screenshotUnavailableNotice(candidate)
        val hasReviewWarning = ScreenshotReviewNotificationPolicy.warning(candidate) != null
        val ordered = buildList {
            if (hasReviewWarning) add(review)
            imageWarning?.let(::add)
            addAll(details)
            if (!hasReviewWarning) add(review)
        }
        return ordered.joinToString("\n")
    }

    private fun reviewReason(candidate: CaptureCandidate): String = when (ScreenshotReviewNotificationPolicy.warning(candidate)) {
        ScreenshotReviewNotificationPolicy.Warning.WEAK_DUPLICATE ->
            tr("疑似与已有记录重复，请核对后再决定是否记入。")
        ScreenshotReviewNotificationPolicy.Warning.NOTIFICATION_KEY_AMBIGUITY ->
            tr("通知记录标识存在歧义，可能是重复提醒，也可能是另一笔交易；请核对后再决定是否记入。")
        ScreenshotReviewNotificationPolicy.Warning.SAME_IMAGE_REPEAT ->
            tr("同一张截图里有相似记录，请核对是否重复后再决定是否记入。")
        null -> when {
        !candidate.ledgerWriteAllowed -> tr("仅预览，不能记入账本")
        candidate.kind in setOf(
            CaptureCandidate.KIND_REFUND,
            CaptureCandidate.KIND_RED_PACKET,
            CaptureCandidate.KIND_WITHDRAWAL,
        ) -> tr("这类交易需要人工确认")
        candidate.reason.contains("auto booking disabled") -> tr("自动记账已关闭")
        // Transfers are not in the "always manual" group any more: a completed
        // transfer with a proven direction and platform can auto-book. Explain
        // which proof is still missing instead of telling the user the whole
        // kind needs manual confirmation.
        candidate.kind == CaptureCandidate.KIND_TRANSFER &&
            candidate.direction !in setOf(
                CaptureCandidate.DIRECTION_INCOME,
                CaptureCandidate.DIRECTION_EXPENSE,
            ) -> tr("转账方向待确认，请先选择收入或支出")
        !candidate.completionExplicit -> tr("截图未明确显示交易状态")
        candidate.kind == CaptureCandidate.KIND_TRANSFER &&
            candidate.completionStatus != CaptureCandidate.COMPLETION_COMPLETED ->
            tr("转账尚未成功，暂不能记账")
        candidate.eventTimeNeedsConfirmation -> tr("截图时间为暂用时间，请核对交易时间")
        else -> tr("请核对截图识别结果")
        }
    }

    private const val TAG = "ScreenshotReview"
}
