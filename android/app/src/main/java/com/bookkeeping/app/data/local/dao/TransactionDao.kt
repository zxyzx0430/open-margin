package com.bookkeeping.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bookkeeping.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

// DAO = Data Access Object
// 这是 Room 的"接口模板"，写出 SQL 注解，Room 自动生成实现类。
// 嵌入式类比：声明寄存器读写接口，Room 帮你生成具体的访问代码。
@Dao
interface TransactionDao {

    // 插入。如果 id 冲突就替换。返回新插入的 id
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    /** Room runs a collection insert in one transaction; a failure rolls back the batch. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Update
    suspend fun updateAll(transactions: List<TransactionEntity>)

    /**
     * One official-bill confirmation: insert new rows and enrich existing
     * duplicates in a single Room transaction. A failure rolls back both.
     */
    @Transaction
    suspend fun importBatch(
        inserts: List<TransactionEntity>,
        updates: List<TransactionEntity>,
    ): List<Long> {
        val ids = if (inserts.isEmpty()) emptyList() else insertAll(inserts)
        if (updates.isNotEmpty()) updateAll(updates)
        return ids
    }

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    // 查所有交易，按时间倒序。
    // Flow<List<...>> 表示"响应式":数据库变了，订阅者自动收到新列表。
    // 嵌入式类比：寄存器变化时自动触发中断给上层。
    @Query("SELECT * FROM transactions WHERE syncDeleted = 0 ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE syncDeleted = 0 AND category = 'OTHER'")
    suspend fun currentOther(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE syncDeleted = 0")
    suspend fun currentActive(): List<TransactionEntity>

    // 查指定时间范围内的（用于"本月"统计等）
    @Query("SELECT * FROM transactions WHERE syncDeleted = 0 AND timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    fun observeBetween(startMs: Long, endMs: Long): Flow<List<TransactionEntity>>

    // 统计某段时间内的总支出（单位:分）。COALESCE 处理"没数据时返回 0 而不是 null"
    @Query("""SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE type = 'EXPENSE' AND currency = 'CNY' AND syncDeleted = 0
              AND remoteReviewStatus NOT IN ('excluded','transfer','refund','possible_duplicate')
              AND duplicateOfId IS NULL
              AND category <> 'TRANSFER' AND timestamp >= :startMs AND timestamp < :endMs""")
    fun observeTotalExpenseBetween(startMs: Long, endMs: Long): Flow<Long>

    // Total income (cents). Product policy: every real CNY INCOME row counts,
    // including transfer-status income and TRANSFER/GIFT categories (incoming
    // transfers and red packets). Only excluded/refund/duplicate rows and
    // foreign-currency rows are omitted. Keep this in sync with
    // StatsTransactionPolicy so the ledger header, stats and budget agree.
    @Query("""SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE type = 'INCOME' AND currency = 'CNY' AND syncDeleted = 0
              AND remoteReviewStatus NOT IN ('excluded','refund','possible_duplicate','duplicate')
              AND duplicateOfId IS NULL
              AND timestamp >= :startMs AND timestamp < :endMs""")
    fun observeTotalIncomeBetween(startMs: Long, endMs: Long): Flow<Long>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    // Legacy v1 uploader handles untouched rows only. A newly captured record
    // edited/deleted before its first upload belongs to the versioned v2
    // mutation queue; uploading its old snapshot through v1 first would
    // create a false base-revision conflict and potentially discard edits.
    @Query("""SELECT * FROM transactions WHERE id > :lastId AND remoteEventId IS NULL
        AND syncDeleted = 0 AND syncDirty = 0 ORDER BY id ASC LIMIT :count""")
    suspend fun getUnsynced(lastId: Long, count: Int): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE remoteEventId = :eventId LIMIT 1")
    suspend fun getByRemoteEventId(eventId: String): TransactionEntity?

    @Query("""SELECT * FROM transactions
        WHERE syncDeleted = 0 AND source = :source AND sourceTransactionId = :sourceTransactionId
        LIMIT 1""")
    suspend fun findBySourceTransactionId(
        source: String,
        sourceTransactionId: String,
    ): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE syncDirty = 1 ORDER BY syncModifiedMs, id LIMIT :count OFFSET :offset")
    suspend fun getPendingMutations(count: Int, offset: Int = 0): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE syncDirty = 1 ORDER BY syncModifiedMs DESC, id DESC LIMIT 100")
    fun observePendingMutations(): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE syncDirty = 1")
    suspend fun pendingCount(): Int

    // One-time audit after upgrading from the floating-point parser.
    // No raw notification text is uploaded; this check runs only on-device.
    @Query("SELECT * FROM transactions WHERE source IN ('WECHAT','ALIPAY','BANK_SMS') AND note != '' ORDER BY id DESC LIMIT 2000")
    suspend fun getAutoCapturedForRoundingAudit(): List<TransactionEntity>

    /** 截图候选去重的只读粗筛：最近的正式账单（截图侧再按订单号/金额/时间精确比对）。 */
    @Query("SELECT * FROM transactions WHERE syncDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentForScreenshotDedup(limit: Int): List<TransactionEntity>

    /**
     * 手工记账保存前的疑似重复候选：同金额、同方向、同币种，时间在粗筛窗口内
     * （最宽 12 小时，按时间精度分档的收窄交给纯逻辑）。
     *
     * 只读查询，不动表结构（timestamp 已有索引）。商户相似度、10 分钟 / 1 小时 /
     * 12 小时窗口这些判定放在纯逻辑
     * [com.bookkeeping.app.ui.screen.transactions.DuplicateRule] 里，这里只做粗筛，
     * 避免把全库拉进内存。
     *
     * 方向与币种必须在 SQL 里过滤：36.5 的退款和 36.5 的支出、USD 36.5 和 CNY 36.5
     * 不是同一笔，靠金额粗筛会把它们混进候选里互相误报。
     *
     * 已隐藏的重复副本（duplicateOfId 非空）和不计入统计的记录不参与，
     * 否则用户会被一条列表里根本看不到的旧记录拦下来。
     */
    @Query("""
        SELECT * FROM transactions
        WHERE syncDeleted = 0 AND amountCents = :amountCents
          AND type = :type AND currency = :currency
          AND (remoteReviewStatus IS NULL OR remoteReviewStatus <> 'excluded')
          AND duplicateOfId IS NULL
          AND timestamp >= :windowStartMs AND timestamp <= :windowEndMs
        ORDER BY timestamp DESC
        LIMIT 100
    """)
    suspend fun findAmountCandidatesAround(
        amountCents: Long,
        type: String,
        currency: String,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<TransactionEntity>

    // 不确定是否重复：同金额/方向/币种、时间接近，但来源或商户可能不同。
    // 命中后只打本地待确认标记，不删除、不阻止入库。
    @Query("""
        SELECT * FROM transactions
        WHERE syncDeleted = 0 AND amountCents = :amountCents AND type = :type
          AND currency = :currency AND timestamp >= :windowStartMs AND timestamp <= :windowEndMs
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun findRecentSimilar(
        amountCents: Long,
        type: String,
        currency: String,
        windowStartMs: Long,
        windowEndMs: Long,
    ): TransactionEntity?

    @Query("""
        SELECT * FROM transactions
        WHERE syncDeleted = 0 AND amountCents = :amountCents AND type = :type
          AND currency = :currency AND timestamp >= :windowStartMs AND timestamp <= :windowEndMs
        ORDER BY id ASC
    """)
    suspend fun findRecentByAmount(
        amountCents: Long,
        type: String,
        currency: String,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<TransactionEntity>

    // 一次性纠正用：所有还挂着「待确认重复」标记的记录（含只有 duplicateOfId 的旧数据）。
    // 自动合并后留下的那条是 excluded + duplicateOfId，不算待确认。
    @Query("""SELECT * FROM transactions WHERE syncDeleted = 0
              AND (remoteReviewStatus = 'possible_duplicate'
                   OR (duplicateOfId IS NOT NULL
                       AND (remoteReviewStatus IS NULL OR remoteReviewStatus <> 'excluded')))
              ORDER BY timestamp ASC""")
    suspend fun findPossibleDuplicates(): List<TransactionEntity>

    @Query("""SELECT * FROM transactions WHERE syncDeleted = 0
              AND (remoteReviewStatus = 'possible_duplicate'
                   OR (duplicateOfId IS NOT NULL
                       AND (remoteReviewStatus IS NULL OR remoteReviewStatus <> 'excluded')))
              ORDER BY timestamp DESC LIMIT 20""")
    fun observeDuplicateCandidates(): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions
        WHERE syncDeleted = 0
          AND (:query = '' OR merchant LIKE '%' || :query || '%'
               OR note LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%')
          AND (:type IS NULL OR type = :type)
          AND (:category IS NULL OR category = :category)
          AND (:startMs IS NULL OR timestamp >= :startMs)
          AND (:endMs IS NULL OR timestamp < :endMs)
        ORDER BY timestamp DESC
    """)
    fun observeFiltered(
        query: String,
        type: String?,
        category: String?,
        startMs: Long?,
        endMs: Long?,
    ): Flow<List<TransactionEntity>>

    /**
     * 「商户分类记忆 → 添加规则」的候选来源：最近出现过的商户名，按最后一次出现时间倒序。
     * 只读查询，不动表结构；GROUP BY 让 DISTINCT 和 ORDER BY MAX(timestamp) 在同一句里成立。
     */
    @Query("""SELECT merchant FROM transactions
              WHERE syncDeleted = 0 AND TRIM(merchant) <> ''
              GROUP BY merchant
              ORDER BY MAX(timestamp) DESC
              LIMIT :limit""")
    suspend fun recentMerchants(limit: Int): List<String>

    @Query("""SELECT COUNT(*) FROM transactions
              WHERE syncDeleted = 0 AND evidenceImageName = :imageName""")
    suspend fun countActiveByEvidenceImageName(imageName: String): Int

    @Query("""SELECT DISTINCT evidenceImageName FROM transactions
              WHERE syncDeleted = 0 AND evidenceImageName IS NOT NULL AND evidenceImageName <> ''""")
    suspend fun activeEvidenceImageNames(): List<String>

    @Query("UPDATE transactions SET evidenceImageName = NULL WHERE evidenceImageName IS NOT NULL")
    suspend fun clearAllEvidenceImageNames()

    @Query("SELECT COUNT(*) FROM transactions WHERE syncDeleted = 0 AND category = :category")
    suspend fun countByCategory(category: String): Int

    @Query("""UPDATE transactions
              SET category = :targetCategory,
                  syncDirty = 1,
                  syncModifiedMs = :nowMs,
                  syncMutationId = :mutationId
              WHERE syncDeleted = 0 AND category = :sourceCategory""")
    suspend fun reassignCategory(
        sourceCategory: String,
        targetCategory: String,
        nowMs: Long,
        mutationId: String,
    ): Int
}
