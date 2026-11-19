package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.voice.asr.AsrErrorCode

/**
 * 录音面板的界面状态。
 *
 * 这里刻意不放任何 Compose / Android 类型：面板的状态迁移、冻结规则和「哪段文本能进表单」
 * 全部由 [VoiceCaptureLogic] 的纯函数决定，`VoiceCaptureLogicTest` 在纯 JVM 上就能
 * 覆盖——CI 里没有真麦克风，唯一能自动验证的就是这套判定。
 *
 * 交互主线（长按即录，手动停止，面板不关）：
 * `Preparing → Listening（可编辑）→ Review（可编辑，等 Final）→ Delivered / Cancelled`。
 * [Listening] 与 [Review] 是同一块输入框的两个阶段，文本都在 [Listening.text] /
 * [Review.text] 里，区别只是麦克风还开着没有。抬手不改变阶段。
 */
sealed interface VoiceCaptureState {

    /** 装模型 / 加载识别器 / 等权限；[detail] 是可直接展示的进度或等待文案。 */
    data class Preparing(val detail: String) : VoiceCaptureState

    /**
     * 正在听（手指抬起后仍继续录音，直到用户点「停止」）。
     *
     * @param text 输入框里的文本：用户没动键盘时跟随 Partial，动过之后就是用户自己写的字。
     * @param autoFollow false = 用户编辑过，后续 Partial 不再覆盖（「已暂停自动更新」）。
     * @param suggestedReplacement 冻结期间到达的 Final 与当前文本不同时的候选，UI 显示成
     *   「识别更新了，点此替换」的 chip；用户点了才替换。
     */
    data class Listening(
        val text: String,
        val autoFollow: Boolean = true,
        val suggestedReplacement: String? = null,
    ) : VoiceCaptureState

    /**
     * 用户点「停止」后：麦克风已停，面板留着同一个输入框让用户改完再交。
     *
     * @param waitingFinal 引擎还在补尾部静音 / 解码最后一帧；此时只显示一个小的「正在识别」，
     *   输入框和「填入账单」都可以照常操作——不把用户扣在转圈上。
     */
    data class Review(
        val text: String,
        val autoFollow: Boolean = true,
        val waitingFinal: Boolean = false,
        val suggestedReplacement: String? = null,
    ) : VoiceCaptureState

    /** 失败但可以重试。[code] 为 [AsrErrorCode.PERMISSION_DENIED] 时面板额外给「去设置」。 */
    data class Failed(val code: AsrErrorCode, val message: String) : VoiceCaptureState

    /** 用户点了「填入账单」：文本已经交给上层解析，面板只差收起。 */
    data class Delivered(val request: VoiceEntryRequest) : VoiceCaptureState

    /** 用户取消（按钮 / 下滑 / 返回键 / 切后台）：本次录音丢弃，绝不填表。 */
    data object Cancelled : VoiceCaptureState
}

/**
 * 面板交给「新建账单」表单的一次请求。
 *
 * [text] 一律非空（[VoiceCaptureLogic.submit] 已经把它 trim 过并挡掉纯标点），
 * 保留可空是为了兼容上层既有的「不预填」分支。
 */
data class VoiceEntryRequest(
    val text: String?,
    val diagnosticAttemptId: String? = null,
)

/**
 * 录音面板的纯逻辑：状态迁移 + 文本冻结/替换规则 + 交付判定。
 *
 * 五条硬约定，都有单测钉着：
 * 1. Partial 只在「还在听且用户没改过」时刷新文本；一旦编辑就冻结，绝不覆盖用户写的字；
 * 2. 冻结期间到达的 Final 不静默替换，只作为 [VoiceCaptureState.Listening.suggestedReplacement]
 *    交给用户点；
 * 3. Listening 只有在用户点「停止」后才变成 Review；停止本身从不交付，交付只发生在用户明确点「填入账单」；
 * 4. 空白 / 纯标点的文本不能提交，UI 把主按钮换成「重说」；
 * 5. 取消是终态：之后的 Partial / Final / Error 一律丢弃。
 */
object VoiceCaptureLogic {

    /** 收尾超时：Final 一直不来也不能把面板卡在「正在识别」。 */
    const val FINISH_TIMEOUT_MS = 5_000L

