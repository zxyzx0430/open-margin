package com.bookkeeping.app.voice.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * 解码线程数：手机上 2 条线程能明显缩短单次 decode（多核并行），但 1 核设备开 2 条
 * 只会互相抢占。VPS 单核实测 2 线程反而更慢（RTF 0.66 vs 0.39），所以按核数夹到 1~2。
 */
internal fun defaultDecodeThreads(): Int =
    Runtime.getRuntime().availableProcessors().coerceIn(1, 2)

/**
 * sherpa-onnx 的本地流式 CTC 实现：pkufool/zipformer-medium-streaming INT8。
 *
 * 线程模型（很重要）：
 * - 采集：`Dispatchers.IO` 上一个协程，每 100ms 把 PCM16 送进队列。
 * - 解码：**单线程** dispatcher 上一个协程，独占 OnlineStream / OnlineRecognizer
 *   的全部 native 调用（createStream / acceptWaveform / decode / getResult / release）。
 *   native 对象不是线程安全的，串行化比加锁更省心。
 * - 调用方（UI 主线程）：startSession/finishInput/close 只改状态、发消息，不做重活。
 *
 * 收尾：finishInput() 之后补 0.6s 静音再 inputFinished()，把 isReady 的剩余帧全部
 * decode 干净，最后才发 Final——最后几个字不会因为“没送够帧”被吞掉。
 */
