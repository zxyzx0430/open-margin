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
 * 5. 成功的 [startSession] 返回递增会话代号；会话相关的 Partial / Final / Error 携带该代号，
 *    调用方可以丢弃上一轮结束后迟到的事件。
 * 6. [close] 释放识别器与 AudioRecord，可重复调用。
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

    /**
     * 用一小段静音跑一遍解码，把 onnxruntime 的图/内存池热起来，让第一次真实出字更快。
     *
     * 约定：只在 READY 时生效、幂等、**不产生任何 [AsrEvent]**；失败只记日志，
     * 不影响引擎可用性。没有这个实现的引擎可以不覆写（默认空实现）。
     */
    suspend fun warmUp() {}

    /** 开一次识别会话。language 为 "zh" / "en"（当前模型中英双语，仅作提示）；返回会话代号。启动被拒并已发 Error 时返回 null。 */
    fun startSession(language: String): Long?

    /** 送入一段 16kHz 单声道 PCM16。 */
    fun acceptAudio(pcm16: ShortArray, sampleRate: Int)

    /** 停止送音频，冲刷尾部并产生 Final。 */
    fun finishInput()

    /**
     * 中止当前会话：立刻放掉麦克风和半句话，识别器保持 READY。
     *
     * 面板关闭 / 切后台走这里，**不要** close()——引擎是进程级常驻的。
     */
    fun cancelSession() {}

    /** 释放资源；幂等。释放后实例不可复用，需要重新创建。 */
    fun close()

    /** Ready / Partial / Final / Error。 */
    val events: Flow<AsrEvent>
}
