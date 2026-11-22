package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// 已处理通知指纹（蓝图 D05）：持久保存平台订单身份 claim 与通知记录链接。
// 通知 key 只作为记录链接；订单身份指纹才可证明同一笔交易。只存哈希，不存原文。
@Entity(tableName = "processed_notices")
data class ProcessedNoticeEntity(
    @PrimaryKey val fingerprint: String,
    /** 来源通道标识（通知为包名，短信为 SMS，后续页面采集为 PAGE）。 */
    val source: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val seenCount: Int,
    /** First-writer ledger link; a reused notification key must never overwrite it. */
    val transactionId: Long? = null,
)
