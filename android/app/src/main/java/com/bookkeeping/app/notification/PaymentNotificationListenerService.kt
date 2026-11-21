package com.bookkeeping.app.notification

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.data.diagnostics.DiagnosticRawText
import com.bookkeeping.app.data.diagnostics.DiagnosticSummary
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
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

    override fun onListenerConnected() {
        super.onListenerConnected()
        CaptureHealth.onListenerConnected(applicationContext)
        KeepAliveService.refreshStatus(applicationContext)
    }

    override fun onListenerDisconnected() {
        val reason = if (NotificationListenerHelper.isEnabled(this)) {
            "listener_disconnected"
        } else {
            "permission_revoked"
        }
        CaptureHealth.onListenerDisconnected(applicationContext, reason)
        KeepAliveService.refreshStatus(applicationContext)
        if (NotificationListenerHelper.isEnabled(this)) {
            scheduleRebind()
        }
        super.onListenerDisconnected()
    }

    private fun scheduleRebind() {
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                requestRebind(ComponentName(this, PaymentNotificationListenerService::class.java))
            }.onFailure {
                Log.e(TAG, "Listener rebind failed: ${it.javaClass.simpleName}")
            }
        }, REBIND_DELAY_MS)
    }

    // 通知到达时回调（关键入口）。任何解析/入库异常只记录类型，不能打崩监听服务。
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handleNotificationPosted(sbn)
        } catch (failure: Exception) {
            CaptureHealth.onFailure(applicationContext, "processing", failure)
            KeepAliveService.refreshStatus(applicationContext)
            // Exception class only; never log notification content or amounts.
            Log.e(TAG, "Notification processing failed: ${failure.javaClass.simpleName}")
        }
    }

    private fun handleNotificationPosted(sbn: StatusBarNotification) {
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

        if (packageName !in setOf("com.eg.android.AlipayGphone", "com.tencent.mm")) return
        // Structured summary plus the verbatim title + body: when a user reports
        // "paid ¥5 on WeChat and it was not recorded", only the原文 shows whether
        // the parse missed or the notification never arrived. Raw text is bounded
        // by DiagnosticRawText, stored only on this device and erasable in the log
        // screen; it never reaches Logcat or the sync payload.
        val appLabel = if (packageName == "com.tencent.mm") "微信" else "支付宝"
        val inputSummary = "$appLabel 通知 1 条（正文 ${body.orEmpty().length} 字）"
        val rawText = DiagnosticRawText.titleAndBody(title, body)
        val diagnosticInput = TransactionDiagnosticInput(
            callSite = "PaymentNotificationListenerService.onNotificationPosted",
            rawText = rawText,
        )
        val parsed = try {
            NotificationParser.parse(
                packageName = packageName,
                title = title,
                text = body,
                timestampMs = originalTime,
            )
        } catch (failure: Exception) {
            scope.launch {
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_STARTED,
                    details = "Payment notification capture started",
                    callSite = diagnosticInput.callSite,
                    inputSummary = inputSummary,
                    rawText = rawText,
                )
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_PARSE,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = "Payment notification operation failed",
                    callSite = diagnosticInput.callSite,
                    inputSummary = inputSummary,
                    rawText = rawText,
                )
            }
            throw failure
        }
        if (parsed == null) {
            scope.launch {
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_STARTED,
                    details = "Payment notification capture started",
                    callSite = diagnosticInput.callSite,
                    inputSummary = inputSummary,
                    rawText = rawText,
                )
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_PARSE,
                    result = TransactionDiagnostics.RESULT_WARNING,
                    details = "Notification did not parse as a completed bill",
                    callSite = diagnosticInput.callSite,
                    inputSummary = inputSummary,
                    outputSummary = "未识别为已完成账单",
                    rawText = rawText,
                )
            }
            return
        }

        // 在后台协程里入库（IO 不能在主线程）。
        // 同一条通知会被系统重复投递：通知更新、MessagingStyle 重组、监听器重连重放、
        // 用户点开通知都可能再次回调 onNotificationPosted。指纹包含包名/标题/正文/
        // 原始时间，同一指纹 + 时间相近 = 同一条通知，不是新的一笔。
        //
        // 判定顺序很关键：先看指纹，再看「这条通知以前是否真的入过账」。指纹只在入库
        // 成功之后写入（见 TransactionRepository.recordNoticeFingerprint），所以写入
        // 失败的记录不会被重放检测当成已入账——宁可多记一行，不能吞掉真钱。
        val fingerprint = NoticeFingerprint.of(packageName, title, body, originalTime)
        val seenMs = System.currentTimeMillis()
        scope.launch {
            try {
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_STARTED,
                    details = "Payment notification capture started",
                    callSite = diagnosticInput.callSite,
                    inputSummary = inputSummary,
                    rawText = rawText,
                )
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_PARSE,
                    result = TransactionDiagnostics.RESULT_SUCCESS,
                    details = "Payment notification parsed",
                    callSite = diagnosticInput.callSite,
                    inputSummary = inputSummary,
                    outputSummary = DiagnosticSummary.amountWithMerchant(
                        amount = DiagnosticSummary.amount(
                            type = parsed.type,
                            amountCents = parsed.amountCents,
                            currency = parsed.currency,
                        ),
                        merchant = parsed.merchant,
                    ),
                    rawText = rawText,
                )
                val previous = repository.findProcessedNotice(fingerprint)
                if (NoticeReplayPolicy.isSameNoticeRepost(
                        previousFingerprint = previous?.fingerprint,
                        previousSeenMs = previous?.lastSeenMs,
                        fingerprint = fingerprint,
                        seenMs = seenMs,
                    )
                ) {
                    // 续期：连续重投递不应该在窗口边界上漏过去。
                    repository.recordNoticeFingerprint(fingerprint, packageName, seenMs)
                    repository.recordDiagnostic(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_DEDUP,
                        result = TransactionDiagnostics.RESULT_WARNING,
                        details = "Duplicate notification repost skipped",
                        callSite = diagnosticInput.callSite,
                        inputSummary = inputSummary,
                        outputSummary = "同一条通知重复投递，已跳过（这笔钱已在账本里）",
                        rawText = rawText,
                    )
                    if (BuildConfig.DEBUG) Log.d(TAG, "Notice repost skipped (already booked)")
                    return@launch
                }
                // insertIfNotDuplicate：实际窗口 8s，同来源+同商户+同金额视为重复。
                // 不跨来源自动合并，避免把独立的支付误删。
                val id = repository.insertIfNotDuplicate(parsed, diagnosticInput = diagnosticInput)
                // 入库成功（新行 / 8 秒内已存在）之后才记指纹：写入失败时下一次
                // 重投递还能重新入账。
                repository.recordNoticeFingerprint(fingerprint, packageName, seenMs)
                if (id == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Skipped duplicate notification")
                } else {
                    Log.i(TAG, "Auto-captured transaction id=$id")
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = "Payment notification operation failed",
                    callSite = diagnosticInput.callSite,
                    outputSummary = "写入失败，通知未入账",
                    rawText = rawText,
                )
                CaptureHealth.onFailure(applicationContext, "persist", failure)
                KeepAliveService.refreshStatus(applicationContext)
                Log.e(TAG, "Notification persist failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "BkNotifListener"
        private const val REBIND_DELAY_MS = 3_000L
    }
}

