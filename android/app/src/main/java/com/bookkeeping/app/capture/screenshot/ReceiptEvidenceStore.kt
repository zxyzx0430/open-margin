package com.bookkeeping.app.capture.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 已入账扫描账单的本地原图归档仓库（`filesDir/receipt_evidence/`）。
 *
 * 与 `page_capture/` 的 7 天待确认临时队列彻底解耦：
 * - 文件名按原始图片内容 SHA-256 命名（`ev_<sha256>.webp`），一图多单天然去重；
 * - 写入时以 WebP 85% 质量压缩，常规手机截图约 40–80KB，文字清晰且节省空间；
 * - 支持 90 天 TTL + 100MB LRU 上限清理（永久保留模式下不做过期/配额淘汰），
 *   并始终清理不再被任何未删除账单引用的孤儿文件。
 */
class ReceiptEvidenceStore internal constructor(
    private val filesRoot: File,
    private val maxQuotaBytes: Long = DEFAULT_MAX_QUOTA_BYTES,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val dir = File(filesRoot, DIR_NAME)

    fun fileFor(imageName: String?): File? {
        val clean = sanitizeFileName(imageName) ?: return null
        return File(dir, clean)
    }

    fun readablePath(imageName: String?): String? {
        val file = fileFor(imageName) ?: return null
        return if (file.isFile && file.length() > 0L) file.absolutePath else null
    }

    /**
     * 将待确认截图文件归档为已入账账单的本地凭证。
     * 若源文件不可读，返回 null，绝不阻断记账主流程。
     */
    fun archiveFromPath(sourcePath: String?): String? {
        val path = sourcePath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val source = File(path)
        if (!source.isFile || source.length() <= 0L) return null
        val rawBytes = runCatching { source.readBytes() }.getOrNull() ?: return null
        if (rawBytes.isEmpty()) return null
        return archiveBytes(rawBytes)
    }

    /**
     * 将图片字节归档至 `receipt_evidence/ev_<sha256>.webp`。
     * 在非 Android JVM 环境下若无法编码 WebP，会优雅降级为原始字节保存（方便单测）。
     */
    fun archiveBytes(rawBytes: ByteArray): String? {
        if (rawBytes.isEmpty()) return null
        return synchronized(lock) {
            if (!dir.exists() && !dir.mkdirs()) return@synchronized null
            val name = "ev_${sha256(rawBytes)}.webp"
            val target = File(dir, name)
            if (target.isFile && target.length() > 0L) {
                target.setLastModified(System.currentTimeMillis())
                return@synchronized name
            }
            val payload = compressToWebpOrRaw(rawBytes)
            val tmp = File(dir, "$name.tmp")
            val written = runCatching {
                FileOutputStream(tmp).use { out ->
                    out.write(payload)
                    out.flush()
                }
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }.getOrDefault(false)
            if (!written) {
                runCatching { tmp.delete() }
                return@synchronized null
            }
            name
        }
    }

    /** 删除单份凭证文件（调用方需先确认 Room 中无其他未删除交易引用）。 */
    fun deleteIfPresent(imageName: String?): Boolean {
        val file = fileFor(imageName) ?: return false
        return synchronized(lock) {
            if (!file.exists()) false else runCatching { file.delete() }.getOrDefault(false)
        }
    }

    /** 清理全部本地归档原图（用于设置页一键清理缓存）。 */
    fun clearAll(): Int = synchronized(lock) {
        val files = dir.listFiles() ?: return@synchronized 0
        var removed = 0
        files.forEach { file ->
            if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) {
                removed++
            }
        }
        removed
    }

    /** 计算当前归档目录占用的字节数。 */
    fun totalSizeBytes(): Long = synchronized(lock) {
        val files = dir.listFiles() ?: return@synchronized 0L
        files.filter { it.isFile && isEvidenceFile(it.name) }.sumOf { it.length() }
    }

    /**
     * 按保留策略与配额清理凭证文件，返回本次删除的文件名集合（便于调用方按需刷新）。
     *
     * 1. 孤儿文件（不在 [activeImageNames] 中）一律清理；
     * 2. 若 [retentionMode] 为 [ReceiptEvidenceSettings.RetentionMode.NINETY_DAYS]：
     *    清理超过 90 天的文件，并按最后修改时间从旧到新淘汰直至总容量 <= [maxQuotaBytes]。
     */
    fun prune(
        activeImageNames: Set<String>,
        retentionMode: ReceiptEvidenceSettings.RetentionMode,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> = synchronized(lock) {
        val files = dir.listFiles()?.filter { it.isFile && isEvidenceFile(it.name) }
            ?: return@synchronized emptyList()
        val entries = files.map { file ->
            EvidenceFileEntry(
                name = file.name,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified(),
            )
        }
        val toDelete = planPrune(
            files = entries,
            activeImageNames = activeImageNames,
            retentionMode = retentionMode,
            nowMs = nowMs,
            retentionMs = retentionMs,
            maxQuotaBytes = maxQuotaBytes,
        )
        val deleted = mutableListOf<String>()
        toDelete.forEach { name ->
            val file = File(dir, name)
            if (runCatching { file.delete() }.getOrDefault(false)) {
                deleted += name
            }
        }
        deleted
    }

    @Suppress("DEPRECATION")
    private fun compressToWebpOrRaw(rawBytes: ByteArray): ByteArray {
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
        }.getOrNull() ?: return rawBytes
        return try {
            val out = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            val ok = bitmap.compress(format, WEBP_QUALITY, out)
            if (ok && out.size() > 0) out.toByteArray() else rawBytes
        } catch (_: Throwable) {
            rawBytes
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    data class EvidenceFileEntry(
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
    )

    companion object {
        const val DIR_NAME = "receipt_evidence"
        const val DEFAULT_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
        const val DEFAULT_MAX_QUOTA_BYTES = 100L * 1024 * 1024
        private const val WEBP_QUALITY = 85
        private val lock = Any()
        private val VALID_NAME = Regex("^ev_[a-f0-9]{64}\\.webp$")

        fun sanitizeFileName(name: String?): String? {
            val trimmed = name?.trim() ?: return null
            return trimmed.takeIf { VALID_NAME.matches(it) }
        }

        private fun isEvidenceFile(name: String): Boolean = VALID_NAME.matches(name)

        /** 纯逻辑淘汰规划，供单测直接验证孤儿、90 天 TTL 与 100MB LRU 规则。 */
        fun planPrune(
            files: List<EvidenceFileEntry>,
            activeImageNames: Set<String>,
            retentionMode: ReceiptEvidenceSettings.RetentionMode,
            nowMs: Long,
            retentionMs: Long = DEFAULT_RETENTION_MS,
            maxQuotaBytes: Long = DEFAULT_MAX_QUOTA_BYTES,
        ): List<String> {
            val toDelete = linkedSetOf<String>()
            // 1. 孤儿文件（没有任何未删除账单引用）
            files.forEach { entry ->
                if (entry.name !in activeImageNames) {
                    toDelete += entry.name
                }
            }
            if (retentionMode == ReceiptEvidenceSettings.RetentionMode.FOREVER) {
                return toDelete.toList()
            }
            // 2. 超过保留期
            val remaining = mutableListOf<EvidenceFileEntry>()
            val cutoff = nowMs - retentionMs
            files.forEach { entry ->
                if (entry.name in toDelete) return@forEach
                if (entry.lastModifiedMs <= cutoff) {
                    toDelete += entry.name
                } else {
                    remaining += entry
                }
            }
            // 3. 超出配额时按最旧优先淘汰
            var totalBytes = remaining.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            if (totalBytes > maxQuotaBytes) {
                for (entry in remaining.sortedBy { it.lastModifiedMs }) {
                    if (totalBytes <= maxQuotaBytes) break
                    toDelete += entry.name
                    totalBytes -= entry.sizeBytes.coerceAtLeast(0L)
                }
            }
            return toDelete.toList()
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
