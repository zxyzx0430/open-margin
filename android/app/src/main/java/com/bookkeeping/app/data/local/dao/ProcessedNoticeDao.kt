package com.bookkeeping.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bookkeeping.app.data.local.entity.ProcessedNoticeEntity

// 已处理通知指纹的存取（蓝图 D05）。
@Dao
interface ProcessedNoticeDao {

    // 已存在则忽略并返回 -1，调用方据此判定"这条通知见过"。
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(notice: ProcessedNoticeEntity): Long

    @Query("SELECT * FROM processed_notices WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun find(fingerprint: String): ProcessedNoticeEntity?

    @Query("UPDATE processed_notices SET lastSeenMs = :seenMs, seenCount = seenCount + 1 WHERE fingerprint = :fingerprint")
    suspend fun touch(fingerprint: String, seenMs: Long)

    // 指纹表只用于近窗口重放检测，老记录定期清理，避免无限增长。
    @Query("DELETE FROM processed_notices WHERE lastSeenMs < :beforeMs")
    suspend fun prune(beforeMs: Long): Int
}
