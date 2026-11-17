# 本地流式语音识别引擎（voice ASR）

语音记账的识别全部在本机完成：按住说话 → 16kHz PCM 进 sherpa-onnx 流式 CTC →
Partial 实时上屏 → 松手补静音收尾 → 只把 Final 交给账单解析器。
音频不出设备，不依赖网络，也不申请除 `RECORD_AUDIO` 之外的任何权限。

本文只覆盖引擎与模型管线，不涉及 UI/导航/设置/统计。

- 引擎实现：`android/app/src/main/java/com/bookkeeping/app/voice/asr/`
- 模型管线：`android/app/download-asr-model.sh`
- 单测：`android/app/src/test/java/com/bookkeeping/app/voice/asr/`

---

## 1. 选型结论

| 项 | 值 |
| --- | --- |
| 模型 | `pkufool/zipformer-medium-streaming` → `ctc-chunk-16-left-64.int8.onnx` |
| 类型 | streaming zipformer2 CTC（单文件，encoder + CTC head 导在同一个 onnx 里） |
| 体积 | 74,410,051 B（70.96 MiB）+ `tokens.txt` 86,313 B |
| sha256 | 模型 `7cb84949b8d4f40d72df137e2e3529a1aadfc897e5257460f720bb73c99f10f1`<br>tokens `e2f16836a4c712a9a01a1656f4610f66093fe283159883ee66b73ebb6e80364b` |
| 词表 | 8776（中英混排，中文按字/词输出、词间带空格） |
| onnx 元数据 | `model_type=zipformer2`、`comment=streaming ctc zipformer2`、`decode_chunk_len=32`、opset 13、16 层 encoder |
| 运行时 | sherpa-onnx 1.13.5（Kotlin），静态链接 onnxruntime 的 AAR，CPU provider |
| 许可 | Apache-2.0 |

选它而不是别的，理由是三件事同时成立：**中文准**（含金额数字）、**带流式 Partial**（松手前就能看到字）、**能塞进 APK**（int8 后 71MB，仓库里已有 45MB 的 OCR 依赖，包体可控）。

---

## 2. 为什么固定 chunk-16（而不是 chunk-32 / chunk-64）

三个变体是同一个模型的不同导出：`chunk-16-left-64` / `chunk-32-left-128` / `chunk-64-left-256`，
体积几乎一样（70.9~71.0 MiB），差别是**看多远再出字**和**算力**。

### 2.1 首字延迟与算力（VPS 单核实测）

测法：每 100ms 喂一批（`accept_waveform` 1600 样本），按实时节奏 `sleep` 补齐，
`is_ready` 时 `decode_stream`，记录第一次拿到非空 Partial 的墙钟时间与当时的音频位置；
RTF = `sum(decode 耗时) / 音频时长`。环境：1 vCPU（Intel Xeon E5 级）、2.9GB RAM、
sherpa-onnx Python 包 CPU、`greedy_search`、`enable_endpoint_detection=False`、单线程。

| 模型 | 音频 | 首字（墙钟） | 首字（音频位置） | RTF | 单次 decode p95 | 峰值 RSS | 收尾 flush |
| --- | --- | --- | --- | --- | --- | --- | --- |
| chunk-16 | zh 5.61s | **1.17s** | 1.20s | 0.388 | 216ms | 172MB | 95ms |
| chunk-16 | en 16.71s | **1.23s** | 1.20s | 0.361 | 226ms | 173MB | 118ms |
| chunk-32 | zh 5.61s | 1.51s | 1.50s | 0.141 | 112ms | 185MB | 76ms |
| chunk-32 | en 16.71s | 1.50s | 1.50s | 0.174 | 164ms | 186MB | 121ms |
| chunk-64 | zh 5.61s | 1.57s | 1.50s | 0.145 | 321ms | 201MB | 2ms |
| chunk-64 | en 16.71s | 1.62s | 1.50s | 0.259 | 532ms | 203MB | 226ms |

结论：
- 首字延迟 chunk-16 比另外两个**早约 0.3s**（1.2s vs 1.5s 音频位置，墙钟 1.17s vs 1.57s）。
- 算力 chunk-16 最贵：RTF 0.36~0.39，也就是单核要吃掉约 40% 的实时预算；
  chunk-32 约 0.15，chunk-64 约 0.15~0.26。三者在本机都还跑得动（<1.0 就是实时）。
