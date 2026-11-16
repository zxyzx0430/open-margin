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
 * 所有方法都加锁：引擎的 close() 可能来自主线程，prepare() 来自协程。
 */
class AsrStateMachine {

    @Volatile
    var state: AsrState = AsrState.NEW
        private set

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

    @Synchronized
    fun beginSession() {
        checkCanStartSession()
        state = AsrState.RECORDING
    }

    @Synchronized
    fun endSession() {
        if (state == AsrState.RECORDING) state = AsrState.READY
    }

    /** @return true 表示本次真的关闭了；false 表示之前已经关闭（幂等）。 */
    @Synchronized
    fun close(): Boolean {
        if (state == AsrState.CLOSED) return false
        state = AsrState.CLOSED
        return true
    }
}
