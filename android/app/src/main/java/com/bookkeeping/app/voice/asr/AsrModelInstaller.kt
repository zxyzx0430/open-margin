package com.bookkeeping.app.voice.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** 模型安装失败。[code] 让 UI 区分“模型没随包提供”和“模型损坏”。 */
class AsrModelException(
    val code: AsrErrorCode,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 把 assets 里的模型装到 filesDir/asr/<id>-<version>/。
 *
 * 为什么必须拷贝：onnxruntime / sherpa 的 native 读不了 APK 里的 assets（没有真实
 * 文件路径），必须先落到可读文件系统。
 *
 * 流程：
 * 1. 目标目录已有 `.ready` 且文件字节数都对 → 直接返回（每次启动的快路径）。
 * 2. 否则在 `<target>.part-<ts>` 里重新拷贝：边拷边算 sha256，逐个比对 spec。
 * 3. 全部通过后写 `.ready`（含版本），再 rename 成正式目录；rename 失败则整目录复制。
 * 4. 任何一个文件不对 → 抛 [AsrModelException]，由引擎转成 Error(MODEL_CORRUPTED)，
 *    UI 提示“语音模型损坏，请重装”，不会让进程崩在 native 里。
 */
class AsrModelInstaller(
    private val context: Context,
    private val spec: AsrModelSpec = AsrModelSpec.DEFAULT,
) {
    private val appContext = context.applicationContext

    fun modelRoot(): File = File(appContext.filesDir, ROOT_DIR)

    fun targetDir(): File = File(modelRoot(), spec.installDirName)

    suspend fun install(onProgress: (String) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        val target = targetDir()
        val cached = AsrModelVerifier.verify(target, spec, deep = false)
        if (cached.ok) {
            onProgress("语音模型已就绪")
            return@withContext Result.success(target)
        }
        if (cached.reason != null && target.exists()) {
            // 目录在但不可信：删掉重装，避免 native 读到半个文件。
            Log.w(TAG, "重建语音模型目录：${cached.reason}")
            target.deleteRecursively()
        }

        val root = modelRoot()
        if (!root.exists() && !root.mkdirs()) {
            return@withContext Result.failure(
                AsrModelException(AsrErrorCode.MODEL_CORRUPTED, "无法创建语音模型目录"),
            )
        }
        // 模型约 71MB + 余量；空间不够时提前给出可读错误，而不是写到一半 ENOSPC。
        val required = spec.totalBytes + (32L * 1024 * 1024)
        if (root.usableSpace in 1 until required) {
            return@withContext Result.failure(
                AsrModelException(
                    AsrErrorCode.MODEL_CORRUPTED,
                    "存储空间不足：语音模型需要约 ${spec.totalBytes / 1024 / 1024} MB",
                ),
            )
        }

        val staging = File(root, "${spec.installDirName}.part-${System.currentTimeMillis()}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            return@withContext Result.failure(
                AsrModelException(AsrErrorCode.MODEL_CORRUPTED, "无法写入语音模型目录"),
            )
        }

        try {
            for (file in spec.files) {
                onProgress("正在释放语音模型 ${file.name}")
                copyAsset("${spec.assetDir}/${file.name}", File(staging, file.name), file)
            }
            onProgress("正在校验语音模型")
            File(staging, AsrModelVerifier.READY_MARKER).writeText(AsrModelVerifier.markerContent(spec))

            val verified = AsrModelVerifier.verify(staging, spec, deep = false)
            if (!verified.ok) {
                throw AsrModelException(
                    AsrErrorCode.MODEL_CORRUPTED,
                    "语音模型校验失败：${verified.reason}",
                )
            }

            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                // 同一分区一般能 rename；个别 ROM 的 filesDir 行为异常时退化成复制。
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
            onProgress("语音模型已就绪")
            Result.success(target)
        } catch (failure: AsrModelException) {
            staging.deleteRecursively()
            Result.failure(failure)
        } catch (failure: IOException) {
            staging.deleteRecursively()
            Result.failure(
                AsrModelException(AsrErrorCode.MODEL_CORRUPTED, "语音模型写入失败", failure),
            )
        }
    }

    private fun copyAsset(assetPath: String, target: File, expected: AsrModelFile) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input = try {
            appContext.assets.open(assetPath)
        } catch (missing: IOException) {
            throw AsrModelException(
                AsrErrorCode.MODEL_MISSING,
                "安装包缺少语音模型：请重新安装完整版本",
                missing,
            )
        }
        input.use { source ->
            FileOutputStream(target).use { sink ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                    total += read
                }
                sink.flush()
                runCatching { sink.fd.sync() }
            }
        }
        if (total != expected.bytes) {
            throw AsrModelException(
                AsrErrorCode.MODEL_CORRUPTED,
                "语音模型不完整：${expected.name}（$total/${expected.bytes} 字节）",
            )
        }
        val actual = digest.digest().toHex()
        if (!actual.equals(expected.sha256, ignoreCase = true)) {
            throw AsrModelException(
                AsrErrorCode.MODEL_CORRUPTED,
                "语音模型校验失败：${expected.name}",
            )
        }
    }

    private companion object {
        const val TAG = "AsrModelInstaller"
        const val ROOT_DIR = "asr"
    }
}
