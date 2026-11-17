package com.bookkeeping.app.voice.asr

/**
 * 系统内存回调 → ASR 常驻策略。
 *
 * 产品决定：**语音引擎常驻，记账要快**。切后台、空闲都不释放——那会让下一次长按
 * 重新等模型加载。只有真的内存紧张时才把模型还给系统：
 *
 * | 回调 | 动作 |
 * | --- | --- |
 * | `TRIM_MEMORY_RUNNING_LOW` | 只记日志，**不**释放（此时释放会让下次记账变慢，收益也小） |
 * | `TRIM_MEMORY_RUNNING_CRITICAL` | 释放 |
 * | `TRIM_MEMORY_COMPLETE` | 释放（进程即将被回收，能还一点是一点） |
 * | 其它（UI_HIDDEN / BACKGROUND / MODERATE …） | 不动 |
 *
 * 这里的常量是 `android.content.ComponentCallbacks2` 的数值镜像：本文件刻意不 import
 * Android 类型，纯 JVM 单测才能覆盖「哪种级别该释放」。Application 启动时会用平台常量
 * 做一次核对，防止两边漂移。
 */
enum class AsrMemoryAction {
    /** 什么都不做。 */
    NONE,

    /** 只打日志，模型继续常驻。 */
    LOG_ONLY,

    /** 释放模型，下一次长按重新懒加载。 */
    RELEASE,
}

object AsrTrimMemoryPolicy {

    /** 镜像 `ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW`。 */
    const val RUNNING_LOW: Int = 10

    /** 镜像 `ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL`。 */
    const val RUNNING_CRITICAL: Int = 15

    /** 镜像 `ComponentCallbacks2.TRIM_MEMORY_COMPLETE`。 */
    const val COMPLETE: Int = 80

    fun actionFor(level: Int): AsrMemoryAction = when (level) {
        RUNNING_CRITICAL, COMPLETE -> AsrMemoryAction.RELEASE
        RUNNING_LOW -> AsrMemoryAction.LOG_ONLY
        else -> AsrMemoryAction.NONE
    }
}