- 内存 chunk-16 最省（172MB vs 201MB）。

> 这台 VPS 只有 1 个 vCPU，所以“2 线程”的测量值全部失真（线程互相抢占，RTF 反而升到 0.66~0.83）。
> 引擎因此按核数取线程：`min(2, availableProcessors)`，单核设备退化成 1 线程。

### 2.2 准确率（这一条才是决定性的）

用 edge-tts 合成 4 句记账口述（16k 单声道转换后），同样按 100ms 实时节奏解码：

| 参考文本 | chunk-16 | chunk-64 |
| --- | --- | --- |
| 今天中午吃饭花了三十块钱 | 今天中午吃饭花了三十块钱 | 今天中午吃饭花了三十 ❌（吞“块钱”） |
| 打车花了二十六块五 | 打车花了二十六块五 | 打车花了二十六块五 |
| 昨天在超市买东西一共一百二十八块四 | 昨天在超市买东西 一共一百二十八块四 | 昨天在超市买东西 一共一百二十八块四 |
| I spent five dollars on coffee this morning | i spent five dollars on coffee this morning | i spent five dollars on coffee this morning |

公开样例（`zh.wav` / `en.wav`）上 chunk-64 也明显更差：
`怎么` → `琢么`、`descent` → `desent`、`finally` → `a obliged…`。
chunk-16 的 `对我做了介绍 汉 怎么我想说的是呢…` 与 `forever / finally` 都正确。

**取舍**：chunk-16 多花 0.25 RTF 的算力，换来 0.3s 更早出字 + 金额单位不被吞。
记账是“一句话、几秒钟”的场景，延迟感知差异 >> 电量差异，所以固定 chunk-16。
换档只需改 `download-asr-model.sh` 的 `MODEL_NAME/MODEL_BYTES/MODEL_SHA256` 与
`AsrModelSpec` 里的同名常量（两处不一致时单测会红）。

### 2.3 收尾静音是必需的，不是保险

用户松手往往落在字中间。把 TTS 音频截断模拟“说到一半松手”，对比收尾静音：

| 截断位置 | 不补静音 | 补 0.6s 静音 |
| --- | --- | --- |
| zh_lunch 2.71s（原 3.19s） | 今天中午吃饭花了三十 | 今天中午吃饭花了三十块钱 |
| zh_lunch 2.87s | 今天中午吃饭花了三十 | 今天中午吃饭花了三十块钱 |
| zh_market 3.51s（原 4.13s） | 昨天在超市买东西 一共一百二十 | 昨天在超市买东西 一共一百二十八块四 |
| zh_market 3.72s | 昨天在超市买东西 一共一百二十八块 | 昨天在超市买东西 一共一百二十八块四 |

所以 `finishInput()` 之后必须补 **0.6s（9600 样本）静音** 再 `inputFinished()`，
然后把 `is_ready` 的剩余帧全部 `decode` 干净，最后才发 Final。
（0.6s 覆盖 chunk-16 的右上下文，实测 flush 只多 95~226ms。）

### 2.4 分块流式 == 整段喂入

同一模型，100ms 分块流式 vs 一次性 `accept_waveform` 整段，5 个音频（含 16.7s 英文）
**最终文本逐字相同**。也就是说：为了实时性做的分块不会牺牲最终质量，
Partial 只是“同一结果的更早前缀”。

---

## 3. 复现验证（VPS 上跑，不需要 Android）

```bash
python3 -m venv .venv && .venv/bin/pip install sherpa-onnx soundfile numpy
# 模型与样例音频（HuggingFace）
curl -L -o ctc-chunk-16-left-64.int8.onnx \
  https://huggingface.co/pkufool/zipformer-medium-streaming/resolve/main/ctc-chunk-16-left-64.int8.onnx
curl -L -o tokens.txt \
  https://huggingface.co/pkufool/zipformer-medium-streaming/resolve/main/data/tokens.txt
```

最小解码（与引擎同样的节奏）：

