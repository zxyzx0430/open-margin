package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.ui.navigation.LocalQingLedgerTransition
import com.bookkeeping.app.ui.components.QingConfirmationSheet

import com.bookkeeping.app.ui.components.MerchantAvatar
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.WalletCardSurface
import com.bookkeeping.app.ui.theme.QingTypeTokens
import com.bookkeeping.app.ui.theme.QingAmountIncome
import com.bookkeeping.app.ui.theme.QingAmountExpense
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.bookkeeping.app.ui.theme.rememberReduceMotion
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.capture.screenshot.ReceiptEvidenceStore
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.DateTimePickerTab
import com.bookkeeping.app.ui.components.LedgerDateTimePickerDialog
import com.bookkeeping.app.ui.components.MerchantRuleDialog
import com.bookkeeping.app.ui.components.StatusChip
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.screen.capture.CaptureImagePreview
import com.bookkeeping.app.ui.theme.LocalQingSemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账单详情：所有行内动作集中到这里。
 *
 * 版面自上而下：头部（返回 / 标题 / 同步胶囊）→ 金额主视觉卡 → 字段卡（含「发生时间」
 * 内嵌淡琥珀待确认条）→ 备注卡（可选）→ 扫描原图缩略图条（可选）→ 审核状态卡（可选）
 * → 固定底部动作区。内容区保留 verticalScroll 兜底，小屏或卡片较多时不会截断。
 */
@Composable
fun TransactionDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    detailViewModel: TransactionDetailViewModel = hiltViewModel(),
    transactionViewModel: TransactionViewModel = hiltViewModel(),
    initialEntry: TransactionEntity? = null,
) {
    val transaction by detailViewModel.transaction.collectAsState()
    val displayedEntry = if (transaction.loaded) transaction.entry else initialEntry
    TransactionDetailScreenContent(
        entry = displayedEntry,
        onBack = onBack,
        onDeleted = onDeleted,
        historyProvider = transactionViewModel::freshRuleHistory,
        animateReceipt = initialEntry != null,
        loadingEntry = !transaction.loaded && initialEntry == null,
        onSaveMerchantRuleWithHistory = { merchant, category, update -> transactionViewModel.saveMerchantRule(merchant, category, update) },
        onSave = { current, fields -> transactionViewModel.saveTransaction(current, fields) },
        onToggleExclude = { current ->
            transactionViewModel.setReviewStatus(
                current,
                if (current.remoteReviewStatus == "excluded") "unverified" else "excluded",
            )
        },
        onSaveMerchantRule = { merchant, category ->
            transactionViewModel.saveMerchantRule(merchant, category)
        },
        onDelete = { current -> transactionViewModel.delete(current) },
        onConfirmEventTime = { current -> transactionViewModel.confirmEventTime(current) },
        onUpdateEventTime = { current, pickedMs ->
            transactionViewModel.updateEventTime(current, pickedMs)
        },
    )
}

/**
 * 无 Hilt 依赖的账单详情页本体，供主页导航与截图导入结果页共同复用。
 */