/**
 * 通知重复投递判定（纯逻辑，无 Android 依赖，可直接跑 JVM 单测）。
 *
 * 依据：[NoticeFingerprint] 把包名 / 标题 / 正文 / 原始通知时间压成一个 SHA-256。
 * 同一指纹 ⇒ 同一条通知证据；两笔真实交易的成交时间不同 ⇒ `originalTime` 不同
 * ⇒ 指纹不同，永远不会被这条规则拦下。
 *
 * 为什么是 10 分钟：通知被更新、MessagingStyle 重组、监听器重连重放、用户点开通知
 * 触发的再次投递，间隔通常是秒级到分钟级；10 分钟足够覆盖「付完款 → 点开详情页 →
 * 返回」这一整段操作，又不会无限期拒绝补记。超过窗口的重复投递仍然会插入：多出来的
 * 一行用户删得掉，被吞掉的那笔钱用户看不见。
 *
 * 这条规则只回答「是不是同一条通知的再次投递」，不回答「账本里有没有这笔钱」——
 * 后者由调用方用持久化指纹（只记录成功入账的通知）来确认。
 */
object NoticeReplayPolicy {

    /** 重投递窗口：同一条通知在这个时间内再次回调，视为重放。 */
    const val REPOST_WINDOW_MS: Long = 10 * 60_000L

    /**
     * @param previousFingerprint 上一次已入账通知的指纹；null 表示没有记录。
     * @param previousSeenMs 上一次成功入账的时间；null 表示从没成功入账过，不能拦。
     */
    fun isSameNoticeRepost(
        previousFingerprint: String?,
        previousSeenMs: Long?,
        fingerprint: String,
        seenMs: Long,
        windowMs: Long = REPOST_WINDOW_MS,
    ): Boolean {
        if (previousFingerprint != fingerprint) return false
        if (previousSeenMs == null) return false
        val elapsed = seenMs - previousSeenMs
        // 系统时间往回走（用户手动改时间）时不判重放：宁可多记一行。
        return elapsed in 0..windowMs
    }
}