    /** 文本 → 可交付内容：去首尾空白，空白视为「没听清」返回 null。 */
    fun entryText(text: String?): String? =
        text?.trim()?.takeIf { it.isNotEmpty() }

    /** 能不能交给解析器：至少得有一个字母或数字（中文算字母），纯标点/空白不算。 */
    fun isSubmittable(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.any { it.isLetterOrDigit() }
    }

    /** Partial 只刷新「还在听且没被编辑过」的文本；其余状态收到旧 Partial 一律丢弃。 */
    fun withPartial(state: VoiceCaptureState, partial: String): VoiceCaptureState {
        if (state !is VoiceCaptureState.Listening || !state.autoFollow) return state
        val next = partial.trim()
        if (next == state.text) return state
        return state.copy(text = next)
    }

    /** 用户在输入框里打字：从此冻结自动刷新，后续 Partial 不再动它。 */
    fun edit(state: VoiceCaptureState, text: String): VoiceCaptureState =
        when (state) {
            is VoiceCaptureState.Listening -> state.copy(text = text, autoFollow = false)
            is VoiceCaptureState.Review -> state.copy(text = text, autoFollow = false)
            else -> state
        }

    /**
     * 用户点「停止」：停止跟随识别结果，但**不交付**。
     *
     * 面板留在 Review，等用户确认或改字。重复调用不会重复迁移。
     */
    fun stop(state: VoiceCaptureState): VoiceCaptureState =
        if (state is VoiceCaptureState.Listening) {
            VoiceCaptureState.Review(
                text = state.text,
                autoFollow = state.autoFollow,
                waitingFinal = true,
                suggestedReplacement = state.suggestedReplacement,
            )
        } else {
            state
        }

    /**
     * 开始一轮干净的重录。只接受 Review / Failed；返回 null 表示已经在新周期或准备中，
     * 可用于阻止重复点击堆叠 startSession()。
     */
    fun restart(state: VoiceCaptureState): VoiceCaptureState.Listening? = when (state) {
        is VoiceCaptureState.Review, is VoiceCaptureState.Failed -> VoiceCaptureState.Listening("")
        else -> null
    }

    /** 只接纳当前会话的事件，避免上一轮迟到的 Partial / Final / Error 污染重录。 */
    fun isCurrentSession(eventSessionId: Long?, currentSessionId: Long?): Boolean =
        eventSessionId == currentSessionId

    /**
     * Final 到达。
     *
     * - 没被编辑过（autoFollow）：直接采用 Final；Final 为空（真的没说话）就保留当前文本。
     * - 被编辑过：不覆盖。只有 Final 与用户写的字不同时，才挂一条「识别更新了，点此替换」。
     * - 已经在 Review 且不在等 Final（收过/超时过）：迟到的 Final 不改字。
     */
    fun onFinal(state: VoiceCaptureState, finalText: String?): VoiceCaptureState {
        val text: String
        val autoFollow: Boolean
        when (state) {
            is VoiceCaptureState.Listening -> {
                text = state.text
                autoFollow = state.autoFollow
            }
            is VoiceCaptureState.Review -> {
                if (!state.waitingFinal) return state
                text = state.text
                autoFollow = state.autoFollow
            }
            else -> return state
        }
        val recognized = entryText(finalText)
        val resolved = if (autoFollow && recognized != null) recognized else text
        // 用户改过字：识别结果只当候选，且必须和用户写的不同才提示，避免无意义 chip。
        val suggested = if (!autoFollow && recognized != null && recognized != text.trim()) {
            recognized
        } else {
            null
        }
        return VoiceCaptureState.Review(
            text = resolved,
            autoFollow = autoFollow,
            waitingFinal = false,
            suggestedReplacement = suggested,
        )
    }

    /** 点「恢复」：回到跟随识别；如果手上还有待替换的 Final，就一并采用它。 */
    fun resumeAutoFollow(state: VoiceCaptureState): VoiceCaptureState = when (state) {
        is VoiceCaptureState.Listening -> state.copy(
            text = state.suggestedReplacement ?: state.text,
            autoFollow = true,
            suggestedReplacement = null,
        )
        is VoiceCaptureState.Review -> state.copy(
            text = state.suggestedReplacement ?: state.text,
            autoFollow = true,
            suggestedReplacement = null,
        )
        else -> state
    }

