package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.voice.asr.AsrErrorCode

/**
 * 「按住说话」录音面板的界面状态。
 *
 * 这里刻意不放任何 Compose / Android 类型：面板的状态迁移和「哪段文本能进表单」
 * 全部由 [VoiceCaptureLogic] 的纯函数决定，`VoiceCaptureLogicTest` 在纯 JVM 上就能
 * 覆盖——CI 里没有真麦克风，唯一能自动验证的就是这套判定。
 */
sealed interface VoiceCaptureState {

    /** 装模型 / 加载识别器 / 等权限；[detail] 是可直接展示的进度或等待文案。 */
    data class Preparing(val detail: String) : VoiceCaptureState

    /** 正在听；[partial] 只用于大字预览，随时会被下一版覆盖，绝不能当结果。 */
    data class Listening(val partial: String) : VoiceCaptureState

    /** 已经点了停止，引擎在补尾部静音、等 Final；[partial] 是最后一版预览。 */
    data class Finishing(val partial: String) : VoiceCaptureState

    /** 失败但可以重试。[code] 为 [AsrErrorCode.PERMISSION_DENIED] 时面板额外给「去设置」。 */
    data class Failed(val code: AsrErrorCode, val message: String) : VoiceCaptureState

    /** 已拿到 Final，面板必须关闭；[text] == null 表示「没听清」，表单照开、预填为空。 */
    data class Captured(val text: String?) : VoiceCaptureState
}

/**
 * 面板交给「新建账单」表单的一次请求。
 *
 * [text] 为 null = 说了但没识别出字：仍然打开表单（用户可以直接手填），
 * 只是不把空串写进备注。
 */
data class VoiceEntryRequest(val text: String?)

/**
 * 录音面板的纯逻辑：状态迁移 + Final 文本选取。
 *
 * 三条硬约定，都有单测钉着：
 * 1. Partial 只刷新预览，永远不触发关闭面板；
 * 2. Final 只有在聆听/收尾阶段才算结果，失败或已交付之后的迟到事件一律丢弃；
 * 3. 空白 Final 返回 null（不是空串），调用方据此打开一个空表单而不是「备注为空串」的表单。
 */
object VoiceCaptureLogic {

    /** 收尾超时：Final 一直不来也要让用户能重试，不能把面板卡在「结束中」。 */
    const val FINISH_TIMEOUT_MS = 5_000L

    /** Final → 表单预填文本：去首尾空白，空白视为「没听清」返回 null。 */
    fun entryText(finalText: String?): String? =
        finalText?.trim()?.takeIf { it.isNotEmpty() }

    /** Partial 只能刷新预览；不在聆听态（收尾/失败/已交付）的旧 Partial 一律丢弃。 */
    fun withPartial(state: VoiceCaptureState, partial: String): VoiceCaptureState =
        if (state is VoiceCaptureState.Listening) {
            VoiceCaptureState.Listening(partial.trim())
        } else {
            state
        }

    /** 只有「正在聆听」能停；收尾中重复点停止不会把最后一版预览弄丢。 */
    fun stop(state: VoiceCaptureState): VoiceCaptureState =
        if (state is VoiceCaptureState.Listening) {
            VoiceCaptureState.Finishing(state.partial)
        } else {
            state
        }

    /**
     * Final 落在聆听/收尾阶段才算结果。
     *
     * 空 Final 也返回 `Captured(null)`：那是「说完了但没听清」，面板仍然把用户送回
     * 新建表单，只是不预填内容。其它状态返回 null，表示这条 Final 属于别的会话（或者
     * 面板已经在失败态），不能拿它去打开表单。
     */
    fun capture(state: VoiceCaptureState, finalText: String?): VoiceCaptureState.Captured? =
        when (state) {
            is VoiceCaptureState.Listening, is VoiceCaptureState.Finishing ->
                VoiceCaptureState.Captured(entryText(finalText))
            else -> null
        }

    /** 收尾超时 → 可重试失败态。 */
    fun finishTimedOut(state: VoiceCaptureState): VoiceCaptureState =
        if (state is VoiceCaptureState.Finishing) forError(AsrErrorCode.INTERNAL) else state

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