```python
import numpy as np, sherpa_onnx, soundfile as sf

rec = sherpa_onnx.OnlineRecognizer.from_zipformer2_ctc(
    tokens="tokens.txt", model="ctc-chunk-16-left-64.int8.onnx",
    num_threads=1, decoding_method="greedy_search", enable_endpoint_detection=False,
)
samples, sr = sf.read("zh.wav", dtype="float32")
assert sr == 16000
stream = rec.create_stream()
for off in range(0, len(samples), 1600):          # 100ms 一批
    stream.accept_waveform(16000, samples[off:off + 1600])
    while rec.is_ready(stream):
        rec.decode_stream(stream)
    print(rec.get_result(stream))                  # 实时 Partial
stream.accept_waveform(16000, np.zeros(9600, dtype=np.float32))  # 0.6s 收尾静音
stream.input_finished()
while rec.is_ready(stream):
    rec.decode_stream(stream)
print("FINAL:", rec.get_result(stream))
```

`from_zipformer2_ctc` 就是 `OnlineZipformer2CtcModelConfig`（C++ 的 `zipformer2_ctc` 分支）；
Python API 里**没有**通用 `from_ctc`，只有 `from_zipformer2_ctc / from_nemo_ctc / from_t_one_ctc / from_wenet_ctc`。

---

## 4. Android 侧配置：为什么是 `OnlineZipformer2CtcModelConfig`

任务里说的“用 `OnlineCtcModelConfig`”在 sherpa-onnx 1.13.5 的 Kotlin API 里**不存在**。
把 `sherpa-onnx-static-link-onnxruntime-1.13.5.aar` 的 `classes.jar` 拆出来 `javap` 过：

```
com/k2fsa/sherpa/onnx/OnlineZipformer2CtcModelConfig.class
com/k2fsa/sherpa/onnx/OnlineNeMoCtcModelConfig.class
com/k2fsa/sherpa/onnx/OnlineToneCtcModelConfig.class
```

`OnlineModelConfig` 的构造参数只有 `transducer / paraformer / zipformer2Ctc / neMoCtc /
toneCtc / tokens / numThreads / debug / provider / modelType / modelingUnit / bpeVocab`。
CTC 家族只有上面三个分支，其中 icefall 导出的单文件 zipformer2 CTC **必须**走 `zipformer2Ctc`
（上游 `OnlineRecognizer.kt` 里 `getModelConfig(15..19)` 的流式 zipformer CTC 就是这么配的）。
模型元数据也自证：`model_type=zipformer2`、`comment=streaming ctc zipformer2`。

所以 `SherpaStreamingAsrEngine.createRecognizer()` 用的是：

```kotlin
OnlineRecognizerConfig(
    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
    modelConfig = OnlineModelConfig(
        zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = <model.onnx 绝对路径>),
        tokens = <tokens.txt 绝对路径>,
        numThreads = min(2, availableProcessors),
        provider = "cpu",
    ),
    enableEndpoint = false,          // 一次录音当一整句，不在静音处切
    decodingMethod = "greedy_search",
)
```

**不是 transducer**：这个模型只有一个 onnx，没有 encoder/decoder/joiner 三段。
用 transducer 配置会直接构造失败（native 侧拿不到三个文件路径）。
`enableEndpoint = false` 是有意的：把“花了三十块，打车二十六”当成一句解，
交给解析器去拆，比在 0.8s 静音处切句、再拼两笔账要稳。

### native 库的坑：为什么用“静态链接 onnxruntime”的 AAR

仓库里已经有 paddle-ocr 的 `libonnxruntime.so`（ORT 1.21.1，arm64）。
JitPack 上的 `com.github.k2-fsa:sherpa-onnx` 普通 AAR 自带 `libonnxruntime.so`（ORT 1.27.1），
两个同名 `.so` 的 JNI 符号都带版本（`OrtGetApiBase@VERS_1.2x.x`），`pickFirst` 只能让其中一个引擎
加载失败——要么 OCR 崩，要么语音崩。所以用官方 release 里的
`sherpa-onnx-static-link-onnxruntime-1.13.5.aar`（arm64 目录只有 `libsherpa-onnx-jni.so`，
ORT 静态编进去），两套 native 共存。

