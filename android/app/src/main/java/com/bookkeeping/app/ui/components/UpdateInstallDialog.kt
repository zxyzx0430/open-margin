package com.bookkeeping.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.update.GitHubReleaseUpdateChecker
import com.bookkeeping.app.update.UpdateInstallFlow
import com.bookkeeping.app.update.UpdatePresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class UpdateInstallStage { PROMPT, DOWNLOADING, VERIFYING, READY, FAILED }

internal data class UpdateInstallDialogState(
    val update: GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable,
    val stage: UpdateInstallStage,
    val progress: Int? = null,
    val message: String? = null,
)

/**
 * Shared download/verify/install controller used by both Settings and the Home update banner
 * so tapping "立即更新" triggers the full installer pipeline directly without duplicating logic.
 */
@Stable
class UpdateInstallController internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    internal var dialogState by mutableStateOf<UpdateInstallDialogState?>(null)
        private set
    private var downloadJob: Job? = null

    fun open(update: GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable) {
        scope.launch {
            val verified = withContext(Dispatchers.IO) {
                UpdateInstallFlow.verifiedDownload(context, update)
            }
            dialogState = UpdateInstallDialogState(
                update = update,
                stage = if (verified != null) UpdateInstallStage.READY else UpdateInstallStage.PROMPT,
            )
        }
    }

    fun close() {
        downloadJob?.cancel()
        dialogState = null
    }

    internal fun startDownload(state: UpdateInstallDialogState) {
        val update = state.update
        if (!UpdateInstallFlow.hasInstallPermission(context)) {
            val opened = UpdateInstallFlow.openInstallPermissionSettings(context)
            dialogState = state.copy(
                stage = UpdateInstallStage.PROMPT,
                progress = null,
                message = if (opened) {
                    tr("请允许「安装未知应用」，返回后再点「下载」")
                } else {
                    tr("无法打开安装权限设置，请在系统设置中允许本应用安装未知应用")
                },
            )
            return
        }
        val id = UpdateInstallFlow.startDownload(context, update).getOrNull()
        if (id == null) {
            dialogState = state.copy(
                stage = UpdateInstallStage.FAILED,
                progress = null,
                message = tr("下载失败，请重试"),
            )
            return
        }
        dialogState = state.copy(
            stage = UpdateInstallStage.DOWNLOADING,
            progress = 0,
            message = null,
        )
        downloadJob?.cancel()
        downloadJob = scope.launch {
            UpdateInstallFlow.observe(context, id, update).collect { event ->
                val current = dialogState ?: return@collect
                if (current.update.apkUrl != update.apkUrl) return@collect
                dialogState = when (event) {
                    is UpdateInstallFlow.DownloadEvent.Running -> current.copy(
                        stage = UpdateInstallStage.DOWNLOADING,
                        progress = event.percent,
                        message = null,
                    )
                    UpdateInstallFlow.DownloadEvent.Verifying -> current.copy(
                        stage = UpdateInstallStage.VERIFYING,
                        progress = null,
                        message = null,
                    )
                    is UpdateInstallFlow.DownloadEvent.Completed -> {
                        val ready = current.copy(
                            stage = UpdateInstallStage.READY,
                            progress = 100,
                            message = null,
                        )
                        when (UpdateInstallFlow.openInstaller(context, event.apk)) {
                            UpdateInstallFlow.InstallOutcome.OPENED_INSTALLER -> null
                            UpdateInstallFlow.InstallOutcome.NEEDS_PERMISSION -> {
                                UpdateInstallFlow.openInstallPermissionSettings(context)
                                ready.copy(
                                    message = tr("请允许「安装未知应用」，返回后点「安装」"),
                                )
                            }
                            UpdateInstallFlow.InstallOutcome.NO_INSTALLER -> ready.copy(
                                message = tr("没有找到系统安装程序，请用文件管理器打开下载的 APK"),
                            )
                        }
                    }
                    is UpdateInstallFlow.DownloadEvent.Failed -> current.copy(
                        stage = UpdateInstallStage.FAILED,
                        progress = null,
                        message = installFailureText(event.reason),
                    )
                }
            }
        }
    }

    internal fun updateMessage(state: UpdateInstallDialogState, message: String) {
        dialogState = state.copy(message = message)
    }

    @Composable
    fun Dialog() {
        val state = dialogState ?: return
        val update = state.update
        val locked = state.stage == UpdateInstallStage.DOWNLOADING ||
            state.stage == UpdateInstallStage.VERIFYING
        AlertDialog(
            onDismissRequest = { if (!locked) close() },
            properties = DialogProperties(
                dismissOnBackPress = !locked,
                dismissOnClickOutside = !locked,
            ),
            title = {
                Text(tr("发现新版本") + " " + (update.apkTag ?: update.tag))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tr("版本") + "：" + (update.apkTag ?: update.tag),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    UpdatePresentation.formatSize(update.apkSizeBytes)?.let { size ->
                        Text(
                            tr("APK 大小") + "：" + size,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    UpdatePresentation.formatDate(update.publishedAt)?.let { date ->
                        Text(
                            tr("发布日期") + "：" + date,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (update.notes.isNotEmpty()) {
                        update.notes.forEach { line ->
                            Text(
                                text = "· " + line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    when (state.stage) {
                        UpdateInstallStage.DOWNLOADING -> {
                            val percent = state.progress
                            if (percent == null) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .height(4.dp),
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { percent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .height(4.dp),
                                    drawStopIndicator = {},
                                )
                            }
                        }
                        UpdateInstallStage.VERIFYING -> LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .height(4.dp),
                        )
                        else -> Unit
                    }
                    state.message?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !locked,
                    onClick = {
                        when (state.stage) {
                            UpdateInstallStage.READY -> {
                                val apk = UpdateInstallFlow.apkFile(context, update)
                                if (apk == null) {
                                    updateMessage(
                                        state,
                                        tr("没有找到已下载的安装包，请重新下载"),
                                    )
                                } else {
                                    when (UpdateInstallFlow.openInstaller(context, apk)) {
                                        UpdateInstallFlow.InstallOutcome.OPENED_INSTALLER -> close()
                                        UpdateInstallFlow.InstallOutcome.NEEDS_PERMISSION -> {
                                            UpdateInstallFlow.openInstallPermissionSettings(context)
                                            updateMessage(
                                                state,
                                                tr("请允许「安装未知应用」，返回后点「安装」"),
                                            )
                                        }
                                        UpdateInstallFlow.InstallOutcome.NO_INSTALLER ->
                                            updateMessage(
                                                state,
                                                tr("没有找到系统安装程序，请用文件管理器打开下载的 APK"),
                                            )
                                    }
                                }
                            }
                            else -> startDownload(state)
                        }
                    },
                ) {
                    Text(
                        text = installConfirmLabel(state),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            dismissButton = {
                if (!locked) {
                    TextButton(onClick = { close() }) {
                        Text(tr("取消"))
                    }
                }
            },
        )
    }
}

@Composable
fun rememberUpdateInstallController(): UpdateInstallController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) {
        UpdateInstallController(context, scope)
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}

private fun installConfirmLabel(state: UpdateInstallDialogState): String = when (state.stage) {
    UpdateInstallStage.PROMPT -> tr("下载")
    UpdateInstallStage.DOWNLOADING -> {
        val percent = state.progress
        if (percent == null) tr("下载中…") else tr("下载中") + " " + percent + "%"
    }
    UpdateInstallStage.VERIFYING -> tr("正在校验…")
    UpdateInstallStage.READY -> tr("安装")
    UpdateInstallStage.FAILED -> tr("重试")
}

private fun installFailureText(reason: UpdateInstallFlow.Failure): String = when (reason) {
    UpdateInstallFlow.Failure.SIZE_MISMATCH,
    UpdateInstallFlow.Failure.DIGEST_MISMATCH,
    -> tr("下载文件校验失败，已删除，请重试")
    UpdateInstallFlow.Failure.SIGNATURE_MISMATCH ->
        tr("安装包签名与当前应用不一致，已删除")
    UpdateInstallFlow.Failure.REJECTED_URL -> tr("更新地址不受信任，已取消下载")
    else -> tr("下载失败，请重试")
}
