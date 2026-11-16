package com.bookkeeping.app.voice.asr

/**
 * 语音识别错误码。
 *
 * 为什么用码而不是直接给 UI 中文字符串：中英文界面共用同一套引擎，错误提示必须能
 * 按当前语言本地化；同时“模型损坏”这类错误要能被设置页/重装流程识别并给出不同动作。
 */
enum class AsrErrorCode {
    /** 未授予 RECORD_AUDIO。 */
    PERMISSION_DENIED,

    /** 安装包里根本没有模型 assets（构建时没跑 download-asr-model.sh）。 */
    MODEL_MISSING,

    /** 模型文件缺失、字节数不符或 sha256 不符。 */
    MODEL_CORRUPTED,

    /** 设备 ABI 没有对应的 sherpa-onnx 原生库。 */
    MODEL_UNSUPPORTED_ABI,

    /** 识别器（encoder/decoder/joiner/tokens）创建失败。 */
    RECOGNIZER_INIT_FAILED,

    /** AudioRecord 初始化或读取失败。 */
    AUDIO_INIT_FAILED,

    /** 没有先 prepare() 就开会话。 */
    NOT_PREPARED,

    /** 引擎已 close()。 */
    ENGINE_CLOSED,

    /** 其他内部异常。 */
    INTERNAL,
}

/**
 * 流式识别事件。
 *
 * [Partial] 与 [Final] 在接口层就分开：Partial 只用于实时预览（会被后续结果覆盖），
 * 只有 Final 才允许进入账单解析，避免半截句子被记成一笔账。
 */
sealed interface AsrEvent {
    /** 模型已加载，可以 startSession()。 */
    data object Ready : AsrEvent

    /** 临时结果，随时可能被更长的临时结果替换。 */
    data class Partial(val text: String) : AsrEvent

    /** 一句话的最终结果；每次 finishInput() 只会发一次（可能是空串）。 */
    data class Final(val text: String) : AsrEvent

    /** 失败。message 是给开发者看的中文提示，UI 应按 code 本地化。 */
    data class Error(
        val code: AsrErrorCode,
        val message: String,
        val cause: Throwable? = null,
    ) : AsrEvent
}
