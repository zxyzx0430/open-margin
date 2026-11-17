package com.bookkeeping.app.voice.asr

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程级语音引擎的「懒加载 + 常驻」核心。
 *
 * 为什么把核心和数据分开：Android 侧（Context / Log / 延迟预热）放在
 * [VoiceAsrHolder]，这里只留状态迁移，纯 JVM 单测就能钉住这几条硬约定：
 *
 * 1. **懒加载**：第一次 [acquire] 才建实例，不 app 启动就加载（预热由调用方决定时机）；
 * 2. **幂等**：并发 acquire 只会 prepare 一次，后来的调用等同一个结果；
 * 3. **常驻**：正常使用不自动释放，面板开关只结束会话，不动实例；
 * 4. **释放后重建**：只有 [releaseNow]（内存紧张）才会 close 并置空，下一次 acquire
 *    重新建实例——`close()` 之后引擎按契约不可复用，所以必须重建；
 * 5. **失败可重试**：prepare 失败立即释放实例，下一次 acquire 从零重来，不会把一个
 *    半死的引擎留在进程里。
 */
class VoiceAsrResidency<E : Any>(
    private val factory: () -> E,
    private val prepare: suspend (E, (String) -> Unit) -> Result<Unit>,
    private val close: (E) -> Unit,
    private val warmUp: suspend (E) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    @Volatile
    private var engine: E? = null

    @Volatile
    private var ready: Boolean = false

    /**
     * 每次 [releaseNow] 自增。prepare 可能持续几秒，期间系统喊了内存紧张：
     * 用代号发现「加载完的实例已经被释放」，不能把它再挂回 [engine]。
     */
    @Volatile
    private var epoch: Long = 0L

    /** 当前实例（可能还没 prepare 完）；没有实例返回 null。 */
    fun current(): E? = engine

    /** 实例存在且 prepare 成功。 */
    fun isReady(): Boolean = engine != null && ready

    /**
     * 拿一个可用的引擎：没有就建，没 prepare 就 prepare（含一次静音 warm-up）。
     *
     * @param onProgress 首次安装模型时的进度文案，只在真的加载时回调。
     */
    suspend fun acquire(onProgress: (String) -> Unit = {}): Result<E> = mutex.withLock {
        val existing = engine
        if (existing != null && ready) return Result.success(existing)

        val created = existing ?: factory().also {
            engine = it
            log("ASR 引擎实例已创建（懒加载）")
        }
        val startedEpoch = epoch

        val installed = prepare(created, onProgress)
        if (installed.isFailure) {
            ready = false
            if (engine === created) engine = null
            close(created)
            log("ASR 模型加载失败，已释放实例（下次会重新加载）")
            return installed.map { created }
        }

        // 加载期间被 releaseNow() 释放过：这个实例已经被 close，不能交给调用方。
        if (epoch != startedEpoch || engine !== created) {
            close(created)
            log("ASR 加载期间被释放，丢弃本次实例")
            return Result.failure(
                IllegalStateException("语音引擎在加载过程中被释放，请重新长按"),
            )
        }

        // warm-up 只是让解码器热起来，失败不影响可用性，更不能把已加载的模型扔掉。
        runCatching { warmUp(created) }
        ready = true
        log("ASR 模型已加载（常驻，不自动释放）")
        Result.success(created)
    }

    /**
     * 立刻释放（内存紧张/进程收尾）。幂等。
     *
     * @return true 表示这次真的释放了一个实例。
     */
    fun releaseNow(reason: String = "release"): Boolean {
        epoch += 1
        ready = false
        val released = engine ?: return false
        engine = null
        close(released)
        log("ASR 模型已释放（$reason），下次长按重新加载")
        return true
    }
}
