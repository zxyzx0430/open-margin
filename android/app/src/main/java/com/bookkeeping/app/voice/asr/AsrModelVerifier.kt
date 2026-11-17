package com.bookkeeping.app.voice.asr

import java.io.File
import java.security.MessageDigest

/**
 * 模型目录校验。刻意不依赖 Android：单元测试可以直接在临时目录上跑。
 *
 * 目录必须满足：
 * 1. 每个文件存在且字节数等于 [AsrModelSpec] 里的期望值；
 * 2. 有 `.ready` 标记，且标记里 `version=` 与当前 spec 一致；
 * 3. deep=true 时再逐文件比对 sha256（安装时用；每次启动只做 1+2，省 71MB 的哈希时间）。
 */
object AsrModelVerifier {

    const val READY_MARKER = ".ready"

    data class Result(val ok: Boolean, val reason: String? = null) {
        companion object {
            val OK = Result(true)
            fun fail(reason: String) = Result(false, reason)
        }
    }

    /** `.ready` 的文件内容：版本 + 每个文件的哈希，便于事后排查装的是哪一版。 */
    fun markerContent(spec: AsrModelSpec): String = buildString {
        appendLine("version=${spec.version}")
        for (file in spec.files) {
            appendLine("${file.name}=${file.sha256}")
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun verify(
        dir: File,
        spec: AsrModelSpec,
        deep: Boolean = true,
    ): Result {
        if (!dir.isDirectory) return Result.fail("目录不存在：${dir.name}")

        for (file in spec.files) {
            val target = File(dir, file.name)
            if (!target.isFile) return Result.fail("缺少 ${file.name}")
            val actualBytes = target.length()
            if (actualBytes != file.bytes) {
                return Result.fail("${file.name} 字节数不符：期望 ${file.bytes}，实际 $actualBytes")
            }
            if (deep && !sha256(target).equals(file.sha256, ignoreCase = true)) {
                return Result.fail("${file.name} sha256 不符")
            }
        }

        val marker = File(dir, READY_MARKER)
        if (!marker.isFile) return Result.fail("缺少 ${READY_MARKER} 标记")
        val markerText = runCatching { marker.readText() }.getOrNull()
            ?: return Result.fail("${READY_MARKER} 不可读")
        if (!markerText.lineSequence().any { it.trim() == "version=${spec.version}" }) {
            return Result.fail("${READY_MARKER} 版本标记与 ${spec.version} 不一致")
        }
        return Result.OK
    }
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(digits[value ushr 4]).append(digits[value and 0x0F])
    }
    return out.toString()
}
