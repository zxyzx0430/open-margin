package com.bookkeeping.app.voice.asr

import kotlinx.coroutines.flow.Flow

/**
 * 本地流式语音识别引擎。
 *
 * 实现约定（SherpaStreamingAsrEngine 遵守，UI 可以依赖）：
 * 1. [prepare] 负责把 assets 里的模型拷到 filesDir 并加载识别器；可重复调用。
 * 2. [startSession] 之前必须 [prepare] 成功，否则抛 [IllegalStateException]。
 * 3. [acceptAudio] 只接受 16kHz 单声道 PCM16；内部排队，不阻塞调用线程（通常是采集线程）。
 * 4. [finishInput] 之后引擎把尾部静音送进解码器，然后发且只发一个 [AsrEvent.Final]。
 * 5. [close] 释放识别器与 AudioRecord，可重复调用。
 *
 * 线程：所有方法都可以从主线程调用；重活（解码、写文件）在内部协程里做。
 */
interface StreamingAsrEngine {
    /**
     * 准备模型与识别器。
     *
     * @param onProgress 进度文案（中文，可直接展示；不是 i18n key）。
     */
    suspend fun prepare(onProgress: (String) -> Unit = {}): Result<Unit>

    /** 开一次识别会话。language 为 "zh" / "en"（当前模型中英双语，仅作提示）。 */
    fun startSession(language: String)

    /** 送入一段 16kHz 单声道 PCM16。 */
    fun acceptAudio(pcm16: ShortArray, sampleRate: Int)

    /** 停止送音频，冲刷尾部并产生 Final。 */
    fun finishInput()

    /** 释放资源；幂等。 */
    fun close()

    /** Ready / Partial / Final / Error。 */
    val events: Flow<AsrEvent>
}
