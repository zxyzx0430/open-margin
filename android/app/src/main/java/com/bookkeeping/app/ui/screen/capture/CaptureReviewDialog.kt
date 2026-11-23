package com.bookkeeping.app.ui.screen.capture

import android.graphics.BitmapFactory
import android.os.SystemClock
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.LedgerFilterChip
import com.bookkeeping.app.ui.theme.QingErrorContainer
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import com.bookkeeping.app.ui.theme.QingWarning
import com.bookkeeping.app.ui.theme.QingWarningContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CaptureReviewDialog(
    onDismiss: () -> Unit,
    viewModel: CaptureReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // 预览只记「当前打开哪张原图」，不再顺手把列表那张 1/4 缩小的位图传下去：
    // 传输略图等于把模糊放大，用户点开是要看清商户名的。
    var previewState by remember { mutableStateOf(CaptureImagePreviewState()) }
    var confirmIgnoreAll by remember { mutableStateOf(false) }

    if (confirmIgnoreAll) {
        AlertDialog(
            onDismissRequest = { confirmIgnoreAll = false },
            title = { Text(tr("忽略全部待确认记录")) },
            text = {
                Text(
                    tr("确认将当前 ${state.pending.size} 条待确认记录全部移入「已忽略」吗？之后仍可在「已忽略」列表中找回。"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmIgnoreAll = false
                        viewModel.ignoreAllPending()
                    },
                ) {
                    Text(tr("全部忽略"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmIgnoreAll = false }) {
                    Text(tr("取消"))
                }
            },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Row 1: unobstructed title + close button so "全部忽略" never crowds "待确认".
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (state.showingIgnored) tr("已忽略的截图") else tr("待确认"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = QingText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val activeCount = if (state.showingIgnored) state.ignored.size else state.pending.size
                    if (activeCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (state.showingIgnored) QingSurfaceVariant else QingWarningContainer,
                        ) {
                            Text(
                                text = tr("%d 笔").format(activeCount),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (state.showingIgnored) QingTextSecondary else QingWarning,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = tr("关闭"),
                            tint = QingTextSecondary,
                        )
                    }
                }
                // Row 2: clean segmented view switcher + right-aligned bulk action.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReviewTabChip(
                            label = if (state.pending.isEmpty()) tr("待确认") else tr("待确认 (%d)").format(state.pending.size),
                            selected = !state.showingIgnored,
                            onClick = { viewModel.showIgnored(false) },
                        )
                        ReviewTabChip(
                            label = if (state.ignored.isEmpty()) tr("已忽略") else tr("已忽略 (%d)").format(state.ignored.size),
                            selected = state.showingIgnored,
                            onClick = { viewModel.showIgnored(true) },
                        )
                    }
                    if (!state.showingIgnored && state.pending.isNotEmpty()) {
                        TextButton(
                            onClick = { confirmIgnoreAll = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = tr("全部忽略"),
                                color = QingTextSecondary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    } else if (state.showingIgnored && state.ignored.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.clearAllIgnored() },
                            enabled = !state.restoring,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = tr("清空"),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                HorizontalDivider(color = QingOutline)
                Text(
                    text = if (state.showingIgnored) {
                        tr("恢复后回到待确认，由你重新确认后才写入账本。")
                    } else {
                        tr("扫描只在本机保存结构化字段和截图，确认后才会写入账本。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
                state.message?.let { message ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = QingPrimaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = QingPrimaryDeep,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                state.undo?.let { offer ->
                    CaptureUndoRow(
                        offer = offer,
                        restoring = state.restoring,
                        onUndo = { viewModel.undoIgnore() },
                        onExpired = { viewModel.expireUndo() },
                    )
                }
                if (state.busy || state.restoring) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                if (state.showingIgnored) {
                    if (state.ignored.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tr("没有被忽略的截图"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = QingTextSecondary,
                            )
                        }
                    } else {
                        Text(
                            text = tr("截图保留 7 天，过期或被清理后无法找回。"),
                            style = MaterialTheme.typography.labelSmall,
                            color = QingTextSecondary,
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.ignored, key = { "ignored:${it.candidateId}" }) { candidate ->
                                IgnoredCandidateRow(
                                    candidate = candidate,
                                    restoring = state.restoring,
                                    onRestore = { viewModel.restoreIgnored(candidate) },
                                    onDelete = { viewModel.deleteIgnored(candidate) },
                                    onOpenPreview = candidate.screenshotPath?.let { path ->
                                        {
                                            previewState = previewState.onThumbnailTapped(
                                                screenshotPath = path,
                                                hasThumbnail = true,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else if (state.pending.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tr("没有待确认扫描结果"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = QingTextSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.pending, key = { it.candidateId }) { candidate ->
                            CaptureCandidateCard(
                                candidate = candidate,
                                busy = state.busy || candidate.candidateId in state.busyIds,
                                onOpenPreview = {
                                    // 只记路径；位图由预览自己按这个路径读原图。
                                    previewState = previewState.onThumbnailTapped(
                                        screenshotPath = candidate.screenshotPath,
                                        hasThumbnail = true,
                                    )
                                },
                                onApprove = { merchant, category, direction ->
                                    viewModel.approve(candidate, merchant, category, direction)
                                },
                                onIgnore = { viewModel.ignore(candidate) },
                            )
                        }
                    }
                }
            }
        }
    }
    // 用 openPath 而不是位图做显示条件：读盘失败时预览自己给降级页，不会拿旧图顶上。
    previewState.openPath?.let { path ->
        CaptureImagePreview(
            path = path,
            onDismiss = {
                previewState = previewState.onDismissed()
            },
        )
    }
}

@Composable
private fun CaptureCandidateCard(
    candidate: CaptureCandidate,
    busy: Boolean,
    onOpenPreview: () -> Unit,
    onApprove: (String, TransactionCategory, String?) -> Unit,
    onIgnore: () -> Unit,
) {
    var merchant by remember(candidate.candidateId) {
        mutableStateOf(candidate.merchantCandidates.firstOrNull().orEmpty())
    }
    var category by remember(candidate.candidateId) {
        mutableStateOf(
            when (candidate.kind) {
                CaptureCandidate.KIND_TRANSFER,
                CaptureCandidate.KIND_WITHDRAWAL -> TransactionCategory.TRANSFER
                CaptureCandidate.KIND_RED_PACKET ->
                    if (candidate.direction == CaptureCandidate.DIRECTION_INCOME) {
                        TransactionCategory.GIFT
                    } else {
                        TransactionCategory.TRANSFER
                    }
                else -> CaptureReviewMapping.defaultCategory(candidate)
            },
        )
    }
    // 方向未知的候选只能由用户点选支出/收入后才能记入；默认不选中。
    val needsDirection = CaptureReviewDirection.requiresSelection(candidate)
    var directionChoice by remember(candidate.candidateId) { mutableStateOf<String?>(null) }
    val directionKnown = candidate.direction != CaptureCandidate.DIRECTION_UNKNOWN ||
        directionChoice != null
    val income = when {
        candidate.kind == CaptureCandidate.KIND_REFUND -> true
        candidate.direction == CaptureCandidate.DIRECTION_UNKNOWN ->
            directionChoice == CaptureCandidate.DIRECTION_INCOME
        else -> candidate.direction == CaptureCandidate.DIRECTION_INCOME
    }
    // 方向未知且未选时不给正负号，避免把待确认的交易显示成支出。
    val showSign = directionKnown || candidate.kind == CaptureCandidate.KIND_REFUND
    var categoryMenu by remember { mutableStateOf(false) }
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, candidate.screenshotPath) {
        value = candidate.screenshotPath?.let { path ->
            withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }
        }
    }

    val hasWarningNotice = likelyDuplicateNoticeText(candidate) != null ||
        CaptureReviewMapping.screenshotUnavailableNotice(candidate) != null ||
        candidate.eventTimeNeedsConfirmation ||
        !candidate.ledgerWriteAllowed

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (hasWarningNotice) QingWarning.copy(alpha = 0.35f) else QingOutline,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = QingSurfaceVariant,
                        ) {
                            Text(
                                text = tr(
                                    when (candidate.confidenceTier) {
                                        CaptureCandidate.TIER_HIGH -> "高置信度"
                                        CaptureCandidate.TIER_LOW -> "低置信度"
                                        else -> "需要确认"
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = QingTextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        if (candidate.eventTimeNeedsConfirmation) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = QingWarningContainer,
                            ) {
                                Text(
                                    text = tr("时间待确认"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = QingWarning,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    val amountCents = candidate.amountCents
                    Text(
                        text = if (amountCents == null) {
                            tr("金额待补")
                        } else {
                            (when {
                                !showSign -> "¥"
                                income -> "+¥"
                                else -> "−¥"
                            }) + "%.2f".format(Locale.CHINA, amountCents / 100.0)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            !showSign -> QingText
                            income -> QingIncome
                            else -> QingExpense
                        },
                    )
                    Text(
                        text = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                            .format(Date(candidate.eventTimeMs ?: candidate.capturedAtMs)) +
                            (candidate.orderId?.let { " · " + tr("单号") + " " + it } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = QingTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val image = thumbnail
                if (image != null) {
                    // 有原图路径才给点击 + 放大镜：否则点开也是同一张 56dp 的图。
                    val previewable = canPreviewCaptureImage(
                        screenshotPath = candidate.screenshotPath,
                        hasThumbnail = true,
                    )
                    Box {
                        Image(
                            bitmap = image,
                            contentDescription = tr("扫描截图"),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = previewable) { onOpenPreview() },
                        )
                        if (previewable) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.88f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ZoomIn,
                                    contentDescription = tr("查看大图"),
                                    tint = Color.Black.copy(alpha = 0.78f),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                } else {
                    CategoryIcon(category = category, size = 48.dp)
                }
            }
            // 跨通道弱证据命中的候选仍可记入账本，这里只说明为什么要他核对、和哪一笔像。
            LikelyDuplicateNotice(candidate)
            CaptureReviewMapping.screenshotUnavailableNotice(candidate)?.let { warning ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = QingErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (!candidate.ledgerWriteAllowed) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = QingErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = tr("仅预览，不能记入账本"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (needsDirection) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (directionChoice == null) {
                        Text(
                            text = tr("请先选择收支方向"),
                            style = MaterialTheme.typography.labelMedium,
                            color = QingWarning,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    CaptureReviewDirection.directions.forEach { option ->
                        LedgerFilterChip(
                            label = if (option == CaptureCandidate.DIRECTION_INCOME) {
                                tr("收入")
                            } else {
                                tr("支出")
                            },
                            selected = directionChoice == option,
                            onClick = { directionChoice = option },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text(tr("商户")) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (candidate.merchantCandidates.size > 1) {
                Text(
                    text = tr("候选：") + candidate.merchantCandidates.joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = QingTextSecondary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = QingSurfaceVariant,
                        border = BorderStroke(1.dp, QingOutline),
                        modifier = Modifier.clickable { categoryMenu = true },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CategoryIcon(category = category, size = 20.dp)
                            Text(
                                text = tr(category.displayName),
                                style = MaterialTheme.typography.labelLarge,
                                color = QingText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = QingTextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
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
                                    category = option
                                    categoryMenu = false
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = onIgnore, enabled = !busy) {
                    // “忽略”听起来像扔掉；这张卡片其实还能从“已忽略的截图”找回。
                    Text(tr("不记这笔"), color = QingTextSecondary)
                }
                Button(
                    onClick = { onApprove(merchant, category, directionChoice) },
                    enabled = !busy &&
                        CaptureReviewDirection.canConfirm(candidate, directionChoice),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QingPrimary),
                ) {
                    Text(tr("记入账本"))
                }
            }
        }
    }
}

@Composable
private fun ReviewTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) QingPrimaryContainer else QingSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) QingPrimary else QingOutline),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) QingPrimaryDeep else QingTextSecondary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 「疑似重复」提示，待确认弹窗与导入候选卡共用。
 *
 * 两种来源共用这个组件：账本里可能已有同额的一笔（跨通道弱证据），或同一张截图里
 * 出现了两条同额、同向、同商户的记录。后者没有账本记录号，文案单列。
 *
 * 只做展示：命中去重弱证据的候选仍保持 PENDING，用户依旧可以选择记入账本或不记这笔，
 * 这里只是把「为什么让你核对」「和哪一笔像」说出来。
 */
@Composable
internal fun LikelyDuplicateNotice(
    candidate: CaptureCandidate,
    modifier: Modifier = Modifier,
) {
    val text = likelyDuplicateNoticeText(candidate) ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/**
 * 是否显示「疑似重复」提示以及文案怎么拼（纯函数，不依赖 Compose）。
 *
 * 只认两个明确原因常量，不做前缀/包含匹配：
 * - [CaptureCandidate.REASON_LIKELY_DUPLICATE]：账本里可能已有同额的一笔
 *   （跨通道弱证据）。[CaptureCandidate.matchedTransactionId] 仅在大于 0 时作为
 *   已有记录标识附在末尾。
 * - [CaptureCandidate.REASON_SAME_IMAGE_REPEAT]：同一张截图里出现了两条同额、
 *   同向、同商户的记录。这时没有账本记录可指，文案不能写「账本里已有一笔」。
 *
 * 金额、商户、方向、状态、置信度等其他字段不参与判断。
 */
internal fun likelyDuplicateNoticeText(candidate: CaptureCandidate): String? =
    when (candidate.reason) {
        CaptureCandidate.REASON_LIKELY_DUPLICATE -> {
            val existingId = candidate.matchedTransactionId?.takeIf { it > 0L }
            if (existingId != null) {
                tr("疑似与账本里已有的一笔重复，请核对后再决定（已有记录 #%d）").format(existingId)
            } else {
                tr("疑似与账本里已有的一笔重复，请核对后再决定")
            }
        }
        CaptureCandidate.REASON_SAME_IMAGE_REPEAT ->
            tr("同一张截图里有金额、商户相同的记录，请核对是否重复")
        else -> null
    }

/**
 * 忽略之后的限时撤销条。倒计时不能只靠组合重组：这里每 200ms 读一次时钟，
 * 归零后通知 ViewModel 收回入口，超时就真的没有“撤销”可点了。
 */
@Composable
private fun CaptureUndoRow(
    offer: CaptureIgnoreUndo.Offer,
    restoring: Boolean,
    onUndo: () -> Unit,
    onExpired: () -> Unit,
) {
    var remainingMs by remember(offer.candidate.candidateId, offer.ignoredAtMs) {
        mutableStateOf(offer.remainingMs(SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(offer.candidate.candidateId, offer.ignoredAtMs) {
        while (true) {
            remainingMs = offer.remainingMs(SystemClock.elapsedRealtime())
            if (remainingMs <= 0L) {
                onExpired()
                break
            }
            delay(200L)
        }
    }
    val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("已忽略，%d 秒内可撤销").format(seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo, enabled = !restoring) { Text(tr("撤销")) }
        }
    }
}

/** “已忽略的截图”里的一行：可恢复为待确认或彻底删除，不提供直接入账入口。 */
@Composable
private fun IgnoredCandidateRow(
    candidate: CaptureCandidate,
    restoring: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onOpenPreview: (() -> Unit)? = null,
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, candidate.screenshotPath) {
        value = candidate.screenshotPath?.let { path ->
            withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val image = thumbnail
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = tr("扫描截图"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (onOpenPreview != null) Modifier.clickable(onClick = onOpenPreview)
                                else Modifier,
                            ),
                    )
                } else {
                    CategoryIcon(
                        category = CaptureReviewMapping.defaultCategory(candidate),
                        size = 48.dp,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = captureAmountLabel(candidate),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val merchant = candidate.merchantCandidates.firstOrNull().orEmpty()
                    if (merchant.isNotBlank()) {
                        Text(
                            text = merchant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                            .format(Date(candidate.eventTimeMs ?: candidate.capturedAtMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDelete, enabled = !restoring) {
                    Text(
                        text = tr("删除"),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onRestore, enabled = !restoring) {
                    Text(tr("恢复为待确认"))
                }
            }
        }
    }
}

/** 方向未知时只显示 ¥：不把待确认的交易先写成支出。 */
private fun captureAmountLabel(candidate: CaptureCandidate): String {
    val cents = candidate.amountCents ?: return tr("金额待补")
    val unknown = candidate.direction == CaptureCandidate.DIRECTION_UNKNOWN &&
        candidate.kind != CaptureCandidate.KIND_REFUND
    val income = candidate.kind == CaptureCandidate.KIND_REFUND ||
        candidate.direction == CaptureCandidate.DIRECTION_INCOME
    val prefix = when {
        unknown -> "¥"
        income -> "+¥"
        else -> "−¥"
    }
    return prefix + "%.2f".format(Locale.CHINA, cents / 100.0)
}
