package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.dao.ProcessedNoticeDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.diagnostics.DiagnosticRawText
import com.bookkeeping.app.data.diagnostics.DiagnosticSummary
import com.bookkeeping.app.data.merchant.MerchantRules
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
import com.bookkeeping.app.data.local.entity.TransactionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlin.math.abs

/**
 * 一次去重入库的显式结果。
 *
 * `Long?` 无法区分「账本里已经有这笔钱」和「这次写入失败」：两条路径都只能拿到
 * null，调用方一旦把异常折成 null（`runCatching { ... }.getOrNull()`），用户的
 * 真钱就被当成「已跳过」蒸发。[insertIfNotDuplicateResult] 返回这个类型，
 * 让成功 / 重复（且重复的判据）/ 失败（带原始异常）三态可分辨。
 *
 * [TransactionRepository.insertIfNotDuplicate] 的旧 `Long?` 契约由
 * [toLegacyTransactionId] 无信息损失地保留，通知 / 短信 / 无障碍 / 手工记账
 * 四条链路的行为不变。
 */
sealed interface LedgerInsertResult {
    /** 新的一行已写入账本。 */
    data class Inserted(val transactionId: Long) : LedgerInsertResult

    /** 并入了已有行（同通道补全商户/订单号）；没有新行，但这笔钱已记账。 */
    data class Merged(val transactionId: Long) : LedgerInsertResult

    /** 没有写入，因为账本里已经有这笔钱；[reason] 说明是哪条判据命中的。 */
    data class Duplicate(
        val existingTransactionId: Long,
        val reason: DuplicateReason,
    ) : LedgerInsertResult

    /** 没有写入，且写入过程抛了异常；调用方必须保留候选、允许重试。 */
    data class Failed(val cause: Throwable) : LedgerInsertResult
}

/** 判定重复的判据；前两条是确定重复，跨通道命中只是启发式。 */
enum class DuplicateReason {
    /** 同来源 + 同订单号：同一笔被重复上报。 */
    SOURCE_ORDER_ID,

    /** 同来源 + 同商户 + 同金额 + 8 秒窗口：同一通道的重发。 */
    SAME_SOURCE_WINDOW,

    /** 跨采集通道的同额同方强证据命中（两侧同一订单号）：已确认是同一笔。 */
    CROSS_SOURCE,

    /**
     * 跨采集通道的**弱证据**命中：一侧没有商户信息（通知的「微信支付」占位名）、
     * 另一侧有真实商户，金额/方向/币种/平台/时间全同。
     *
     * 只在调用方显式要求（`holdWeakCrossSourceMatch = true`）时返回，表示
     * 「没有写新行，但这不一定是同一笔」。调用方必须把候选留在待确认列表，
     * 绝不能当成已入账把候选丢掉。
     */
    CROSS_SOURCE_WEAK,
}

/**
 * 旧 `Long?` 视图：成功（含并入）返回 id，重复返回 null，失败原样抛出同一个
 * 异常实例。这正是 [LedgerInsertResult] 出现之前四条既有调用方看到的契约。
 */
