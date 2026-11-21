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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.bookkeeping.app.ui.screen.capture.CaptureReviewDirection
import com.bookkeeping.app.ui.screen.capture.CaptureReviewMapping
import com.bookkeeping.app.ui.screen.capture.LikelyDuplicateNotice
import com.bookkeeping.app.ui.theme.BookkeepingAppTheme
import dagger.hilt.android.EntryPointAccessors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One screen for both manual intakes: ACTION_SEND from another app, and the
 * in-app Photo Picker. Both hand the URI to [ScreenshotIntakeCoordinator], so
 * there is exactly one recognition path.
 *
 * A screenshot can hold several transactions, so the recognised candidates are
 * listed in one scrolling page and filled in any order: the user no longer has
 * to tap "next" once per transaction.
 */
class ScreenshotImportActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: ScreenshotIntakeCoordinator
    private lateinit var store: CaptureCandidateStore
    private var repository: TransactionRepository? = null
    private val settings by lazy { SettingsPreferences(applicationContext) }

    private val review = mutableStateOf(ScreenshotImportReviewState())
    private val recognizing = mutableStateOf(false)
    private val message = mutableStateOf<String?>(null)
    /** True when [message] is a recognition result: show it, then leave. */
    private val resultMessage = mutableStateOf(false)
    /** A picker is on screen; the summary must not close the page underneath it. */
    private val picking = mutableStateOf(false)

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        picking.value = false
        if (uri == null) {
            // Nothing has been imported yet, so cancelling the picker leaves
            // nothing on screen: close instead of showing an empty page.
            if (review.value.rows.isEmpty() && !recognizing.value) finish()
        } else {
            ingest(uri, IntakeSource.PHOTO_PICKER)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = ScreenshotIntakeCoordinator(applicationContext)
        store = CaptureCandidateStore(applicationContext)
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
                ScreenshotImportScreen(
                    state = review.value,
                    recognizing = recognizing.value,
                    message = message.value,
                    closeAfterMessage = resultMessage.value,
                    picking = picking.value,
                    onClose = { close() },
                    onPickImage = { openPicker() },
                    onConfirm = { candidate, merchant, category, direction ->
                        confirm(candidate, merchant, category, direction)
                    },
                    onIgnore = { candidate -> ignore(candidate) },
                )
            }
        }

        when (intent?.action) {
            // A rotation replays onCreate with the same intent; rows kept above
            // mean this image was already admitted, so nothing is re-ingested.
            Intent.ACTION_SEND -> if (review.value.rows.isEmpty()) handleSend(intent)
            // Re-opening the picker on every recreation would stack a second
            // system sheet on top of the first.
            else -> if (savedInstanceState == null && review.value.rows.isEmpty()) openPicker()
        }
    }

    /**
     * A rotation or a dark-mode switch recreates this screen. Without this, every
     * card the user already filled in would silently reset (and the share intent
     * would be ingested a second time).
     */
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
        picking.value = true
        message.value = null
        // A picker that cannot open stays on screen so the message is readable
        // and the row list is not thrown away.
        resultMessage.value = false
        runCatching {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            picking.value = false
            message.value = tr("当前系统不支持图片选择器")
        }
    }

    private fun handleSend(intent: Intent) {
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri == null) {
            resultMessage.value = false
            message.value = tr("没有收到图片")
            return
        }
        ingest(uri, IntakeSource.SHARE)
    }

    private fun ingest(uri: Uri, source: IntakeSource) {
        // Share intents and the Photo Picker both hand over a read grant with the
        // URI; a revoked grant surfaces as UNREADABLE / PERMISSION_DENIED below.
        recognizing.value = true
        message.value = null
        scope.launch {
            val outcome = runCatching { coordinator.ingest(uri, source) }.getOrNull()
            runOnUiThread { applyOutcome(outcome) }
        }
    }

    private fun applyOutcome(outcome: ScreenshotIntakeCoordinator.Outcome?) {
        recognizing.value = false
        if (outcome == null) {
            resultMessage.value = true
            message.value = tr("图片读不出来")
            return
        }
        if (outcome.confirmationCandidates.isNotEmpty()) {
            // Every recognised transaction joins the same page; nothing is queued
            // behind a "next" step.
            review.value = review.value.withCandidates(outcome.confirmationCandidates)
            message.value = null
            return
        }
        val text = describe(outcome)
        if (review.value.rows.isEmpty()) {
            resultMessage.value = true
            message.value = text
        } else {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun describe(outcome: ScreenshotIntakeCoordinator.Outcome): String {
        val base = when {
            outcome.failure == FailureReason.PERMISSION_DENIED -> tr("没有读取这张图片的权限")
            outcome.failure == FailureReason.URI_INVALID -> tr("图片地址已失效")
            outcome.failure == FailureReason.UNSUPPORTED_MIME -> tr("不是支持的图片格式")
            outcome.failure == FailureReason.UNREADABLE -> tr("图片读不出来")
            outcome.failure == FailureReason.OVERSIZED -> tr("图片太大，请先裁剪")
            outcome.failure == FailureReason.DECODE_FAILED -> tr("图片解码失败")
            outcome.failure == FailureReason.OCR_EMPTY -> tr("没有识别到文字")
            outcome.failure == FailureReason.NOT_TRANSACTION -> tr("这张图片里没有交易")
            outcome.failure == FailureReason.UNKNOWN_PAGE -> tr("没认出是微信或支付宝的交易页")
            outcome.repeatImage -> tr("这张截图已经处理过")
            outcome.booked > 0 && outcome.pending == 0 -> tr("已记录 %d 笔").format(outcome.booked)
            outcome.pending > 0 -> tr("有 %d 笔等你确认").format(outcome.pending)
            outcome.waiting > 0 -> tr("交易还没完成，稍后再看")
            outcome.linked > 0 -> tr("这笔已经在账本里")
            else -> tr("没有可记录的交易")
        }
        // Waiting placeholders this import closed are part of the result: say so
        // instead of silently dropping them from the count.
        return if (outcome.superseded > 0) {
            base + tr("（已更新 %d 笔旧记录）").format(outcome.superseded)
        } else {
            base
        }
    }

    /**
     * Books one card with the merchant and category the user left in the row.
     *
     * The review page writes through the same repository call as the capture
     * inbox, so edits are honoured; [ScreenshotIntakeCoordinator.confirmCandidate]
     * cannot take them because it re-derives merchant/category from the stored
     * candidate.
     */
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
                    WriteOutcome.SAVED -> review.value = review.value.save(candidate.candidateId)
                    is WriteOutcome.SuspectedDuplicate -> {
                        // 仓库已经先试过合并；这里剩下的是无法安全合并的跨通道匹配。
                        // 卡片留在待确认，绝不因为「已在账本中」把用户的信息丢掉。
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
        val entity = try {
            CaptureReviewMapping.toTransactionEntity(
                candidate = candidate,
                merchant = merchant,
                category = category,
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
                // 审阅页的确认同样是「截图通道」：账本里可能已有通知记下的同一笔。
                // 用户已经确认要记，就把截图的商户/订单号并进那一行，而不是再插一行、
                // 也不是把卡片当成「已存在」丢掉。
                crossSourceWindowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                // 用户确认时同样把「检测到但合不了」的跨通道疑似重复留在待确认，
                // 不悄悄写成第二行。
                holdWeakCrossSourceMatch = true,
                mergeConfirmedCrossSource = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext WriteOutcome.FAILED
        }
        return@withContext when (inserted) {
            is LedgerInsertResult.Inserted, is LedgerInsertResult.Merged -> {
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
                WriteOutcome.SAVED
            }
            is LedgerInsertResult.Duplicate -> when (inserted.reason) {
                // 跨通道的同一笔只有在无法安全合并时才会走到这里（商户矛盾、
                // 订单号矛盾、超出合并窗口）：卡片保留待确认，账本不动。
                DuplicateReason.CROSS_SOURCE,
                DuplicateReason.CROSS_SOURCE_WEAK,
                -> WriteOutcome.SuspectedDuplicate(inserted.existingTransactionId)
                // 同一通道的精确重发：账本已经有这一行，卡片可以收起。
                DuplicateReason.SOURCE_ORDER_ID,
                DuplicateReason.SAME_SOURCE_WINDOW,
                DuplicateReason.SAME_PAGE_IDENTITY,
                -> {
                    store.mark(candidate, CaptureCandidate.STATUS_IGNORED)
                    WriteOutcome.ALREADY_EXISTS
                }
            }
            is LedgerInsertResult.Failed -> WriteOutcome.FAILED
        }
    }

    private fun ignore(candidate: CaptureCandidate) {
        if (!review.value.canConfirm(candidate.candidateId)) return
        review.value = review.value.begin(candidate.candidateId)
        scope.launch {
            // Marked through the store, exactly like the capture inbox: the
            // coordinator's ignoreCandidate() looks the card up in
            // store.pending(), which returns only the oldest card per image, so
            // the second transaction of one screenshot could never be ignored.
            val ignored = runCatching {
                store.mark(candidate, CaptureCandidate.STATUS_IGNORED)
            }.getOrDefault(false)
            runOnUiThread {
                if (ignored) {
                    review.value = review.value.skip(candidate.candidateId)
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

    private fun close() {
        if (!isFinishing) finish()
    }

    /** Keeps the reviewed rows (and their settled status) across recreation. */
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

private sealed interface WriteOutcome {
    data object SAVED : WriteOutcome
    data object ALREADY_EXISTS : WriteOutcome
    data class SuspectedDuplicate(val existingTransactionId: Long) : WriteOutcome
    data object NOT_WRITABLE : WriteOutcome
    data object FAILED : WriteOutcome
}

/**
 * What the user typed for one row.
 *
 * Held above the LazyColumn: a row that scrolls out of the viewport is disposed,
 * and its remember{} state with it, so merchant/category edits would silently
 * revert if they lived inside the card.
 */
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
    recognizing: Boolean,
    message: String?,
    closeAfterMessage: Boolean,
    picking: Boolean,
    onClose: () -> Unit,
    onPickImage: () -> Unit,
    onConfirm: (CaptureCandidate, String, TransactionCategory, String?) -> Unit,
    onIgnore: (CaptureCandidate) -> Unit,
) {
    // Everything handled -> summary, then leave by itself (完成 closes sooner).
    // A one-off result (nothing recognised) leaves the same way, matching the old
    // toast-and-exit flow. Never close while the photo picker is open on top.
    LaunchedEffect(state.isDone, picking, message, closeAfterMessage, recognizing) {
        val closesAfterSummary = state.isDone && !picking
        val closesAfterMessage = closeAfterMessage && !recognizing &&
            state.rows.isEmpty() && message != null && !picking
        if (closesAfterSummary || closesAfterMessage) {
            delay(if (closesAfterSummary) 2600 else 2200)
            onClose()
        }
    }

    // Row edits live here, not inside the LazyColumn: a card that scrolls out is
    // disposed and would silently drop a half-typed merchant.
    val drafts = remember { mutableStateMapOf<String, ImportDraft>() }

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
            if (state.rows.isNotEmpty() && !state.isDone) {
                ImportBottomBar(state = state, onPickImage = onPickImage)
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
            if (recognizing) {
                item(key = "recognizing") { RecognizingCard() }
            }
            if (state.rows.isEmpty()) {
                if (!recognizing) {
                    item(key = "pick") { PickImageCard(message = message, onPickImage = onPickImage) }
                }
            } else {
                item(key = "header") {
                    if (state.isDone) {
                        SummaryCard(state = state, onClose = onClose)
                    } else {
                        ReviewHeader(state = state, message = message)
                    }
                }
                items(state.rows, key = { it.candidate.candidateId }) { row ->
                    when (row.status) {
                        ScreenshotImportReviewState.Status.PENDING,
                        ScreenshotImportReviewState.Status.SAVING -> ImportCandidateCard(
                            row = row,
                            draft = drafts[row.candidate.candidateId] ?: ImportDraft.of(row.candidate),
                            onDraftChange = { next -> drafts[row.candidate.candidateId] = next },
                            onConfirm = { merchant, category, direction ->
                                onConfirm(row.candidate, merchant, category, direction)
                            },
                            onIgnore = { onIgnore(row.candidate) },
                        )
                        // Settled rows collapse to one line: the page keeps the
                        // history of this import without shouting about it.
                        else -> SettledRow(row = row)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickImageCard(message: String?, onPickImage: () -> Unit) {
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
                text = tr("识别微信 / 支付宝交易截图"),
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
                onClick = onPickImage,
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
                Text(tr("选择图片"))
            }
        }
    }
}

@Composable
private fun RecognizingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = tr("正在识别截图"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = tr("识别微信 / 支付宝交易截图"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReviewHeader(state: ScreenshotImportReviewState, message: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = tr("全部 %d 笔都在这里，上下滑动逐笔确认").format(state.rows.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = tr("扫描只在本机保存结构化字段和截图，确认后才会写入账本。"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SummaryCard(state: ScreenshotImportReviewState, onClose: () -> Unit) {
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
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = tr("全部处理完了"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = tr("已记 %d 笔 · 跳过 %d 笔").format(state.savedCount, state.skippedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) { Text(tr("完成")) }
        }
    }
}

/** One compact line for a card that is already saved or skipped. */
@Composable
private fun SettledRow(row: ScreenshotImportReviewState.Row) {
    val saved = row.status == ScreenshotImportReviewState.Status.SAVED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (saved) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                tint = if (saved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = amountLabel(row.candidate, directionChoice = null),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (saved) tr("已记入账本") else tr("已跳过"),
                style = MaterialTheme.typography.labelLarge,
                color = if (saved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun ImportBottomBar(state: ScreenshotImportReviewState, onPickImage: () -> Unit) {
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = tr("还有 %d 笔待确认").format(state.pendingCount),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = tr("已记 %d 笔 · 跳过 %d 笔").format(state.savedCount, state.skippedCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onPickImage) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(tr("再选一张"))
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
    onConfirm: (String, TransactionCategory, String?) -> Unit,
    onIgnore: () -> Unit,
) {
    val candidate = row.candidate
    val saving = row.status == ScreenshotImportReviewState.Status.SAVING
    val merchant = draft.merchant
    val category = draft.category
    // 方向未知的候选只能由用户点选支出/收入后才能记入；默认不选中。
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
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = tr("扫描截图"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
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

            // 与待确认弹窗共用同一句提示和同一套文案，避免两个入口解释不一致。
            LikelyDuplicateNotice(candidate)

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

/** Amount with the sign the current direction implies; no sign while unknown. */
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