CI 里有一步专门守这条：解包 release APK，断言
`lib/arm64-v8a/libonnxruntime.so` **恰好 1 份**、`lib/arm64-v8a/libsherpa-onnx-jni.so` 存在。

---

## 5. 引擎实现要点

| 要求 | 实现 |
| --- | --- |
| 采集 | `AudioRecord(VOICE_RECOGNITION, 16000, CHANNEL_IN_MONO, PCM_16BIT)`，缓冲 ≥1s |
| 批次 | `ShortArray(1600)` = 100ms 一批，转 float（`/32768f`）后入队 |
| 解码线程 | 采集在 `Dispatchers.IO`；解码**单线程** executor，native 对象只被一个协程碰（native 非线程安全） |
| Partial / Final | 分开的事件；Partial 只在文本变化时发；Final 每次 `finishInput()` 只发一次（可能是空串） |
| 收尾 | 补 0.6s 静音 → `inputFinished()` → 把 `is_ready` 的帧全解完 → 发 Final（见 §2.3） |
| close 幂等 | `AsrStateMachine.close()` 返回“本次是否真的关”，第二次直接返回；协程退出后才 `release()` native 对象 |
| 状态机 | 未 prepare 就 `startSession()` 抛 `IllegalStateException("…请先 prepare()")`；close 后一切操作抛错 |
| 会话代号 | `beginSession()` 返回代号，`endSession(代号)` 只认当前代，避免旧会话的异步收尾把新会话打回 READY |
| 队列背压 | 32 批（3.2s）环形队列，满了丢最旧的音频并保证 Finish 一定入队 |
| 权限 | 只在 `startSession` 前检查 `RECORD_AUDIO`；`<uses-feature microphone required=false>` 不挡无麦设备安装 |

`prepare()` 可以重复调用（READY/RECORDING 时直接成功）；模型安装到
`filesDir/asr/zipformer-medium-ctc-chunk16-left64-int8/`，带 `.ready`（内含版本 + 每个文件 sha256）。
启动时只做“存在 + 字节数 + 版本标记”的浅校验（省 71MB 的哈希时间），
安装时逐文件边拷边算 sha256（深校验）。

---

## 6. 模型管线与 CI

`android/app/download-asr-model.sh`：

- 下载 `ctc-chunk-16-left-64.int8.onnx` + `tokens.txt` → `android/app/src/main/assets/asr/zipformer-medium-ctc/`
- 下载 `sherpa-onnx-static-link-onnxruntime-1.13.5.aar`（37,749,854 B）→ `android/app/libs/`
- 每件都校验 **字节数 + sha256**，先写 `.part` 再原子改名，失败重试 5 次
- 幂等：已存在且校验通过就跳过；`--check` 只校验不下载（CI 用）；`--force` 强制重下
- 缓存目录 `~/.cache/open-margin-asr`（`OPEN_MARGIN_ASR_CACHE` 可覆盖）

`.gitignore`：`/android/app/src/main/assets/asr/`、`/android/app/libs/sherpa-onnx-*.aar` ——
模型和 AAR 都不进 git（仓库只放脚本 + 哈希）。

CI（`.github/workflows/android-build.yml` 与 `android-emulator-smoke.yml`）：

1. `actions/cache` 缓存 `assets/asr` + `libs` + `~/.cache/open-margin-asr`，
   key 里带模型名与两个文件的 sha256（换模型自动 miss，不会拿旧缓存骗过校验）；
2. 跑脚本 + `--check` 复核落盘资产；
3. 构建后解包 release APK，断言模型、tokens、`libsherpa-onnx-jni.so` 都在，
   且 arm64 的 `libonnxruntime.so` 只有 1 份；
4. 打印 `APK_MB`。

`android/app/build.gradle.kts` 在**配置阶段**就检查 AAR 是否存在，缺了直接报
“请先运行 bash android/app/download-asr-model.sh”，而不是让 Kotlin 编译报几十条
`Unresolved reference: k2fsa`。

---

## 7. 包体

