package com.bookkeeping.app.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// 监听全系统通知。当支付宝/微信发出支付成功通知时被回调。
// 嵌入式类比：注册了一个"中断处理函数"，系统在通知到达时调用我们。
class PaymentNotificationListenerService : NotificationListenerService() {

    private lateinit var repository: TransactionRepository

    // 后台协程作用域。Service 销毁时一起 cancel。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Hilt EntryPoint 方式拿到 Repository（@AndroidEntryPoint 不支持本类）
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationListenerEntryPoint::class.java
        )
        repository = entryPoint.transactionRepository()
        if (BuildConfig.DEBUG) Log.d(TAG, "Service created")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // 通知到达时回调（关键入口）
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 分组汇总通知只是把子通知汇总展示，子通知会各自回调；
        // 汇总若再处理一次就会重复记账，直接跳过。
        // Children arrive individually, so the summary would double-count.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        // 展开态/样式文本：金额常常只出现在这些 extras 里。
        // Expanded / style extras: the amount often only lives here.
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val textLines = extras.getCharSequenceArray("android.textLines")
            ?.mapNotNull { it?.toString() }
            .orEmpty()

        // android.messages 是 Parcelable(Bundle) 数组，逐个安全读取，脏数据不崩。
        // Each entry carries its own "text" and "time".
        val messageTexts = mutableListOf<String>()
        val messageTimes = mutableListOf<Long>()
        extras.getParcelableArray("android.messages")?.forEach { raw ->
            val bundle = raw as? Bundle ?: return@forEach
            bundle.getCharSequence("text")?.toString()?.let(messageTexts::add)
            // time=0 表示未知，不作为时间候选。
            val messageTime = bundle.getLong("time", 0L)
            if (messageTime > 0L) messageTimes.add(messageTime)
        }

        // 按 bigText -> textLines -> messages -> text 的顺序挑正文。
        val body = NotificationTextExtractor.bestBody(
            text = text,
            bigText = bigText,
            textLines = textLines,
            messages = messageTexts,
        )

        // postTime 会被 MessagingStyle 重发/通知刷新推后；取 24h 内最新的
        // 可信候选作为事件时间，避免新支付被旧消息时间吞掉。
        // Reposts move postTime forward; book the latest plausible real time.
        val originalTime = NotificationTextExtractor.resolveOriginalTimeMs(
            postTimeMs = sbn.postTime,
            notificationWhenMs = notification.`when`,
            messageTimesMs = messageTimes,
        )

        // 不在 release 构建中记录通知内容（含 PII：金额/商户/付款方式）
        // Notification bodies can contain transaction and identity details;
        // do not print them to logcat even in debug builds.

        val parsed = NotificationParser.parse(
            packageName = packageName,
            title = title,
            text = body,
            timestampMs = originalTime,
        ) ?: return

        // 在后台协程里入库（IO 不能在主线程）。
        // D05 影子模式：先持久记录通知指纹。重放只计数并写 debug 日志，
        // 不拦截入库——跨渠道去重启用要等 D06 核验（蓝图铁律）。
        // Shadow mode: record the fingerprint, never block the insert yet.
        val fingerprint = NoticeFingerprint.of(packageName, title, body, originalTime)
        scope.launch {
            val replay = repository.recordNoticeFingerprint(
                fingerprint = fingerprint,
                source = packageName,
                seenMs = System.currentTimeMillis(),
            )
            if (replay && BuildConfig.DEBUG) {
                Log.d(TAG, "Notice fingerprint already seen (shadow mode, not blocking)")
            }
            // insertIfNotDuplicate：实际窗口 8s，同来源+同商户+同金额视为重复。
            // 不跨来源自动合并，避免把独立的支付误删。
            val id = repository.insertIfNotDuplicate(parsed)
            if (id == null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipped duplicate notification")
            } else if (BuildConfig.DEBUG) {
                Log.i(TAG, "Auto-captured transaction id=$id amount=${parsed.amountCents} source=${parsed.source}")
            } else {
                Log.i(TAG, "Auto-captured transaction id=$id")
            }
        }
    }

    companion object {
        private const val TAG = "BkNotifListener"
    }
}
