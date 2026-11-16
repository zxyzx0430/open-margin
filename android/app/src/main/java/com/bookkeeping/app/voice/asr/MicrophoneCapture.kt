package com.bookkeeping.app.voice.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * AudioRecord 的最小封装：16kHz / 单声道 / PCM16。
 *
 * 用 VOICE_RECOGNITION 音源（不是 MIC）：系统会关掉 AGC、回声消除和降噪，识别
 * 准确率更稳。read() 会阻塞到凑够一批样本，所以调用方必须在 IO 线程。
 */
internal class MicrophoneCapture(private val sampleRate: Int = 16000) {

    private var record: AudioRecord? = null

    @Synchronized
    fun start() {
        if (record != null) return
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 至少留 1 秒缓冲：read 偶尔被调度延迟时不会丢音。
        val bufferBytes = maxOf(minBytes, sampleRate * 2 * 2)
        val created = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            throw IllegalStateException("AudioRecord 初始化失败")
        }
        created.startRecording()
        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            created.release()
            throw IllegalStateException("AudioRecord 启动录音失败")
        }
        record = created
    }

    /**
     * 读一批 PCM16。返回读取样本数；负数表示 AudioRecord 报错。
     *
     * 不加 @Synchronized：stop() 需要能打断正在阻塞的 read()，加锁会让 stop 一起卡住。
     */
    fun read(target: ShortArray, size: Int): Int =
        runCatching { record?.read(target, 0, size) ?: 0 }.getOrElse { -1 }

    @Synchronized
    fun stop() {
        record?.let { runCatching { it.stop() } }
    }

    @Synchronized
    fun release() {
        val current = record ?: return
        record = null
        runCatching { current.stop() }
        runCatching { current.release() }
    }
}
