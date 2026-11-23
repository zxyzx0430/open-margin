package com.bookkeeping.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.bookkeeping.app.data.diagnostics.DiagnosticRawText
import com.bookkeeping.app.data.diagnostics.DiagnosticSummary
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.BookkeepingDatabase
import com.bookkeeping.app.data.local.dao.ProcessedNoticeDao
import com.bookkeeping.app.data.local.dao.TransactionDao
import com.bookkeeping.app.data.local.entity.ProcessedNoticeEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.merchant.MerchantRules
import com.bookkeeping.app.data.merchant.matchesOtherMerchant
import com.bookkeeping.app.data.merchant.markCategorized
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.notification.CaptureHealth
import com.bookkeeping.app.notification.CaptureNotifier
import com.bookkeeping.app.notification.KeepAliveService
import com.bookkeeping.app.notification.NoticeFingerprint
import com.bookkeeping.app.notification.NoticeReplayPolicy
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.notification.capture.CandidateSaveFailure
import com.bookkeeping.app.notification.capture.CandidateSaveResult
import com.bookkeeping.app.notification.capture.NotificationReviewCandidateFactory
import com.bookkeeping.app.capture.screenshot.ScreenshotReviewNotifier
import com.bookkeeping.app.capture.screenshot.CandidateStorageIntakeKind
import com.bookkeeping.app.sync.TransactionSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
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

    /** 并入了已有行（同通道或跨通道补全商户/订单号）；没有新行，但这笔钱已记账。 */
    data class Merged(val transactionId: Long) : LedgerInsertResult

    /** 没有写入，因为账本里已经有这笔钱；[reason] 说明是哪条判据命中的。 */
    data class Duplicate(
        val existingTransactionId: Long,
        val reason: DuplicateReason,
    ) : LedgerInsertResult

    /** 没有写入，且写入过程抛了异常；调用方必须保留候选、允许重试。 */
    data class Failed(val cause: Throwable) : LedgerInsertResult
}

/** Result of an atomic notification event claim plus ledger write. */
sealed interface NotificationInsertResult {
    /** This exact platform-scoped transaction ID is already linked; no ledger write was attempted. */
    data class Replayed(val transactionId: Long) : NotificationInsertResult

    /** A record key or legacy fingerprint is ambiguous; it is preserved for review. */
    data class NeedsReview(val reason: String, val candidateTransactionId: Long? = null) :
        NotificationInsertResult

    /** First delivery of this fingerprint; [ledgerResult] explains its ledger outcome. */
    data class Processed(val ledgerResult: LedgerInsertResult) : NotificationInsertResult

    /** The transaction rolled back, including its fingerprint claim; the caller may retry. */
    data class Failed(val cause: Throwable) : NotificationInsertResult
}

