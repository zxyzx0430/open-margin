package com.bookkeeping.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.data.diagnostics.DiagnosticRawText
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.data.repository.NotificationInsertResult
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

// 接收 SMS_RECEIVED 广播，解析银行短信入库。
// Hilt 直接支持 BroadcastReceiver：@AndroidEntryPoint 就能 @Inject。
//
// 嵌入式类比：注册"短信到达"中断的 ISR。
@AndroidEntryPoint
class SmsBroadcastReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: TransactionRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // 一条短信可能因长度被拆成多条 PDU，Telephony 帮我们合并
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // 拼接同一条短信的所有 part
        val sender = messages.first().originatingAddress
        val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val timestamp = messages.first().timestampMillis

        // 不在 release 构建中记录短信内容（含 PII：银行/卡尾号/金额）
        // Never write raw SMS or card details to logcat (including debug builds).

        val bankSignal = listOf(
            "工商银行", "招商银行", "建设银行", "中国银行", "农业银行", "交通银行",
            "邮政储蓄银行", "邮储银行", "中信银行", "光大银行", "民生银行", "浦发银行",
            "平安银行", "广发银行", "兴业银行", "华夏银行", "工行", "招行", "建行", "中行",
            "农行", "交行",
        ).any(fullBody::contains)
        if (!bankSignal) return
        // Sender + full body: an SMS that failed to parse is only diagnosable with
        // the text the bank actually sent. Bounded by DiagnosticRawText (this is
        // not the 160-char summary clip), local-only, erasable in the log screen.
        val rawText = DiagnosticRawText.titleAndBody(sender, fullBody)
        val diagnosticInput = TransactionDiagnosticInput(rawText = rawText)
        val parsed = try {
            SmsParser.parse(sender, fullBody, timestamp)
        } catch (failure: Exception) {
            val pending = goAsync()
            scope.launch {
                try {
                    repository.recordDiagnostic(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_CAPTURE,
                        result = TransactionDiagnostics.RESULT_STARTED,
                        details = "Bank SMS capture started",
                        rawText = rawText,
                    )
                    repository.recordDiagnostic(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_PARSE,
                        result = TransactionDiagnostics.RESULT_FAILURE,
                        details = "Bank SMS operation failed",
                        rawText = rawText,
                    )
                } finally {
                    pending.finish()
                }
            }
            Log.e(TAG, "SMS parse failed: ${failure.javaClass.simpleName}")
            return
        }
        if (parsed == null) {
            val pending = goAsync()
            scope.launch {
                try {
                    repository.recordDiagnostic(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_CAPTURE,
                        result = TransactionDiagnostics.RESULT_STARTED,
                        details = "Bank SMS capture started",
                        rawText = rawText,
                    )
                    repository.recordDiagnostic(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_PARSE,
                        result = TransactionDiagnostics.RESULT_WARNING,
                        details = "Bank SMS did not parse as a transaction",
                        rawText = rawText,
                    )
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val pending = goAsync()
        scope.launch {
            try {
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_STARTED,
                    details = "Bank SMS capture started",
                    rawText = rawText,
                )
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_PARSE,
                    result = TransactionDiagnostics.RESULT_SUCCESS,
                    details = "Bank SMS parsed",
                    rawText = rawText,
                )
                // SMS_RECEIVED does not give this receiver a stable provider URI
                // or message ID. The content tuple is only a legacy-ambiguity
                // lookup key; it is never claimed as a replay identity.
                val legacyFingerprint = NoticeFingerprint.of(
                    packageName = "BANK_SMS",
                    title = sender,
                    body = fullBody,
                    originalTimeMs = timestamp,
                )
                when (val insertion = repository.insertNotificationIfNotReplayed(
                    fingerprint = null,
                    source = "BANK_SMS",
                    seenMs = System.currentTimeMillis(),
                    transaction = parsed,
                    notifySuccess = false,
                    diagnosticInput = diagnosticInput,
                    legacyFingerprints = setOf(legacyFingerprint),
                )) {
                    is NotificationInsertResult.Replayed -> {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Skipped replayed bank SMS")
                    }
                    is NotificationInsertResult.NeedsReview -> {
                        repository.recordDiagnostic(
                            attemptId = diagnosticInput.attemptId,
                            stage = TransactionDiagnostics.STAGE_DEDUP,
                            result = TransactionDiagnostics.RESULT_WARNING,
                            details = insertion.reason,
                            transactionId = insertion.candidateTransactionId,
                            outputSummary = "短信重放身份存在歧义，已保留待复核",
                            rawText = rawText,
                        )
                    }
                    is NotificationInsertResult.Failed -> throw (insertion.cause as? Exception
                        ?: IllegalStateException("SMS ledger write failed", insertion.cause))
                    is NotificationInsertResult.Processed -> when (val result = insertion.ledgerResult) {
                        is LedgerInsertResult.Failed -> throw (result.cause as? Exception
                            ?: IllegalStateException("SMS ledger write failed", result.cause))
                        is LedgerInsertResult.Duplicate -> {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Skipped exact duplicate SMS")
                        }
                        is LedgerInsertResult.Inserted ->
                            Log.i(TAG, "Auto-captured bank SMS: id=${result.transactionId}")
                        is LedgerInsertResult.Merged ->
                            Log.i(TAG, "Auto-captured bank SMS: id=${result.transactionId}")
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = "Bank SMS operation failed",
                    rawText = rawText,
                )
                Log.e(TAG, "SMS processing failed: ${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BkSmsReceiver"
    }
}
