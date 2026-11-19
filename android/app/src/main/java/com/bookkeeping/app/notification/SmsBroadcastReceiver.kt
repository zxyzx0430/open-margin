package com.bookkeeping.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
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
        val diagnosticInput = TransactionDiagnosticInput()
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
                    )
                    repository.recordDiagnostic(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_PARSE,
                        result = TransactionDiagnostics.RESULT_FAILURE,
                        details = "Bank SMS operation failed",
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
                    )
                    repository.recordDiagnostic(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_PARSE,
                        result = TransactionDiagnostics.RESULT_WARNING,
                        details = "Bank SMS did not parse as a transaction",
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
                )
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_PARSE,
                    result = TransactionDiagnostics.RESULT_SUCCESS,
                    details = "Bank SMS parsed",
                )
                val id = repository.insertIfNotDuplicate(parsed, diagnosticInput = diagnosticInput)
                if (id == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Skipped duplicate SMS")
                } else {
                    Log.i(TAG, "Auto-captured bank SMS: id=$id")
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                repository.recordDiagnostic(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = "Bank SMS operation failed",
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
