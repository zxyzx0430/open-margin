package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.dao.ProcessedNoticeDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
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
    private val diagnostics: TransactionDiagnostics,
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

    suspend fun insert(
        transaction: TransactionEntity,
        notifySuccess: Boolean = false,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
    ): Long {
        val operationStage = if (transaction.source == "MANUAL") {
            TransactionDiagnostics.STAGE_ENTRY
        } else {
            TransactionDiagnostics.STAGE_CAPTURE
        }
        if (operationStage == TransactionDiagnostics.STAGE_ENTRY) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = operationStage,
                result = TransactionDiagnostics.RESULT_STARTED,
                details = "Transaction save requested",
            )
        }
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_STARTED,
        )
        val id = try {
            dao.insert(transaction)
        } catch (failure: Exception) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_SAVE,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
            )
            if (operationStage == TransactionDiagnostics.STAGE_ENTRY) {
                diagnostics.record(
                    attemptId = diagnosticInput.attemptId,
                    stage = operationStage,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = diagnosticError(failure),
                )
            }
            throw failure
        }
        val saved = transaction.copy(id = id)
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            details = "Transaction row inserted",
            transactionId = id,
        )
        if (operationStage == TransactionDiagnostics.STAGE_ENTRY) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = operationStage,
                result = TransactionDiagnostics.RESULT_SUCCESS,
                transactionId = id,
            )
        }
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
                        transaction = saved,
                        possibleDuplicate = possibleDuplicate,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                diagnostics.record(
                    attemptId = diagnosticInput.attemptId,
                    stage = operationStage,
                    result = TransactionDiagnostics.RESULT_WARNING,
                    details = "Transaction saved but success notification failed",
                    transactionId = id,
                )
            }
        }
        requestSync(diagnosticInput, saved)
        return id
    }

    // 自动抓取路径专用。
    // 1) 同一订单号已经在库里 → 直接跳过（同一笔被重复上报）。
    // 2) 8s 内同来源同商户同金额 → 跳过（同一通道重发）。
    // 3) 120s 内同来源同金额，且商户/订单号互证 → 自动合并进先入库的那条：
    //    不插新行、不弹「待确认重复」，把新记录里更全的信息写回旧行。
    // 4) 其余同金额/方向/币种、3 分钟内相近的记录只标注为「待确认重复」，
    //    仍然插入账本，绝不删除旧记录，由用户在交易页决定保留还是删除新记录。
    /** Recent ledger rows for screenshot cross-source de-duplication (read-only). */
    suspend fun screenshotDedupRows(limit: Int = 500): List<TransactionEntity> =
        dao.recentForScreenshotDedup(limit)

    suspend fun insertIfNotDuplicate(
        transaction: TransactionEntity,
        windowMs: Long = 8_000L,
        notifySuccess: Boolean = true,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
    ): Long? = duplicateMutex.withLock {
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_DEDUP,
            result = TransactionDiagnostics.RESULT_STARTED,
        )
        transaction.sourceTransactionId?.let { orderId ->
            val matched = try {
                dao.findBySourceTransactionId(transaction.source, orderId)
            } catch (failure: Exception) {
                diagnostics.record(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_DEDUP,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = diagnosticError(failure),
                )
                throw failure
            }
            if (matched != null) {
                diagnostics.record(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_DEDUP,
                    result = TransactionDiagnostics.RESULT_WARNING,
                    details = "Existing source transaction ID; candidate not inserted",
                    transactionId = matched.id,
                )
                return@withLock null
            }
        }
        val existing = try {
            dao.findRecentSameAmount(
                source = transaction.source,
                merchant = transaction.merchant,
                amountCents = transaction.amountCents,
                type = transaction.type,
                currency = transaction.currency,
                windowStartMs = transaction.timestamp - windowMs,
                windowEndMs = transaction.timestamp + windowMs,
            )
        } catch (failure: Exception) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
            )
            throw failure
        }
        if (existing != null) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_WARNING,
                details = "Exact duplicate candidate skipped",
                transactionId = existing.id,
            )
            return@withLock null
        }

        // 同一笔支付被两条采集路径各扫一次（通知文本缺商户、支付成功页有商户）：
        // 合成一条，写回先入库的行，不再让用户确认。
        val mergeTarget = try {
            findAutoMergeTarget(transaction)
        } catch (failure: Exception) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
            )
            throw failure
        }
        if (mergeTarget != null) {
            val merged = CaptureMergePolicy.mergeInto(mergeTarget, transaction)
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_WARNING,
                details = "Candidate merged into existing transaction",
                transactionId = merged.id,
            )
            update(merged, diagnosticInput, operationStage = TransactionDiagnostics.STAGE_CAPTURE)
            CaptureHealth.onTransactionSaved(context)
            KeepAliveService.refreshStatus(context)
            if (notifySuccess) {
                try {
                    if (settings.successNotificationsEnabledNow()) {
                        CaptureNotifier.notifySaved(
                            context = context,
                            transaction = merged,
                            possibleDuplicate = false,
                            merged = true,
                        )
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    diagnostics.record(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_CAPTURE,
                        result = TransactionDiagnostics.RESULT_WARNING,
                        details = "Merged transaction notification failed",
                        transactionId = merged.id,
                    )
                }
            }
            return@withLock merged.id
        }

        val similar = try {
            dao.findRecentSimilar(
                amountCents = transaction.amountCents,
                type = transaction.type,
                currency = transaction.currency,
                windowStartMs = transaction.timestamp - POSSIBLE_DUPLICATE_WINDOW_MS,
                windowEndMs = transaction.timestamp + POSSIBLE_DUPLICATE_WINDOW_MS,
            )
        } catch (failure: Exception) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
            )
            throw failure
        }
        val candidate = if (DuplicateReviewPolicy.shouldFlag(similar, transaction)) {
            transaction.copy(
                duplicateOfId = similar!!.id,
                remoteReviewStatus = "possible_duplicate",
            )
        } else {
            transaction
        }
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_DEDUP,
            result = if (candidate.duplicateOfId != null) {
                TransactionDiagnostics.RESULT_WARNING
            } else {
                TransactionDiagnostics.RESULT_SUCCESS
            },
            details = if (candidate.duplicateOfId != null) {
                "Possible duplicate retained for review"
            } else {
                "No duplicate found"
            },
        )
        val id = insert(candidate, notifySuccess = notifySuccess, diagnosticInput = diagnosticInput)
        if (id > 0L) {
            CaptureHealth.onTransactionSaved(context)
            KeepAliveService.refreshStatus(context)
        }
        id
    }

    /** 自动合并的目标：窗口内证据最充分的同来源记录，优先 id 最小（先入库）的那条。 */
    private suspend fun findAutoMergeTarget(transaction: TransactionEntity): TransactionEntity? =
        CaptureMergePolicy.findMergeTarget(
            candidates = dao.findRecentByAmount(
                amountCents = transaction.amountCents,
                type = transaction.type,
                currency = transaction.currency,
                windowStartMs = transaction.timestamp - CaptureMergePolicy.AUTO_MERGE_WINDOW_MS,
                windowEndMs = transaction.timestamp + CaptureMergePolicy.AUTO_MERGE_WINDOW_MS,
            ),
            incoming = transaction,
        )

    /**
     * 一次性本地纠正：修掉升级前已经各插一条的重复对（通知缺商户 + 支付成功页有商户）。
     *
     * 扫描仍带「待确认重复」标记的记录，满足 [CaptureMergePolicy.isSamePayment] 的
     * 按同一规则合并：保留信息更全的那条并补全字段，穷的那条改成 excluded +
     * duplicateOfId 指向保留行（列表默认不显示），不做物理删除。
     *
     * 纯本地写：不调用 [update]，因此不会把这次纠正推给服务器；只在 prefs 里
     * 记一个版本标记，保证只跑一次。不满足条件的重复对仍然留在人工确认卡片里。
     *
     * @return 实际合并的重复对数量。
     */
    suspend fun repairCapturedDuplicatesOnce(): Int = duplicateMutex.withLock {
        if (settings.captureDuplicateRepairDone()) return@withLock 0
        var mergedPairs = 0
        for (flagged in dao.findPossibleDuplicates()) {
            // Re-read inside the loop: an earlier pair may have consumed this row.
            val current = dao.getById(flagged.id) ?: continue
            if (!CaptureMergePolicy.canReceiveMerge(current)) continue
            val originalId = current.duplicateOfId ?: continue
            val original = dao.getById(originalId) ?: continue
            val plan = CaptureMergePolicy.planRepair(current, original) ?: continue
            dao.update(plan.keep)
            dao.update(plan.absorbed)
            mergedPairs++
        }
        settings.markCaptureDuplicateRepairDone()
        mergedPairs
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

    suspend fun update(
        transaction: TransactionEntity,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
        operationStage: String = TransactionDiagnostics.STAGE_EDIT,
    ) {
        val updated = transaction.copy(
            syncDirty = true,
            syncModifiedMs = System.currentTimeMillis(),
            syncMutationId = UUID.randomUUID().toString(),
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = operationStage,
            result = TransactionDiagnostics.RESULT_STARTED,
            details = "Transaction edit requested",
            transactionId = transaction.id,
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_STARTED,
            transactionId = transaction.id,
        )
        try {
            dao.update(updated)
        } catch (failure: Exception) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_SAVE,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
                transactionId = transaction.id,
            )
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = operationStage,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
                transactionId = transaction.id,
            )
            throw failure
        }
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            details = "Transaction row updated",
            transactionId = transaction.id,
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = operationStage,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            transactionId = transaction.id,
        )
        requestSync(diagnosticInput, updated)
    }

    suspend fun recordDiagnostic(
        attemptId: String,
        stage: String,
        result: String,
        details: String = "",
        transactionId: Long? = null,
    ) = diagnostics.record(
        attemptId = attemptId,
        stage = stage,
        result = result,
        details = details,
        transactionId = transactionId,
    )

    /**
     * Official bill imports are all-or-nothing. New rows are inserted and
     * duplicates are enriched in one Room transaction. No success notification
     * is emitted for bulk historical data, and sync starts once after commit.
     */
    suspend fun importBillBatch(
        inserts: List<TransactionEntity>,
        updates: List<TransactionEntity> = emptyList(),
        insertDiagnostics: List<TransactionDiagnosticInput> = emptyList(),
        updateDiagnostics: Map<Long, TransactionDiagnosticInput> = emptyMap(),
    ): Pair<Int, Int> {
        if (inserts.isEmpty() && updates.isEmpty()) return 0 to 0
        val insertInputs = inserts.mapIndexed { index, _ ->
            insertDiagnostics.getOrNull(index) ?: TransactionDiagnosticInput()
        }
        val updateInputs = updates.associate { entity ->
            entity.id to (updateDiagnostics[entity.id] ?: TransactionDiagnosticInput())
        }
        val now = System.currentTimeMillis()
        val markedUpdates = updates.map {
            it.copy(
                syncDirty = true,
                syncModifiedMs = now,
                syncMutationId = UUID.randomUUID().toString(),
            )
        }
        val ids = dao.importBatch(inserts, markedUpdates)
        val saved = mutableListOf<Pair<TransactionDiagnosticInput, TransactionEntity>>()
        inserts.forEachIndexed { index, transaction ->
            val id = ids.getOrNull(index) ?: 0L
            val input = insertInputs[index]
            if (id > 0L) {
                val inserted = transaction.copy(id = id)
                saved += input to inserted
                diagnostics.record(
                    attemptId = input.attemptId,
                    stage = TransactionDiagnostics.STAGE_SAVE,
                    result = TransactionDiagnostics.RESULT_SUCCESS,
                    details = "Imported transaction row inserted",
                    transactionId = id,
                )
                diagnostics.record(
                    attemptId = input.attemptId,
                    stage = TransactionDiagnostics.STAGE_IMPORT,
                    result = TransactionDiagnostics.RESULT_SUCCESS,
                    transactionId = id,
                )
            }
        }
        markedUpdates.forEach { updated ->
            val input = updateInputs.getValue(updated.id)
            saved += input to updated
            diagnostics.record(
                attemptId = input.attemptId,
                stage = TransactionDiagnostics.STAGE_SAVE,
                result = TransactionDiagnostics.RESULT_SUCCESS,
                details = "Imported fields added to existing transaction",
                transactionId = updated.id,
            )
            diagnostics.record(
                attemptId = input.attemptId,
                stage = TransactionDiagnostics.STAGE_IMPORT,
                result = TransactionDiagnostics.RESULT_SUCCESS,
                transactionId = updated.id,
            )
        }
        requestSyncForTransactions(saved)
        return ids.count { it > 0L } to markedUpdates.size
    }

    suspend fun insertImportedBatch(transactions: List<TransactionEntity>): Int =
        importBillBatch(inserts = transactions).first

    suspend fun delete(
        transaction: TransactionEntity,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
    ) {
        // Keep a local trace even though the ledger row becomes a sync tombstone.
        val tombstone = transaction.copy(
            syncDeleted = true,
            syncDirty = true,
            syncModifiedMs = System.currentTimeMillis(),
            syncMutationId = UUID.randomUUID().toString(),
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_DELETE,
            result = TransactionDiagnostics.RESULT_STARTED,
            details = "Transaction deletion requested",
            transactionId = transaction.id,
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_STARTED,
            transactionId = transaction.id,
        )
        try {
            dao.update(tombstone)
        } catch (failure: Exception) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_SAVE,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
                transactionId = transaction.id,
            )
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_DELETE,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
                transactionId = transaction.id,
            )
            throw failure
        }
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            details = "Transaction tombstone saved",
            transactionId = transaction.id,
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_DELETE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            transactionId = transaction.id,
        )
        requestSync(diagnosticInput, tombstone)
    }

    private suspend fun requestSync(
        input: TransactionDiagnosticInput,
        transaction: TransactionEntity,
    ) = requestSyncForTransactions(listOf(input to transaction))

    private suspend fun requestSyncForTransactions(
        items: List<Pair<TransactionDiagnosticInput, TransactionEntity>>,
    ) {
        if (items.isEmpty()) return
        val enabled = context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        items.forEach { (input, transaction) ->
            diagnostics.record(
                attemptId = input.attemptId,
                stage = TransactionDiagnostics.STAGE_SYNC,
                result = TransactionDiagnostics.RESULT_STARTED,
                details = if (enabled) "Sync work requested" else "Sync is disabled; transaction remains local",
                transactionId = transaction.id.takeIf { it > 0L },
            )
        }
        try {
            TransactionSyncWorker.requestImmediate(context)
            items.forEach { (input, transaction) ->
                diagnostics.record(
                    attemptId = input.attemptId,
                    stage = TransactionDiagnostics.STAGE_SYNC,
                    result = if (enabled) TransactionDiagnostics.RESULT_SUCCESS
                    else TransactionDiagnostics.RESULT_WARNING,
                    details = if (enabled) "Sync work queued" else "No network upload scheduled",
                    transactionId = transaction.id.takeIf { it > 0L },
                )
            }
        } catch (failure: Exception) {
            items.forEach { (input, transaction) ->
                diagnostics.record(
                    attemptId = input.attemptId,
                    stage = TransactionDiagnostics.STAGE_SYNC,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = diagnosticError(failure),
                    transactionId = transaction.id.takeIf { it > 0L },
                )
            }
        }
    }

    private fun diagnosticError(failure: Exception): String =
        if (failure is android.database.sqlite.SQLiteException) "Database operation failed"
        else "Operation failed"

    companion object {
        const val POSSIBLE_DUPLICATE_WINDOW_MS: Long = DuplicateReviewPolicy.WINDOW_MS
    }
}