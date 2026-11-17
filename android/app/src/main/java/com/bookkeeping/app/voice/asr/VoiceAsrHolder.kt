package com.bookkeeping.app.voice.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 进程级语音引擎持有者：**一次加载，一直常驻**。
 *
 * 产品取舍（用户明确要求）：记账要快，不能每次长按都等模型。所以
 * - 第一次 acquire 才加载（懒加载），不在 Application.onCreate 同步加载；
 * - 启动后延迟 [PREWARM_DELAY_MS] 在后台预热，首帧/主线程空闲之后才开始，不拖慢启动；
 * - 面板关闭只结束会话，**不 close 引擎**；切后台、空闲都不释放；
 * - 只有系统内存紧张（[AsrMemoryAction.RELEASE]）或进程被杀才回到「冷」状态，
 *   进程被杀后下次长按自然重新懒加载——这是唯一允许的冷启动；
 * - releaseNow() 之后下一次 acquire 会重建实例（close() 后的实例按契约不可复用）。
 *
 * 真正的状态迁移在 [VoiceAsrResidency]（纯 JVM 可测），这里只做 Context / 调度 / 日志。
 * 加载与释放都用 Log.d 打一行 `ASR 模型已加载` / `ASR 模型已释放`，真机用
 * `adb logcat -s VoiceAsrHolder` 就能看到常驻策略是否按预期工作。
 */
object VoiceAsrHolder {

    /**
     * 预热延迟：等首帧画完、主线程空闲再开始拷模型/建识别器。
     * 2.5s 足够让冷启动动画走完，又不会让用户第一次长按时大概率撞上「还没开始」。
     */
    const val PREWARM_DELAY_MS: Long = 2_500L

    private const val TAG = "VoiceAsrHolder"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prewarmJob: Job? = null

    private val residency = VoiceAsrResidency<StreamingAsrEngine>(
        factory = {
            val context = appContext ?: error("VoiceAsrHolder.install() 还没被调用")
            SherpaStreamingAsrEngine(context)
        },
        prepare = { engine, onProgress -> engine.prepare(onProgress) },
        close = { engine -> engine.close() },
        warmUp = { engine -> engine.warmUp() },
        log = { message -> Log.d(TAG, message) },
    )

    /** 由 Application.onCreate 调用；重复调用无副作用。 */
    fun install(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Log.d(TAG, "VoiceAsrHolder 已安装（此时还没有加载模型）")
        }
    }

    /**
     * 启动后延迟预热。重复调用只有第一次生效。
     *
     * 预热失败不重试、不弹任何 UI：用户长按时 [acquire] 会再试一次，并把可读的错误
     * 交给录音面板。
     */
    fun schedulePrewarm(delayMs: Long = PREWARM_DELAY_MS) {
        if (appContext == null) return
        if (prewarmJob?.isActive == true) return
        prewarmJob = scope.launch {
            delay(delayMs)
            Log.d(TAG, "后台预热开始（首帧之后，不阻塞启动）")
            acquire().fold(
                onSuccess = { Log.d(TAG, "后台预热完成：ASR 模型已加载，后续录音无需等待") },
                onFailure = { Log.w(TAG, "后台预热失败：${it.message}") },
            )
        }
    }

    /**
     * 拿一个可用的引擎。首次会加载模型 + 静音 warm-up；之后就是直接返回常驻实例。
     *
     * 失败统一包成 [AsrModelException]，录音面板才能按错误码给「重试 / 去设置」。
     */
    suspend fun acquire(onProgress: (String) -> Unit = {}): Result<StreamingAsrEngine> {
        if (appContext == null) {
            return Result.failure(AsrModelException(AsrErrorCode.NOT_PREPARED, "语音引擎未初始化"))
        }
        return residency.acquire(onProgress).recoverCatching { failure ->
            throw if (failure is AsrModelException) {
                failure
            } else {
                AsrModelException(
                    AsrErrorCode.INTERNAL,
                    failure.message ?: "语音引擎初始化失败",
                    failure,
                )
            }
        }
    }

    /** 已经加载好的实例；给录音面板在组合开始时同步取用，避免闪一下「准备中」。 */
    fun current(): StreamingAsrEngine? = residency.current()

    fun isReady(): Boolean = residency.isReady()

    /**
     * 释放模型（内存紧张/进程收尾）。下一次 [acquire] 会自动重建。
     *
     * @return true 表示确实释放了一个已存在的实例。
     */
    fun releaseNow(reason: String): Boolean = residency.releaseNow(reason)
}
