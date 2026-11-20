package com.bookkeeping.app.capture.screenshot

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.bookkeeping.app.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One screen for both manual intakes: ACTION_SEND from another app, and the
 * in-app Photo Picker. Both hand the URI to [ScreenshotIntakeCoordinator], so
 * there is exactly one recognition path.
 */
class ScreenshotImportActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: ScreenshotIntakeCoordinator
    private lateinit var status: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            finish()
        } else {
            ingest(uri, IntakeSource.PHOTO_PICKER)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = ScreenshotIntakeCoordinator(applicationContext)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        status = TextView(this).apply {
            text = tr("正在识别截图")
            textSize = 16f
        }
        val choose = Button(this).apply {
            text = tr("选择图片")
            setOnClickListener { openPicker() }
        }
        column.addView(status)
        column.addView(choose)
        setContentView(column)

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSend(intent)
            else -> openPicker()
        }
    }

    private fun openPicker() {
        runCatching {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            status.text = tr("当前系统不支持图片选择器")
        }
    }

    private fun handleSend(intent: Intent) {
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri == null) {
            status.text = tr("没有收到图片")
            return
        }
        ingest(uri, IntakeSource.SHARE)
    }

    private fun ingest(uri: Uri, source: IntakeSource) {
        // Share intents and the Photo Picker both hand over a read grant with the
        // URI; a revoked grant surfaces as UNREADABLE / PERMISSION_DENIED below.
        status.text = tr("正在识别截图")
        scope.launch {
            val outcome = coordinator.ingest(uri, source)
            runOnUiThread { showOutcome(outcome) }
        }
    }

    private fun showOutcome(outcome: ScreenshotIntakeCoordinator.Outcome) {
        if (outcome.confirmationCandidates.isNotEmpty()) {
            status.text = tr("请确认截图交易")
            showCandidateConfirmation(outcome.confirmationCandidates, index = 0, confirmed = 0)
            return
        }
        val message = when {
            outcome.failure == FailureReason.PERMISSION_DENIED -> tr("没有读取这张图片的权限")
            outcome.failure == FailureReason.URI_INVALID -> tr("图片地址已失效")
            outcome.failure == FailureReason.UNSUPPORTED_MIME -> tr("不是支持的图片格式")
            outcome.failure == FailureReason.UNREADABLE -> tr("图片读不出来")
            outcome.failure == FailureReason.OVERSIZED -> tr("图片太大，请先裁剪")
            outcome.failure == FailureReason.DECODE_FAILED -> tr("图片解码失败")
            outcome.failure == FailureReason.OCR_EMPTY -> tr("没有识别到文字")
            outcome.failure == FailureReason.NOT_TRANSACTION -> tr("这张图片里没有交易")
            outcome.failure == FailureReason.UNKNOWN_PAGE -> tr("没认出是微信或支付宝的交易页")
            outcome.repeatImage -> tr("这张截图已经处理过")
            outcome.booked > 0 && outcome.pending == 0 -> tr("已记录 %d 笔").format(outcome.booked)
            outcome.pending > 0 -> tr("有 %d 笔等你确认").format(outcome.pending)
            outcome.waiting > 0 -> tr("交易还没完成，稍后再看")
            outcome.linked > 0 -> tr("这笔已经在账本里")
            else -> tr("没有可记录的交易")
        }
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        status.postDelayed({ if (!isFinishing) finish() }, 1200)
    }

    private fun showCandidateConfirmation(
        candidates: List<com.bookkeeping.app.notification.capture.CaptureCandidate>,
        index: Int,
        confirmed: Int,
    ) {
        if (isFinishing) return
        if (index >= candidates.size) {
            val message = if (confirmed > 0) {
                tr("已确认记入 %d 笔截图交易").format(confirmed)
            } else {
                tr("截图交易确认完成")
            }
            status.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            status.postDelayed({ if (!isFinishing) finish() }, 1200)
            return
        }
        val candidate = candidates[index]
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("请确认这笔交易"))
            .setMessage(ScreenshotReviewNotifier.candidateDetails(candidate))
            .setCancelable(true)
            .setNegativeButton(tr("忽略")) { _, _ ->
                status.text = tr("正在更新截图候选")
                scope.launch {
                    val ignored = coordinator.ignoreCandidate(candidate)
                    runOnUiThread {
                        if (ignored) {
                            showCandidateConfirmation(candidates, index + 1, confirmed)
                        } else {
                            Toast.makeText(this@ScreenshotImportActivity, tr("候选状态未能更新，请重试"), Toast.LENGTH_LONG).show()
                            showCandidateConfirmation(candidates, index, confirmed)
                        }
                    }
                }
            }
            .setNeutralButton(tr("稍后")) { _, _ ->
                val message = tr("这笔交易仍待确认")
                status.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                status.postDelayed({ if (!isFinishing) finish() }, 1200)
            }
            .setOnCancelListener {
                val message = tr("这笔交易仍待确认")
                status.text = message
                status.postDelayed({ if (!isFinishing) finish() }, 1200)
            }
        if (candidate.ledgerWriteAllowed) {
            dialog.setPositiveButton(tr("确认记入")) { _, _ ->
                status.text = tr("正在记录截图交易")
                scope.launch {
                    val result = coordinator.confirmCandidate(candidate)
                    runOnUiThread {
                        when (result) {
                            ScreenshotIntakeCoordinator.ConfirmationResult.SAVED ->
                                showCandidateConfirmation(candidates, index + 1, confirmed + 1)
                            ScreenshotIntakeCoordinator.ConfirmationResult.ALREADY_EXISTS -> {
                                Toast.makeText(this@ScreenshotImportActivity, tr("这笔交易已在账本中"), Toast.LENGTH_LONG).show()
                                showCandidateConfirmation(candidates, index + 1, confirmed)
                            }
                            ScreenshotIntakeCoordinator.ConfirmationResult.ALREADY_HANDLED ->
                                showCandidateConfirmation(candidates, index + 1, confirmed)
                            ScreenshotIntakeCoordinator.ConfirmationResult.NOT_WRITABLE -> {
                                Toast.makeText(this@ScreenshotImportActivity, tr("仅预览，不能记入账本"), Toast.LENGTH_LONG).show()
                                showCandidateConfirmation(candidates, index + 1, confirmed)
                            }
                            ScreenshotIntakeCoordinator.ConfirmationResult.FAILED -> {
                                Toast.makeText(this@ScreenshotImportActivity, tr("暂时无法记录，请稍后重试"), Toast.LENGTH_LONG).show()
                                showCandidateConfirmation(candidates, index, confirmed)
                            }
                        }
                    }
                }
            }
        } else {
            dialog.setPositiveButton(tr("继续")) { _, _ ->
                showCandidateConfirmation(candidates, index + 1, confirmed)
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        coordinator.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ScreenshotImportActivity::class.java)

        fun requiredPermissions(): List<String> = emptyList()
    }
}
