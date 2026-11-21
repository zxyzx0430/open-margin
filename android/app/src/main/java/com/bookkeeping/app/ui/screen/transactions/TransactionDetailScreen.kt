package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.MerchantRuleDialog
import com.bookkeeping.app.ui.components.StatusChip
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账单详情：所有行内动作集中到这里，整页不滚动。
 *
 * 版面自上而下：头部（返回 / 标题 / 同步胶囊）→ 金额主视觉卡 → 字段卡 → 备注卡
 * （可选）→ 审核状态卡（可选）→ 固定底部动作区。删掉了「服务器同步」「本地改动」
 * 两行大段文字，同步状态只留头部胶囊。
 */
@Composable
fun TransactionDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    detailViewModel: TransactionDetailViewModel = hiltViewModel(),
    transactionViewModel: TransactionViewModel = hiltViewModel(),
) {
    val transaction by detailViewModel.transaction.collectAsState()
    val entry = transaction
    // Room 的 Flow 首帧是 null。详情页现在是同一帧切过来（没有转场遮挡），
    // 直接渲染「这笔记录已不存在」会闪一下错误文案，所以先留一帧缓冲；
    // 真被删掉的记录 300ms 后照样给出提示。
    var missingConfirmed by remember { mutableStateOf(false) }
    LaunchedEffect(entry) {
        if (entry == null) {
            delay(300)
            missingConfirmed = true
        } else {
            missingConfirmed = false
        }
    }
    var editing by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var addingRule by remember { mutableStateOf(false) }
    // 「时间待确认」标签点开后的日期 + 时间修正流程；取消不写库。
    var fixingEventTime by remember { mutableStateOf(false) }

    if (editing && entry != null) {
        // 全屏编辑页替换详情页；保存/取消都回到这里，删除复用同一个二次确认。
        TransactionEditScreen(
            initial = entry,
            onCancel = { editing = false },
            onDelete = { confirmingDelete = true },
            onSave = { fields ->
                transactionViewModel.saveTransaction(entry, fields)
                editing = false
            },
        )
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
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
                        onToggleExclude = {
                            transactionViewModel.setReviewStatus(
                                entry,
                                if (entry.remoteReviewStatus == "excluded") "unverified" else "excluded",
                            )
                        },
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
                    onFixEventTime = { fixingEventTime = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }

    if (addingRule && entry != null) {
        MerchantRuleDialog(
            merchant = entry.merchant,
            initialCategory = runCatching { TransactionCategory.valueOf(entry.category) }.getOrNull(),
            onDismiss = { addingRule = false },
            onSave = { merchant, category ->
                transactionViewModel.saveMerchantRule(merchant, category)
                addingRule = false
            },
        )
    }

    entry?.takeIf { confirmingDelete }?.let { current ->
        DeleteTransactionDialog(
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                transactionViewModel.delete(current)
                confirmingDelete = false
                editing = false
                onDeleted()
            },
        )
    }

    // 点「时间待确认」→ 先选日期、再选时间；两步都确认才写库，任何一步取消都不动。
    if (fixingEventTime && entry != null) {
        EventTimeFixFlow(
            initialTimestamp = entry.timestamp,
            onDismiss = { fixingEventTime = false },
            onConfirm = { timestampMs ->
                transactionViewModel.confirmEventTime(entry, timestampMs)
                fixingEventTime = false
            },
        )
    }
}

