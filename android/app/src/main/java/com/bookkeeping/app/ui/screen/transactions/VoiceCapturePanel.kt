package com.bookkeeping.app.ui.screen.transactions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

/**
 * 长按「+」满 1 秒后出现的录音面板（**录音中就已经打开**，不是松手才出现）。
 *
 * 为什么不是 `ModalBottomSheet`：录音刚开始时 FAB 手势还可能需要处理「按住滑出取消」。它是 Dialog，
 * 会在新窗口拿焦点时把这段触摸标成 CANCEL，导致滑出取消收不到。所以这里画在同一个窗口里
 * （`Box` + 底部 `Surface`）；下滑关闭也自己实现（拖动手柄/标题区）。
 *
 * 状态流转全部走 [VoiceCaptureLogic]（纯 JVM 可测），这里只负责：
 * - 从进程级 [VoiceAsrHolder] 拿引擎（首次懒加载，之后常驻），面板关闭/销毁只
 *   `cancelSession()` 结束会话，**绝不 close() 引擎**；
 * - 把引擎事件翻译成面板状态：Partial 只刷输入框（用户改过就冻结），Final 只更新文本；
 * - 权限、切后台、超时这些「会捂着麦克风」的路径都收敛到 `cancelSession()` + 取消。
 *
 * 关键取舍：**录音中不填表**。用户点「停止」只把 Listening 变成 Review，用户看到识别结果、改完字，
 * 点「填入账单」才把文本交给上层解析。
 *
 * @param onCancel 用户取消时关闭面板。
 * @param onSubmit 用户确认后的文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCapturePanel(
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 回调可能在生命周期观察者 / 协程里被调用，用最新引用，避免拿到旧 composition 的 lambda。
    val latestCancel by rememberUpdatedState(onCancel)
    val latestSubmit by rememberUpdatedState(onSubmit)

    // 引擎是进程级常驻的：这里只是拿引用，不负责它的生死。
    var engine by remember { mutableStateOf(VoiceAsrHolder.current()) }

    var state by remember {
        mutableStateOf<VoiceCaptureState>(VoiceCaptureState.Preparing(tr("正在准备语音模型…")))
    }
    // 会话代号：重说后丢弃上一轮可能迟到的 Partial / Final / Error。
    var activeSessionId by remember { mutableStateOf<Long?>(null) }
    // 输入框自己的状态：文本与光标。文本的真源是 state（沿用 Logic 的冻结规则），
    // 这里只镜像一份给 TextField，保证 Partial 刷新时光标落在末尾。
    var field by remember { mutableStateOf(TextFieldValue("")) }
    // 重试只换 attempt、重跑 acquire；引擎实例由 holder 复用（已就绪时 acquire 立即返回）。
    var attempt by remember { mutableIntStateOf(0) }
    // 结果只交付一次：交付或取消之后，迟到的 Partial/Final/Error 全部丢弃。
    var settled by remember { mutableStateOf(false) }
    // 系统权限对话框会让 Activity 走 onStop；这期间不能把面板当成「切后台」关掉。
    var awaitingPermission by remember { mutableStateOf(false) }
    // 下滑关闭：拖动过程中整体上移，超过阈值就取消。
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(density) { 72.dp.toPx() }

    /** 点面板「停止」：Listening → Review（面板不关、文本保留），并让引擎补尾部静音。 */
    fun stopRecording() {
        if (state !is VoiceCaptureState.Listening) return
        state = VoiceCaptureLogic.stop(state)
        engine?.finishInput()
    }

    /** 开一次会话。引擎没准备好 / 已释放会抛 IllegalStateException，转成可重试失败。 */
    fun startListening(active: StreamingAsrEngine?) {
        activeSessionId = null
        val target = active ?: engine
        if (target == null) {
            state = VoiceCaptureLogic.forError(AsrErrorCode.NOT_PREPARED)
            return
        }
        val language = if (AppLanguage.current() == AppLanguage.ENGLISH) "en" else "zh"
        val started = runCatching { target.startSession(language) }
        val sessionId = started.getOrNull()
        activeSessionId = sessionId
        state = when {
            started.isFailure -> VoiceCaptureLogic.forError(AsrErrorCode.NOT_PREPARED)
            sessionId != null -> VoiceCaptureState.Listening("")
            else -> VoiceCaptureState.Preparing(tr("正在准备语音模型…"))
        }
    }

    /** 取消：丢弃本次，绝不填表；引擎只结束会话，模型留在内存里。 */
    fun cancel() {
        if (settled) return
        settled = true
        state = VoiceCaptureLogic.cancel()
        activeSessionId = null
        engine?.cancelSession()
        latestCancel()
    }

    /**
     * 重说：立即清空上一轮输入。常驻引擎就绪时同步开启新会话，不经过 Preparing；
     * 只有引擎不可用或权限缺失时才重新 acquire / 请求权限。
     */
    fun restart() {
        val freshState = VoiceCaptureLogic.restart(state) ?: return
        val active = engine
        val canStartImmediately = active != null &&
            active === VoiceAsrHolder.current() &&
            VoiceAsrHolder.isReady() &&
            hasRecordPermission(context)

        field = TextFieldValue("")
        activeSessionId = null
        state = freshState
        active?.cancelSession()
        if (canStartImmediately) {
            startListening(active)
        } else {
            state = VoiceCaptureState.Preparing(tr("正在准备语音模型…"))
            attempt += 1
        }
    }

    /** 唯一交付路径：把用户确认过的文本交给上层去解析 + 开表单。 */
    fun submit() {
        if (settled) return
        val delivered = VoiceCaptureLogic.submit(state) ?: return
        settled = true
        state = delivered
        // 可能还在收尾：结束会话，避免引擎继续占用麦克风。
        activeSessionId = null
        engine?.cancelSession()
        latestSubmit(delivered.request.text.orEmpty())
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

    // 引擎事件：Partial 刷输入框（冻结时不刷），Final 只更新文本，Error 转失败/保留文本。
    // 放在 acquire 之前声明：Compose 按声明顺序启动 effect，先订阅再开会话，
    // 不会漏掉第一段 Partial。
    LaunchedEffect(engine) {
        val active = engine ?: return@LaunchedEffect
        active.events.collect { event ->
            if (settled) return@collect
            when (event) {
                AsrEvent.Ready -> Unit
                is AsrEvent.Partial -> if (
                    VoiceCaptureLogic.isCurrentSession(event.sessionId, activeSessionId)
                ) {
                    state = VoiceCaptureLogic.withPartial(state, event.text)
                }
                is AsrEvent.Final -> if (
                    VoiceCaptureLogic.isCurrentSession(event.sessionId, activeSessionId)
                ) {
                    state = VoiceCaptureLogic.onFinal(state, event.text)
                }
                is AsrEvent.Error -> {
                    if (!VoiceCaptureLogic.isCurrentSession(event.sessionId, activeSessionId)) {
                        return@collect
                    }
                    state = VoiceCaptureLogic.onError(state, event.code)
                    activeSessionId = null
                    // 采集已经出错：顺手把麦克风放掉，别让失败的会话一直占着。
                    engine?.cancelSession()
                }
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

    // 收尾兜底：Final 一直不来也不能把面板卡在「正在识别」。
    LaunchedEffect(state) {
        val current = state
        if (current !is VoiceCaptureState.Review || !current.waitingFinal) return@LaunchedEffect
        delay(VoiceCaptureLogic.FINISH_TIMEOUT_MS)
        if (!settled) state = VoiceCaptureLogic.finishTimedOut(state)
    }

    // 输入框跟随 state 的文本：用户没编辑时每个 Partial 都会替换它，光标放到末尾。
    val panelText = when (val current = state) {
        is VoiceCaptureState.Listening -> current.text
        is VoiceCaptureState.Review -> current.text
        else -> null
    }
    LaunchedEffect(panelText) {
        if (panelText != null && panelText != field.text) {
            field = TextFieldValue(panelText, selection = TextRange(panelText.length))
        }
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

    BackHandler { cancel() }

    EntryPanelLayout(
        title = tr("语音记账"),
        onDismiss = { cancel() },
        headerTrailing = { RecordingBadge(state = state) },
    ) {
        when (val current = state) {
            is VoiceCaptureState.Preparing -> PreparingBody(
                detail = current.detail,
                onCancel = { cancel() },
            )

            is VoiceCaptureState.Listening -> EditorBody(
                state = current,
                field = field,
                onFieldChange = { value ->
                    field = value
                    state = VoiceCaptureLogic.edit(state, value.text)
                },
                onResumeAutoFollow = { state = VoiceCaptureLogic.resumeAutoFollow(state) },
                onAcceptReplacement = { state = VoiceCaptureLogic.acceptReplacement(state) },
                primaryLabel = tr("停止"),
                onPrimary = { stopRecording() },
                onCancel = { cancel() },
            )

            is VoiceCaptureState.Review -> EditorBody(
                state = current,
                field = field,
                onFieldChange = { value ->
                    field = value
                    state = VoiceCaptureLogic.edit(state, value.text)
                },
                onResumeAutoFollow = { state = VoiceCaptureLogic.resumeAutoFollow(state) },
                onAcceptReplacement = { state = VoiceCaptureLogic.acceptReplacement(state) },
                primaryLabel = if (VoiceCaptureLogic.isSubmittable(current.text)) {
                    tr("填入账单")
                } else {
                    tr("重说")
                },
                onPrimary = {
                    if (VoiceCaptureLogic.isSubmittable(current.text)) submit() else restart()
                },
                onRetry = { restart() },
                onCancel = { cancel() },
            )

            is VoiceCaptureState.Failed -> FailedBody(
                state = current,
                onRetry = { if (!settled) restart() },
                onOpenSettings = { openAppSettings(context) },
                onCancel = { cancel() },
            )

            // 终态：面板正在收起，这里只是过渡帧。
            is VoiceCaptureState.Delivered -> Unit
            VoiceCaptureState.Cancelled -> Unit
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = tr("本地识别，音频不会上传"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
