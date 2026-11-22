package com.bookkeeping.app.notification

import java.security.MessageDigest

// 通知指纹（蓝图 M5/D05）：把通知记录 key 或平台订单号压成 SHA-256 指纹，
// 用于持久化处理记录。只存哈希，不存通知原文（PII）。通知记录 key 本身不是交易 ID。
// Pure logic on java.security only — JVM-testable, Android-safe.
//
// 内容与时间组成的旧指纹只用于识别升级前遗留的未关联行，不能作为新的事件 ID：
// 两条不同通知可以有相同可见内容和时间，通知正文也可能在同一事件内刷新。
object NoticeFingerprint {

    /** Fingerprint for a notification record key such as StatusBarNotification.key. */
    fun forSourceEvent(packageName: String, sourceEventId: String?): String? {
        val eventId = sourceEventId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        // Retain the namespace so already stored notification-record links remain readable.
        return hash(listOf("source-event-v2", packageName.trim(), eventId))
    }

    /** Strong transaction identity: an exact order ID scoped to its platform. */
    fun forPlatformTransaction(platform: String, transactionId: String?): String? {
        val normalizedPlatform = platform.trim().uppercase(java.util.Locale.ROOT)
            .takeIf(String::isNotEmpty) ?: return null
        val exactId = transactionId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return hash(listOf("platform-transaction-v1", normalizedPlatform, exactId))
    }

    /**
     * Weak content tuple used only to find an unlinked pre-event-ID fingerprint.
     * A match is ambiguous and must be surfaced for review, never treated as booked.
     */
    fun of(packageName: String, title: String?, body: String?, originalTimeMs: Long): String {
        val material = listOf(
            packageName.trim(),
            normalize(title),
            normalize(body),
            originalTimeMs.toString(),
        )
        return hash(material)
    }

    private fun hash(fields: List<String>): String {
        val material = fields.joinToString(separator = "\u001F")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // 空白折叠 + 英文字母小写化：同一通知在不同投递路径下可能有多余空白，
    // 不应因此被当成两条证据。中文不受 lowercase 影响。
    // 用 Locale.ROOT 保证指纹与系统语言无关（土耳其语 i/I 规则会改变哈希）。
    private fun normalize(s: String?): String =
        s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(java.util.Locale.ROOT).orEmpty()
}
