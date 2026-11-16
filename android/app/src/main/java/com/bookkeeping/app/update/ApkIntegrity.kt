package com.bookkeeping.app.update

import java.io.File
import java.security.MessageDigest

enum class ApkCheck {
    OK,
    MISSING,
    SIZE_MISMATCH,
    DIGEST_MISMATCH,
}

/**
 * Integrity gate before the installer is opened. The size always has to match
 * the Release API; the SHA-256 digest is checked when GitHub reports one.
 */
object ApkIntegrity {
    private const val CHUNK = 64 * 1024

    fun verify(file: File?, expectedSize: Long, expectedSha256: String?): ApkCheck {
        if (file == null || !file.isFile) return ApkCheck.MISSING
        if (expectedSize <= 0L || file.length() != expectedSize) return ApkCheck.SIZE_MISMATCH
        if (expectedSha256.isNullOrBlank()) return ApkCheck.OK
        val actual = sha256(file) ?: return ApkCheck.MISSING
        return if (actual.equals(expectedSha256, ignoreCase = true)) {
            ApkCheck.OK
        } else {
            ApkCheck.DIGEST_MISMATCH
        }
    }

    /** `sha256:<64 hex chars>` from the GitHub asset payload, or null when unusable. */
    fun normalizeSha256(digest: String?): String? {
        val value = digest?.trim().orEmpty()
        if (!value.startsWith("sha256:", ignoreCase = true)) return null
        val hex = value.substringAfter(':')
        if (hex.length != 64) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return hex.lowercase()
    }

    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