@Composable
private fun DetailHeader(
    syncLabel: String?,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun DetailContent(
    entry: TransactionEntity,
    onFixEventTime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val category = runCatching { TransactionCategory.valueOf(entry.category) }
        .getOrDefault(TransactionCategory.OTHER)
    val review = transactionReviewChip(entry)

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AmountHero(entry = entry, category = category)
        FieldCard(
            rows = buildList<Pair<String, String>> {
                add(tr("收支类型") to transactionTypeLabel(entry.type))
                add(tr("分类") to tr(category.displayName))
                // 账户只在有值时出现；空着就不占一行，也不显示「未设置」。
                entry.account?.takeIf { it.isNotBlank() }?.let { add(tr("账户") to it) }
                add(tr("来源") to transactionSourceLabel(entry.source))
                add(tr("发生时间") to transactionTime(entry.timestamp))
            },
        )
        if (entry.eventTimeNeedsConfirmation) {
            // 可点击：点开日期 + 时间选择器，确认后写回发生时间并清掉这个标记。
            // 编辑图标只是可点的提示，读屏靠 onClickLabel 说明动作。
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = tr("修改发生时间"),
                        onClick = onFixEventTime,
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = tr("时间待确认"),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (entry.note.isNotBlank()) {
            NoteCard(note = entry.note)
        }
        review?.let { (label, tone) ->
            ReviewCard(label = label, tone = tone)
        }
    }
}

/** 金额主视觉区：大字金额 + 分类图标 / 商户 / 时间，单独一张浅色卡片。 */
@Composable
private fun AmountHero(
    entry: TransactionEntity,
    category: TransactionCategory,
) {
    val income = entry.type == TransactionType.INCOME.name
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Text(
            text = transactionAmount(entry),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 34.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = if (income) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CategoryIcon(category = category, size = 20.dp)
            Text(
                text = entry.merchant.ifBlank { tr(category.displayName) },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "·",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = transactionTimeCompact(entry.timestamp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 字段卡：label 左、值右，44dp 行高，行间 1dp 分隔线（左缩进 16dp）。 */
@Composable
private fun FieldCard(rows: List<Pair<String, String>>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = value,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1.5f),
                    )
                }
                if (index != rows.lastIndex) {
                    // 卡片自带 16dp 内边距，分隔线因此与 label 左对齐；不再二次缩进。
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
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
 *
 * 所有按钮同高 [ACTION_BUTTON_HEIGHT]、同圆角 [ACTION_BUTTON_SHAPE]，行内间距
 * [ACTION_BUTTON_GAP]，行间 [ACTION_ROW_GAP]，整块左右各 16dp、顶部一条 1dp 分隔线、
 * 底部再留 [ACTION_BOTTOM_GAP] 给系统手势区。第一行两个等价按钮各占一半，第二行
 * 「编辑」占 2/3、「删除」占 1/3，位置严格对齐，不再出现一行缩进、一行贴边。
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
                Row(horizontalArrangement = Arrangement.spacedBy(ACTION_BUTTON_GAP)) {
                    ActionButton(
                        text = tr("编辑"),
                        onClick = onEdit,
                        style = ActionButtonStyle.PRIMARY,
                        modifier = Modifier.weight(2f),
                    )
                    ActionButton(
                        text = tr("删除"),
                        onClick = onDelete,
                        style = ActionButtonStyle.DANGER_OUTLINED,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 底部动作区三种按钮样式，尺寸/圆角/字重由 [ActionButton] 统一兜住。 */
private enum class ActionButtonStyle { TONAL, PRIMARY, DANGER_OUTLINED }

/** 所有底部按钮同高。 */
private val ACTION_BUTTON_HEIGHT = 46.dp

/** 所有底部按钮同圆角。 */
private val ACTION_BUTTON_SHAPE = RoundedCornerShape(12.dp)

/** 同一行内两个按钮的间距。 */
private val ACTION_BUTTON_GAP = 12.dp

/** 两行按钮之间、分隔线与按钮之间的间距。 */
private val ACTION_ROW_GAP = 10.dp

/** 按钮底部与系统手势区之间的留白。 */
private val ACTION_BOTTOM_GAP = 12.dp

/** 超过这个字数就把字号降一档，保证一行放得下；再长就占两行，不省略。 */
private const val ACTION_LABEL_LONG_THRESHOLD = 8

/**
 * 动作按钮：不用 Material 的 Button 家族，是因为它们会把 46dp 撑到 48dp 的最小交互尺寸，
 * 两行高度就对不上了。这里用固定尺寸的 Box 自己保证高度与圆角完全一致。
 */
@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    style: ActionButtonStyle,
    modifier: Modifier = Modifier,
) {
    // 统一 15sp Medium；文案确实长（英文按钮常见）才降到 14sp，中文 6 个字仍是一行。
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
                    Modifier.border(1.dp, MaterialTheme.colorScheme.error, ACTION_BUTTON_SHAPE)
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
            // 不换行、不缩字号、不省略：太长就占两行，多出来的字绝不吞掉。
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
    AlertDialog(
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

/**
 * 「时间待确认」的修正流程：先选日期，再选时间，两步都确认才回调 [onConfirm]。
 *
 * 默认值一律取 [initialTimestamp]（这笔账当前的发生时间）：日期步保留原来的时分，
 * 时间步保留刚选的日期。任何一步点取消都只关流程，不写库。
 * 换算复用编辑页那组 `TransactionEditRules` 函数，不另造日期 / 时间语义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventTimeFixFlow(
    initialTimestamp: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var pendingTimestamp by remember(initialTimestamp) { mutableLongStateOf(initialTimestamp) }
    var choosingTime by remember(initialTimestamp) { mutableStateOf(false) }
    var timeError by remember(initialTimestamp) { mutableStateOf<String?>(null) }

    if (!choosingTime) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = TransactionEditRules.localDateToUtcMillis(pendingTimestamp),
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { utcDate ->
                            pendingTimestamp =
                                TransactionEditRules.applyUtcDateToMillis(utcDate, pendingTimestamp)
                        }
                        choosingTime = true
                    },
                ) { Text(tr("下一步")) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(tr("取消")) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    } else {
        val timePickerState = rememberTimePickerState(
            initialHour = TransactionEditRules.hourOf(pendingTimestamp),
            initialMinute = TransactionEditRules.minuteOf(pendingTimestamp),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(tr("时间")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePicker(state = timePickerState)
                    timeError?.let { error ->
                        Text(
                            text = tr(error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val candidate = TransactionEditRules.withLocalTime(
                            pendingTimestamp,
                            timePickerState.hour,
                            timePickerState.minute,
                        )
                        val error = TransactionEditRules.timestampError(
                            candidate,
                            System.currentTimeMillis(),
                        )
                        if (error == null) {
                            onConfirm(candidate)
                        } else {
                            // 与编辑页同一条规则：不写一个未来时间，留在弹窗里改。
                            timeError = error
                        }
                    },
                ) { Text(tr("确定")) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(tr("取消")) }
            },
        )
    }
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
