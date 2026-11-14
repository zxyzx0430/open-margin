package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.dao.ProcessedNoticeDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.notification.CaptureHealth
import com.bookkeeping.app.notification.CaptureNotifier
import com.bookkeeping.app.notification.KeepAliveService
import com.bookkeeping.app.sync.TransactionSyncWorker
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bookkeeping.app.data.local.entity.ProcessedNoticeEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.CancellationException
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
    private val settings: SettingsPreferences,
    @ApplicationContext private val context: Context,
) {
    private val duplicateMutex = Mutex()

    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    fun observeDuplicateCandidates(): Flow<List<TransactionEntity>> =
        dao.observeDuplicateCandidates()

    fun observeBetween(startMs: Long, endMs: Long): Flow<List<TransactionEntity>> =
        dao.observeBetween(startMs, endMs)

    fun observeTotalExpenseBetween(startMs: Long, endMs: Long): Flow<Long> =
        dao.observeTotalExpenseBetween(startMs, endMs)

    fun observeTotalIncomeBetween(startMs: Long, endMs: Long): Flow<Long> =
        dao.observeTotalIncomeBetween(startMs, endMs)

    suspend fun insert(transaction: TransactionEntity, notifySuccess: Boolean = false): Long {
        val id = dao.insert(transaction)
        // Only a real insert reaches this point. Notification failures must
        // never affect the ledger or sync path.
        val possibleDuplicate = transaction.duplicateOfId != null ||
            transaction.remoteReviewStatus == "possible_duplicate"
        if (notifySuccess && id > 0L) {
            try {
                // Duplicate review is an important prompt, not a success
                // notification; it remains visible when success is disabled.
                if (possibleDuplicate || settings.successNotificationsEnabledNow()) {
                    CaptureNotifier.notifySaved(
                        context = context,
                        transaction = transaction.copy(id = id),
                        possibleDuplicate = possibleDuplicate,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // A notification failure must never affect the saved ledger.
            }
        }
        TransactionSyncWorker.requestImmediate(context)
        return id
    }

    // 自动抓取路径专用：8s 窗口内同来源同商户同金额视为高置信度重复，直接跳过。
    // 其他同金额/方向/币种、3 分钟内相近的记录只标注为“待确认重复”，
    // 仍然插入账本，绝不删除旧记录，由用户在交易页决定保留还是删除新记录。
    suspend fun insertIfNotDuplicate(
        transaction: TransactionEntity,
        windowMs: Long = 8_000L,
        notifySuccess: Boolean = true,
    ): Long? = duplicateMutex.withLock {
        val existing = dao.findRecentSameAmount(
            source = transaction.source,
            merchant = transaction.merchant,
            amountCents = transaction.amountCents,
            type = transaction.type,
            currency = transaction.currency,
            windowStartMs = transaction.timestamp - windowMs,
            windowEndMs = transaction.timestamp + windowMs,
        )
        if (existing != null) return@withLock null

        val similar = dao.findRecentSimilar(
            amountCents = transaction.amountCents,
            type = transaction.type,
            currency = transaction.currency,
            windowStartMs = transaction.timestamp - POSSIBLE_DUPLICATE_WINDOW_MS,
            windowEndMs = transaction.timestamp + POSSIBLE_DUPLICATE_WINDOW_MS,
        )
        val candidate = if (DuplicateReviewPolicy.shouldFlag(similar, transaction)) {
            transaction.copy(
                duplicateOfId = similar!!.id,
                remoteReviewStatus = "possible_duplicate",
            )
        } else {
            transaction
        }
        val id = insert(candidate, notifySuccess = notifySuccess)
        if (id > 0L) {
            CaptureHealth.onTransactionSaved(context)
            KeepAliveService.refreshStatus(context)
        }
        id
    }

    // 用户在交易页确认“两笔都是真的”：清除本地标记，并把状态同步为已确认。
    suspend fun keepBoth(transaction: TransactionEntity) {
        if (transaction.duplicateOfId == null &&
            transaction.remoteReviewStatus != "possible_duplicate") return
        update(transaction.copy(duplicateOfId = null, remoteReviewStatus = "confirmed"))
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

    companion object {
        const val POSSIBLE_DUPLICATE_WINDOW_MS: Long = DuplicateReviewPolicy.WINDOW_MS
    }
}
