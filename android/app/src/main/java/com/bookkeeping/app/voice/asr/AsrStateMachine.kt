package com.bookkeeping.app.voice.asr

enum class AsrState {
    /** 还没 prepare。 */
    NEW,

    /** 正在装模型 / 加载识别器。 */
    PREPARING,

    /** 识别器就绪，可以开会话。 */
    READY,

    /** 会话进行中。 */
    RECORDING,

    /** 已 close，任何操作都不再允许。 */
    CLOSED,
}

/**
 * 引擎状态机。
 *
 * 单独抽出来是为了让「没 prepare 就 startSession 必须报错」「close 幂等」这两条
 * 硬约定能在纯 JVM 单测里验证——真机/模拟器在 CI 里跑不了，但状态迁移必须可测。
 *
 * 会话带**代号**：每次 beginSession() 递增并返回本次代号，endSession() 必须带回同一个。
 * 原因：用户在录音中再点一次「开始」时，旧会话的解码协程会被取消，它的 finally 可能在
 * 新会话已经 RECORDING 之后才跑到；没有代号校验的话它会把状态打回 READY，接着
 * finishInput() 就会因为“不在录音”直接返回，用户松手后一个字都拿不到。
 *
 * 所有方法都加锁：引擎的 close() 可能来自主线程，prepare()/解码收尾来自协程。
 */
class AsrStateMachine {

    @Volatile
    var state: AsrState = AsrState.NEW
        private set

    private var sessionGeneration = 0L

    @Synchronized
    fun beginPrepare() {
        check(state != AsrState.CLOSED) { "语音引擎已关闭" }
        state = AsrState.PREPARING
    }

    @Synchronized
    fun finishPrepare(success: Boolean) {
        if (state != AsrState.PREPARING) return
        state = if (success) AsrState.READY else AsrState.NEW
    }

    /** startSession() 前的检查：必须已经 prepare 成功。 */
    @Synchronized
    fun checkCanStartSession() {
        check(state != AsrState.CLOSED) { "语音引擎已关闭" }
        check(state == AsrState.READY || state == AsrState.RECORDING) {
            "语音引擎未就绪：请先 prepare()"
        }
    }

    /** 开一次会话，返回本次会话代号；必须原样传给 [endSession]。 */
    @Synchronized
    fun beginSession(): Long {
        checkCanStartSession()
        state = AsrState.RECORDING
        sessionGeneration += 1
        return sessionGeneration
    }

    /**
     * 结束会话。只有「当前代号」且仍在 RECORDING 时才回到 READY：
     * 上一代会话被取消后的异步收尾不会篡改当前会话的状态。
     */
    @Synchronized
    fun endSession(generation: Long) {
        if (state == AsrState.RECORDING && generation == sessionGeneration) {
            state = AsrState.READY
        }
    }

    /** 当前会话代号；没有会话时是上一次的值。 */
    @Synchronized
    fun currentGeneration(): Long = sessionGeneration

    /** @return true 表示本次真的关闭了；false 表示之前已经关闭（幂等）。 */
    @Synchronized
    fun close(): Boolean {
        if (state == AsrState.CLOSED) return false
        state = AsrState.CLOSED
        return true
    }
}
