package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.dao.ProcessedNoticeDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.sync.TransactionSyncWorker
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bookkeeping.app.data.local.entity.ProcessedNoticeEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

// Repository：业务层与数据层的桥梁。
// 上层（ViewModel）只跟 Repository 打交道，不直接接触 DAO/网络/SharedPreferences。
// 好处：将来加缓存、加远程同步、切换数据源都只改这里，不动 UI。
// 嵌入式类比：HAL 层封装了寄存器操作，上层应用代码不直接读寄存器。
@Singleton
class TransactionRepository @Inject constructor(
    private val dao: TransactionDao,
    private val processedNoticeDao: ProcessedNoticeDao,
    @ApplicationContext private val context: Context,
) {
    private val duplicateMutex = Mutex()

    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    fun observeBetween(startMs: Long, endMs: Long): Flow<List<TransactionEntity>> =
        dao.observeBetween(startMs, endMs)

    fun observeTotalExpenseBetween(startMs: Long, endMs: Long): Flow<Long> =
        dao.observeTotalExpenseBetween(startMs, endMs)

    fun observeTotalIncomeBetween(startMs: Long, endMs: Long): Flow<Long> =
        dao.observeTotalIncomeBetween(startMs, endMs)

    suspend fun insert(transaction: TransactionEntity): Long {
        val id = dao.insert(transaction)
        TransactionSyncWorker.requestImmediate(context)
        return id
    }

    // 自动抓取路径专用：8s 窗口内同来源同商户同金额视为重复，跳过入库。
    // 返回 null 表示重复，返回 Long 表示新插入的 id。
    suspend fun insertIfNotDuplicate(
        transaction: TransactionEntity,
        windowMs: Long = 8_000L,
    ): Long? = duplicateMutex.withLock {
        val existing = dao.findRecentSameAmount(
            source = transaction.source,
            merchant = transaction.merchant,
            amountCents = transaction.amountCents,
            windowStartMs = transaction.timestamp - windowMs,
            windowEndMs = transaction.timestamp + windowMs,
        )
        if (existing != null) null else insert(transaction)
    }

    // D05 影子模式：持久记录通知指纹，返回 true 表示这条证据以前处理过
    // （疑似重放）。当前阶段只记录与计数，不改变 insertIfNotDuplicate 的
    // 入库决策；跨渠道拦截要等 D06 双端核验通过后才启用（蓝图铁律）。
    // Shadow only: never blocks an insert yet.
    suspend fun recordNoticeFingerprint(
        fingerprint: String,
        source: String,
        seenMs: Long,
    ): Boolean = duplicateMutex.withLock {
        val inserted = processedNoticeDao.insertIgnore(
            ProcessedNoticeEntity(
                fingerprint = fingerprint,
                source = source,
                firstSeenMs = seenMs,
                lastSeenMs = seenMs,
                seenCount = 1,
            )
        )
        if (inserted == -1L) {
            processedNoticeDao.touch(fingerprint, seenMs)
            true
        } else {
            false
        }
    }

    suspend fun update(transaction: TransactionEntity) {
        dao.update(transaction.copy(syncDirty = true, syncModifiedMs = System.currentTimeMillis(),
                                    syncMutationId = UUID.randomUUID().toString()))
        TransactionSyncWorker.requestImmediate(context)
    }

    suspend fun delete(transaction: TransactionEntity) {
        // Tombstone remains until the server acknowledges deletion; no stale
        // offline device may resurrect it on the next network reconnect.
        dao.update(transaction.copy(syncDeleted = true, syncDirty = true,
                                    syncModifiedMs = System.currentTimeMillis(),
                                    syncMutationId = UUID.randomUUID().toString()))
        TransactionSyncWorker.requestImmediate(context)
    }
}
