package com.bookkeeping.app.voice.asr

import android.content.Context
import android.media.AudioEffect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * AudioRecord 的最小封装：16kHz / 单声道 / PCM16。
 *
 * 优先用 VOICE_RECOGNITION 并显式启用 AEC/NS；若 AEC 不可用，改试
 * VOICE_COMMUNICATION（系统通信音源），最终才退回 VOICE_RECOGNITION。
 * read() 会阻塞到凑够一批样本，所以调用方必须在 IO 线程。
 */
internal class MicrophoneCapture(
    context: Context,
    private val sampleRate: Int = 16000,
) {
    private val audioFocus = DictationAudioFocus(context)

    @Volatile
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Synchronized
    fun start() {
        if (record != null) return
        try {
            audioFocus.request()
            val primary = createCandidate(DictationCaptureSource.VOICE_RECOGNITION)
            val selected = if (primary?.aecEnabled == true) {
                primary
            } else {
                primary?.let(::releaseCandidate)
                DictationCaptureSourcePolicy
                    .fallbacksAfterVoiceRecognition(aecEnabled = primary?.aecEnabled == true)
                    .firstNotNullOfOrNull(::createCandidate)
            } ?: throw IllegalStateException("AudioRecord 初始化失败")

            record = selected.record
            echoCanceler = selected.echoCanceler
            noiseSuppressor = selected.noiseSuppressor
            Log.i(
                TAG,
                "AudioRecord source=${selected.source} aec=${selected.aecEnabled} " +
                    "ns=${selected.noiseSuppressor != null}",
            )
        } catch (failure: Throwable) {
            releaseEffects()
            audioFocus.abandon()
            throw failure
        }
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
        releaseEffects()
        audioFocus.abandon()
    }

    @Synchronized
    fun release() {
        val current = record
        record = null
        current?.let { runCatching { it.stop() } }
        releaseEffects()
        audioFocus.abandon()
        current?.let { runCatching { it.release() } }
    }

    private fun createCandidate(source: DictationCaptureSource): CaptureCandidate? {
        var created: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            val minBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            // 至少留 1 秒缓冲：read 偶尔被调度延迟时不会丢音。
            val bufferBytes = maxOf(minBytes, sampleRate * 2 * 2)
            val record = AudioRecord(
                source.toMediaRecorderSource(),
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            created = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord 初始化失败，source=$source")
            }

            val sessionId = record.audioSessionId
            aec = createEchoCanceler(sessionId)
            ns = createNoiseSuppressor(sessionId)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord 启动录音失败，source=$source")
            }
            return CaptureCandidate(
                record = record,
                source = source,
                echoCanceler = aec,
                noiseSuppressor = ns,
            )
        } catch (security: SecurityException) {
            releaseCandidateResources(created, aec, ns)
            throw security
        } catch (failure: Exception) {
            Log.w(TAG, "AudioRecord source=$source unavailable", failure)
            releaseCandidateResources(created, aec, ns)
            return null
        }
    }

    private fun createEchoCanceler(sessionId: Int): AcousticEchoCanceler? = createEnabledEffect(
        label = "AcousticEchoCanceler",
        isAvailable = { AcousticEchoCanceler.isAvailable() },
        create = { AcousticEchoCanceler.create(sessionId) },
    )

    private fun createNoiseSuppressor(sessionId: Int): NoiseSuppressor? = createEnabledEffect(
        label = "NoiseSuppressor",
        isAvailable = { NoiseSuppressor.isAvailable() },
        create = { NoiseSuppressor.create(sessionId) },
    )

    private fun <T : AudioEffect> createEnabledEffect(
        label: String,
        isAvailable: () -> Boolean,
        create: () -> T?,
    ): T? {
        val available = runCatching(isAvailable)
        if (!available.getOrDefault(false)) {
            Log.w(TAG, "$label unavailable${available.exceptionOrNull()?.let { ": ${it.message}" } ?: ""}")
            return null
        }
        val created = runCatching(create)
        val effect = created.getOrNull()
        if (effect == null) {
            Log.w(TAG, "$label could not be created${created.exceptionOrNull()?.let { ": ${it.message}" } ?: ""}")
            return null
        }
        val enabled = runCatching {
            if (!effect.enabled && effect.setEnabled(true) != AudioEffect.SUCCESS) {
                false
            } else {
                effect.enabled
            }
        }.getOrDefault(false)
        if (!enabled) {
            Log.w(TAG, "$label could not be enabled")
            runCatching { effect.release() }
            return null
        }
        return effect
    }

    private fun releaseEffects() {
        val aec = echoCanceler
        val ns = noiseSuppressor
        echoCanceler = null
        noiseSuppressor = null
        aec?.let { runCatching { it.release() } }
        ns?.let { runCatching { it.release() } }
    }

    private fun releaseCandidate(candidate: CaptureCandidate) {
        releaseCandidateResources(candidate.record, candidate.echoCanceler, candidate.noiseSuppressor)
    }

    private fun releaseCandidateResources(
        record: AudioRecord?,
        aec: AcousticEchoCanceler?,
        ns: NoiseSuppressor?,
    ) {
        record?.let { runCatching { it.stop() } }
        aec?.let { runCatching { it.release() } }
        ns?.let { runCatching { it.release() } }
        record?.let { runCatching { it.release() } }
    }

    private fun DictationCaptureSource.toMediaRecorderSource(): Int = when (this) {
        DictationCaptureSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        DictationCaptureSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    private data class CaptureCandidate(
        val record: AudioRecord,
        val source: DictationCaptureSource,
        val echoCanceler: AcousticEchoCanceler?,
        val noiseSuppressor: NoiseSuppressor?,
    ) {
        val aecEnabled: Boolean get() = echoCanceler != null
    }

    private companion object {
        const val TAG = "MicrophoneCapture"
    }
}