    /** 点「识别更新了，点此替换」：换成识别文本，之后不再让识别结果自动覆盖。 */
    fun acceptReplacement(state: VoiceCaptureState): VoiceCaptureState {
        val replacement = when (state) {
            is VoiceCaptureState.Listening -> state.suggestedReplacement
            is VoiceCaptureState.Review -> state.suggestedReplacement
            else -> null
        } ?: return state
        return when (state) {
            is VoiceCaptureState.Listening ->
                state.copy(text = replacement, suggestedReplacement = null)
            is VoiceCaptureState.Review ->
                state.copy(text = replacement, suggestedReplacement = null)
            else -> state
        }
    }

    /**
     * 唯一允许的交付：只有 Review 且文本有意义时才返回 [VoiceCaptureState.Delivered]。
     *
     * 录音中、准备中、失败态、取消态一律返回 null——「录音中直接填表」和「空文本填表」都被这里挡住。
     */
    fun submit(state: VoiceCaptureState): VoiceCaptureState.Delivered? {
        if (state !is VoiceCaptureState.Review) return null
        if (!isSubmittable(state.text)) return null
        val text = entryText(state.text) ?: return null
        return VoiceCaptureState.Delivered(VoiceEntryRequest(text))
    }

    /** 取消 / 下滑 / 返回 / 切后台统一走这里：终态，之后的事件全部丢弃。 */
    fun cancel(): VoiceCaptureState = VoiceCaptureState.Cancelled

    /**
     * 收尾超时：不再等 Final，但保留已经有的文本让用户自己决定。
     *
     * 以前这里直接转失败态，用户明明看到了一行字却被判「失败」；现在只把转圈停掉，
     * 空文本时按钮自然变成「重说」。
     */
    fun finishTimedOut(state: VoiceCaptureState): VoiceCaptureState =
        if (state is VoiceCaptureState.Review && state.waitingFinal) {
            state.copy(waitingFinal = false)
        } else {
            state
        }

    /**
     * 引擎报错。
     *
     * 已经在 Review 且有输入框时保留文本（引擎挂了不该连用户改好的字一起没收），
     * 只把「正在识别」停掉；其余阶段才转可重试的失败态。
     */
    fun onError(state: VoiceCaptureState, code: AsrErrorCode?): VoiceCaptureState =
        if (state is VoiceCaptureState.Review) {
            state.copy(waitingFinal = false)
        } else {
            forError(code)
        }

    /** 权限被拒：必须给「去设置」，不能静默失败。 */
    fun permissionDenied(): VoiceCaptureState.Failed =
        forError(AsrErrorCode.PERMISSION_DENIED)

    /** 错误码 → 可重试的失败态。[code] 为 null（异常里拿不到码）按 INTERNAL 处理。 */
    fun forError(code: AsrErrorCode?): VoiceCaptureState.Failed {
        val resolved = code ?: AsrErrorCode.INTERNAL
        return VoiceCaptureState.Failed(resolved, messageFor(resolved))
    }

    /** 错误码 → 用户能读的中文提示（同时是 tr() 的 key，英文界面靠它查表）。 */
    fun messageFor(code: AsrErrorCode): String = when (code) {
        AsrErrorCode.PERMISSION_DENIED -> "没有麦克风权限，无法语音记账"
        AsrErrorCode.MODEL_MISSING -> "安装包缺少语音模型，请重新安装应用"
        AsrErrorCode.MODEL_CORRUPTED -> "语音模型损坏，请重装应用后再试"
        AsrErrorCode.MODEL_UNSUPPORTED_ABI -> "此设备的 CPU 架构不支持本地语音识别"
        AsrErrorCode.RECOGNIZER_INIT_FAILED -> "语音引擎初始化失败，请重试"
        AsrErrorCode.AUDIO_INIT_FAILED -> "麦克风被占用或打不开，请稍后重试"
        AsrErrorCode.NOT_PREPARED -> "语音引擎还没准备好，请重试"
        AsrErrorCode.ENGINE_CLOSED -> "语音会话已经结束，请重新长按"
        AsrErrorCode.INTERNAL -> "没有听清，请再说一次"
    }
}
