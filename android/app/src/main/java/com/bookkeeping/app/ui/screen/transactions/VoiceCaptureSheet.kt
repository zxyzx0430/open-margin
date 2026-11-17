package com.bookkeeping.app.ui.screen.transactions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.voice.asr.AsrErrorCode
import com.bookkeeping.app.voice.asr.AsrEvent
import com.bookkeeping.app.voice.asr.AsrModelException
import com.bookkeeping.app.voice.asr.StreamingAsrEngine
import com.bookkeeping.app.voice.asr.VoiceAsrHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 长按「+」1 秒后出现的录音面板。
 *
 * 状态流转全部走 [VoiceCaptureLogic]（纯 JVM 可测），这里只负责：
 * - 从进程级 [VoiceAsrHolder] 拿引擎（首次懒加载，之后常驻），面板关闭/销毁只
 *   `cancelSession()` 结束会话，**绝不 close() 引擎**；
 * - 把引擎事件翻译成面板状态：Partial 只刷大字预览，Final 才收面板；
 * - 权限、切后台、超时这些「会捂着麦克风」的路径都收敛到 `cancelSession()` + 取消。
 *
 * 关键取舍：**Partial 绝不进表单**。CTC 的 Partial 只是「当前前缀」，用户还在说话时
 * 它随时会被改写；拿它记账会把半句话记成一笔。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCaptureSheet(
    onCancel: () -> Unit,
    onFinal: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 回调可能在生命周期观察者 / 协程里被调用，用最新引用，避免拿到旧 composition 的 lambda。
    val latestCancel by rememberUpdatedState(onCancel)
    val latestFinal by rememberUpdatedState(onFinal)

    // 引擎是进程级常驻的：这里只是拿引用，不负责它的生死。
    // 已经预热过就同步拿到实例；还没加载完则由下面的 acquire() 等它。
    var engine by remember { mutableStateOf(VoiceAsrHolder.current()) }

    var state by remember {
        mutableStateOf<VoiceCaptureState>(VoiceCaptureState.Preparing(tr("正在准备语音模型…")))
    }
    // 重试只换 attempt、重跑 acquire；引擎实例由 holder 复用（已就绪时 acquire 立即返回）。
    var attempt by remember { mutableIntStateOf(0) }
    // 结果只交付一次：交付或取消之后，迟到的 Partial/Final/Error 全部丢弃。
    var settled by remember { mutableStateOf(false) }
    // 系统权限对话框会让 Activity 走 onStop；这期间不能把面板当成「切后台」关掉。
    var awaitingPermission by remember { mutableStateOf(false) }

    /** 停止后的收尾：等 Final。 */
    fun stopListening() {
        state = VoiceCaptureLogic.stop(state)
        engine?.finishInput()
    }

    /** 开一次会话。引擎没准备好 / 已释放会抛 IllegalStateException，转成可重试失败。 */
    fun startListening(active: StreamingAsrEngine?) {
        val target = active ?: engine
        if (target == null) {
            state = VoiceCaptureLogic.forError(AsrErrorCode.NOT_PREPARED)
            return
        }
        val language = if (AppLanguage.current() == AppLanguage.ENGLISH) "en" else "zh"
        val started = runCatching { target.startSession(language) }
        state = if (started.isSuccess) {
            VoiceCaptureState.Listening("")
        } else {
            VoiceCaptureLogic.forError(AsrErrorCode.NOT_PREPARED)
        }
    }

    /**
     * 收面板：先结束会话放掉麦克风（取消时立刻），再滑下去，最后把结果交给调用方。
     * 注意只 end session，不 close 引擎——模型留在内存里，下一次长按直接能用。
     */
    fun closePanel(deliver: () -> Unit) {
        engine?.cancelSession()
        scope.launch {
            // 收起动画失败/被取消都不影响交付：结果已经定了。
            runCatching { sheetState.hide() }
            deliver()
        }
    }

    val deliverFinal: (String?) -> Unit = { text ->
        if (!settled) {
            settled = true
            closePanel { latestFinal(text) }
        }
    }

    val cancel: () -> Unit = {
        if (!settled) {
            settled = true
            closePanel { latestCancel() }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        awaitingPermission = false
        if (settled) return@rememberLauncherForActivityResult
        if (granted) {
            startListening(engine)
        } else {
            // 被拒不是静默失败：面板留在失败态，给「重试」和「去设置」。
            state = VoiceCaptureLogic.permissionDenied()
        }
    }

    // 引擎事件：Partial 只刷新预览，Final 才结束，Error 转可重试失败。
    // 放在 acquire 之前声明：Compose 按声明顺序启动 effect，先订阅再开会话，
    // 不会漏掉第一段 Partial。
    LaunchedEffect(engine) {
        val active = engine ?: return@LaunchedEffect
        active.events.collect { event ->
            if (settled) return@collect
            when (event) {
                AsrEvent.Ready -> Unit
                is AsrEvent.Partial -> state = VoiceCaptureLogic.withPartial(state, event.text)
                is AsrEvent.Final -> {
                    val captured = VoiceCaptureLogic.capture(state, event.text)
                    if (captured != null) {
                        state = captured
                        deliverFinal(captured.text)
                    }
                }
                is AsrEvent.Error -> state = VoiceCaptureLogic.forError(event.code)
            }
        }
    }

    // 懒加载 → 权限 → 开录音。首次可能要拷 71MB 模型，进度直接用引擎给的文案；
    // 预热过之后 acquire() 立即返回，面板基本是秒开。
    LaunchedEffect(attempt) {
        if (settled) return@LaunchedEffect
        if (state !is VoiceCaptureState.Preparing) {
            state = VoiceCaptureState.Preparing(tr("正在准备语音模型…"))
        }
        val acquired = VoiceAsrHolder.acquire { message ->
            // 进度回调不在主线程：只换文案，不碰引擎。
            scope.launch(Dispatchers.Main) {
                if (!settled && state is VoiceCaptureState.Preparing) {
                    state = VoiceCaptureState.Preparing(tr(message))
                }
            }
        }
        if (settled) return@LaunchedEffect
        val failure = acquired.exceptionOrNull()
        if (failure != null) {
            state = VoiceCaptureLogic.forError((failure as? AsrModelException)?.code)
            return@LaunchedEffect
        }
        val ready = acquired.getOrThrow()
        engine = ready
        if (hasRecordPermission(context)) {
            startListening(ready)
        } else {
            state = VoiceCaptureState.Preparing(tr("正在请求麦克风权限…"))
            awaitingPermission = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 收尾兜底：Final 一直不来也不能把面板卡在「结束中」。
    LaunchedEffect(state) {
        if (state !is VoiceCaptureState.Finishing) return@LaunchedEffect
        delay(VoiceCaptureLogic.FINISH_TIMEOUT_MS)
        if (!settled) state = VoiceCaptureLogic.finishTimedOut(state)
    }

    // 切后台 / 锁屏 / 页面销毁：麦克风必须先放掉。权限对话框引起的 onStop 不算（见 awaitingPermission）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !settled && !awaitingPermission) {
                Log.i(TAG, "页面离开前台，结束语音会话（引擎常驻）")
                settled = true
                engine?.cancelSession()
                latestCancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 面板离开组合（关闭、Activity 销毁、配置变更）只结束会话；引擎由 holder 管，
    // 不在这里 close()，否则下一次长按又要重新加载模型。
    DisposableEffect(Unit) {
        onDispose {
            engine?.cancelSession()
            Log.i(TAG, "语音面板销毁，会话已结束（引擎保持常驻）")
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            // 下滑 / 系统返回：用户主动取消，直接放麦克风回账单页。
            if (!settled) {
                settled = true
                engine?.cancelSession()
                latestCancel()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = tr("语音记账"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            when (val current = state) {
                is VoiceCaptureState.Preparing -> PreparingBody(
                    detail = current.detail,
                    onCancel = cancel,
                )

                is VoiceCaptureState.Listening -> ListeningBody(
                    partial = current.partial,
                    onStop = { stopListening() },
                    onCancel = cancel,
                )

                is VoiceCaptureState.Finishing -> FinishingBody(partial = current.partial)

                is VoiceCaptureState.Failed -> FailedBody(
                    state = current,
                    onRetry = {
                        if (!settled) {
                            state = VoiceCaptureState.Preparing(tr("正在准备语音模型…"))
                            attempt += 1
                        }
                    },
                    onOpenSettings = { openAppSettings(context) },
                    onCancel = cancel,
                )

                // 终态：面板正在收起，这里只是过渡帧（Final 文本已交给表单）。
                is VoiceCaptureState.Captured -> CapturedBody(partial = current.text.orEmpty())
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = tr("本地识别，音频不会上传"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PreparingBody(detail: String, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) { Text(tr("取消")) }
    }
}

@Composable
private fun ListeningBody(partial: String, onStop: () -> Unit, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // 大字实时预览：用户能立刻看出识别对不对，说错了可以取消重来。
                text = partial.ifBlank { tr("请说话…") },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = if (partial.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 4,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStop) { Text(tr("停止")) }
            OutlinedButton(onClick = onCancel) { Text(tr("取消")) }
        }
    }
}

@Composable
private fun FinishingBody(partial: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = partial.ifBlank { tr("正在识别最后几个字…") },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
        )
        if (partial.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = tr("正在识别最后几个字…"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CapturedBody(partial: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = partial.ifBlank { tr("没有听清，请再说一次") },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
        )
    }
}

@Composable
private fun FailedBody(
    state: VoiceCaptureState.Failed,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = tr(state.message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text(tr("重试")) }
            OutlinedButton(onClick = onCancel) { Text(tr("取消")) }
        }
        if (state.code == AsrErrorCode.PERMISSION_DENIED) {
            // 拒绝一次后再申请，系统可能不再弹框：必须给一条去系统设置的路。
            TextButton(onClick = onOpenSettings) { Text(tr("去系统设置授权")) }
        }
    }
}

private fun hasRecordPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** 打开本应用的系统详情页；打不开时退到系统设置首页，绝不因为按钮没反应而卡住用户。 */
private fun openAppSettings(context: Context) {
    val details = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val opened = runCatching { context.startActivity(details) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private const val TAG = "VoiceCaptureSheet"
