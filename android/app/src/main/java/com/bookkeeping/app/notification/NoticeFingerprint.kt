package com.bookkeeping.app.notification

import java.security.MessageDigest

// 通知指纹（蓝图 M5/D05）：把一条通知证据压成稳定的 SHA-256 指纹，
// 用于持久化"已处理"记录与重放检测。只存哈希，不存通知原文（PII）。
// Pure logic on java.security only — JVM-testable, Android-safe.
//
// 同一通知被系统重发（MessagingStyle 重组、监听器重启重放）时，
// 只要包名/标题/正文/原始时间一致，指纹就一致 → 可判定为同一条证据。
// 交易层面的"同一笔钱"判断不在这里做，那是 DuplicateResolver 的事。
object NoticeFingerprint {

    fun of(packageName: String, title: String?, body: String?, originalTimeMs: Long): String {
        val material = listOf(
            packageName.trim(),
            normalize(title),
            normalize(body),
            originalTimeMs.toString(),
        ).joinToString(separator = "\u001F")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // 空白折叠 + 英文字母小写化：同一通知在不同投递路径下可能有多余空白，
    // 不应因此被当成两条证据。中文不受 lowercase 影响。
    private fun normalize(s: String?): String =
        s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase().orEmpty()
}
