package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// 已处理通知指纹（蓝图 D05）：持久保存，进程/设备重启后仍能识别
// 同一条通知的重放，避免重复记账。只存哈希指纹与计数，不存通知原文。
@Entity(tableName = "processed_notices")
data class ProcessedNoticeEntity(
    @PrimaryKey val fingerprint: String,
    /** 来源通道标识（通知为包名，短信为 SMS，后续页面采集为 PAGE）。 */
    val source: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val seenCount: Int,
    /** 关联的账目 id；影子模式下可为空。 */
    val transactionId: Long? = null,
)
