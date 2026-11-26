package com.bookkeeping.app.ui.screen.settings

import com.bookkeeping.app.ui.components.QingConfirmationSheet
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors
import com.bookkeeping.app.ui.components.QingSheetGrabber
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.bookkeeping.app.ui.components.QingSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.merchant.MerchantRules
import com.bookkeeping.app.data.merchant.otherMerchantGroups
import com.bookkeeping.app.data.merchant.OtherMerchantGroup
import com.bookkeeping.app.ui.components.AutoSizeAmountText
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.MerchantAvatar
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.components.formatCny
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.LedgerFilterChip
import com.bookkeeping.app.ui.screen.stats.categoryColor
import com.bookkeeping.app.ui.theme.QingWarning
import kotlinx.coroutines.launch

/**
 * 设置 → 自动分类 → 商户分类记忆。
 *
 * 全屏子页：自绘 56dp 标题栏（返回 / 标题 / 添加）、搜索框、规则列表、
 * 底部「清除全部分类记忆」。点任意一行打开编辑面板，右上角「添加」打开新增面板。
 * 页面只读写 DataStore 里的规则表，不动数据库结构、不动同步协议。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MerchantRulesScreen(
    onBack: () -> Unit,
    initialOpenCleanup: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val cleanupRows by viewModel.cleanupTransactions.collectAsState()
    val groups = remember(cleanupRows) { otherMerchantGroups(cleanupRows) }
    val totalOtherCount = remember(groups) { groups.sumOf { it.rows.size } }
    var showCleanup by rememberSaveable(initialOpenCleanup) { mutableStateOf(initialOpenCleanup) }
    var selectedGroup by remember { mutableStateOf<OtherMerchantGroup?>(null) }
    val rulesByMerchant by viewModel.merchantRules.collectAsState()
    val learningDisabled by viewModel.learningDisabled.collectAsState()
    val recentMerchants by viewModel.recentMerchants.collectAsState()

    val entries = remember(rulesByMerchant, learningDisabled) {
        merchantRuleEntries(rulesByMerchant, learningDisabled)
    }
    var query by remember { mutableStateOf("") }
    val visible = remember(entries, query) { filterMerchantRules(entries, query) }

    var editing by remember { mutableStateOf<MerchantRuleEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 状态栏留白由外层 RootNavigation 的 Scaffold 提供，这里再 pad 一次会翻倍。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MerchantRulesHeader(
                onBack = onBack,
                onAdd = { adding = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OtherCleanupEntryCard(
                groupCount = groups.size,
                transactionCount = totalOtherCount,
                onClick = { showCleanup = true },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            MerchantSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                when {
                    entries.isEmpty() -> MerchantRulesEmptyState(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    visible.isEmpty() -> Text(
                        text = tr("没有匹配的商户"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        textAlign = TextAlign.Center,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = 12.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.merchant }) { entry ->
                            MerchantRuleRow(entry = entry, onClick = { editing = entry })
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { confirmingClear = true },
                enabled = entries.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (entries.isEmpty()) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(tr("清除全部分类记忆"))
            }
        }
    }

    if (showCleanup) {
        OtherCategoryCleanupSheet(
            groups = groups,
            totalCount = totalOtherCount,
            onDismiss = { showCleanup = false },
            onSelectGroup = { selectedGroup = it },
        )
    }
    selectedGroup?.let { group ->
        OtherGroupCategorizeSheet(
            group = group,
            onDismiss = { selectedGroup = null },
            onSave = { category, rememberRule ->
                viewModel.categorizeOther(group.merchant, group.singleId, category, rememberRule) { updated ->
                    notify(
                        if (updated > 0) tr("已整理 %d 笔记录").format(updated)
                        else tr("已保存"),
                    )
                }
                selectedGroup = null
            },
        )
    }

    if (confirmingClear) {
        QingConfirmationSheet(
            onDismissRequest = { confirmingClear = false },
            title = { Text(tr("清除全部分类记忆？")) },
            text = { Text(tr("自定义规则和自动学习结果会一起删除，无法恢复。")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        viewModel.clearMerchantRules()
                        notify(tr("已清除"))
                    },
                ) {
                    Text(tr("清除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text(tr("取消")) }
            },
        )
    }

    if (adding) {
        MerchantRuleSheet(
            entry = null,
            existingMerchants = rulesByMerchant.keys,
            recentMerchants = recentMerchants,
            onDismiss = { adding = false },
            onSave = { merchant, category, paused, applyToHistory ->
                adding = false
                viewModel.saveMerchantRule(
                    previousMerchant = null,
                    merchant = merchant,
                    category = category,
                    learningPaused = paused,
                    applyToHistory = applyToHistory,
                ) { updated ->
                    notify(
                        if (updated > 0) tr("已保存，同步更新 %d 笔历史账单").format(updated)
                        else tr("已保存"),
                    )
                }
            },
            onDelete = {},
            onEditExisting = { merchant ->
                adding = false
                val key = MerchantRules.normalize(merchant)
                editing = entries.firstOrNull { it.merchant == key }
                    ?: MerchantRuleEntry(
                        merchant = key,
                        category = rulesByMerchant[key] ?: TransactionCategory.OTHER,
                        learningPaused = key in learningDisabled,
                    )
            },
        )
    }

    editing?.let { entry ->
        MerchantRuleSheet(
            entry = entry,
            existingMerchants = rulesByMerchant.keys,
            recentMerchants = emptyList(),
            onDismiss = { editing = null },
            onSave = { merchant, category, paused, applyToHistory ->
                editing = null
                viewModel.saveMerchantRule(
                    previousMerchant = entry.merchant,
                    merchant = merchant,
                    category = category,
                    learningPaused = paused,
                    applyToHistory = applyToHistory,
                ) { updated ->
                    notify(
                        if (updated > 0) tr("已保存，同步更新 %d 笔历史账单").format(updated)
                        else tr("已保存"),
                    )
                }
            },
            onDelete = { merchant ->
                editing = null
                viewModel.removeMerchantRule(merchant)
                notify(tr("已删除"))
            },
            onEditExisting = {},
        )
    }
}

@Composable
private fun OtherCleanupEntryCard(
    groupCount: Int,
    transactionCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CategoryIcon(category = TransactionCategory.OTHER, size = 38.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = tr("批量整理其他分类"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (transactionCount == 0) {
                        tr("当前没有待整理的「其他」记录")
                    } else {
                        tr("共 %d 个商户 · %d 笔待整理").format(groupCount, transactionCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (transactionCount > 0) {
                SoftBadge(
                    text = tr("%d 笔").format(transactionCount),
                    tone = StatusTone.WARNING,
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherCategoryCleanupSheet(
    groups: List<OtherMerchantGroup>,
    totalCount: Int,
    onDismiss: () -> Unit,
    onSelectGroup: (OtherMerchantGroup) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    ModalBottomSheet(
        shape = RoundedCornerShape(topStart = QingRadiusTokens.radiusHero, topEnd = QingRadiusTokens.radiusHero),
        dragHandle = { QingSheetGrabber() },
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = qingColors.surfaceSheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = tr("批量整理其他分类"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (groups.isEmpty()) {
                            tr("当前没有待整理的「其他」记录")
                        } else {
                            tr("共 %d 个商户 · %d 笔待整理").format(groups.size, totalCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("关闭"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (groups.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CategoryIcon(category = TransactionCategory.OTHER, size = 44.dp)
                        Text(
                            text = tr("全部「其他」记录已整理完成"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(groups, key = { it.key }) { group ->
                        val lead = group.rows.maxByOrNull { it.timestamp } ?: group.rows.first()
                        val amountText = formatOtherGroupAmounts(group.rows)
                        val timeText = timeFormat.format(Date(lead.timestamp))
                        val shape = RoundedCornerShape(16.dp)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .clickable(role = Role.Button) { onSelectGroup(group) },
                            shape = shape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                MerchantAvatar(
                                    merchant = group.merchant,
                                    merchantIconKey = lead.merchantIconKey,
                                    merchantIconPath = lead.merchantIconPath,
                                    category = TransactionCategory.OTHER,
                                    size = 40.dp,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = group.merchant.ifBlank { tr("未知商户") },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        SoftBadge(
                                            text = tr("%d 笔").format(group.rows.size),
                                            tone = StatusTone.NEUTRAL,
                                        )
                                    }
                                    Text(
                                        text = if (group.singleId != null) {
                                            timeText + " · " + tr("单笔处理")
                                        } else {
                                            timeText
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                AutoSizeAmountText(
                                    text = amountText,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.widthIn(min = 72.dp, max = 116.dp),
                                )
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OtherGroupCategorizeSheet(
    group: OtherMerchantGroup,
    onDismiss: () -> Unit,
    onSave: (TransactionCategory, Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember(group.key) { mutableStateOf<TransactionCategory?>(null) }
    var rememberRule by remember(group.key) { mutableStateOf(group.singleId == null) }
    val lead = remember(group) { group.rows.maxByOrNull { it.timestamp } ?: group.rows.first() }
    val amountText = remember(group) { formatOtherGroupAmounts(group.rows) }
    val timeText = remember(group) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lead.timestamp))
    }

    ModalBottomSheet(
        shape = RoundedCornerShape(topStart = QingRadiusTokens.radiusHero, topEnd = QingRadiusTokens.radiusHero),
        dragHandle = { QingSheetGrabber() },
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = qingColors.surfaceSheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MerchantAvatar(
                    merchant = group.merchant,
                    merchantIconKey = lead.merchantIconKey,
                    merchantIconPath = lead.merchantIconPath,
                    category = TransactionCategory.OTHER,
                    size = 42.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = group.merchant.ifBlank { tr("未知商户") },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = tr("%d 笔").format(group.rows.size) + " · " + amountText + " · " + timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("关闭"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = tr("选择分类"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TransactionCategory.entries
                    .filter { it != TransactionCategory.OTHER }
                    .forEach { item ->
                        LedgerFilterChip(
                            label = tr(item.displayName),
                            selected = category == item,
                            onClick = { category = item },
                        )
                    }
            }

            if (group.singleId == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .toggleable(
                            value = rememberRule,
                            role = Role.Switch,
                            onValueChange = { rememberRule = it },
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = tr("记住该商户规则，后续自动归类"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    QingSwitch(checked = rememberRule, onCheckedChange = null)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(tr("取消"))
                }
                Button(
                    onClick = {
                        val selected = category ?: return@Button
                        onSave(selected, rememberRule)
                    },
                    enabled = category != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(tr("保存"))
                }
            }
        }
    }
}

private fun formatOtherGroupAmounts(rows: List<TransactionEntity>): String =
    rows.groupBy { it.currency }.entries.joinToString(" / ") { (currency, items) ->
        val sum = items.sumOf { it.amountCents }
        if (currency == "CNY") formatCny(sum)
        else currency + " " + "%.2f".format(Locale.CHINA, sum / 100.0)
    }

@Composable
private fun MerchantRulesHeader(
    onBack: () -> Unit,
    onAdd: () -> Unit,
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
                text = tr("商户分类记忆"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 56.dp),
            )
            IconButton(
                onClick = onAdd,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = tr("添加"),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun MerchantSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        placeholder = {
            Text(
                text = tr("搜索商户"),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("清除"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
private fun MerchantRuleRow(
    entry: MerchantRuleEntry,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行高固定 56dp；字体放大时靠 heightIn 下限长高，不裁字。
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = entry.merchant,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CategoryPill(entry.category)
        Text(
            text = if (entry.learningPaused) tr("已暂停学习") else tr("已生效"),
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.learningPaused) {
                QingWarning
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/** 分类胶囊：底色是分类色的淡化版，文字用分类色本身，和统计页同一套配色。 */
@Composable
private fun CategoryPill(category: TransactionCategory) {
    val color = categoryColor(category)
    Text(
        text = tr(category.displayName),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun MerchantRulesEmptyState(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = tr("还没有自定义规则"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tr("在账单里改一次分类就会记住，也可以点右上角「添加」新建一条。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 新增 / 编辑共用面板。新增时商户名下方给「最近商户」候选；
 * 编辑时多一个「删除」（二次确认）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MerchantRuleSheet(
    entry: MerchantRuleEntry?,
    existingMerchants: Set<String>,
    recentMerchants: List<String>,
    onDismiss: () -> Unit,
    onSave: (merchant: String, category: TransactionCategory, learningPaused: Boolean, applyToHistory: Boolean) -> Unit,
    onDelete: (merchant: String) -> Unit,
    onEditExisting: (merchant: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(entry) { mutableStateOf(entry?.merchant.orEmpty()) }
    var category by remember(entry) { mutableStateOf(entry?.category) }
    var paused by remember(entry) { mutableStateOf(entry?.learningPaused ?: false) }
    var applyToHistory by remember(entry) { mutableStateOf(true) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val issue = merchantRuleIssue(
        merchant = name,
        category = category,
        existingMerchants = existingMerchants,
        originalMerchant = entry?.merchant,
    )
    val candidates = remember(recentMerchants, existingMerchants, name, entry) {
        if (entry != null) emptyList() else merchantCandidates(recentMerchants, existingMerchants, name)
    }

    ModalBottomSheet(
        shape = RoundedCornerShape(topStart = QingRadiusTokens.radiusHero, topEnd = QingRadiusTokens.radiusHero),
        dragHandle = { QingSheetGrabber() },
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = qingColors.surfaceSheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (entry == null) tr("添加规则") else tr("编辑规则"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(tr("商户名")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (candidates.isNotEmpty()) {
                Text(
                    text = tr("最近商户"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    candidates.forEach { candidate ->
                        LedgerFilterChip(
                            label = if (candidate.hasRule) {
                                candidate.merchant + " · " + tr("已有规则")
                            } else {
                                candidate.merchant
                            },
                            selected = false,
                            onClick = { name = candidate.merchant },
                        )
                    }
                }
            }

            RuleIssueHint(
                issue = issue,
                canEditExisting = entry == null && name.isNotBlank(),
                onEditExisting = {
                    val key = name.trim()
                    if (key.isNotEmpty()) onEditExisting(key)
                },
            )

            Text(
                text = tr("分类"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TransactionCategory.entries.forEach { option ->
                    LedgerFilterChip(
                        label = tr(option.displayName),
                        selected = category == option,
                        onClick = { category = option },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .toggleable(
                        value = applyToHistory,
                        role = Role.Switch,
                        onValueChange = { applyToHistory = it },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = tr("同时更新历史账单"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = tr("将已记录的同名商户历史账单一起改为此分类"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                QingSwitch(checked = applyToHistory, onCheckedChange = null)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .toggleable(
                        value = paused,
                        role = Role.Switch,
                        onValueChange = { paused = it },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = tr("暂停学习此商户"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = tr("暂停后只按这条规则分类，不再自动学习"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                QingSwitch(checked = paused, onCheckedChange = null)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (entry != null) {
                    TextButton(
                        onClick = { confirmingDelete = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(tr("删除"))
                    }
                }
                Button(
                    onClick = {
                        val selected = category ?: return@Button
                        onSave(name.trim(), selected, paused, applyToHistory)
                    },
                    enabled = issue == null || issue == MerchantRuleIssue.DUPLICATE,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(tr("保存"))
                }
            }
        }
    }

    if (confirmingDelete && entry != null) {
        QingConfirmationSheet(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(tr("删除这条规则？")) },
            text = { Text(tr("删除后这个商户不再自动套用分类。")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete(entry.merchant)
                    },
                ) {
                    Text(tr("删除"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(tr("取消")) }
            },
        )
    }
}

/**
 * 保存按钮为什么不能点。同名规则单独用警告色，并给一条「改为编辑」的出路，
 * 避免用户在这里改一个已经存在的商户时无路可走。
 */
@Composable
private fun RuleIssueHint(
    issue: MerchantRuleIssue?,
    canEditExisting: Boolean,
    onEditExisting: () -> Unit,
) {
    if (issue == null) return
    val duplicate = issue == MerchantRuleIssue.DUPLICATE
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when (issue) {
                MerchantRuleIssue.EMPTY_NAME -> tr("请先输入商户名")
                MerchantRuleIssue.NO_CATEGORY -> tr("请选择分类")
                MerchantRuleIssue.DUPLICATE -> tr("已存在同名规则，将覆盖现有规则")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (duplicate) QingWarning else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (duplicate && canEditExisting) {
            TextButton(onClick = onEditExisting) {
                Text(tr("改为编辑"), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