internal fun LedgerInsertResult.toLegacyTransactionId(): Long? = when (this) {
    is LedgerInsertResult.Inserted -> transactionId
    is LedgerInsertResult.Merged -> transactionId
    is LedgerInsertResult.Duplicate -> null
    is LedgerInsertResult.Failed -> throw cause
}


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
        val callSite = diagnosticInput.callSite ?: "TransactionRepository.insert"
        val money = DiagnosticSummary.amountWithMerchant(
            amount = DiagnosticSummary.amount(transaction.type, transaction.amountCents, transaction.currency),
            merchant = transaction.merchant,
        )
        // Manual and voice entries: the raw text is what the user actually sent.
        // The dialog hands over the ASR transcript when it has one; otherwise the
        // submitted merchant + note are the closest available original.
        val entryRawText = if (operationStage == TransactionDiagnostics.STAGE_ENTRY) {
            diagnosticInput.rawText ?: DiagnosticRawText.manualEntry(transaction.merchant, transaction.note)
        } else {
            null
        }
        if (operationStage == TransactionDiagnostics.STAGE_ENTRY) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = operationStage,
                result = TransactionDiagnostics.RESULT_STARTED,
                details = "Transaction save requested",
                callSite = callSite,
                inputSummary = money,
                rawText = entryRawText,
            )
        }
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_STARTED,
            callSite = callSite,
            inputSummary = money,
        )
        val id = try {
            dao.insert(transaction)
        } catch (failure: Exception) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_SAVE,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
                callSite = callSite,
                inputSummary = money,
            )
            if (operationStage == TransactionDiagnostics.STAGE_ENTRY) {
                diagnostics.record(
                    attemptId = diagnosticInput.attemptId,
                    stage = operationStage,
                    result = TransactionDiagnostics.RESULT_FAILURE,
                    details = diagnosticError(failure),
                    callSite = callSite,
                    inputSummary = money,
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
            callSite = callSite,
            inputSummary = money,
            outputSummary = "已写入 #$id",
        )
        if (operationStage == TransactionDiagnostics.STAGE_ENTRY) {
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = operationStage,
                result = TransactionDiagnostics.RESULT_SUCCESS,
                transactionId = id,
                callSite = callSite,
                inputSummary = money,
                outputSummary = "已写入 #$id",
                rawText = entryRawText,
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
    //    跨通道的一侧缺商户（通知占位名）+ 另一侧有真实商户也走这里，见
    //    CaptureMergePolicy.isSamePaymentWithOneSidedMerchant。
    // 4) 其余同金额/方向/币种、3 分钟内相近的记录只标注为「待确认重复」，
    //    仍然插入账本，绝不删除旧记录，由用户在交易页决定保留还是删除新记录。
    // 5) 跨通道只在截图/页面路径显式传 crossSourceWindowMs 时检查（默认 0 = 关闭，
    //    通知/短信/手工完全不变）：同额同方同向、时间邻近、另一条通道已经记过
    //    → 不插新行。通道靠 captureObservedAtMs 区分，不靠 source 字符串。
    //    只有两侧同一订单号时才直接当重复（CROSS_SOURCE）；一侧没商户、另一侧有
    //    商户的弱证据只在调用方传 holdWeakCrossSourceMatch = true 时处理：
    //    同样不插新行，但结果标成 CROSS_SOURCE_WEAK，调用方必须保留候选待确认。
    /** Recent ledger rows for screenshot cross-source de-duplication (read-only). */
    suspend fun screenshotDedupRows(limit: Int = 500): List<TransactionEntity> =
        dao.recentForScreenshotDedup(limit)

    suspend fun insertIfNotDuplicate(
        transaction: TransactionEntity,
        windowMs: Long = 8_000L,
        notifySuccess: Boolean = true,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
        /**
         * 跨通道重复窗口（毫秒）。默认 0 = 关闭，通知/短信/手工记账的行为
         * 与改动前完全一致；截图/页面路径显式传入
         * [CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS] 才启用跨通道检查。
         */
        crossSourceWindowMs: Long = 0L,
        /**
         * 跨通道只有弱证据（一侧没有商户信息）时也不插新行，但把结果标成
         * [DuplicateReason.CROSS_SOURCE_WEAK] 让调用方保留候选待确认。
         * 默认 false：除页面识别外的调用方行为不变。
         */
        holdWeakCrossSourceMatch: Boolean = false,
    ): Long? = insertIfNotDuplicateResult(
        transaction = transaction,
        windowMs = windowMs,
        notifySuccess = notifySuccess,
        diagnosticInput = diagnosticInput,
        crossSourceWindowMs = crossSourceWindowMs,
        holdWeakCrossSourceMatch = holdWeakCrossSourceMatch,
    ).toLegacyTransactionId()

    /**
     * 与 [insertIfNotDuplicate] 完全同一套去重规则，但把「为什么没写」讲清楚：
     * 重复（含跨通道启发式）与写入失败不再共用一个 null。
     *
     * 写入异常被收敛成 [LedgerInsertResult.Failed]（[CancellationException] 除外），
     * 调用方据此保留候选、记录真实异常并允许重试；旧入口仍会把同一个异常实例
     * 原样抛出。
     */
    suspend fun insertIfNotDuplicateResult(
        transaction: TransactionEntity,
        windowMs: Long = 8_000L,
        notifySuccess: Boolean = true,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
        crossSourceWindowMs: Long = 0L,
        holdWeakCrossSourceMatch: Boolean = false,
    ): LedgerInsertResult = try {
        insertIfNotDuplicateLocked(
            transaction = transaction,
            windowMs = windowMs,
            notifySuccess = notifySuccess,
            diagnosticInput = diagnosticInput,
            crossSourceWindowMs = crossSourceWindowMs,
            holdWeakCrossSourceMatch = holdWeakCrossSourceMatch,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LedgerInsertResult.Failed(failure)
    }

    private suspend fun insertIfNotDuplicateLocked(
        transaction: TransactionEntity,
        windowMs: Long,
        notifySuccess: Boolean,
        diagnosticInput: TransactionDiagnosticInput,
        crossSourceWindowMs: Long,
        holdWeakCrossSourceMatch: Boolean,
    ): LedgerInsertResult = duplicateMutex.withLock {
        val callSite = diagnosticInput.callSite ?: "TransactionRepository.insertIfNotDuplicate"
        val money = DiagnosticSummary.amountWithMerchant(
            amount = DiagnosticSummary.amount(transaction.type, transaction.amountCents, transaction.currency),
            merchant = transaction.merchant,
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_DEDUP,
            result = TransactionDiagnostics.RESULT_STARTED,
            callSite = callSite,
            inputSummary = "$money · 比对同商户同金额",
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
                    callSite = callSite,
                    inputSummary = money,
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
                    callSite = callSite,
                    inputSummary = money,
                    outputSummary = "命中已有 #${matched.id}（订单号相同），跳过写入",
                )
                return@withLock LedgerInsertResult.Duplicate(
                    existingTransactionId = matched.id,
                    reason = DuplicateReason.SOURCE_ORDER_ID,
                )
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
                callSite = callSite,
                inputSummary = money,
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
                callSite = callSite,
                inputSummary = money,
                outputSummary = "命中已有 #${existing.id}，跳过写入",
            )
            return@withLock LedgerInsertResult.Duplicate(
                existingTransactionId = existing.id,
                reason = DuplicateReason.SAME_SOURCE_WINDOW,
            )
        }

        // 层 2：跨通道拦截。默认关闭（crossSourceWindowMs = 0），只有截图/页面路径显式
        // 传窗口才走到这里；通知/短信/手工记账的调用一行未改。
        // 强证据（两侧同一订单号）→ 直接当重复；弱证据（一侧没有商户信息，另一侧有
        // 真实商户）只在 holdWeakCrossSourceMatch = true 时返回 CROSS_SOURCE_WEAK，
        // 由调用方把候选留在待确认列表，绝不静默丢弃。
        val crossSourceMatch = if (crossSourceWindowMs > 0L) {
            try {
                CrossSourceDuplicatePolicy.findMatchDetailed(
                    candidates = dao.findRecentByAmount(
                        amountCents = transaction.amountCents,
                        type = transaction.type,
                        currency = transaction.currency,
                        windowStartMs = transaction.timestamp - crossSourceWindowMs,
                        windowEndMs = transaction.timestamp + crossSourceWindowMs,
                    ),
                    incoming = transaction,
                    windowMs = crossSourceWindowMs,
                    allowWeakEvidence = holdWeakCrossSourceMatch,
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
        } else {
            null
        }
        if (crossSourceMatch != null) {
            val weak = crossSourceMatch.strength == CrossSourceDuplicatePolicy.MatchStrength.WEAK
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_WARNING,
                details = if (weak) {
                    "Cross-channel weak duplicate held for review"
                } else {
                    "Cross-channel duplicate candidate skipped"
                },
                transactionId = crossSourceMatch.transaction.id,
                callSite = callSite,
                inputSummary = money,
                outputSummary = if (weak) {
                    "疑似与已有 #${crossSourceMatch.transaction.id} 重复，保留待确认"
                } else {
                    "命中已有 #${crossSourceMatch.transaction.id}（跨通道同订单号），跳过写入"
                },
            )
            return@withLock LedgerInsertResult.Duplicate(
                existingTransactionId = crossSourceMatch.transaction.id,
                reason = if (weak) DuplicateReason.CROSS_SOURCE_WEAK else DuplicateReason.CROSS_SOURCE,
            )
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
                callSite = callSite,
                inputSummary = money,
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
                callSite = callSite,
                inputSummary = money,
                outputSummary = "并入已有 #${merged.id}，补全商户与订单号",
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
            return@withLock LedgerInsertResult.Merged(merged.id)
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
            callSite = callSite,
            inputSummary = money,
            outputSummary = if (candidate.duplicateOfId != null) {
                "疑似 #${candidate.duplicateOfId}，仍写入并标记待确认"
            } else {
                "未命中重复，继续写入"
            },
        )
        val id = insert(candidate, notifySuccess = notifySuccess, diagnosticInput = diagnosticInput)
        if (id > 0L) {
            CaptureHealth.onTransactionSaved(context)
            KeepAliveService.refreshStatus(context)
            LedgerInsertResult.Inserted(id)
        } else {
            // Room 的 REPLACE 冲突不会抛异常，只返回 -1；账本里没有这一行，
            // 绝不能把它当成「已跳过」。交给调用方保留候选并重试。
            LedgerInsertResult.Failed(
                IllegalStateException("ledger insert returned non-positive id=$id"),
            )
        }
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

    /**
     * 上一条同指纹通知的持久记录；没记录过返回 null。
     *
     * 指纹只在入库成功之后才写入（见 [recordNoticeFingerprint]），所以非 null 就
     * 表示这条通知对应的钱已经在账本里，同一指纹的重投递可以安全跳过。
     */
    suspend fun findProcessedNotice(fingerprint: String): ProcessedNoticeEntity? =
        processedNoticeDao.find(fingerprint)

    /**
     * 记录 / 续期一条**已经成功入账**的通知指纹（重放检测用；只存哈希，不存原文）。
     *
     * 记录时机是入库成功之后，而不是解析之后：写入失败（数据库异常、进程被杀）时
     * 指纹不存在，用户再点一次通知还能重新入账，不会被重放检测误拦——重放检测
     * 只允许「同一条通知重复投递」少记一次，绝不允许把没入账的钱当成已入账。
     */
    suspend fun recordNoticeFingerprint(
        fingerprint: String,
        source: String,
        seenMs: Long,
    ) = duplicateMutex.withLock {
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
        val callSite = diagnosticInput.callSite ?: "TransactionRepository.update"
        val money = DiagnosticSummary.amountWithMerchant(
            amount = DiagnosticSummary.amount(transaction.type, transaction.amountCents, transaction.currency),
            merchant = transaction.merchant,
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = operationStage,
            result = TransactionDiagnostics.RESULT_STARTED,
            details = "Transaction edit requested",
            transactionId = transaction.id,
            callSite = callSite,
            inputSummary = money,
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_STARTED,
            transactionId = transaction.id,
            callSite = callSite,
            inputSummary = money,
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
                callSite = callSite,
                inputSummary = money,
            )
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = operationStage,
                result = TransactionDiagnostics.RESULT_FAILURE,
                details = diagnosticError(failure),
                transactionId = transaction.id,
                callSite = callSite,
                inputSummary = money,
            )
            throw failure
        }
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = TransactionDiagnostics.STAGE_SAVE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            details = "Transaction row updated",
            transactionId = transaction.id,
            callSite = callSite,
            inputSummary = money,
            outputSummary = "已更新 #${transaction.id}",
        )
        diagnostics.record(
            attemptId = diagnosticInput.attemptId,
            stage = operationStage,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            transactionId = transaction.id,
            callSite = callSite,
            outputSummary = "已更新 #${transaction.id}",
        )
        requestSync(diagnosticInput, updated)
    }

    suspend fun recordDiagnostic(
        attemptId: String,
        stage: String,
        result: String,
        details: String = "",
        transactionId: Long? = null,
        callSite: String? = null,
        inputSummary: String? = null,
        outputSummary: String? = null,
        rawText: String? = null,
    ) = diagnostics.record(
        attemptId = attemptId,
        stage = stage,
        result = result,
        details = details,
        transactionId = transactionId,
        callSite = callSite,
        inputSummary = inputSummary,
        outputSummary = outputSummary,
        rawText = rawText,
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
                callSite = input.callSite ?: "TransactionRepository.requestSync",
                inputSummary = DiagnosticSummary.amountWithMerchant(
                    amount = DiagnosticSummary.amount(transaction.type, transaction.amountCents, transaction.currency),
                    merchant = transaction.merchant,
                ),
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
                    callSite = input.callSite ?: "TransactionRepository.requestSync",
                    outputSummary = if (enabled) "已排队上传" else "同步未开启，仅保留本机",
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
                    callSite = input.callSite ?: "TransactionRepository.requestSync",
                    outputSummary = "排队失败，账本不受影响",
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
/**
 * 跨来源防重复（纯逻辑，无 Android / Room 依赖，可直接跑 JVM 单测）。
 *
 * 现场：用户在微信里付了 ¥5.00，通知抓取已经记了一行；随后用户点开交易详情页，
 * 无障碍识别又抓到同一笔，账本变成两行。根因不是「来源字符串不同」——通知和截图
 * 都写 `source = WECHAT`（包名只进通知指纹表，不进账本）。真正的差别是**采集通道**：
 * 截图/卡片/页面识别会写 `captureObservedAtMs`，通知、短信、无障碍转账提取器都不写。
 * 加上 8 秒窗口和 `merchant = :merchant` 全等，[TransactionDao.findRecentSameAmount]
 * 在「几十秒后点开页面、且通知里没有商户名」的场景永远返回 null。
 *
 * 这里只在截图/页面路径显式启用（[TransactionRepository.insertIfNotDuplicate] 的
 * `crossSourceWindowMs`，默认 0 = 关闭），所以通知/短信/手工记账的行为不变。
 *
 * 判据分两级，因为「不写新行」判错就等于用户看不见那笔钱：
 * - **强证据**：两侧都存在且相等的平台内订单号（[MatchStrength.STRONG]）。命中即
 *   确认是同一笔，直接不插新行。
 * - **弱证据**（[MatchStrength.WEAK]，只有调用方显式允许时才计算）：一侧完全没有
 *   商户信息（空 / 「微信支付」这类占位名），另一侧有真实商户，金额、方向、币种、
 *   平台、时间窗全部一致。通知通道永远拿不到订单号，这是唯一能对上页面记录的线索；
 *   命中后调用方**必须把候选留在待确认列表**，不能当成已入账丢掉。
 *
 * 两级都要求：本行来自页面/截图采集（`captureObservedAtMs` 非空）、对侧来自另一条
 * 通道（该字段为空）、同平台、同额、同向、同币种、时间在窗口内。跨平台（微信 vs
 * 支付宝）同额同方是两笔钱，一律不拦。时间只是截屏时间兜底
 * （`eventTimeNeedsConfirmation`）时不拦：时间本身不可信。
 */
object CrossSourceDuplicatePolicy {

    /** 截图确认路径的跨来源窗口。页面有真实交易时间时，同一笔的时间差通常接近 0。 */
    const val SCREENSHOT_WINDOW_MS: Long = 10 * 60_000L

    /**
     * 弱证据的窗口单独收窄到 3 分钟：支付成功页是付款后立刻点开的，而 10 分钟足够
     * 让「同一家店连续两笔同额消费」误撞。3 分钟与
     * [DuplicateReviewPolicy.WINDOW_MS] 同一量级。
     */
    const val WEAK_EVIDENCE_WINDOW_MS: Long = 3 * 60_000L

    /** 命中的强度：[STRONG] 是同一笔的强证据，[WEAK] 只是「疑似」，必须留给用户确认。 */
    enum class MatchStrength { STRONG, WEAK }

    /** 一条命中：哪条已有记录，以及证据强度。 */
    data class Match(
        val transaction: TransactionEntity,
        val strength: MatchStrength,
    )

    /**
     * 只返回**强证据**命中（两侧同一订单号），既有调用方的语义与改动前完全一致。
     *
     * @param windowMs 0 表示关闭跨来源检查，直接返回 null（默认行为）。
     */
    fun findMatch(
        candidates: List<TransactionEntity>,
        incoming: TransactionEntity,
        windowMs: Long,
    ): TransactionEntity? = findMatchDetailed(
        candidates = candidates,
        incoming = incoming,
        windowMs = windowMs,
        allowWeakEvidence = false,
    )?.takeIf { it.strength == MatchStrength.STRONG }?.transaction

    /**
     * 在 [candidates] 里找「和 [incoming] 是同一笔钱、由另一条采集通道先记下」的一条，
     * 并说明是强证据还是弱证据。
     *
     * @param allowWeakEvidence true 时，两侧都没有可比较的订单号、但一侧有真实商户而
     *   另一侧没有商户信息，也可以返回 [MatchStrength.WEAK]。
     * @return 时间上最接近的一条已有记录；没有就返回 null。
     */
    fun findMatchDetailed(
        candidates: List<TransactionEntity>,
        incoming: TransactionEntity,
        windowMs: Long,
        allowWeakEvidence: Boolean = false,
    ): Match? {
        if (windowMs <= 0L) return null
        if (incoming.eventTimeNeedsConfirmation) return null
        // 只有截图/卡片/页面采集出来的行才需要这次检查；其它来源一行不变。
        if (incoming.captureObservedAtMs == null) return null
        val pool = candidates
            .asSequence()
            .filter { it.id != incoming.id }
            .filter { !it.syncDeleted && it.duplicateOfId == null }
            .filter { it.remoteReviewStatus !in SKIPPED_STATUSES }
            // 对侧必须来自另一条通道，否则只是同一通道的重发，交给 8 秒窗口。
            .filter { it.captureObservedAtMs == null }
            // 微信和支付宝同额同方也是两笔钱：按支付平台比较，不按 source 字符串。
            .filter { samePaymentPlatform(it.source, incoming.source) }
            .filter { it.amountCents == incoming.amountCents }
            .filter { it.type == incoming.type }
            .filter { it.currency.equals(incoming.currency, ignoreCase = true) }
            .filter { abs(it.timestamp - incoming.timestamp) <= windowMs }
            .toList()

        // 强证据：订单号必须两侧都存在且相等；单侧 ID 不是证据，缺 ID 更不是。
        val incomingOrderId = incoming.platformOrderId()
        if (incomingOrderId != null) {
            val strong = pool.asSequence()
                .filter { existing ->
                    existing.platformOrderId()?.equals(incomingOrderId, ignoreCase = true) == true
                }
                // 订单号相同但商户名明确对不上：数据自相矛盾，交人工，不在这里吞掉。
                .filter { existing -> !merchantsContradict(existing.merchant, incoming.merchant) }
                .minByOrNull { abs(it.timestamp - incoming.timestamp) }
            if (strong != null) return Match(strong, MatchStrength.STRONG)
        }

        if (!allowWeakEvidence) return null
        // 弱证据：一侧完全没有商户信息、另一侧有真实商户。两侧都有商户（哪怕不同）
        // 或两侧都没有商户都不算——前者可能是两家店，后者只剩金额 + 时间。
        val weakWindowMs = minOf(windowMs, WEAK_EVIDENCE_WINDOW_MS)
        val weak = pool.asSequence()
            .filter { abs(it.timestamp - incoming.timestamp) <= weakWindowMs }
            .filter { oneSideHasMerchantInfo(it.merchant, incoming.merchant) }
            // 两侧都有订单号但不同 → 明确是两笔，不做弱匹配。
            .filter { existing -> !orderIdsContradict(existing, incoming) }
            .minByOrNull { abs(it.timestamp - incoming.timestamp) }
        return weak?.let { Match(it, MatchStrength.WEAK) }
    }

    /** 恰好一侧有商户信息：通知侧的「微信支付」占位名与空名字都算没有。 */
    private fun oneSideHasMerchantInfo(first: String, second: String): Boolean =
        CaptureMergePolicy.hasMerchantInfo(first) != CaptureMergePolicy.hasMerchantInfo(second)

    /** 两侧都有订单号且不同 → 两笔不同的支付；单侧有 ID 不矛盾（通知永远没有）。 */
    private fun orderIdsContradict(first: TransactionEntity, second: TransactionEntity): Boolean {
        val firstId = first.platformOrderId() ?: return false
        val secondId = second.platformOrderId() ?: return false
        return !firstId.equals(secondId, ignoreCase = true)
    }

    /** 平台内订单号：空白等于没有。比较前先按平台过滤，所以不会跨平台比较。 */
    private fun TransactionEntity.platformOrderId(): String? =
        sourceTransactionId?.trim()?.takeIf(String::isNotEmpty)

    /**
     * 两边都有真实商户、且明确不是同一家 → 数据自相矛盾。
     * 任一侧缺商户（含「微信支付」这类占位名）都不算矛盾：订单号相同已经足够。
     */
    private fun merchantsContradict(first: String, second: String): Boolean {
        if (!CaptureMergePolicy.hasMerchantInfo(first)) return false
        if (!CaptureMergePolicy.hasMerchantInfo(second)) return false
        return !merchantsSame(first, second)
    }

    /**
     * 两个账本来源是否属于同一个支付平台。
     *
     * 账本里的 `source` 是 [TransactionSource] 名称，不是支付平台常量；截图和通知
     * 都会写 `WECHAT` / `ALIPAY`，剩下的是手工、银行短信、其它页面。微信和支付宝
     * 同额同方也是两笔钱，所以这里按平台比较，未知一律归为 OTHER 互相匹配。
     */
    private fun samePaymentPlatform(first: String, second: String): Boolean =
        paymentPlatformOf(first) == paymentPlatformOf(second)

    private fun paymentPlatformOf(source: String): String = when (source) {
        TransactionSource.WECHAT.name -> TransactionSource.WECHAT.name
        TransactionSource.ALIPAY.name -> TransactionSource.ALIPAY.name
        else -> TransactionSource.OTHER.name
    }

    /**
     * 两个商户名是否明确指向同一家店。
     *
     * 占位名（「微信支付」「未知商户」…）和空名字都不算证据，返回 false：
     * 此时无法断言「同方」，宁可多一行让用户删，也不吞掉一笔真钱。
     */
    fun merchantsSame(first: String, second: String): Boolean {
        if (!CaptureMergePolicy.hasMerchantInfo(first)) return false
        if (!CaptureMergePolicy.hasMerchantInfo(second)) return false
        val firstKey = MerchantRules.normalize(first)
        val secondKey = MerchantRules.normalize(second)
        if (firstKey.isEmpty() || secondKey.isEmpty()) return false
        if (firstKey == secondKey) return true
        val shorter = if (firstKey.length <= secondKey.length) firstKey else secondKey
        val longer = if (shorter == firstKey) secondKey else firstKey
        return shorter.length >= 2 && longer.contains(shorter)
    }

    /** 已经不计入或已判定为重复的行不参与比较，否则会被列表里看不见的旧行拦下。 */
    private val SKIPPED_STATUSES = setOf("excluded", "possible_duplicate")
}