`defaultConfig` 里把 ABI 收敛到 `arm64-v8a`（debug/release 一致）。原因不只是体积：
sherpa 的 AAR 自带 x86/x86_64 的 `libsherpa-onnx-jni.so`，如果 debug 包混进 x86_64 `.so`，
系统就会把应用当成 x86_64 原生应用，反而加载不了只有 arm64 的 OCR/OpenCV 库
（`PaddleOcrInstrumentedTest` 会以 `opencv_init_failed` 失败）。收敛后模拟器继续走
系统的 arm64 翻译层，与加语音之前的行为一致，debug 包也少约 70MB。

release APK 实测 **125,389,711 B（119.6 MiB，CI 日志 `APK_MB=119`）**，达成 ~120MB 目标。
其中语音部分：

| APK 内条目 | 原始 | APK 内 |
| --- | --- | --- |
| `assets/asr/.../ctc-chunk-16-left-64.int8.onnx` | 74,410,051 | 53,606,917（deflate，省 28%） |
| `assets/asr/.../tokens.txt` | 86,313 | 37,904 |
| `lib/arm64-v8a/libsherpa-onnx-jni.so` | 23,646,824 | 23,646,824（`extractNativeLibs=false`，不压缩） |

语音合计约 77.3MB；去掉语音的同等构建约 48MB。
如果后续还要压：换 40MB 级小模型（准确率下降）或首次启动联网下载（破坏离线承诺），都不建议现在做。

---

## 8. 纯 JVM 单测覆盖

`android/app/src/test/java/com/bookkeeping/app/voice/asr/`：

| 测试 | 钉住什么 |
| --- | --- |
| `AsrModelSpecTest` | 模型 id/版本/文件名/字节数/sha256 长度与格式 |
| `AsrModelVerifierTest` | 缺文件、字节数不符、sha256 不符、缺 `.ready`、版本过期、浅校验 vs 深校验 |
| `AsrStateMachineTest` | 未 prepare 就开会话报错、失败可重试、close 幂等、close 后操作报错、旧会话不能结束新会话 |
| `AsrTextTest` | CTC 输出里汉字之间的空格清理，中英/数字混排保持原样 |
| `AsrModelPipelineTest` | 脚本常量 == Kotlin 常量、`.gitignore` 忽略资产、CI 缓存键带两个 sha256、两个 workflow 都准备了模型 |

没有真机/模拟器也能跑：`./gradlew testDebugUnitTest`（CI 每次构建都跑）。

---

## 9. 真机待测清单

CI 只能证明“能编译、能打包、单测过”，下面这些必须在真机上过一遍（尤其国产 ROM）：

1. **权限流**：首次按住说话弹 `RECORD_AUDIO`，拒绝后再次按住要有可读提示，不崩。
2. **首字延迟**：中端机（如骁龙 7 系）说“今天中午吃饭花了三十块钱”，松手前应看到 ≥6 个字上屏；
   若 2s 还没字，记录机型 + `logcat -s SherpaAsrEngine`。
3. **金额准确率**：连说 20 句含金额的口述（带“块/毛/分/十/百”），统计错字率；
   重点看“三十块”会不会被吞成“三十”。
4. **收尾完整**：说到最后一个字立刻松手，连续 10 次，Final 不应缺尾字（这是 §2.3 的核心场景）。
5. **长句**：连说 15s 不断句，观察内存（`adb shell dumpsys meminfo`）与是否掉帧。
6. **打断/重入**：录音中再按一次开始、录音中切后台、录音中锁屏、录音中旋转屏幕。
7. **close 幂等**：进出语音页 20 次，`libsherpa-onnx-jni.so` 的 native 内存不应持续增长
   （`adb shell dumpsys meminfo <pkg> | grep -i native`）。
8. **无麦设备**：平板上安装，语音入口给出可读错误，其他记账通道不受影响。
9. **与 OCR 共存**：先触发一次页面识别（paddle-ocr 用 ORT），再说话，两个引擎都要能工作
   （验证静态链接 ORT 真的避开了撞名）。
10. **省电/发热**：连续 10 次 5s 录音，机身不应明显发热（RTF 0.4 单核 ≈ 中端机 0.15~0.2）。

---

## 10. 已知限制

