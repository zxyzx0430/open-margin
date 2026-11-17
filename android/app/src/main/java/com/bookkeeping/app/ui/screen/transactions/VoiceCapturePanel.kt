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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 为什么不是 `ModalBottomSheet`：它是 Dialog，会在手指还按着的时候抢走 Activity 窗口的
 * 焦点，正在进行的按压手势会被系统 CANCEL —— 松手事件就永远收不到，「松手结束录音」直接失效。
 * 所以这里画在同一个窗口里（`Box` + 底部 `Surface`），FAB 的 pointerInput 才能把 UP 交上来；
 * 下滑关闭也自己实现（拖动手柄/标题区）。
 *
 * 状态流转全部走 [VoiceCaptureLogic]（纯 JVM 可测），这里只负责：
 * - 从进程级 [VoiceAsrHolder] 拿引擎（首次懒加载，之后常驻），面板关闭/销毁只
 *   `cancelSession()` 结束会话，**绝不 close() 引擎**；
 * - 把引擎事件翻译成面板状态：Partial 只刷输入框（用户改过就冻结），Final 只更新文本；
 * - 权限、切后台、超时这些「会捂着麦克风」的路径都收敛到 `cancelSession()` + 取消。
 *
 * 关键取舍：**松手不填表**。松手只把 Listening 变成 Review，用户看到识别结果、改完字，
 * 点「填入账单」才把文本交给上层解析。
 *
 * @param stopRequested 上层监听到 FAB 松手时置 true；面板收到后立刻 `finishInput()`。
 * @param onStopRequestConsumed 消费掉上面的标志，避免面板重开时误判「已经松手」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCapturePanel(
    stopRequested: Boolean,
    onStopRequestConsumed: () -> Unit,
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
    // 输入框自己的状态：文本与光标。文本的真源是 state（沿用 Logic 的冻结规则），
    // 这里只镜像一份给 TextField，保证 Partial 刷新时光标落在末尾。
    var field by remember { mutableStateOf(TextFieldValue("")) }
    // 重试只换 attempt、重跑 acquire；引擎实例由 holder 复用（已就绪时 acquire 立即返回）。
    var attempt by remember { mutableIntStateOf(0) }
    // 结果只交付一次：交付或取消之后，迟到的 Partial/Final/Error 全部丢弃。
    var settled by remember { mutableStateOf(false) }
    // 系统权限对话框会让 Activity 走 onStop；这期间不能把面板当成「切后台」关掉。
    var awaitingPermission by remember { mutableStateOf(false) }
    // 松手可能比会话先到（面板刚弹、模型还在准备）：先记下，进 Listening 后立刻收尾。
    var releaseRequested by remember { mutableStateOf(false) }
    // 下滑关闭：拖动过程中整体上移，超过阈值就取消。
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(density) { 72.dp.toPx() }

    /** 松手 = 结束录音：Listening → Review（面板不关、文本保留），并让引擎补尾部静音。 */
    fun finishRecording() {
        releaseRequested = true
        if (state is VoiceCaptureState.Listening) {
            state = VoiceCaptureLogic.release(state)
            engine?.finishInput()
        }
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
        // 手指在面板出现前就松了：会话一开就收尾，别让用户对着一个「永远在听」的面板。
        if (VoiceCaptureLogic.shouldFinishImmediately(state, releaseRequested)) finishRecording()
    }

    /** 取消：丢弃本次，绝不填表；引擎只结束会话，模型留在内存里。 */
    fun cancel() {
        if (settled) return
        settled = true
        state = VoiceCaptureLogic.cancel()
        engine?.cancelSession()
        latestCancel()
    }

    /** 重说 / 重试：结束当前会话回到 Preparing，attempt+1 让 LaunchedEffect 重跑一次。 */
    fun restart() {
        engine?.cancelSession()
        releaseRequested = false
        state = VoiceCaptureState.Preparing(tr("正在准备语音模型…"))
        attempt += 1
    }

    /** 唯一交付路径：把用户确认过的文本交给上层去解析 + 开表单。 */
    fun submit() {
        if (settled) return
        val delivered = VoiceCaptureLogic.submit(state) ?: return
        settled = true
        state = delivered
        // 可能还在收尾：结束会话，避免引擎继续占用麦克风。
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
                is AsrEvent.Partial -> state = VoiceCaptureLogic.withPartial(state, event.text)
                is AsrEvent.Final -> state = VoiceCaptureLogic.onFinal(state, event.text)
                is AsrEvent.Error -> {
                    state = VoiceCaptureLogic.onError(state, event.code)
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

    // 松手信号：上层在 FAB 松手时置 true。先消费标志，再结束录音，
    // 这样面板重开时不会因为残留的 true 立刻收尾。
    LaunchedEffect(stopRequested) {
        if (!stopRequested) return@LaunchedEffect
        onStopRequestConsumed()
        finishRecording()
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

    // 入场：淡入 + 轻微上滑。录音中面板是「跟着手指出现」的，不能有等待感。
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val fade by animateFloatAsState(if (visible) 1f else 0f, tween(160), label = "voicePanelFade")
    val rise by animateDpAsState(if (visible) 0.dp else 56.dp, tween(180), label = "voicePanelRise")

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：点一下 = 取消（丢弃本次，不填表）。
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(fade)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = interaction, indication = null) { cancel() },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // safeDrawing 的底部 = max(导航栏, 输入法)：键盘弹出时整块面板上推，
                // 输入框和主按钮都不会被输入法盖住。
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .offset { IntOffset(0, dragOffset.roundToInt() + rise.roundToPx()) },
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 下滑关闭的手柄区：标题 + 拖柄都算，拖动超过阈值就取消。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = rememberDraggableState { delta ->
                                    dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                                },
                                orientation = Orientation.Vertical,
                                onDragStopped = {
                                    if (dragOffset > dismissThresholdPx) {
                                        cancel()
                                    } else {
                                        dragOffset = 0f
                                    }
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 10.dp, bottom = 6.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tr("语音记账"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(10.dp))
                            RecordingBadge(state = state)
                        }
                    }
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
                            onPrimary = { finishRecording() },
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
        }
    }
}

