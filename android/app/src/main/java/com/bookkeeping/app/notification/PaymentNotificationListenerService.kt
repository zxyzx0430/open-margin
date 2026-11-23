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
import com.bookkeeping.app.capture.screenshot.ScreenshotWatchController
import com.bookkeeping.app.data.diagnostics.DiagnosticRawText
import com.bookkeeping.app.data.diagnostics.DiagnosticSummary
import com.bookkeeping.app.data.diagnostics.ServiceHealthLog
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.data.repository.NotificationInsertResult
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
        ServiceHealthLog.record(
            context = applicationContext,
            channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
            event = ServiceHealthLog.EVENT_CONNECTED,
            summary = "通知监听服务已连接",
        )
        KeepAliveService.refreshStatus(applicationContext)
    }

    override fun onListenerDisconnected() {
        val reason = if (NotificationListenerHelper.isEnabled(this)) {
            "listener_disconnected"
        } else {
            "permission_revoked"
        }
        CaptureHealth.onListenerDisconnected(applicationContext, reason)
        ServiceHealthLog.record(
            context = applicationContext,
            channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
            event = ServiceHealthLog.EVENT_DISCONNECTED,
            summary = "通知监听服务已断开",
            detail = reason,
        )
        KeepAliveService.refreshStatus(applicationContext)
        if (NotificationListenerHelper.isEnabled(this)) {
            scheduleRebind()
        }
        super.onListenerDisconnected()
    }

    private fun scheduleRebind() {
        ServiceHealthLog.record(
            context = applicationContext,
            channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
            event = ServiceHealthLog.EVENT_REBIND_SCHEDULED,
            summary = "已计划重连通知监听服务",
            detail = "delayMs=$REBIND_DELAY_MS",
        )
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                requestRebind(ComponentName(this, PaymentNotificationListenerService::class.java))
            }.onSuccess {
                ServiceHealthLog.record(
                    context = applicationContext,
                    channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
                    event = ServiceHealthLog.EVENT_REBIND_REQUESTED,
                    summary = "已向系统请求重连通知监听",
                )
            }.onFailure {
                ServiceHealthLog.record(
                    context = applicationContext,
                    channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
                    event = ServiceHealthLog.EVENT_REBIND_FAILED,
                    summary = "请求重连通知监听失败",
                    detail = it.javaClass.simpleName,
                )
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
        if (packageName == "com.tencent.mm" || packageName == "com.eg.android.AlipayGphone") {
            ScreenshotWatchController.catchUp(applicationContext)
        }
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 分组汇总通知只是把子通知汇总展示，子通知会各自回调；
        // 汇总若再处理一次就会重复记账，直接跳过。
        // Children arrive individually, so the summary would double-count.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            if (packageName in setOf("com.eg.android.AlipayGphone", "com.tencent.mm")) {
                val appLabel = if (packageName == "com.tencent.mm") "微信" else "支付宝"
                ServiceHealthLog.record(
                    context = applicationContext,
                    channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
                    event = ServiceHealthLog.EVENT_SKIPPED,
                    summary = "已跳过${appLabel}汇总通知（避免重复）",
                )
            }
            return
        }

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
            ServiceHealthLog.record(
                context = applicationContext,
                channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
                event = ServiceHealthLog.EVENT_SKIPPED,
                summary = "已忽略非交易或无金额的${appLabel}通知",
                detail = title?.take(60),
            )
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
        // This identifies the Android notification record only. Android apps can
        // reuse a record ID for another payment, so it is never transaction identity.
        val fingerprint = NoticeFingerprint.forSourceEvent(packageName, sbn.key)
        val legacyFingerprint = NoticeFingerprint.of(packageName, title, body, originalTime)
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
                when (val insertion = repository.insertNotificationIfNotReplayed(
                    fingerprint = fingerprint,
                    source = packageName,
                    seenMs = seenMs,
                    transaction = parsed,
                    notifySuccess = false,
                    diagnosticInput = diagnosticInput,
                    legacyFingerprints = setOf(legacyFingerprint),
                )) {
                    is NotificationInsertResult.Replayed -> {
                        ServiceHealthLog.record(
                            context = applicationContext,
                            channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
                            event = ServiceHealthLog.EVENT_SKIPPED,
                            summary = "已忽略重复${appLabel}通知（指纹/订单号已记录）",
                        )
                        repository.recordDiagnostic(
                            attemptId = diagnosticInput.attemptId,
                            stage = TransactionDiagnostics.STAGE_DEDUP,
                            result = TransactionDiagnostics.RESULT_WARNING,
                            details = "Exact order ID already recorded for the same payment platform; notification replay skipped",
                            callSite = diagnosticInput.callSite,
                            inputSummary = inputSummary,
                            outputSummary = "该支付平台的相同订单号已记录，重复通知已跳过",
                            rawText = rawText,
                        )
                        if (BuildConfig.DEBUG) Log.d(TAG, "Exact platform transaction already booked")
                    }
                    is NotificationInsertResult.NeedsReview -> {
                        repository.recordDiagnostic(
                            attemptId = diagnosticInput.attemptId,
                            stage = TransactionDiagnostics.STAGE_DEDUP,
                            result = TransactionDiagnostics.RESULT_WARNING,
                            details = insertion.reason,
                            transactionId = insertion.candidateTransactionId,
                            callSite = diagnosticInput.callSite,
                            inputSummary = inputSummary,
                            outputSummary = "通知重放身份存在歧义，已保留待复核",
                            rawText = rawText,
                        )
                    }
                    is NotificationInsertResult.Failed -> throw (insertion.cause as? Exception
                        ?: IllegalStateException("notification ledger write failed", insertion.cause))
                    is NotificationInsertResult.Processed -> when (val result = insertion.ledgerResult) {
                        is LedgerInsertResult.Failed -> throw (result.cause as? Exception
                            ?: IllegalStateException("notification ledger write failed", result.cause))
                        is LedgerInsertResult.Duplicate -> {
                            ServiceHealthLog.record(
                                context = applicationContext,
                                channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
                                event = ServiceHealthLog.EVENT_SKIPPED,
                                summary = "已忽略重复${appLabel}通知（账本已有相同交易）",
                                detail = "existingId=${result.existingTransactionId}",
                            )
                            if (BuildConfig.DEBUG) Log.d(TAG, "Skipped exact duplicate notification")
                        }
                        is LedgerInsertResult.Inserted ->
                            Log.i(TAG, "Auto-captured transaction id=${result.transactionId}")
                        is LedgerInsertResult.Merged ->
                            Log.i(TAG, "Auto-captured transaction id=${result.transactionId}")
                    }
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