- 模型不带标点/数字规范化：输出是 `一百二十八块四`，转成 `128.4` 是解析器的事（另一个分支）。
- 中英混说可以，但纯英文长句偶有拼写错误（`descent` → `disscent`），金额/关键词不受影响。
- 首次启动要把 71MB 模型从 assets 拷到 `filesDir`（约 1~3s，带进度文案），之后启动只做浅校验。
- 不支持热词/自定义词表（sherpa 的 `hotwords` 需要额外建模），账单场景暂不需要。

---

## 11. 语音入口如何接入 UI

引擎只负责「把一段话变成 Final」，谁开麦克风、什么时候关、Final 进哪个表单，都在 UI 层。

### 11.1 链路

```
账单页 VoiceFab（按住 1s 松手）
  → RootNavigation.onVoiceEntry            （voiceSheetOpen = true，已开着就忽略）
  → VoiceCaptureSheet                      （ModalBottomSheet，模态）
      prepare() → 权限 → startSession()
      events: Partial 只刷大字预览 / Final 才收尾 / Error 转可重试失败
  → onFinal(text: String?)                 （面板 hide() 后回调一次）
  → RootNavigation.voiceRequest            （VoiceEntryRequest(text)）
  → 账单页 LaunchedEffect(voiceRequest)     （打开 AddTransactionDialog）
  → AddTransactionDialog(initialQuickEntry = text.orEmpty())
```

| 文件 | 职责 |
| --- | --- |
| `ui/screen/transactions/VoiceCaptureLogic.kt` | 面板状态 + 纯逻辑（Final 文本选取、Partial 丢弃、错误码→文案），无 Android 依赖 |
| `ui/screen/transactions/VoiceCaptureSheet.kt` | 面板 UI、权限申请、引擎持有与 `close()`、生命周期 |
| `ui/navigation/RootNavigation.kt` | 长按落点、面板开合、把 Final 交给账单页 |
| `ui/screen/transactions/TransactionListScreen.kt` | 收到 Final 后打开新建表单并预填；录音中禁用 FAB |
| `ui/components/VoiceFab.kt` | `enabled = false` 时按压手势与读屏动作一起失效 |

### 11.2 三条硬约定

1. **Partial 绝不进表单。** CTC 的 Partial 是「当前前缀」，说话过程中随时被改写；
   只有 `finishInput()` 之后那一个 Final 才能交给解析器。`VoiceCaptureLogic.withPartial`
   在非聆听态直接丢弃，`capture()` 也只接受聆听/收尾两个状态。
2. **空 Final 仍然开表单。** `entryText()` 把空白串折叠成 `null`，`Captured(null)` 表示
   「说了但没听清」：表单照开、预填为空，绝不把空串写进备注 —— 用户点一下「保存」
   就能手填，比再长按一次快。
3. **一个时刻只有一个会话，且必须放麦克风。** 面板开着时 `voiceSheetOpen` 同时是
   面板可见标志和 FAB 的 `enabled = !voiceBusy`；面板 `onDismissRequest` / 取消 /
   `ON_STOP` / 组合销毁四条路径都收敛到 `engine.close()`（幂等）。面板每次打开都新建
   引擎，因为 `close()` 之后按接口契约不可复用（`prepare()` 会返回 `ENGINE_CLOSED`）。

### 11.3 权限与异常

- `prepare()` 成功后才检查 `RECORD_AUDIO`：模型拷完了再弹系统对话框，用户不会先看到
  一个「准备中」的转圈再被权限框打断两次。
- 权限被拒不是静默失败：面板留在失败态，给「重试」和「去系统设置授权」
  （`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`，打不开时退到系统设置首页）。
  拒绝一次后系统可能不再弹框，所以必须留这条去设置的路。
- 申请权限时 Activity 会走 `onStop`，这期间**不**当作「切后台」关面板（`awaitingPermission`），
  否则权限框一弹面板就自己没了。
- 麦克风被占用 / `AudioRecord` 打不开：引擎发 `Error(AUDIO_INIT_FAILED)`，面板显示错误 + 「重试」。
  引擎此时仍在 READY，重试直接 `startSession()`，不需要重建引擎。