@Composable
private fun RecordingBadge(state: VoiceCaptureState) {
    val (label, color) = when (state) {
        is VoiceCaptureState.Listening -> tr("录音中…") to MaterialTheme.colorScheme.error
        is VoiceCaptureState.Review -> tr("已停止录音") to MaterialTheme.colorScheme.onSurfaceVariant
        else -> return
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(4.dp)),
    )
    Spacer(Modifier.width(6.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

/**
 * 可编辑的文本区 + 冻结提示 + 按钮。Listening 与 Review 共用：
 * 同一个输入框贯穿「说话中」和「松手后」，用户不用在两个界面之间搬文本。
 */
@Composable
private fun EditorBody(
    state: VoiceCaptureState,
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    onResumeAutoFollow: () -> Unit,
    onAcceptReplacement: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onCancel: () -> Unit,
) {
    val listening = state is VoiceCaptureState.Listening
    val text = when (state) {
        is VoiceCaptureState.Listening -> state.text
        is VoiceCaptureState.Review -> state.text
        else -> ""
    }
    val autoFollow = when (state) {
        is VoiceCaptureState.Listening -> state.autoFollow
        is VoiceCaptureState.Review -> state.autoFollow
        else -> true
    }
    val waitingFinal = (state as? VoiceCaptureState.Review)?.waitingFinal == true
    val suggestion = when (state) {
        is VoiceCaptureState.Listening -> state.suggestedReplacement
        is VoiceCaptureState.Review -> state.suggestedReplacement
        else -> null
    }
    val submittable = VoiceCaptureLogic.isSubmittable(text)

    OutlinedTextField(
        value = field,
        onValueChange = onFieldChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 116.dp),
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        placeholder = {
            Text(
                text = if (listening) tr("请说话…") else tr("没有听清，请再说一次"),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        minLines = 2,
        maxLines = 5,
        shape = RoundedCornerShape(16.dp),
    )

    Spacer(Modifier.height(8.dp))
    // 一行状态：跟随中 / 已冻结（可恢复）/ 正在识别 / 没听清。
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (listening) {
            if (autoFollow) {
                Text(
                    text = tr("松手结束，说话时可以直接改"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = tr("已暂停自动更新"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onResumeAutoFollow, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(tr("恢复"))
                }
            }
        }
        if (waitingFinal) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = tr("正在识别最后几个字…"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!listening && !submittable) {
            Text(
                text = tr("没有听清，请再说一次"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (suggestion != null) {
        AssistChip(
            onClick = onAcceptReplacement,
            label = { Text(tr("识别更新了，点此替换")) },
            leadingIcon = {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )
        Spacer(Modifier.height(4.dp))
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onCancel) { Text(tr("取消")) }
        if (!listening && onRetry != null && submittable) {
            OutlinedButton(onClick = onRetry) { Text(tr("重说")) }
        }
        Button(onClick = onPrimary, modifier = Modifier.weight(1f)) { Text(primaryLabel) }
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

private const val TAG = "VoiceCapturePanel"