class SherpaStreamingAsrEngine(
    context: Context,
    private val spec: AsrModelSpec = AsrModelSpec.DEFAULT,
    private val captureFromMicrophone: Boolean = true,
    private val numThreads: Int = defaultDecodeThreads(),
) : StreamingAsrEngine {

    private val appContext = context.applicationContext
    private val installer = AsrModelInstaller(appContext, spec)
    private val state = AsrStateMachine()
    private val prepareMutex = Mutex()
    private val nativeLock = Any()

    private val eventFlow = MutableSharedFlow<AsrEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<AsrEvent> = eventFlow.asSharedFlow()

    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "asr-decode").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + decodeExecutor.asCoroutineDispatcher())
    private val captureDispatcher = Dispatchers.IO

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var sessionStream: OnlineStream? = null

    @Volatile
    private var microphone: MicrophoneCapture? = null

    private var audioQueue: Channel<AudioCommand>? = null
    private var decodeJob: Job? = null
    private var captureJob: Job? = null
    private val finishRequested = AtomicBoolean(false)
    private val badSampleRateReported = AtomicBoolean(false)

    @Volatile
    private var currentLanguage: String = LANGUAGE_ZH

    /** warm-up 只做一次；close()/重新加载识别器时复位。 */
    @Volatile
    private var warmedUp: Boolean = false

    override suspend fun prepare(onProgress: (String) -> Unit): Result<Unit> = prepareMutex.withLock {
        when (state.state) {
            AsrState.CLOSED -> return@withLock Result.failure(
                AsrModelException(AsrErrorCode.ENGINE_CLOSED, "语音引擎已关闭"),
            )
            // 识别器已经在内存里（可能正在录音）：重复 prepare() 直接成功，
            // 不能把 RECORDING 打回 PREPARING，否则当前会话的 Final 会被丢掉。
            AsrState.READY, AsrState.RECORDING -> return@withLock Result.success(Unit)
            else -> Unit
        }
        state.beginPrepare()

        val installed = installer.install(onProgress)
        if (installed.isFailure) {
            val failure = installed.exceptionOrNull() ?: RuntimeException("语音模型准备失败")
            state.finishPrepare(false)
            val code = (failure as? AsrModelException)?.code ?: AsrErrorCode.MODEL_CORRUPTED
            val message = failure.message ?: "语音模型损坏，请重装"
            eventFlow.tryEmit(AsrEvent.Error(code, message, failure))
            return@withLock Result.failure(failure)
        }
        val modelDir = installed.getOrThrow()

        var created: OnlineRecognizer? = null
        var createFailure: Throwable? = null
        try {
            onProgress("正在加载语音识别引擎")
            created = withContext(Dispatchers.Default) { createRecognizer(modelDir) }
        } catch (cancel: CancellationException) {
            state.finishPrepare(false)
            throw cancel
        } catch (failure: Throwable) {
            createFailure = failure
        }

        val readyRecognizer = created
        if (readyRecognizer == null) {
            state.finishPrepare(false)
            val unsupported = createFailure is UnsatisfiedLinkError || createFailure is NoClassDefFoundError
            val code = if (unsupported) AsrErrorCode.MODEL_UNSUPPORTED_ABI else AsrErrorCode.RECOGNIZER_INIT_FAILED
            val message = if (unsupported) "此设备的 CPU 架构没有语音识别原生库" else "语音引擎初始化失败"
            eventFlow.tryEmit(AsrEvent.Error(code, message, createFailure))
            return@withLock Result.failure(AsrModelException(code, message, createFailure))
        }

        synchronized(nativeLock) { recognizer = readyRecognizer }
        warmedUp = false
        state.finishPrepare(true)
        eventFlow.tryEmit(AsrEvent.Ready)
        Result.success(Unit)
    }

    /**
     * 静音 warm-up：建一条临时流，喂 0.6s 静音后 inputFinished + 把可解的帧全解完，
     * 再 release 掉这条流。ORT 的图执行器/内存池因此提前热起来，第一次真实录音的
     * 首字延迟更短。全程不发任何事件（Final 只属于用户会话）。
     */
    override suspend fun warmUp() {
        if (warmedUp) return
        if (state.state != AsrState.READY) return
        val active = synchronized(nativeLock) { recognizer } ?: return
        withContext(Dispatchers.Default) {
            synchronized(nativeLock) {
                // 排到锁时可能已经开始录音 / 已经热身过了，再确认一次。
                if (warmedUp || state.state != AsrState.READY) return@synchronized
                val stream = try {
                    active.createStream()
                } catch (failure: Throwable) {
                    Log.w(TAG, "warm-up 建流失败，忽略", failure)
                    return@synchronized
                }
                val startedAt = System.currentTimeMillis()
                try {
                    stream.acceptWaveform(FloatArray(WARMUP_SILENCE_SAMPLES), SAMPLE_RATE)
                    stream.inputFinished()
                    drain(active, stream)
                    warmedUp = true
                    Log.d(TAG, "warm-up 完成：0.6s 静音，耗时 ${System.currentTimeMillis() - startedAt}ms")
                } catch (failure: Throwable) {
                    Log.w(TAG, "warm-up 失败，忽略（不影响识别）", failure)
                } finally {
                    runCatching { stream.release() }
                }
            }
        }
    }

    override fun startSession(language: String): Long? {
        // 未 prepare 会在这里抛 IllegalStateException（接口契约）。
        state.checkCanStartSession()

        if (captureFromMicrophone && !hasRecordPermission()) {
            eventFlow.tryEmit(
                AsrEvent.Error(AsrErrorCode.PERMISSION_DENIED, "没有录音权限，无法语音记账"),
            )
            return null
        }

        closeActiveSession()

        val active = synchronized(nativeLock) { recognizer }
        if (active == null) {
            eventFlow.tryEmit(AsrEvent.Error(AsrErrorCode.NOT_PREPARED, "语音引擎未就绪：请先 prepare()"))
            return null
        }

        currentLanguage = if (language.equals(LANGUAGE_EN, ignoreCase = true)) LANGUAGE_EN else LANGUAGE_ZH
        Log.i(TAG, "startSession language=$currentLanguage threads=$numThreads capture=$captureFromMicrophone")
        finishRequested.set(false)
        badSampleRateReported.set(false)

        val queue = Channel<AudioCommand>(capacity = AUDIO_QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        audioQueue = queue
        val generation = state.beginSession()

        decodeJob = scope.launch { decodeLoop(active, queue, generation) }
        if (captureFromMicrophone) {
            captureJob = scope.launch(captureDispatcher) { captureLoop(queue, generation) }
        }
        return generation
    }

    override fun acceptAudio(pcm16: ShortArray, sampleRate: Int) {
        val queue = audioQueue ?: return
        if (pcm16.isEmpty()) return
        if (sampleRate != SAMPLE_RATE) {
            if (badSampleRateReported.compareAndSet(false, true)) {
                eventFlow.tryEmit(
                    AsrEvent.Error(
                        AsrErrorCode.AUDIO_INIT_FAILED,
                        "语音采样率必须是 ${SAMPLE_RATE}Hz，收到 ${sampleRate}Hz",
                        sessionId = state.currentGeneration(),
                    ),
                )
            }
            return
        }
        queue.trySend(AudioCommand.Audio(toFloatSamples(pcm16)))
    }

    override fun finishInput() {
        if (state.state != AsrState.RECORDING) return
        finishRequested.set(true)
        val queue = audioQueue ?: return
        if (captureFromMicrophone && captureJob != null) {
            // 中断阻塞中的 read()；captureLoop 退出时会在 finally 里补一条 Finish，
            // 这样“最后一批音频”一定排在 Finish 之前。
            microphone?.stop()
        } else {
            queue.trySend(AudioCommand.Finish)
        }
    }

    /**
     * 中止会话：麦克风立刻放掉，半句话丢弃，识别器继续留在内存里（进程级常驻）。
     * 面板关闭 / 切后台 / Activity 销毁都走这里，绝不 close() 引擎。
     */
    override fun cancelSession() {
        if (state.state != AsrState.RECORDING) return
        Log.d(TAG, "会话被放弃，麦克风释放；识别器保持常驻")
        closeActiveSession()
    }

    override fun close() {
        // 幂等：第二次调用直接返回。
        if (!state.close()) return
        warmedUp = false
        finishRequested.set(true)
        runCatching { microphone?.stop() }
        runCatching { microphone?.release() }
        microphone = null

        val jobs = listOfNotNull(decodeJob, captureJob)
        audioQueue?.close()
        audioQueue = null
        decodeJob = null
        captureJob = null
        scope.cancel()

        // 识别器要等解码协程彻底退出后再释放：native 侧不能并发调用。
        CoroutineScope(Dispatchers.Default).launch {
            withTimeoutOrNull(RELEASE_TIMEOUT_MS) { jobs.joinAll() }
            synchronized(nativeLock) {
                runCatching { sessionStream?.release() }
                sessionStream = null
                runCatching { recognizer?.release() }
                recognizer = null
            }
            decodeExecutor.shutdown()
            Log.i(TAG, "语音引擎已释放")
        }
    }

    // ---------------------------------------------------------------- internals

    private fun createRecognizer(modelDir: File): OnlineRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM, dither = 0f),
            modelConfig = OnlineModelConfig(
                // 单文件流式 CTC：模型自带 encoder + CTC head，sherpa-onnx 侧走
                // zipformer2Ctc 分支（对应 C++ 的 OnlineModelConfig::zipformer2_ctc）。
                // 1.13.5 的 Kotlin API 并没有 OnlineCtcModelConfig 这个类：javap 只有
                // zipformer2Ctc / neMoCtc / toneCtc 三个 CTC 分支，其中 icefall 导出的
                // 单文件 zipformer2 CTC 必须用 zipformer2Ctc（上游 getModelConfig(15..19)
                // 的流式 zipformer CTC 也是这么配的）。绝不能用 transducer 的
                // encoder/decoder/joiner 三件套——这个模型只有一个 onnx，没有那三段。
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = spec.modelFile(modelDir).absolutePath),
                tokens = spec.tokensFile(modelDir).absolutePath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
            // 一次录音当一整句：不在静音处自动切句，避免把“花了三十块”切成两笔。
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(assetManager = null, config = config)
    }

    private suspend fun decodeLoop(active: OnlineRecognizer, queue: Channel<AudioCommand>, generation: Long) {
        val stream = active.createStream()
        synchronized(nativeLock) { sessionStream = stream }
        var lastPartial = ""
        try {
            for (command in queue) {
                when (command) {
                    is AudioCommand.Audio -> {
                        stream.acceptWaveform(command.samples, SAMPLE_RATE)
                        drain(active, stream)
                        val text = AsrText.normalize(active.getResult(stream).text)
                        if (text.isNotBlank() && text != lastPartial) {
                            lastPartial = text
                            eventFlow.tryEmit(AsrEvent.Partial(text, sessionId = generation))
                        }
                    }

                    AudioCommand.Finish -> {
                        stream.acceptWaveform(FloatArray(TAIL_SILENCE_SAMPLES), SAMPLE_RATE)
                        stream.inputFinished()
                        drain(active, stream)
                        val finalText = AsrText.normalize(active.getResult(stream).text).trim()
                        eventFlow.tryEmit(AsrEvent.Final(finalText, sessionId = generation))
                        return
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            eventFlow.tryEmit(AsrEvent.Error(AsrErrorCode.INTERNAL, "语音识别中断", failure, generation))
        } finally {
            synchronized(nativeLock) {
                runCatching { stream.release() }
                if (sessionStream === stream) sessionStream = null
            }
            // 带上本次会话代号：如果是被新一轮 startSession 取消的旧会话，
            // 这里不会把新会话的状态打回 READY。
            state.endSession(generation)
        }
    }

    /** isReady 时反复 decode，直到没有可解的帧（带步数上限，防止异常模型死循环）。 */
    private fun drain(active: OnlineRecognizer, stream: OnlineStream) {
        var steps = 0
        while (active.isReady(stream)) {
            active.decode(stream)
            if (++steps > MAX_DECODE_STEPS) {
                Log.w(TAG, "单批 decode 次数超过 $MAX_DECODE_STEPS，跳过剩余帧")
                break
            }
        }
    }

    private suspend fun captureLoop(queue: Channel<AudioCommand>, sessionId: Long) {
        val capturer = MicrophoneCapture(appContext, SAMPLE_RATE)
        synchronized(nativeLock) { microphone = capturer }
        try {
            capturer.start()
            val buffer = ShortArray(SAMPLES_PER_BATCH)
            while (!finishRequested.get() && coroutineContext.isActive) {
                val read = capturer.read(buffer, buffer.size)
                if (read > 0) {
                    queue.trySend(AudioCommand.Audio(toFloatSamples(buffer, read)))
                } else if (read < 0) {
                    eventFlow.tryEmit(
                        AsrEvent.Error(
                            AsrErrorCode.AUDIO_INIT_FAILED,
                            "录音读取失败（$read）",
                            sessionId = sessionId,
                        ),
                    )
                    return
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (security: SecurityException) {
            eventFlow.tryEmit(
                AsrEvent.Error(
                    AsrErrorCode.PERMISSION_DENIED,
                    "没有录音权限",
                    security,
                    sessionId,
                ),
            )
        } catch (failure: Throwable) {
            eventFlow.tryEmit(
                AsrEvent.Error(
                    AsrErrorCode.AUDIO_INIT_FAILED,
                    "麦克风初始化失败",
                    failure,
                    sessionId,
                ),
            )
        } finally {
            capturer.release()
            synchronized(nativeLock) { if (microphone === capturer) microphone = null }
            queue.trySend(AudioCommand.Finish)
        }
    }

    /** 取消上一次会话（重复 startSession 时），解码协程自己释放它的 stream。 */
    private fun closeActiveSession() {
        finishRequested.set(true)
        runCatching { microphone?.stop() }
        captureJob?.cancel()
        decodeJob?.cancel()
        captureJob = null
        decodeJob = null
        audioQueue?.close()
        audioQueue = null
        state.endSession(state.currentGeneration())
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun toFloatSamples(samples: ShortArray, length: Int = samples.size): FloatArray {
        val out = FloatArray(length)
        for (index in 0 until length) {
            out[index] = samples[index] / 32768.0f
        }
        return out
    }

    private sealed interface AudioCommand {
        class Audio(val samples: FloatArray) : AudioCommand
        data object Finish : AudioCommand
    }

    private companion object {
        const val TAG = "SherpaAsrEngine"
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val SAMPLES_PER_BATCH = 1_600 // 100ms
        const val TAIL_SILENCE_SAMPLES = 9_600 // 0.6s，保证最后一个字的帧被送进解码器
        const val WARMUP_SILENCE_SAMPLES = 9_600 // 0.6s，静音热身，不产生任何事件
        const val AUDIO_QUEUE_CAPACITY = 32
        const val MAX_DECODE_STEPS = 512
        const val RELEASE_TIMEOUT_MS = 2_000L
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"
    }
}