/** 判定重复的判据；订单号是精确证据，跨通道命中可能仍需用户复核。 */
enum class DuplicateReason {
    /** 同来源 + 同订单号：同一笔被重复上报。 */
    SOURCE_ORDER_ID,

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
    private val database: BookkeepingDatabase,
    private val settings: SettingsPreferences,
    private val diagnostics: TransactionDiagnostics,
    @ApplicationContext private val context: Context,
) {
    private val duplicateMutex = Mutex()

    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    suspend fun categorizeOther(merchant: String, singleId: Long?, category: TransactionCategory, remember: Boolean) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            val updates = dao.currentOther().filter { category != TransactionCategory.OTHER && matchesOtherMerchant(it, merchant, singleId) }.map {
                markCategorized(it, category.name, now, UUID.randomUUID().toString())
            }
            if (updates.isNotEmpty()) dao.updateAll(updates)
        }
        // DataStore is a separate persistence system and cannot join the Room transaction.
        if (remember && MerchantRules.normalize(merchant).isNotBlank()) settings.rememberMerchantCategory(merchant, category)
    }

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
    ): Long = insertInternal(
        transaction = transaction,
        notifySuccess = notifySuccess,
        diagnosticInput = diagnosticInput,
        deferPostCommitEffects = false,
    )

    private suspend fun insertInternal(
        transaction: TransactionEntity,
        notifySuccess: Boolean,
        diagnosticInput: TransactionDiagnosticInput,
        deferPostCommitEffects: Boolean,
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
        if (!deferPostCommitEffects && notifySuccess && id > 0L) {
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
        if (!deferPostCommitEffects) requestSync(diagnosticInput, saved)
        return id
    }

    // 自动抓取路径专用。
    // 1) 同一非空平台订单号已经在库里 → 直接跳过。
    // 2) 只有同一非空订单号能自动合并并补全先入库行；金额、商户、时间相似本身不足以
    //    证明是同一事件，可能重复的行仍然插入并标记待复核。
    // 3) 跨通道只在截图/页面路径显式传 crossSourceWindowMs 时检查（默认 0 = 关闭）。
    //    两侧同一订单号是强证据（CROSS_SOURCE）；弱证据只在调用方明确要求时计算，
    //    命中后保留候选待确认。用户确认后仍可合并已有行中的强证据匹配。
    /** Recent ledger rows for screenshot cross-source de-duplication (read-only). */
    suspend fun screenshotDedupRows(limit: Int = 500): List<TransactionEntity> =
        dao.recentForScreenshotDedup(limit)

    suspend fun insertIfNotDuplicate(
        transaction: TransactionEntity,
        notifySuccess: Boolean = true,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
        /**
         * 跨通道重复窗口（毫秒）。默认 0 = 关闭，通知/短信/手工记账的行为
         * 与改动前完全一致；截图/页面路径显式传入
         * [CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS] 才启用跨通道检查。
         */
        crossSourceWindowMs: Long = 0L,
        /**
         * 自动路径遇到弱跨通道证据（一侧没有商户信息）时不插新行，把结果标成
         * [DuplicateReason.CROSS_SOURCE_WEAK] 让调用方保留候选待确认。用户确认后按独立新行写入。
         * 默认 false：除页面识别外的调用方行为不变。
         */
        holdWeakCrossSourceMatch: Boolean = false,
    ): Long? = insertIfNotDuplicateResult(
        transaction = transaction,
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
        notifySuccess: Boolean = true,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
        crossSourceWindowMs: Long = 0L,
        holdWeakCrossSourceMatch: Boolean = false,
        /**
         * 用户已经在审阅页确认「这笔要记」：跨通道强证据命中的同一笔不再只回
         * 「已存在」，而是把本次截图里更完整的商户/订单号/capture 溯源并进
         * 已有行（不新增行）。默认 false，自动采集路径的语义不变。
         */
        mergeConfirmedCrossSource: Boolean = false,
    ): LedgerInsertResult = try {
        duplicateMutex.withLock {
            insertIfNotDuplicateLocked(
                transaction = transaction,
                notifySuccess = notifySuccess,
                diagnosticInput = diagnosticInput,
                crossSourceWindowMs = crossSourceWindowMs,
                holdWeakCrossSourceMatch = holdWeakCrossSourceMatch,
                mergeConfirmedCrossSource = mergeConfirmedCrossSource,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LedgerInsertResult.Failed(failure)
    }

    private suspend fun insertIfNotDuplicateLocked(
        transaction: TransactionEntity,
        notifySuccess: Boolean,
        diagnosticInput: TransactionDiagnosticInput,
        crossSourceWindowMs: Long,
        holdWeakCrossSourceMatch: Boolean,
        mergeConfirmedCrossSource: Boolean = false,
        deferPostCommitEffects: Boolean = false,
    ): LedgerInsertResult {
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
            inputSummary = "$money · 检查订单号与事件证据",
        )
        transaction.sourceTransactionId?.trim()?.takeIf(String::isNotEmpty)?.let { orderId ->
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
                return LedgerInsertResult.Duplicate(
                    existingTransactionId = matched.id,
                    reason = DuplicateReason.SOURCE_ORDER_ID,
                )
            }
        }
        // 层 2：跨通道拦截。默认关闭（crossSourceWindowMs = 0），只有截图/页面路径显式
        // 传窗口才走到这里；通知/短信/手工记账的调用一行未改。
        //
        // 判定顺序刻意是「先看能不能合并，再决定是否拦下」：
        // - 强证据（两侧同一订单号）默认仍然只报 CROSS_SOURCE；只有用户显式确认
        //   （mergeConfirmedCrossSource）时才允许把截图里更完整的字段并进已有行。
        // - 弱证据（一侧没有商户信息，另一侧有真实商户）只在调用方明确要求时计算；
        //   自动路径保留候选给用户确认，用户确认后仍按独立新行写入，不做弱证据合并。
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
        // 跨通道命中只会在强 ID 证据且用户确认后成为合并目标。
        var crossChannelMergeTarget: TransactionEntity? = null
        if (crossSourceMatch != null) {
            val weak = crossSourceMatch.strength == CrossSourceDuplicatePolicy.MatchStrength.WEAK
            val matched = crossSourceMatch.transaction
            val disposition = CaptureMergePolicy.crossChannelDisposition(
                matched = matched,
                incoming = transaction,
                weak = weak,
                confirmedByUser = mergeConfirmedCrossSource,
            )
            when (disposition) {
                CaptureMergePolicy.CrossChannelDisposition.MERGE_INTO_EXISTING -> {
                    crossChannelMergeTarget = matched
                }

                CaptureMergePolicy.CrossChannelDisposition.INSERT_SEPARATELY -> {
                    diagnostics.record(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_DEDUP,
                        result = TransactionDiagnostics.RESULT_WARNING,
                        details = "Weak cross-channel resemblance explicitly confirmed as a separate transaction",
                        transactionId = matched.id,
                        callSite = callSite,
                        inputSummary = money,
                        outputSummary = "弱相似度没有证明同一笔，按用户确认保留为新记录",
                    )
                }

                CaptureMergePolicy.CrossChannelDisposition.HOLD_FOR_REVIEW -> {
                    diagnostics.record(
                        attemptId = diagnosticInput.attemptId,
                        stage = TransactionDiagnostics.STAGE_DEDUP,
                        result = TransactionDiagnostics.RESULT_WARNING,
                        details = if (weak) {
                            "Cross-channel weak duplicate held for review"
                        } else {
                            "Cross-channel duplicate candidate skipped"
                        },
                        transactionId = matched.id,
                        callSite = callSite,
                        inputSummary = money,
                        outputSummary = if (weak) {
                            "疑似与已有 #${matched.id} 重复，保留待确认"
                        } else {
                            "命中已有 #${matched.id}（跨通道同订单号），跳过写入"
                        },
                    )
                    return LedgerInsertResult.Duplicate(
                        existingTransactionId = matched.id,
                        reason = if (weak) DuplicateReason.CROSS_SOURCE_WEAK else DuplicateReason.CROSS_SOURCE,
                    )
                }
            }
        }

        // 同一笔支付被两条采集路径各扫一次（通知文本缺商户、支付成功页有商户）：
        // 合成一条，写回先入库的行，不再让用户确认。跨通道已经确定合并目标的，
        // 直接用它，不再重复查一遍窗口。
        val mergeTarget = crossChannelMergeTarget ?: try {
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
            val crossChannel = crossChannelMergeTarget != null
            diagnostics.record(
                attemptId = diagnosticInput.attemptId,
                stage = TransactionDiagnostics.STAGE_DEDUP,
                result = TransactionDiagnostics.RESULT_WARNING,
                details = if (crossChannel) {
                    "Cross-channel match merged into existing transaction"
                } else {
                    "Candidate merged into existing transaction"
                },
                transactionId = merged.id,
                callSite = callSite,
                inputSummary = money,
                outputSummary = if (crossChannel) {
                    "跨通道同一笔，并入已有 #${merged.id}，补全商户与订单号"
                } else {
                    "并入已有 #${merged.id}，补全商户与订单号"
                },
            )
            updateInternal(
                merged,
                diagnosticInput,
                operationStage = TransactionDiagnostics.STAGE_CAPTURE,
                requestSyncAfterWrite = !deferPostCommitEffects,
            )
            if (!deferPostCommitEffects) {
                CaptureHealth.onTransactionSaved(context)
                KeepAliveService.refreshStatus(context)
            }
            if (!deferPostCommitEffects && notifySuccess) {
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
            return LedgerInsertResult.Merged(merged.id)
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
        val id = insertInternal(
            candidate,
            notifySuccess = notifySuccess,
            diagnosticInput = diagnosticInput,
            deferPostCommitEffects = deferPostCommitEffects,
        )
        return if (id > 0L) {
            if (!deferPostCommitEffects) {
                CaptureHealth.onTransactionSaved(context)
                KeepAliveService.refreshStatus(context)
            }
            LedgerInsertResult.Inserted(id)
        } else {
            // Room 的 REPLACE 冲突不会抛异常，只返回 -1；账本里没有这一行，
            // 绝不能把它当成「已跳过」。交给调用方保留候选并重试。
            LedgerInsertResult.Failed(
                IllegalStateException("ledger insert returned non-positive id=$id"),
            )
        }
    }

    /** 自动合并目标只允许同一非空平台订单号命中的记录。 */
    private suspend fun findAutoMergeTarget(transaction: TransactionEntity): TransactionEntity? =
        dao.findRecentByAmount(
                amountCents = transaction.amountCents,
                type = transaction.type,
                currency = transaction.currency,
                windowStartMs = transaction.timestamp - CaptureMergePolicy.AUTO_MERGE_WINDOW_MS,
                windowEndMs = transaction.timestamp + CaptureMergePolicy.AUTO_MERGE_WINDOW_MS,
            )
            .asSequence()
            .filter { CaptureMergePolicy.isSamePayment(it, transaction) }
            .minByOrNull { it.id }

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

    /**
     * Atomically persist transaction identity claims, notification record links,
     * and the ledger write. Exact platform + order ID is the replay identity;
     * a notification key is only a first-writer link and repeated key-only input
     * returns NeedsReview. The repository mutex is acquired before Room, matching
     * every other de-duplicating insert path.
     * @param fingerprint Hash of the source notification record key, if available;
     * it is not a payment identity and cannot independently produce Replayed.
     */
    suspend fun insertNotificationIfNotReplayed(
        fingerprint: String?,
        source: String,
        seenMs: Long,
        transaction: TransactionEntity,
        notifySuccess: Boolean = false,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
        legacyFingerprints: Set<String> = emptySet(),
    ): NotificationInsertResult {
        val result = try {
            duplicateMutex.withLock {
                database.withTransaction {
                    insertNotificationInTransaction(
                        fingerprint = fingerprint,
                        source = source,
                        seenMs = seenMs,
                        transaction = transaction,
                        notifySuccess = notifySuccess,
                        diagnosticInput = diagnosticInput,
                        legacyFingerprints = legacyFingerprints,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rollback: NotificationInsertRollback) {
            return NotificationInsertResult.Failed(rollback.failure)
        } catch (failure: Exception) {
            return NotificationInsertResult.Failed(failure)
        }

        if (result is NotificationInsertResult.Processed) {
            withContext(NonCancellable) {
                postCommitNotificationEffects(
                    ledgerResult = result.ledgerResult,
                    transaction = transaction,
                    notifySuccess = notifySuccess,
                    diagnosticInput = diagnosticInput,
                )
            }
        } else if (result is NotificationInsertResult.NeedsReview) {
            val saved = try {
                withContext(NonCancellable) {
                    val store = CaptureCandidateStore(context)
                    val candidate = NotificationReviewCandidateFactory.create(
                        transaction = transaction,
                        seenAtMs = seenMs,
                        reason = result.reason,
                        diagnosticAttemptId = diagnosticInput.attemptId,
                        matchedTransactionId = result.candidateTransactionId,
                    ) ?: throw IllegalArgumentException("ambiguous transaction cannot form a review candidate")
                    when (val savedCandidate = store.saveWithResult(candidate, null)) {
                        is CandidateSaveResult.Saved -> Unit
                        is CandidateSaveResult.Failed -> {
                            if (savedCandidate.reason == CandidateSaveFailure.INDEX_FULL) {
                                diagnostics.record(
                                    attemptId = diagnosticInput.attemptId,
                                    stage = TransactionDiagnostics.STAGE_SAVE,
                                    result = TransactionDiagnostics.RESULT_FAILURE,
                                    details = "Candidate index budget full; notification review candidate was not saved",
                                )
                                val intakeKind = if (transaction.source == TransactionSource.BANK_SMS.name) {
                                    CandidateStorageIntakeKind.SMS
                                } else {
                                    CandidateStorageIntakeKind.NOTIFICATION
                                }
                                ScreenshotReviewNotifier.notifyCandidateStorageFull(context, intakeKind)
                            }
                            throw IllegalStateException(
                                if (savedCandidate.reason == CandidateSaveFailure.INDEX_FULL) {
                                    "candidate review storage is full"
                                } else {
                                    "ambiguous transaction review candidate could not be stored"
                                },
                            )
                        }
                    }
                    ScreenshotReviewNotifier.notifyPending(context, store.pending())
                    true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return NotificationInsertResult.Failed(failure)
            }
            if (!saved) return NotificationInsertResult.Failed(
                IllegalStateException("ambiguous transaction review candidate could not be stored"),
            )
        }
        return result
    }

    /** Caller holds [duplicateMutex] and a Room transaction. */
    private suspend fun insertNotificationInTransaction(
        fingerprint: String?,
        source: String,
        seenMs: Long,
        transaction: TransactionEntity,
        notifySuccess: Boolean,
        diagnosticInput: TransactionDiagnosticInput,
        legacyFingerprints: Set<String>,
    ): NotificationInsertResult {
        val orderId = transaction.sourceTransactionId?.trim()?.takeIf(String::isNotEmpty)
        val transactionFingerprint = NoticeFingerprint.forPlatformTransaction(
            platform = transaction.source,
            transactionId = orderId,
        )

        // Exact, platform-scoped order IDs are the only notification replay
        // identity. A linked claim remains idempotent even if its ledger row was
        // later removed; if it is still present, verify the claim before using it.
        val previousTransactionClaim = transactionFingerprint?.let { processedNoticeDao.find(it) }
        if (transactionFingerprint != null && previousTransactionClaim != null) {
            val linkedId = previousTransactionClaim.transactionId
            if (linkedId != null) {
                val linked = dao.getById(linkedId)
                if (linked != null && !linked.matchesPlatformOrder(transaction.source, orderId!!)) {
                    return NotificationInsertResult.NeedsReview(
                        reason = "Exact platform transaction identity points to a different ledger row",
                        candidateTransactionId = linkedId,
                    )
                }
                recordNotificationLinkIfAbsent(fingerprint, source, seenMs, linkedId)
                processedNoticeDao.touch(transactionFingerprint, seenMs)
                return NotificationInsertResult.Replayed(linkedId)
            }

            // A previous transaction claim without a ledger link is unresolved.
            // An exact matching ledger row can safely repair that link; otherwise
            // keep the claim intact and expose the ambiguity to diagnostics.
            val exactExisting = dao.findBySourceTransactionId(transaction.source, orderId!!)
            if (exactExisting != null && linkTransactionIdentity(
                    transactionFingerprint,
                    source,
                    seenMs,
                    exactExisting.id,
                )
            ) {
                recordNotificationLinkIfAbsent(fingerprint, source, seenMs, exactExisting.id)
                return NotificationInsertResult.Replayed(exactExisting.id)
            }
            return NotificationInsertResult.NeedsReview(
                reason = "Exact platform transaction identity is already claimed without a proven ledger link",
                candidateTransactionId = previousTransactionClaim.transactionId,
            )
        }

        // A transaction ID can also match a ledger row captured through another
        // path or an older app version. Exact platform + order ID is sufficient
        // proof; the notification record link is inserted only if still unused.
        if (transactionFingerprint != null) {
            val exactExisting = dao.findBySourceTransactionId(transaction.source, orderId!!)
            if (exactExisting != null) {
                if (!linkTransactionIdentity(
                        transactionFingerprint,
                        source,
                        seenMs,
                        exactExisting.id,
                    )
                ) {
                    return NotificationInsertResult.NeedsReview(
                        reason = "Exact platform transaction identity conflicts with an existing claim",
                        candidateTransactionId = exactExisting.id,
                    )
                }
                recordNotificationLinkIfAbsent(fingerprint, source, seenMs, exactExisting.id)
                return NotificationInsertResult.Replayed(exactExisting.id)
            }
        }

        val previousRecord = fingerprint?.let { processedNoticeDao.find(it) }
        if (previousRecord != null) {
            val priorLedger = previousRecord.transactionId?.let { dao.getById(it) }
            val priorMatchesTransaction = orderId != null && priorLedger?.matchesPlatformOrder(
                transaction.source,
                orderId,
            ) == true
            when (NoticeReplayPolicy.classifyRecordKey(
                previousRecordExists = true,
                hasTrustedTransactionId = transactionFingerprint != null,
                previousLinkMatchesTransactionId = priorMatchesTransaction,
            )) {
                NoticeReplayPolicy.RecordKeyDecision.EXACT_TRANSACTION -> {
                    val priorId = requireNotNull(previousRecord.transactionId)
                    if (transactionFingerprint != null && !linkTransactionIdentity(
                            transactionFingerprint,
                            source,
                            seenMs,
                            priorId,
                        )
                    ) {
                        return NotificationInsertResult.NeedsReview(
                            reason = "Notification record link conflicts with the exact transaction identity",
                            candidateTransactionId = priorId,
                        )
                    }
                    return NotificationInsertResult.Replayed(priorId)
                }
                NoticeReplayPolicy.RecordKeyDecision.DISTINCT_TRANSACTION -> {
                    // The exact incoming ID proves this is another payment. Keep
                    // the original notification-key link unchanged and continue.
                }
                NoticeReplayPolicy.RecordKeyDecision.AMBIGUOUS_REUSE ->
                    return NotificationInsertResult.NeedsReview(
                        reason = "Notification record key was seen before, but no trustworthy transaction ID can distinguish a refresh from a new payment",
                        candidateTransactionId = previousRecord.transactionId,
                    )
                NoticeReplayPolicy.RecordKeyDecision.NEW_RECORD -> Unit
            }
        }

        // Historical content-only fingerprints are not event identities. Without
        // an exact order ID they remain explicitly ambiguous; with an exact ID,
        // the strong identity below safely distinguishes a new payment.
        if (transactionFingerprint == null) {
            for (legacyFingerprint in legacyFingerprints) {
                if (legacyFingerprint == fingerprint) continue
                val candidate = processedNoticeDao.find(legacyFingerprint) ?: continue
                return resolveUnlinkedNotice(candidate)
            }
        }

        // Claim the appropriate identity before writing the ledger row. A record
        // key with no transaction ID can only claim once; a repeat is ambiguity,
        // never an automatic replay. Strong transaction claims are namespaced by
        // source platform and exact order ID.
        var claimedRecordFingerprint = false
        if (transactionFingerprint == null && fingerprint != null) {
            val claimed = processedNoticeDao.insertIgnore(
                ProcessedNoticeEntity(
                    fingerprint = fingerprint,
                    source = source,
                    firstSeenMs = seenMs,
                    lastSeenMs = seenMs,
                    seenCount = 1,
                ),
            )
            if (claimed == -1L) {
                val winner = processedNoticeDao.find(fingerprint)
                return NotificationInsertResult.NeedsReview(
                    reason = "Notification record key was concurrently claimed without transaction-level identity",
                    candidateTransactionId = winner?.transactionId,
                )
            }
            claimedRecordFingerprint = true
        }
        if (transactionFingerprint != null) {
            val claimed = processedNoticeDao.insertIgnore(
                ProcessedNoticeEntity(
                    fingerprint = transactionFingerprint,
                    source = source,
                    firstSeenMs = seenMs,
                    lastSeenMs = seenMs,
                    seenCount = 1,
                ),
            )
            if (claimed == -1L) {
                val winner = processedNoticeDao.find(transactionFingerprint)
                if (winner?.transactionId != null && NoticeReplayPolicy.isSamePlatformTransaction(
                        previousFingerprint = winner.fingerprint,
                        incomingFingerprint = transactionFingerprint,
                        linkedTransactionId = winner.transactionId,
                    )
                ) {
                    recordNotificationLinkIfAbsent(fingerprint, source, seenMs, winner.transactionId)
                    return NotificationInsertResult.Replayed(winner.transactionId)
                }
                return NotificationInsertResult.NeedsReview(
                    reason = "Exact platform transaction identity was concurrently claimed without a ledger link",
                    candidateTransactionId = winner?.transactionId,
                )
            }
        }

        val ledgerResult = insertIfNotDuplicateLocked(
            transaction = transaction,
            notifySuccess = notifySuccess,
            diagnosticInput = diagnosticInput,
            crossSourceWindowMs = 0L,
            holdWeakCrossSourceMatch = false,
            deferPostCommitEffects = true,
        )
        if (ledgerResult is LedgerInsertResult.Failed) {
            // Throwing exits the Room transaction, removing the stable claim.
            throw NotificationInsertRollback(ledgerResult.cause)
        }
        val transactionId = when (ledgerResult) {
            is LedgerInsertResult.Inserted -> ledgerResult.transactionId
            is LedgerInsertResult.Merged -> ledgerResult.transactionId
            is LedgerInsertResult.Duplicate -> ledgerResult.existingTransactionId
            is LedgerInsertResult.Failed -> error("handled above")
        }
        if (transactionFingerprint != null &&
            processedNoticeDao.linkTransactionIfUnlinked(transactionFingerprint, transactionId) != 1
        ) {
            throw IllegalStateException("Could not link the exact transaction identity to its ledger row")
        }
        if (claimedRecordFingerprint &&
            processedNoticeDao.linkTransactionIfUnlinked(requireNotNull(fingerprint), transactionId) != 1
        ) {
            throw IllegalStateException("Could not link the notification record key to its first ledger row")
        }
        if (transactionFingerprint != null) {
            // A second, distinct order ID may reuse an Android notification key.
            // Insert its record link only when no earlier notice has claimed it.
            recordNotificationLinkIfAbsent(fingerprint, source, seenMs, transactionId)
        }
        return NotificationInsertResult.Processed(ledgerResult)
    }

    private fun resolveUnlinkedNotice(notice: ProcessedNoticeEntity): NotificationInsertResult {
        return NotificationInsertResult.NeedsReview(
            reason = "Legacy notification fingerprint has no proven ledger link",
            candidateTransactionId = notice.transactionId,
        )
    }

    private suspend fun linkTransactionIdentity(
        fingerprint: String,
        source: String,
        seenMs: Long,
        transactionId: Long,
    ): Boolean {
        val inserted = processedNoticeDao.insertIgnore(
            ProcessedNoticeEntity(
                fingerprint = fingerprint,
                source = source,
                firstSeenMs = seenMs,
                lastSeenMs = seenMs,
                seenCount = 1,
                transactionId = transactionId,
            ),
        )
        if (inserted != -1L) return true
        val existing = processedNoticeDao.find(fingerprint) ?: return false
        if (existing.transactionId == transactionId) return true
        if (existing.transactionId != null) return false
        return processedNoticeDao.linkTransactionIfUnlinked(fingerprint, transactionId) == 1
    }

    private suspend fun recordNotificationLinkIfAbsent(
        fingerprint: String?,
        source: String,
        seenMs: Long,
        transactionId: Long,
    ) {
        if (fingerprint == null) return
        processedNoticeDao.insertIgnore(
            ProcessedNoticeEntity(
                fingerprint = fingerprint,
                source = source,
                firstSeenMs = seenMs,
                lastSeenMs = seenMs,
                seenCount = 1,
                transactionId = transactionId,
            ),
        )
    }

    private fun TransactionEntity.matchesPlatformOrder(platform: String, exactOrderId: String): Boolean =
        source == platform && sourceTransactionId?.trim() == exactOrderId

    /** Runs only after the claim and ledger row have both committed. */
    private suspend fun postCommitNotificationEffects(
        ledgerResult: LedgerInsertResult,
        transaction: TransactionEntity,
        notifySuccess: Boolean,
        diagnosticInput: TransactionDiagnosticInput,
    ) {
        val transactionId = when (ledgerResult) {
            is LedgerInsertResult.Inserted -> ledgerResult.transactionId
            is LedgerInsertResult.Merged -> ledgerResult.transactionId
            is LedgerInsertResult.Duplicate, is LedgerInsertResult.Failed -> return
        }
        val committed = runCatching { dao.getById(transactionId) }.getOrNull()
            ?: transaction.copy(id = transactionId)
        runCatching { CaptureHealth.onTransactionSaved(context) }
        runCatching { KeepAliveService.refreshStatus(context) }
        requestSync(diagnosticInput, committed)
        if (notifySuccess) {
            val possibleDuplicate = committed.duplicateOfId != null ||
                committed.remoteReviewStatus == "possible_duplicate"
            try {
                if (possibleDuplicate || settings.successNotificationsEnabledNow()) {
                    CaptureNotifier.notifySaved(
                        context = context,
                        transaction = committed,
                        possibleDuplicate = possibleDuplicate,
                        merged = ledgerResult is LedgerInsertResult.Merged,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                diagnostics.record(
                    attemptId = diagnosticInput.attemptId,
                    stage = TransactionDiagnostics.STAGE_CAPTURE,
                    result = TransactionDiagnostics.RESULT_WARNING,
                    details = "Committed transaction notification failed",
                    transactionId = transactionId,
                )
            }
        }
    }

    private class NotificationInsertRollback(val failure: Throwable) : RuntimeException(failure)

    // 用户在交易页确认“两笔都是真的”：清除本地标记，并把状态同步为已确认。
    suspend fun keepBoth(transaction: TransactionEntity) {
        if (transaction.duplicateOfId == null &&
            transaction.remoteReviewStatus != "possible_duplicate") return
        update(transaction.copy(duplicateOfId = null, remoteReviewStatus = "confirmed"))
    }

    suspend fun update(
        transaction: TransactionEntity,
        diagnosticInput: TransactionDiagnosticInput = TransactionDiagnosticInput(),
        operationStage: String = TransactionDiagnostics.STAGE_EDIT,
    ) = updateInternal(
        transaction = transaction,
        diagnosticInput = diagnosticInput,
        operationStage = operationStage,
        requestSyncAfterWrite = true,
    )

    private suspend fun updateInternal(
        transaction: TransactionEntity,
        diagnosticInput: TransactionDiagnosticInput,
        operationStage: String,
        requestSyncAfterWrite: Boolean,
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
        if (requestSyncAfterWrite) requestSync(diagnosticInput, updated)
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
        transaction.evidenceImageName?.takeIf { it.isNotBlank() }?.let { imageName ->
            runCatching {
                if (dao.countActiveByEvidenceImageName(imageName) == 0) {
                    com.bookkeeping.app.capture.screenshot.ReceiptEvidenceStore(context)
                        .deleteIfPresent(imageName)
                }
            }
        }
        requestSync(diagnosticInput, tombstone)
    }

    suspend fun clearAllEvidenceImages(): Int {
        val removed = com.bookkeeping.app.capture.screenshot.ReceiptEvidenceStore(context).clearAll()
        dao.clearAllEvidenceImageNames()
        return removed
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
 * 截图/页面识别会写 `captureObservedAtMs`，通知、短信、无障碍转账提取器不写。
 * 同来源金额/商户/时间相似只用于保留疑似记录供用户复核，不能阻止账本新行；通知
 * 重投由 Room 事务内的稳定事件指纹 claim 处理。
 *
 * 这里只在截图/页面路径显式启用（[TransactionRepository.insertIfNotDuplicate] 的
 * `crossSourceWindowMs`，默认 0 = 关闭），所以通知/短信/手工记账不会走跨来源判定。
 *
 * 判据分两级，因为「不写新行」判错就等于用户看不见那笔钱：
 * - **强证据**：两侧都存在且相等的平台内订单号（[MatchStrength.STRONG]）。命中即
 *   确认是同一笔，直接不插新行。
 * - **弱证据**（[MatchStrength.WEAK]，只有调用方显式允许时才计算）：一侧完全没有
 *   商户信息（空 / 「微信支付」这类占位名），另一侧有真实商户，金额、方向、币种、
 *   平台、时间窗全部一致。自动路径把候选留在待确认列表；用户确认后保留为独立新行。
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
            // 对侧必须来自另一条通道；同通道重放只由精确订单号或稳定事件指纹拦截。
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
