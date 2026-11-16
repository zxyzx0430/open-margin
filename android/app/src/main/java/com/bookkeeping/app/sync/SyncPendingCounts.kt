package com.bookkeeping.app.sync

import android.content.SharedPreferences

/**
 * 待上传 与 冲突 是两件事，必须分开读。
 *
 * - uploads：还没推送到服务器的本地修改，正常现象，中性色。
 * - conflicts：同一笔在手机和服务器都被改过，需要用户逐笔决定，警告色。
 *
 * v1.2.0 及更早版本只写 `pending_sync_conflicts`，而且写的是「全部待推送修改」
 * （dao.pendingCount()）。升级后第一次同步完成前，这个值仍是旧的合并数字；
 * 此时按「待上传」处理，避免把自己的本地修改误报成冲突。
 */
internal data class PendingSyncCounts(
    val uploads: Int,
    val conflicts: Int,
)

internal fun readPendingCounts(prefs: SharedPreferences): PendingSyncCounts {
    val uploads = prefs.getInt(TransactionSyncWorker.PENDING_UPLOADS, -1)
    val conflicts = prefs.getInt(TransactionSyncWorker.PENDING_CONFLICTS, 0)
    return if (uploads < 0) {
        PendingSyncCounts(uploads = conflicts.coerceAtLeast(0), conflicts = 0)
    } else {
        PendingSyncCounts(uploads = uploads.coerceAtLeast(0), conflicts = conflicts.coerceAtLeast(0))
    }
}