- 收尾 5s 没有 Final：`finishTimedOut()` 转成可重试失败，绝不把面板卡在「结束中」。

### 11.4 纯 JVM 单测

`android/app/src/test/java/com/bookkeeping/app/ui/screen/transactions/VoiceCaptureLogicTest.kt`：

| 测试 | 钉住什么 |
| --- | --- |
| `finalTextIsTrimmedAndBlankBecomesNull` | Final 去首尾空白；空串/纯空格/换行 → `null` |
| `partialOnlyRefreshesPreviewWhileListening` | Partial 只在 Listening 生效；收尾/失败/已交付后的迟到 Partial 被丢弃 |
| `stopKeepsLastPartialAndIgnoresRepeats` | 停止保留最后一版预览；连点停止幂等；准备阶段点不到 |
| `emptyFinalStillClosesSheetWithoutPrefill` | 空 Final → `Captured(null)`（表单照开、不预填） |
| `finalOutsideListeningDoesNotOpenForm` | 准备/失败/已交付状态收到的 Final 不触发开表单 |
| `finishTimeoutOnlyAppliesWhileFinishing` | 超时兜底只对收尾态生效 |
| `everyErrorCodeHasDistinctReadableMessage` | 每个 `AsrErrorCode` 都有独立的中文提示；null 码不崩 |
| `everyErrorMessageHasEnglishTranslation` | 错误提示都有 EnglishUiText 条目（它们不走 `tr("字面量")`，覆盖率测试扫不到） |

### 11.5 真机必测项（UI 接线）

CI 只能证明能编译、能打包、单测过；下面这些必须在真机上过一遍：

1. **面板状态**：长按 1 秒松手 → 看到「正在准备语音模型…」→ 首次会显示「正在释放语音模型 …」
   进度 → 进入聆听态，说 2~3 个字后大字区出现 Partial。
2. **Partial 不落表单**：说完不点停止、直接划掉面板，账单列表不应多出任何记录。
3. **Final 预填**：说「今天中午吃饭花了三十块钱」→ 点停止 → 面板收起 → 新建表单已打开，
   金额 30.00、分类餐饮、一句话原文在顶部输入框里。
4. **空 Final**：捂住麦克风或小声说一句听不清的话 → 停止 → 表单仍然打开且金额为空，
   备注里没有空串，用户能直接手填。
5. **权限首次拒绝**：全新安装 → 长按 → 系统对话框点「拒绝」→ 面板显示可读提示 +
   「重试」「去系统设置授权」；去设置授予后返回，再长按应能直接说话。
6. **权限永久拒绝**（勾选「不再询问」后）：再长按不应毫无反应，应仍显示失败态与去设置入口。
7. **切后台/锁屏**：录音中按 Home 或锁屏 → 回到 App 时面板已关闭、麦克风已释放
   （系统状态栏的麦克风指示消失）；期间不应崩溃。
8. **旋转屏幕**：录音中旋转 → Activity 重建，面板关闭，麦克风释放；再长按能重新开始。
9. **快速连按**：面板打开的瞬间再连按 FAB 数次 → 只有一个会话、不会叠两个面板
   （`voiceSheetOpen` 守卫 + FAB `enabled=false`）。
10. **麦克风占用**：先用其他 App（录音机/通话）占住麦克风再长按 → 显示「麦克风被占用或打不开」
    + 「重试」；关掉占用 App 后点重试应能正常识别。
11. **取消路径**：聆听中点「取消」、下滑面板、按返回键三种方式 → 都回到账单页，
    不弹新建表单，麦克风指示消失。
12. **连续 20 次开关**：每次长按→取消，`adb shell dumpsys meminfo <pkg> | grep -i native`
    不应持续增长（每开一次面板都会 new 一个引擎，靠 `close()` 释放 native）。
13. **与 OCR 共存**：先触发一次页面识别（paddle-ocr 用 ORT），再语音记账，两个引擎都要能工作。
14. **英文界面**：切到 English → 面板所有文案（含准备进度、错误提示）都是英文，不露中文。
15. **读屏**：TalkBack 下 FAB 的「语音记账」长按动作能打开面板；面板开着时 FAB 不再响应长按。

