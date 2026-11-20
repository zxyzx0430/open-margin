package com.bookkeeping.app.ui.screen.capture

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.LedgerFilterChip
import com.bookkeeping.app.ui.theme.QingIncome
import kotlinx.coroutines.Dispatchers
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
    // 预览位图与“当前打开的是哪张”放同一作用域：跨 composable 声明会 Unresolved reference。
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewState by remember { mutableStateOf(CaptureImagePreviewState()) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr("待确认扫描结果"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(tr("关闭")) }
                }
                Text(
                    text = tr("扫描只在本机保存结构化字段和截图，确认后才会写入账本。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                if (state.pending.isEmpty()) {
                    Text(
                        text = tr("没有待确认扫描结果"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.pending, key = { it.candidateId }) { candidate ->
                            CaptureCandidateCard(
                                candidate = candidate,
                                busy = state.busy,
                                onOpenPreview = { bitmap ->
                                    previewBitmap = bitmap
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
    previewBitmap?.takeIf { previewState.open }?.let { bitmap ->
        CaptureImagePreview(
            bitmap = bitmap,
            onDismiss = {
                previewBitmap = null
                previewState = previewState.onDismissed()
            },
        )
    }
}

@Composable
private fun CaptureCandidateCard(
    candidate: CaptureCandidate,
    busy: Boolean,
    onOpenPreview: (ImageBitmap) -> Unit,
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val image = thumbnail
            if (image != null) {
                // 有原图路径才给点击 + 放大镜：否则点开也是同一张 64dp 的图。
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
                            .size(64.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = previewable) { onOpenPreview(image) },
                    )
                    if (previewable) {
                        // 右下角放大镜：缩略图太小，不给提示用户不知道能点。
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.82f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ZoomIn,
                                contentDescription = tr("查看大图"),
                                tint = Color.Black.copy(alpha = 0.75f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            } else {
                CategoryIcon(category = category, size = 64.dp)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            !showSign -> MaterialTheme.colorScheme.onSurface
                            income -> QingIncome
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = tr(
                            when (candidate.confidenceTier) {
                                CaptureCandidate.TIER_HIGH -> "高置信度"
                                CaptureCandidate.TIER_LOW -> "低置信度"
                                else -> "需要确认"
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (needsDirection) {
                    if (directionChoice == null) {
                        Text(
                            text = tr("请先选择收支方向"),
                            style = MaterialTheme.typography.labelMedium,
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
                    modifier = Modifier.fillMaxWidth(),
                )
                if (candidate.merchantCandidates.size > 1) {
                    Text(
                        text = tr("候选：") + candidate.merchantCandidates.joinToString(" / "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (candidate.eventTimeNeedsConfirmation) {
                    Text(
                        text = tr("时间待确认"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (!candidate.ledgerWriteAllowed) {
                    Text(
                        text = tr("仅预览，不能记入账本"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                            .format(Date(candidate.eventTimeMs ?: candidate.capturedAtMs)) +
                            (candidate.orderId?.let { " · " + tr("单号") + " " + it } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        TextButton(onClick = { categoryMenu = true }) {
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
                                        category = option
                                        categoryMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onIgnore, enabled = !busy) {
                        Text(tr("忽略"))
                    }
                    Button(
                        onClick = { onApprove(merchant, category, directionChoice) },
                        enabled = !busy &&
                            CaptureReviewDirection.canConfirm(candidate, directionChoice),
                    ) {
                        Text(tr("记入账本"))
                    }
                }
            }
        }
    }
}
