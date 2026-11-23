package com.bookkeeping.app.capture.screenshot

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.diagnostics.TransactionDiagnosticInput
import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.CrossSourceDuplicatePolicy
import com.bookkeeping.app.data.repository.DuplicateReason
import com.bookkeeping.app.data.repository.LedgerInsertResult
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.LedgerFilterChip
import com.bookkeeping.app.ui.screen.capture.CaptureImagePreview
import com.bookkeeping.app.ui.screen.capture.CaptureReviewDirection
import com.bookkeeping.app.ui.screen.capture.CaptureReviewMapping
import com.bookkeeping.app.ui.screen.capture.LikelyDuplicateNotice
import com.bookkeeping.app.ui.screen.transactions.LedgerTransactionRow
import com.bookkeeping.app.ui.screen.transactions.TransactionDetailScreenContent
import com.bookkeeping.app.ui.screen.transactions.TransactionEditFields
import com.bookkeeping.app.ui.screen.transactions.TransactionEditRules
import com.bookkeeping.app.ui.theme.BookkeepingAppTheme
import dagger.hilt.android.EntryPointAccessors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 截图批量导入页：统一承载应用内选图（点击后才弹出，单次最多 10 张）与系统分享
 * （ACTION_SEND / ACTION_SEND_MULTIPLE）。
 *
 * 导入完成后停留在结果页，按「先清待办」顺序展示：
 * 顶部进度/汇总 → 待你确认区 → 本次已记入账本（主页同款卡片，点击可进详情）→
 * 可折叠的重复/未识别说明；由用户点击「完成」主动退出。
 */
class ScreenshotImportActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: ScreenshotIntakeCoordinator
    private lateinit var store: CaptureCandidateStore
    private lateinit var evidenceStore: ReceiptEvidenceStore
    private var repository: TransactionRepository? = null
    private val settings by lazy { SettingsPreferences(applicationContext) }

    private val review = mutableStateOf(ScreenshotImportReviewState())
    private val batchProgress = mutableStateOf<BatchProgress?>(null)
    private val message = mutableStateOf<String?>(null)
    private val selectedTransactionId = mutableStateOf<Long?>(null)
    private val highlightedTransactionId = mutableStateOf<Long?>(null)
    private val lastIgnoredCandidate = mutableStateOf<CaptureCandidate?>(null)
    private var batchJob: Job? = null
    private var sessionImageCounter: Int = 0

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ScreenshotBatchPolicy.MAX_BATCH_IMAGES),
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val capped = ScreenshotBatchPolicy.capSelection(uris, ScreenshotBatchPolicy.MAX_BATCH_IMAGES)
        if (capped.truncated) {
            Toast.makeText(
                this,
                tr("单次最多导入 %d 张截图，已自动选取前 %d 张").format(
                    ScreenshotBatchPolicy.MAX_BATCH_IMAGES,
                    ScreenshotBatchPolicy.MAX_BATCH_IMAGES,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
        ingestBatch(capped.accepted, IntakeSource.PHOTO_PICKER)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = ScreenshotIntakeCoordinator(applicationContext)
        store = CaptureCandidateStore(applicationContext)
        evidenceStore = ReceiptEvidenceStore(applicationContext)
        repository = runCatching {
            EntryPointAccessors.fromApplication(
                applicationContext,
                ScreenshotImportEntryPoint::class.java,
            ).transactionRepository()
        }.getOrNull()
        restoreRetainedReview()
        applyEdgeToEdge()

        setContent {
            BookkeepingAppTheme {
                val allTransactions by (repository?.observeAll() ?: flowOf(emptyList()))
                    .collectAsState(initial = emptyList())
                val state = review.value
                val byId = remember(allTransactions) { allTransactions.associateBy { it.id } }
                val bookedTransactions = remember(state.bookedTransactionIds, byId) {
                    state.bookedTransactionIds.mapNotNull { byId[it] }
                }
                val activeDetailId = selectedTransactionId.value
                if (activeDetailId != null) {
                    BackHandler { selectedTransactionId.value = null }
                    TransactionDetailScreenContent(
                        entry = byId[activeDetailId],
                        onBack = { selectedTransactionId.value = null },
                        onDeleted = { selectedTransactionId.value = null },
                        onSave = { current, fields -> saveEditedTransaction(current, fields) },
                        onToggleExclude = { current -> toggleExcludeTransaction(current) },
                        onSaveMerchantRule = { merchant, category ->
                            saveMerchantRule(merchant, category)
                        },
                        onDelete = { current -> deleteTransaction(current) },
                        onConfirmEventTime = { current -> confirmTransactionTime(current) },
                        onUpdateEventTime = { current, pickedMs ->
                            updateTransactionTime(current, pickedMs)
                        },
                    )
                } else {
                    ScreenshotImportScreen(
                        state = state,
                        bookedTransactions = bookedTransactions,
                        highlightedTransactionId = highlightedTransactionId.value,
                        onHighlightFinished = { id ->
                            if (highlightedTransactionId.value == id) {
                                highlightedTransactionId.value = null
                            }
                        },
                        progress = batchProgress.value,
                        onStopBatch = { stopBatch() },
                        lastIgnored = lastIgnoredCandidate.value,
                        onUndoIgnore = { candidate -> undoIgnore(candidate) },
                        onUndoDismissed = { lastIgnoredCandidate.value = null },
                        message = message.value,
                        onClose = { close() },
                        onPickImages = { openPicker() },
                        onOpenTransaction = { id -> selectedTransactionId.value = id },
                        onConfirm = { candidate, merchant, category, direction ->
                            confirm(candidate, merchant, category, direction)
                        },
                        onIgnore = { candidate -> ignore(candidate) },
                    )
                }
            }
        }

        // 仅外部系统分享进来时直接开始识别；App 内点进时停留在落地页，由用户点按钮再弹选图器。
        if (!review.value.hasAnyContent) {
            when (intent?.action) {
                Intent.ACTION_SEND -> handleSendSingle(intent)
                Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(intent)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreRetainedReview() {
        (lastCustomNonConfigurationInstance as? ScreenshotImportReviewState)?.let { kept ->
            review.value = kept
        }
    }

    private fun applyEdgeToEdge() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val transparent = AndroidColor.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (dark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            },
            navigationBarStyle = if (dark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            },
        )
    }

    private fun openPicker() {
        message.value = null
        runCatching {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            message.value = tr("当前系统不支持图片选择器")
        }
    }

    private fun handleSendSingle(intent: Intent) {
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri == null) {
            message.value = tr("没有收到图片")
            return
        }
        ingestBatch(listOf(uri), IntakeSource.SHARE)
    }

    private fun handleSendMultiple(intent: Intent) {
        val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
        if (uris.isEmpty()) {
            message.value = tr("没有收到图片")
            return
        }
        val capped = ScreenshotBatchPolicy.capSelection(uris, ScreenshotBatchPolicy.MAX_BATCH_IMAGES)
        if (capped.truncated) {
            Toast.makeText(
                this,
                tr("单次最多导入 %d 张截图，已自动选取前 %d 张").format(
                    ScreenshotBatchPolicy.MAX_BATCH_IMAGES,
                    ScreenshotBatchPolicy.MAX_BATCH_IMAGES,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
        ingestBatch(capped.accepted, IntakeSource.SHARE)
    }

    private fun ingestBatch(uris: List<Uri>, source: IntakeSource) {
        if (uris.isEmpty()) return
        message.value = null
        val total = uris.size
        val hadContentBefore = review.value.hasAnyContent
        batchProgress.value = BatchProgress(current = 1, total = total)
        batchJob?.cancel()
        batchJob = scope.launch {
            try {
                uris.forEachIndexed { index, uri ->
                    val globalIndex = ++sessionImageCounter
                    runOnUiThread {
                        batchProgress.value = BatchProgress(current = index + 1, total = total)
                    }
                    val outcome = runCatching { coordinator.ingest(uri, source) }.getOrNull()
                    runOnUiThread {
                        applyOutcome(
                            outcome = outcome,
                            globalIndex = globalIndex,
                            isMultiImage = total > 1 || hadContentBefore,
                            showDuplicateToast = hadContentBefore && total == 1,
                        )
                    }
                }
            } finally {
                runOnUiThread {
                    batchProgress.value = null
                }
            }
        }
    }

    private fun stopBatch() {
        batchJob?.cancel()
        batchJob = null
        batchProgress.value = null
        Toast.makeText(this, tr("已停止识别剩余截图"), Toast.LENGTH_SHORT).show()
    }

    private fun applyOutcome(
        outcome: ScreenshotIntakeCoordinator.Outcome?,
        globalIndex: Int,
        isMultiImage: Boolean,
        showDuplicateToast: Boolean,
    ) {
        if (outcome == null) {
            val detail = tr("图片读不出来")
            val note = if (isMultiImage) tr("第 %d 张：%s").format(globalIndex, detail) else detail
            review.value = review.value.withNotice(note)
            if (showDuplicateToast) Toast.makeText(this, detail, Toast.LENGTH_SHORT).show()
            return
        }
        var next = review.value
        if (outcome.bookedTransactionIds.isNotEmpty()) {
            next = next.withBookedTransactions(outcome.bookedTransactionIds)
        }
        if (outcome.confirmationCandidates.isNotEmpty()) {
            next = next.withCandidates(outcome.confirmationCandidates)
        }
        val hasDirectResult = outcome.bookedTransactionIds.isNotEmpty() ||
            outcome.confirmationCandidates.isNotEmpty()
        if (!hasDirectResult) {
            val detail = describe(outcome)
            val note = if (isMultiImage) tr("第 %d 张：%s").format(globalIndex, detail) else detail
            next = next.withNotice(note)
            if (showDuplicateToast || outcome.repeatImage) {
                Toast.makeText(this, detail, Toast.LENGTH_SHORT).show()
            }
        }
        review.value = next
    }

    private fun describe(outcome: ScreenshotIntakeCoordinator.Outcome): String {
        val failureMessage = outcome.failure?.let { failure ->
            ScreenshotIntakeFailureNoticeCopy.activityMessage(failure) { tr(it) }
        }
        val base = failureMessage ?: when {
            outcome.repeatImage -> tr("这张截图已经处理过")
            outcome.booked > 0 && outcome.pending == 0 -> tr("已记录 %d 笔").format(outcome.booked)
            outcome.pending > 0 -> tr("有 %d 笔等你确认").format(outcome.pending)
            outcome.waiting > 0 -> tr("交易还没完成，稍后再看")
            outcome.linked > 0 -> tr("这笔已经在账本里")
            else -> tr("没有可记录的交易")
        }
        return if (outcome.superseded > 0) {
            base + tr("（已更新 %d 笔旧记录）").format(outcome.superseded)
        } else {
            base
        }
    }

    private fun confirm(
        candidate: CaptureCandidate,
        merchant: String,
        category: TransactionCategory,
        direction: String?,
    ) {
        val state = review.value
        if (!state.canConfirm(candidate.candidateId)) return
        val resolved = CaptureReviewDirection.resolve(candidate, direction)
        if (!CaptureBookGate.mayConfirm(resolved) || resolved.amountCents == null) {
            message.value = if (
                CaptureReviewDirection.requiresSelection(candidate) &&
                !CaptureReviewDirection.isChoice(direction)
            ) {
                tr("请先选择收支方向")
            } else {
                tr("仅预览，不能记入账本")
            }
            return
        }
        review.value = state.begin(candidate.candidateId)
        scope.launch {
            val outcome = writeToLedger(resolved, merchant.trim(), category)
            runOnUiThread {
                when (outcome) {
                    is WriteOutcome.Saved -> {
                        review.value = review.value.save(
                            candidateId = candidate.candidateId,
                            transactionId = outcome.transactionId,
                        )
                        highlightedTransactionId.value = outcome.transactionId
                    }
                    is WriteOutcome.SuspectedDuplicate -> {
                        Toast.makeText(
                            this@ScreenshotImportActivity,
                            tr("疑似与账本里已有的一笔重复，请核对后再决定（已有记录 #%d）")
                                .format(outcome.existingTransactionId),
                            Toast.LENGTH_LONG,
                        ).show()
                        review.value = review.value.fail(candidate.candidateId)
                    }
                    WriteOutcome.ALREADY_EXISTS -> {
                        Toast.makeText(
                            this@ScreenshotImportActivity,
                            tr("这笔交易已在账本中"),
                            Toast.LENGTH_SHORT,
                        ).show()
                        review.value = review.value.skip(candidate.candidateId)
                    }
                    WriteOutcome.NOT_WRITABLE -> {
                        review.value = review.value.fail(candidate.candidateId)
                        message.value = tr("仅预览，不能记入账本")
                    }
                    WriteOutcome.FAILED -> {
                        review.value = review.value.fail(candidate.candidateId)
                        Toast.makeText(
                            this@ScreenshotImportActivity,
                            tr("暂时无法记录，请稍后重试"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    private suspend fun writeToLedger(
        candidate: CaptureCandidate,
        merchant: String,
        category: TransactionCategory,
    ): WriteOutcome = withContext(Dispatchers.IO) {
        if (!CaptureBookGate.mayConfirm(candidate) || candidate.amountCents == null) {
            return@withContext WriteOutcome.NOT_WRITABLE
        }
        val repo = repository ?: return@withContext WriteOutcome.FAILED
        val evidenceImageName = evidenceStore.archiveFromPath(candidate.screenshotPath)
        val entity = try {
            CaptureReviewMapping.toTransactionEntity(
                candidate = candidate,
                merchant = merchant,
                category = category,
                evidenceImageName = evidenceImageName,
            )
        } catch (_: Exception) {
            return@withContext WriteOutcome.FAILED
        }
        val inserted: LedgerInsertResult = try {
            repo.insertIfNotDuplicateResult(
                transaction = entity,
                notifySuccess = false,
                diagnosticInput = TransactionDiagnosticInput(
                    attemptId = candidate.diagnosticAttemptId ?: TransactionDiagnostics.newAttemptId(),
                ),
                crossSourceWindowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                holdWeakCrossSourceMatch = true,
                mergeConfirmedCrossSource = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext WriteOutcome.FAILED
        }
        return@withContext when (inserted) {
            is LedgerInsertResult.Inserted -> {
                finalizeSavedCandidate(candidate, merchant, category)
                WriteOutcome.Saved(inserted.transactionId)
            }
            is LedgerInsertResult.Merged -> {
                finalizeSavedCandidate(candidate, merchant, category)
                WriteOutcome.Saved(inserted.transactionId)
            }
            is LedgerInsertResult.Duplicate -> when (inserted.reason) {
                DuplicateReason.CROSS_SOURCE,
                DuplicateReason.CROSS_SOURCE_WEAK,
                -> WriteOutcome.SuspectedDuplicate(inserted.existingTransactionId)
                DuplicateReason.SOURCE_ORDER_ID -> {
                    store.mark(candidate, CaptureCandidate.STATUS_IGNORED)
                    WriteOutcome.ALREADY_EXISTS
                }
            }
            is LedgerInsertResult.Failed -> WriteOutcome.FAILED
        }
    }

    private suspend fun finalizeSavedCandidate(
        candidate: CaptureCandidate,
        merchant: String,
        category: TransactionCategory,
    ) {
        store.mark(candidate, CaptureCandidate.STATUS_APPROVED)
        if (merchant.isNotBlank()) {
            try {
                settings.recordCategoryLearning(merchant, category)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Category learning is best effort; the booking already landed.
            }
        }
    }

    private fun saveEditedTransaction(original: TransactionEntity, fields: TransactionEditFields) {
        val now = System.currentTimeMillis()
        if (!TransactionEditRules.errorsOf(fields, now).isValid) return
        if (!TransactionEditRules.hasChanges(original, fields)) return
        val repo = repository ?: return
        scope.launch {
            repo.update(TransactionEditRules.applyTo(original, fields), TransactionDiagnosticInput())
            val category = runCatching { TransactionCategory.valueOf(fields.category) }.getOrNull()
            if (category != null && original.category != fields.category && fields.merchant.isNotBlank()) {
                runCatching { settings.recordCategoryLearning(fields.merchant, category) }
            }
        }
    }

    private fun toggleExcludeTransaction(entity: TransactionEntity) {
        if (entity.remoteReviewStatus == "refund") return
        val nextStatus = if (entity.remoteReviewStatus == "excluded") "unverified" else "excluded"
        val repo = repository ?: return
        scope.launch {
            repo.update(
                entity.copy(
                    remoteReviewStatus = nextStatus,
                    duplicateOfId = if (nextStatus == "excluded") null else entity.duplicateOfId,
                ),
            )
        }
    }

    private fun saveMerchantRule(merchant: String, category: TransactionCategory) {
        if (merchant.isBlank()) return
        scope.launch { settings.rememberMerchantCategory(merchant, category) }
    }

    private fun deleteTransaction(entity: TransactionEntity) {
        val repo = repository ?: return
        scope.launch { repo.delete(entity) }
    }

    private fun confirmTransactionTime(entity: TransactionEntity) {
        if (!entity.eventTimeNeedsConfirmation) return
        val repo = repository ?: return
        scope.launch {
            repo.update(entity.copy(eventTimeNeedsConfirmation = false))
        }
    }

    private fun updateTransactionTime(entity: TransactionEntity, timestampMs: Long) {
        if (TransactionEditRules.timestampError(timestampMs, System.currentTimeMillis()) != null) return
        if (entity.timestamp == timestampMs && !entity.eventTimeNeedsConfirmation) return
        val repo = repository ?: return
        scope.launch {
            repo.update(
                entity.copy(
                    timestamp = timestampMs,
                    eventTimeNeedsConfirmation = false,
                ),
            )
        }
    }

    private fun ignore(candidate: CaptureCandidate) {
        if (!review.value.canConfirm(candidate.candidateId)) return
        review.value = review.value.begin(candidate.candidateId)
        scope.launch {
            val ignored = runCatching {
                store.mark(candidate, CaptureCandidate.STATUS_IGNORED)
            }.getOrDefault(false)
            runOnUiThread {
                if (ignored) {
                    review.value = review.value.skip(candidate.candidateId)
                    lastIgnoredCandidate.value = candidate
                } else {
                    review.value = review.value.fail(candidate.candidateId)
                    Toast.makeText(
                        this@ScreenshotImportActivity,
                        tr("候选状态未能更新，请重试"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun undoIgnore(candidate: CaptureCandidate) {
        lastIgnoredCandidate.value = null
        scope.launch {
            val restored = runCatching {
                store.mark(candidate, CaptureCandidate.STATUS_PENDING)
            }.getOrDefault(false)
            runOnUiThread {
                if (restored) {
                    review.value = review.value.restore(candidate.candidateId)
                } else {
                    Toast.makeText(
                        this@ScreenshotImportActivity,
                        tr("撤销失败，请稍后重试"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun close() {
        if (!isFinishing) finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any = review.value

    override fun onDestroy() {
        coordinator.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ScreenshotImportActivity::class.java)

        fun requiredPermissions(): List<String> = emptyList()
    }
}

private data class BatchProgress(
    val current: Int,
    val total: Int,
)

private sealed interface WriteOutcome {
    data class Saved(val transactionId: Long) : WriteOutcome
    data object ALREADY_EXISTS : WriteOutcome
    data class SuspectedDuplicate(val existingTransactionId: Long) : WriteOutcome
    data object NOT_WRITABLE : WriteOutcome
    data object FAILED : WriteOutcome
}

private data class ImportDraft(
    val merchant: String,
    val category: TransactionCategory,
    val directionChoice: String? = null,
) {
    companion object {
        fun of(candidate: CaptureCandidate) = ImportDraft(
            merchant = candidate.merchantCandidates.firstOrNull().orEmpty(),
            category = initialCategory(candidate),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenshotImportScreen(
    state: ScreenshotImportReviewState,
    bookedTransactions: List<TransactionEntity>,
    progress: BatchProgress?,
    message: String?,
    onClose: () -> Unit,
    onPickImages: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onConfirm: (CaptureCandidate, String, TransactionCategory, String?) -> Unit,
    onIgnore: (CaptureCandidate) -> Unit,
) {
    val drafts = remember { mutableStateMapOf<String, ImportDraft>() }
    var noticesExpanded by remember { mutableStateOf(false) }
    var previewScreenshotPath by remember { mutableStateOf<String?>(null) }
    val pendingRows = state.pendingRows
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = tr("截图记账"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr("返回"),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            if (state.hasAnyContent && progress == null) {
                ImportResultBottomBar(
                    onPickImages = onPickImages,
                    onClose = onClose,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (progress != null) {
                item(key = "recognizing") {
                    RecognizingCard(progress = progress, onStopBatch = onStopBatch)
                }
            }
            if (!state.hasAnyContent) {
                if (progress == null) {
                    item(key = "pick") {
                        PickImageCard(message = message, onPickImages = onPickImages)
                    }
                }
            } else {
                item(key = "summary") {
                    ImportSummaryBanner(
                        bookedCount = bookedTransactions.size,
                        pendingCount = pendingRows.size,
                        noticeCount = state.notices.size,
                        message = message,
                    )
                }

                // 1. 先清待办：需要手动确认的候选排在最前面
                if (pendingRows.isNotEmpty()) {
                    item(key = "pending-header") {
                        SectionTitle(
                            title = tr("待你确认"),
                            count = pendingRows.size,
                        )
                    }
                    items(pendingRows, key = { "pending-${it.candidate.candidateId}" }) { row ->
                        ImportCandidateCard(
                            row = row,
                            draft = drafts[row.candidate.candidateId] ?: ImportDraft.of(row.candidate),
                            onDraftChange = { next -> drafts[row.candidate.candidateId] = next },
                            onPreviewImage = { path -> previewScreenshotPath = path },
                            onConfirm = { merchant, category, direction ->
                                onConfirm(row.candidate, merchant, category, direction)
                            },
                            onIgnore = { onIgnore(row.candidate) },
                        )
                    }
                }

                // 2. 本次已记入账本：复用主页同款账单卡片，点击直达账单详情
                if (bookedTransactions.isNotEmpty()) {
                    item(key = "booked-header") {
                        SectionTitle(
                            title = tr("本次已记入账本"),
                            count = bookedTransactions.size,
                        )
                    }
                    item(key = "booked-card") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                bookedTransactions.forEachIndexed { index, transaction ->
                                    LedgerTransactionRow(
                                        transaction = transaction,
                                        timeText = timeFormat.format(Date(transaction.timestamp)),
                                        highlightedTransactionId = highlightedTransactionId,
                                        onHighlightFinished = onHighlightFinished,
                                        onClick = { onOpenTransaction(transaction.id) },
                                    )
                                    if (index != bookedTransactions.lastIndex) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 重复 / 未识别说明（默认折叠，按需展开）
                if (state.notices.isNotEmpty()) {
                    item(key = "notices") {
                        CollapsibleNoticesCard(
                            notices = state.notices,
                            expanded = noticesExpanded,
                            onToggle = { noticesExpanded = !noticesExpanded },
                        )
                    }
                }
            }
        }
    }

    previewScreenshotPath?.let { path ->
        CaptureImagePreview(
            path = path,
            onDismiss = { previewScreenshotPath = null },
        )
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportSummaryBanner(
    bookedCount: Int,
    pendingCount: Int,
    noticeCount: Int,
    message: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val summary = buildList {
                add(tr("已记 %d 笔").format(bookedCount))
                if (pendingCount > 0) add(tr("待确认 %d 笔").format(pendingCount))
                if (noticeCount > 0) add(tr("跳过或未识别 %d 项").format(noticeCount))
            }.joinToString(" · ")
            Text(
                text = summary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (pendingCount > 0) {
                    tr("先确认待办，点下方账单卡片可查看原图或修改")
                } else {
                    tr("点击下方账单卡片可查看扫描原图、修改时间或分类")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CollapsibleNoticesCard(
    notices: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("未记入说明（%d）").format(notices.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    notices.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickImageCard(message: String?, onPickImages: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                text = tr("从相册选截图"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = tr("支持批量导入微信 / 支付宝交易截图，单次最多 10 张"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = tr("扫描只在本机保存结构化字段和截图，确认后才会写入账本。"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = onPickImages,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(tr("选择截图"))
            }
        }
    }
}

@Composable
private fun RecognizingCard(progress: BatchProgress, onStopBatch: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (progress.total > 1) {
                        tr("正在识别第 %d / %d 张截图").format(progress.current, progress.total)
                    } else {
                        tr("正在识别截图")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = tr("识别微信 / 支付宝交易截图"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress.total > 1) {
                TextButton(onClick = onStopBatch) {
                    Text(tr("停止剩余"))
                }
            }
        }
    }
}

@Composable
private fun ImportResultBottomBar(
    onPickImages: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onPickImages,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(tr("继续导入"))
                }
                Button(
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("完成"))
                }
            }
        }
    }
}

@Composable
private fun ImportCandidateCard(
    row: ScreenshotImportReviewState.Row,
    draft: ImportDraft,
    onDraftChange: (ImportDraft) -> Unit,
    onPreviewImage: (String) -> Unit,
    onConfirm: (String, TransactionCategory, String?) -> Unit,
    onIgnore: () -> Unit,
) {
    val candidate = row.candidate
    val saving = row.status == ScreenshotImportReviewState.Status.SAVING
    val merchant = draft.merchant
    val category = draft.category
    val needsDirection = CaptureReviewDirection.requiresSelection(candidate)
    val directionChoice = draft.directionChoice
    var categoryMenu by remember(candidate.candidateId) { mutableStateOf(false) }
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, candidate.screenshotPath) {
        value = candidate.screenshotPath?.let { path ->
            withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }
        }
    }
    val canBook = candidate.amountCents != null &&
        CaptureReviewDirection.canConfirm(candidate, directionChoice)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val image = thumbnail
                val path = candidate.screenshotPath
                if (image != null) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .then(
                                if (!path.isNullOrBlank()) {
                                    Modifier.clickable { onPreviewImage(path) }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Image(
                            bitmap = image,
                            contentDescription = tr("扫描截图"),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (!path.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(3.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ZoomIn,
                                    contentDescription = tr("查看大图"),
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                } else {
                    CategoryIcon(category = category, size = 56.dp)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = amountLabel(candidate, directionChoice),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = amountColor(candidate, directionChoice),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = confidenceLabel(candidate),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = timeLabel(candidate),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            LikelyDuplicateNotice(candidate)
            CaptureReviewMapping.screenshotUnavailableNotice(candidate)?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (needsDirection) {
                if (directionChoice == null) {
                    Text(
                        text = tr("请先选择收支方向"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureReviewDirection.directions.forEach { option ->
                        LedgerFilterChip(
                            label = if (option == CaptureCandidate.DIRECTION_INCOME) {
                                tr("收入")
                            } else {
                                tr("支出")
                            },
                            selected = directionChoice == option,
                            onClick = { onDraftChange(draft.copy(directionChoice = option)) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = merchant,
                onValueChange = { onDraftChange(draft.copy(merchant = it)) },
                label = { Text(tr("商户")) },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            if (candidate.merchantCandidates.size > 1) {
                Text(
                    text = tr("候选：") + candidate.merchantCandidates.joinToString(" / "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (candidate.eventTimeNeedsConfirmation) {
                Text(
                    text = tr("时间待确认"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!candidate.ledgerWriteAllowed) {
                Text(
                    text = tr("仅预览，不能记入账本"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { categoryMenu = true }, enabled = !saving) {
                        Text(tr(category.displayName))
                    }
                    DropdownMenu(
                        expanded = categoryMenu,
                        onDismissRequest = { categoryMenu = false },
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        TransactionCategory.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(tr(option.displayName)) },
                                onClick = {
                                    onDraftChange(draft.copy(category = option))
                                    categoryMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onIgnore, enabled = !saving) {
                    Text(tr("忽略"))
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { onConfirm(merchant, category, directionChoice) }, enabled = !saving && canBook) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(tr("正在保存…"))
                    } else {
                        Text(tr("记入账本"))
                    }
                }
            }
        }
    }
}

private fun amountLabel(candidate: CaptureCandidate, directionChoice: String?): String {
    val cents = candidate.amountCents ?: return tr("金额待补")
    val directionUnknown = candidate.direction == CaptureCandidate.DIRECTION_UNKNOWN
    val income = when {
        candidate.kind == CaptureCandidate.KIND_REFUND -> true
        directionUnknown -> directionChoice == CaptureCandidate.DIRECTION_INCOME
        else -> candidate.direction == CaptureCandidate.DIRECTION_INCOME
    }
    val sign = when {
        directionUnknown && directionChoice == null -> "¥"
        income -> "+¥"
        else -> "−¥"
    }
    return sign + "%.2f".format(Locale.CHINA, cents / 100.0)
}

@Composable
private fun amountColor(candidate: CaptureCandidate, directionChoice: String?): Color {
    val directionUnknown = candidate.direction == CaptureCandidate.DIRECTION_UNKNOWN
    if (directionUnknown && directionChoice == null) return MaterialTheme.colorScheme.onSurface
    val income = when {
        candidate.kind == CaptureCandidate.KIND_REFUND -> true
        directionUnknown -> directionChoice == CaptureCandidate.DIRECTION_INCOME
        else -> candidate.direction == CaptureCandidate.DIRECTION_INCOME
    }
    return if (income) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
}

private fun confidenceLabel(candidate: CaptureCandidate): String = tr(
    when (candidate.confidenceTier) {
        CaptureCandidate.TIER_HIGH -> "高置信度"
        CaptureCandidate.TIER_LOW -> "低置信度"
        else -> "需要确认"
    },
)

private fun timeLabel(candidate: CaptureCandidate): String {
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        .format(Date(candidate.eventTimeMs ?: candidate.capturedAtMs))
    val order = candidate.orderId?.let { " · " + tr("单号") + " " + it }.orEmpty()
    return time + order
}

private fun initialCategory(candidate: CaptureCandidate): TransactionCategory = when (candidate.kind) {
    CaptureCandidate.KIND_TRANSFER,
    CaptureCandidate.KIND_WITHDRAWAL -> TransactionCategory.TRANSFER
    CaptureCandidate.KIND_RED_PACKET ->
        if (candidate.direction == CaptureCandidate.DIRECTION_INCOME) {
            TransactionCategory.GIFT
        } else {
            TransactionCategory.TRANSFER
        }
    else -> CaptureReviewMapping.defaultCategory(candidate)
}