@Composable
fun TransactionDetailScreenContent(
    entry: TransactionEntity?,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onSave: (TransactionEntity, TransactionEditFields) -> Unit,
    onToggleExclude: (TransactionEntity) -> Unit,
    onSaveMerchantRule: (String, TransactionCategory) -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    onConfirmEventTime: (TransactionEntity) -> Unit,
    onUpdateEventTime: (TransactionEntity, Long) -> Unit,
    historyTransactions: List<TransactionEntity> = emptyList(),
    historyProvider: (suspend () -> List<TransactionEntity>)? = null,
    onSaveMerchantRuleWithHistory: ((String, TransactionCategory, Boolean) -> Unit)? = null,
    animateReceipt: Boolean = false,
    loadingEntry: Boolean = false,
    inBottomSheet: Boolean = false,
    onEditingChanged: (Boolean) -> Unit = {},
) {
    // Room 的 Flow 首帧是 null。详情页现在是同一帧切过来（没有转场遮挡），
    // 直接渲染「这笔记录已不存在」会闪一下错误文案，所以先留一帧缓冲；
    // 真被删掉的记录 300ms 后照样给出提示。
    var missingConfirmed by remember { mutableStateOf(false) }
    LaunchedEffect(entry, loadingEntry) {
        if (entry == null && !loadingEntry) {
            delay(300)
            missingConfirmed = true
        } else {
            missingConfirmed = false
        }
    }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        onEditingChanged(editing)
    }
    var editingTimeInline by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var addingRule by remember { mutableStateOf(false) }
    var previewEvidencePath by remember { mutableStateOf<String?>(null) }

    if (editing && entry != null) {
        // 全屏编辑页替换详情页；保存/取消都回到这里，删除复用同一个二次确认。
        Box(
            modifier = if (inBottomSheet) Modifier.fillMaxWidth().fillMaxHeight(0.92f) else Modifier.fillMaxSize(),
        ) {
            TransactionEditScreen(
                initial = entry,
                onCancel = { editing = false },
                onDelete = { confirmingDelete = true },
                onSave = { fields ->
                    onSave(entry, fields)
                    editing = false
                },
            )
        }
    } else if (inBottomSheet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val syncLabel = entry?.let(::transactionSyncLabel)
                if (!syncLabel.isNullOrBlank()) {
                    StatusChip(text = syncLabel, tone = StatusTone.NEUTRAL)
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = tr("关闭"),
                        tint = qingColors.textSecondary,
                    )
                }
            }
            if (entry == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (missingConfirmed) {
                        Text(
                            text = tr("这笔记录已不存在"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                DetailContent(
                    entry = entry,
                    animateReceipt = animateReceipt,
                    onEditTime = { editingTimeInline = true },
                    onConfirmEventTime = { onConfirmEventTime(entry) },
                    onPreviewEvidence = { path -> previewEvidencePath = path },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                )
                DetailActionBar(
                    showExclude = entry.remoteReviewStatus != "refund",
                    excluded = entry.remoteReviewStatus == "excluded",
                    onToggleExclude = { onToggleExclude(entry) },
                    onAddRule = { addingRule = true },
                    onEdit = { editing = true },
                    onDelete = { confirmingDelete = true },
                )
            }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            topBar = {
                DetailHeader(
                    syncLabel = entry?.let(::transactionSyncLabel),
                    onBack = onBack,
                )
            },
            bottomBar = {
                if (entry != null) {
                    DetailActionBar(
                        showExclude = entry.remoteReviewStatus != "refund",
                        excluded = entry.remoteReviewStatus == "excluded",
                        onToggleExclude = { onToggleExclude(entry) },
                        onAddRule = { addingRule = true },
                        onEdit = { editing = true },
                        onDelete = { confirmingDelete = true },
                    )
                }
            },
        ) { innerPadding ->
            if (entry == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (missingConfirmed) {
                        Text(
                            text = tr("这笔记录已不存在"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                DetailContent(
                    entry = entry,
                    animateReceipt = animateReceipt,
                    onEditTime = { editingTimeInline = true },
                    onConfirmEventTime = { onConfirmEventTime(entry) },
                    onPreviewEvidence = { path -> previewEvidencePath = path },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }

    if (addingRule && entry != null) {
        val history by produceState<List<TransactionEntity>?>(if (historyProvider == null) historyTransactions else null, historyProvider, historyTransactions) {
            if (historyProvider != null) value = withContext(Dispatchers.IO) { historyProvider() }
        }
        val historyCounter: ((String) -> Int)? = remember(history, onSaveMerchantRuleWithHistory != null) {
            val rows = history
            if (onSaveMerchantRuleWithHistory == null || rows == null) null else { name: String ->
                rows.count { !it.syncDeleted && com.bookkeeping.app.data.merchant.MerchantRules.matches(it.merchant, name) }
            }
        }
        MerchantRuleDialog(
            merchant = entry.merchant,
            historyLoading = history == null,
            historyCount = historyCounter,
            onSaveWithHistory = onSaveMerchantRuleWithHistory?.let { save -> { name, category, update ->
                save(name, category, update)
                addingRule = false
            } },
            initialCategory = runCatching { TransactionCategory.valueOf(entry.category) }.getOrNull(),
            onDismiss = { addingRule = false },
            onSave = { merchant, category ->
                onSaveMerchantRule(merchant, category)
                addingRule = false
            },
        )
    }

    entry?.takeIf { confirmingDelete }?.let { current ->
        DeleteTransactionDialog(
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                onDelete(current)
                confirmingDelete = false
                editing = false
                onDeleted()
            },
        )
    }

    if (editingTimeInline && entry != null) {
        LedgerDateTimePickerDialog(
            initialTimestampMs = entry.timestamp,
            initialTab = DateTimePickerTab.TIME,
            onDismiss = { editingTimeInline = false },
            onConfirm = { pickedMs ->
                onUpdateEventTime(entry, pickedMs)
                editingTimeInline = false
            },
        )
    }

    previewEvidencePath?.let { path ->
        CaptureImagePreview(
            path = path,
            onDismiss = { previewEvidencePath = null },
        )
    }
}

@Composable
private fun DetailHeader(
    syncLabel: String?,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("返回"),
                )
            }
            Text(
                text = tr("账单详情"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
            if (syncLabel != null) {
                Text(
                    text = syncLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = qingColors.strokeHairline)
    }
}

@Composable
private fun DetailContent(
    entry: TransactionEntity,
    onEditTime: () -> Unit,
    onConfirmEventTime: () -> Unit,
    onPreviewEvidence: (String) -> Unit,
    modifier: Modifier = Modifier,
    animateReceipt: Boolean = false,
) {
    val context = LocalContext.current
    val category = runCatching { TransactionCategory.valueOf(entry.category) }
        .getOrDefault(TransactionCategory.OTHER)
    val review = transactionReviewChip(entry)
    val evidencePath = remember(entry.id, entry.evidenceImageName) {
        ReceiptEvidenceStore(context).readablePath(entry.evidenceImageName)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AmountHero(entry = entry, category = category, animateReceipt = animateReceipt)
        FieldCard(
            rows = buildList<Pair<String, String>> {
                add(tr("收支类型") to transactionTypeLabel(entry.type))
                add(tr("分类") to tr(category.displayName))
                // 账户只在有值时出现；空着就不占一行，也不显示「未设置」。
                entry.account?.takeIf { it.isNotBlank() }?.let { add(tr("账户") to it) }
                add(tr("来源") to transactionSourceLabel(entry.source))
                add(tr("发生时间") to transactionTime(entry.timestamp))
            },
            note = entry.note,
            showTimeNeedsConfirmation = entry.eventTimeNeedsConfirmation,
            onConfirmEventTime = onConfirmEventTime,
            onEditTime = onEditTime,
        )
        if (evidencePath != null) {
            EvidenceImageCard(
                path = evidencePath,
                onClick = { onPreviewEvidence(evidencePath) },
            )
        }
        review?.let { (label, tone) ->
            ReviewCard(label = label, tone = tone)
        }
    }
}

/** 金额主视觉区：大字金额 + 分类图标 / 商户 / 时间，单独一张浅色卡片。 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun AmountHero(
    entry: TransactionEntity,
    category: TransactionCategory,
    animateReceipt: Boolean,
) {
    val income = entry.type == TransactionType.INCOME.name
    val reduced = rememberReduceMotion()
    val offsetPx = with(LocalDensity.current) { 8.dp.toPx() }
    val usesShared = LocalQingLedgerTransition.current?.id == entry.id
    val progress = remember(entry.id) { Animatable(if (animateReceipt && !reduced && !usesShared) 0f else 1f) }
    LaunchedEffect(entry.id, reduced) {
        if (reduced || usesShared) progress.snapTo(1f)
        else progress.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
    }
    Column(
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            translationY = offsetPx * (1f - progress.value)
            alpha = 0.96f + 0.04f * progress.value
        }.padding(top = 24.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MerchantAvatar(merchant = entry.merchant, merchantIconKey = entry.merchantIconKey,
            merchantIconPath = entry.merchantIconPath, category = category, size = 48.dp, cornerRadius = 14.dp)
        Spacer(Modifier.height(10.dp))
        Text(entry.merchant.ifBlank { tr(category.displayName) },
            style = QingTypeTokens.title.copy(fontSize = 18.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        AutoSizeAmountText(
            text = transactionAmount(entry),
            style = QingTypeTokens.receiptAmount,
            modifier = Modifier.fillMaxWidth(),
            color = if (income) QingAmountIncome else QingAmountExpense,
            textAlign = TextAlign.Center,
            minimumSizeSp = 14f,
        )
        Spacer(Modifier.height(10.dp))
        SoftBadge(text = tr("来源") + ": " + ledgerTransactionSourceTag(entry), tone = StatusTone.NEUTRAL)
    }
}

/**
 * 字段卡：label 左、值右，44dp 行高，行间 1dp 分隔线。
 *
 * 当发生时间来自截图时间（[showTimeNeedsConfirmation]）时，在同一张卡片内的
 * 「发生时间」行下方嵌一条淡琥珀提示条，并给出「确认」与「修改」两个轻量入口，
 * 不再在卡片外悬浮突兀的粉红方块。
 */
@Composable
private fun FieldCard(
    rows: List<Pair<String, String>>,
    note: String,
    showTimeNeedsConfirmation: Boolean,
    onConfirmEventTime: () -> Unit,
    onEditTime: () -> Unit,
) {
    val semantic = LocalQingSemanticColors.current
    WalletCardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                val isTimeRow = index == rows.lastIndex && showTimeNeedsConfirmation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isTimeRow) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(semantic.warningText),
                            )
                        }
                        Text(
                            text = value,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                if (index != rows.lastIndex) {
                    HorizontalDivider(thickness = 0.5.dp, color = qingColors.strokeHairline)
                }
            }
            if (showTimeNeedsConfirmation) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(semantic.warningContainer)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = semantic.warningText,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = tr("时间为截图时间，建议核对"),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = semantic.warningText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = tr("确认"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = semantic.warningText,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button, onClick = onConfirmEventTime)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Text(
                        text = tr("修改"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button, onClick = onEditTime)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            if (note.isNotBlank()) {
                HorizontalDivider(thickness = 0.5.dp, color = qingColors.strokeHairline)
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(tr("备注"), fontSize = 14.sp, color = qingColors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text(note, fontSize = 15.sp, color = qingColors.textPrimary)
                }
            }
        }
    }
}

/** 扫描原图紧凑横条卡：左侧 48dp 圆角缩略图 + 标题/副文案 + 右箭头，点开进全屏缩放预览。 */
@Composable
private fun EvidenceImageCard(
    path: String,
    onClick: () -> Unit,
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val image = thumbnail
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = tr("扫描原图"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = tr("扫描原图"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = tr("点击查看原图"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 备注卡：没有备注时整张卡不渲染；最多 3 行，全文在编辑页看。 */
@Composable
private fun NoteCard(note: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = tr("备注"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = note,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 审核状态卡：默认（未核实）不出现，只展示用户/系统明确设过的状态。 */
@Composable
private fun ReviewCard(label: String, tone: StatusTone) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("审核状态"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            StatusChip(text = label, tone = tone)
        }
    }
}

/**
 * 底部动作区：两行按钮共用一套栅格。
 */
@Composable
private fun DetailActionBar(
    showExclude: Boolean,
    excluded: Boolean,
    onToggleExclude: () -> Unit,
    onAddRule: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = ACTION_BOTTOM_GAP),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(ACTION_ROW_GAP),
            ) {
                ActionButton(
                    text = tr("编辑"),
                    onClick = onEdit,
                    style = ActionButtonStyle.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ACTION_BUTTON_GAP)) {
                    if (showExclude) {
                        ActionButton(
                            text = if (excluded) tr("恢复计入统计") else tr("不计入统计"),
                            onClick = onToggleExclude,
                            style = ActionButtonStyle.TONAL,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ActionButton(
                        text = tr("添加分类规则"),
                        onClick = onAddRule,
                        style = ActionButtonStyle.TONAL,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        text = tr("删除此账单"),
                        color = QingAmountExpense,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private enum class ActionButtonStyle { TONAL, PRIMARY, DANGER_OUTLINED }

private val ACTION_BUTTON_HEIGHT = 48.dp
private val ACTION_BUTTON_SHAPE = RoundedCornerShape(QingRadiusTokens.radiusControl)
private val ACTION_BUTTON_GAP = 12.dp
private val ACTION_ROW_GAP = 10.dp
private val ACTION_BOTTOM_GAP = 12.dp
private const val ACTION_LABEL_LONG_THRESHOLD = 8

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    style: ActionButtonStyle,
    modifier: Modifier = Modifier,
) {
    val fontSize = if (text.length > ACTION_LABEL_LONG_THRESHOLD) 14.sp else 15.sp
    val container = when (style) {
        ActionButtonStyle.TONAL -> MaterialTheme.colorScheme.surfaceVariant
        ActionButtonStyle.PRIMARY -> MaterialTheme.colorScheme.primary
        ActionButtonStyle.DANGER_OUTLINED -> Color.Transparent
    }
    val content = when (style) {
        ActionButtonStyle.TONAL -> MaterialTheme.colorScheme.onSurface
        ActionButtonStyle.PRIMARY -> MaterialTheme.colorScheme.onPrimary
        ActionButtonStyle.DANGER_OUTLINED -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = modifier
            .height(ACTION_BUTTON_HEIGHT)
            .clip(ACTION_BUTTON_SHAPE)
            .background(container)
            .then(
                if (style == ActionButtonStyle.DANGER_OUTLINED) {
                    Modifier
                } else {
                    Modifier
                }
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = content,
            fontSize = fontSize,
            lineHeight = (fontSize.value + 4).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun DeleteTransactionDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    QingConfirmationSheet(
        onDismissRequest = onDismiss,
        title = { Text(tr("删除这笔交易？")) },
        text = {
            Text(
                tr("删除将在电脑或 VPS 在线后双向同步；其他设备也会删除该笔交易。若只是误识别或退款，建议在网站标记为不计入或退款。")
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(tr("确认删除"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消")) }
        },
    )
}

private fun transactionAmount(transaction: TransactionEntity): String {
    val amount = if (transaction.currency == "CNY") {
        formatCny(transaction.amountCents)
    } else {
        transaction.currency + " " + "%.2f".format(Locale.CHINA, transaction.amountCents / 100.0)
    }
    return (if (transaction.type == TransactionType.INCOME.name) "+" else "−") + amount
}

/** 头部同步胶囊：未上报过的事件是「未同步」，有本地改动是「待同步」。 */
private fun transactionSyncLabel(transaction: TransactionEntity): String = when {
    transaction.remoteEventId == null -> tr("未同步")
    transaction.syncDirty -> tr("待同步")
    else -> tr("已同步")
}

private fun transactionTypeLabel(type: String): String = when (type) {
    TransactionType.INCOME.name -> tr("收入")
    else -> tr("支出")
}

private fun transactionSourceLabel(source: String): String = when (source) {
    "ALIPAY" -> tr("支付宝")
    "WECHAT" -> tr("微信")
    "BANK_SMS" -> tr("银行短信")
    "MANUAL" -> tr("手动")
    else -> tr("其他来源")
}

/**
 * 审核状态 chip；默认状态（unverified / 空）返回 null，界面里不再出现「未核实」。
 */
private fun transactionReviewChip(transaction: TransactionEntity): Pair<String, StatusTone>? = when {
    transaction.duplicateOfId != null || transaction.remoteReviewStatus == "possible_duplicate" ->
        tr("待确认重复") to StatusTone.WARNING
    transaction.remoteReviewStatus == "confirmed" -> tr("已确认") to StatusTone.POSITIVE
    transaction.remoteReviewStatus == "duplicate" -> tr("已确认") to StatusTone.POSITIVE
    transaction.remoteReviewStatus == "refund" -> tr("退款") to StatusTone.WARNING
    transaction.remoteReviewStatus == "transfer" -> tr("转账") to StatusTone.NEUTRAL
    transaction.remoteReviewStatus == "excluded" -> tr("不计入") to StatusTone.NEUTRAL
    else -> null
}

private fun transactionTime(timestamp: Long): String {
    val locale = if (AppLanguage.current() == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.CHINA
    val pattern = if (AppLanguage.current() == AppLanguage.ENGLISH) {
        "MMM d, yyyy HH:mm"
    } else {
        "yyyy-MM-dd HH:mm"
    }
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}

/** 金额卡里的短时间：字段卡里已有完整时间，这里只做辅助定位。 */
private fun transactionTimeCompact(timestamp: Long): String {
    val locale = if (AppLanguage.current() == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.CHINA
    val pattern = if (AppLanguage.current() == AppLanguage.ENGLISH) "MMM d HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}
