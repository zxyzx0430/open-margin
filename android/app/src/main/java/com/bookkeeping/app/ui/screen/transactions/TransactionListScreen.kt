package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.notification.SmsPermissionHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    viewModel: TransactionViewModel = hiltViewModel()
) {
    val transactions by viewModel.displayedTransactions.collectAsState()
    val monthlyExpense by viewModel.monthlyExpenseCents.collectAsState()
    val monthlyIncome by viewModel.monthlyIncomeCents.collectAsState()
    val selectedCategory by viewModel.categoryFilter.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }

    // 监听 Activity 生命周期：每次回到前台时重新检查两类权限状态
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationListenerEnabled by remember { mutableStateOf(NotificationListenerHelper.isEnabled(context)) }
    var smsPermissionGranted by remember { mutableStateOf(SmsPermissionHelper.isGranted(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationListenerEnabled = NotificationListenerHelper.isEnabled(context)
                smsPermissionGranted = SmsPermissionHelper.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // SMS 权限请求 launcher
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        smsPermissionGranted = results.values.all { it }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("无感记账") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建账单")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 通知监听未授权时显示提示横幅
            if (!notificationListenerEnabled) {
                NotificationPermissionBanner(
                    onClick = { NotificationListenerHelper.openSettings(context) }
                )
            }

            // SMS 权限未授权时显示提示横幅
            if (!smsPermissionGranted) {
                SmsPermissionBanner(
                    onClick = { smsPermissionLauncher.launch(SmsPermissionHelper.REQUIRED_PERMISSIONS) }
                )
            }

            // 顶部统计卡片（基于全月数据，不受 filter 影响）
            MonthlySummaryCard(
                monthlyExpenseCents = monthlyExpense,
                monthlyIncomeCents = monthlyIncome,
            )

            // 分类筛选 chip 行
            CategoryFilterRow(
                selected = selectedCategory,
                onSelect = viewModel::setCategoryFilter,
            )

            if (transactions.isEmpty()) {
                EmptyState(filterActive = selectedCategory != null)
            } else {
                // 按日期分组渲染
                GroupedTransactionList(
                    transactions = transactions,
                    onDelete = { pendingDelete = it },
                    onEdit = { editing = it },
                    onReview = viewModel::setReviewStatus,
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这笔交易？") },
            text = { Text("删除将在电脑或 VPS 在线后双向同步；其他设备也会删除该笔交易。若只是误识别或退款，建议在网站标记为不计入或退款。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry)
                    pendingDelete = null
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    editing?.let { entry ->
        AddTransactionDialog(
            initial = entry,
            onDismiss = { editing = null },
            onConfirm = { amount,type,category,merchant,note ->
                viewModel.updateTransaction(entry,amount,type,category,merchant,note)
                editing = null
            }
        )
    }

    if (showAddDialog) {
        AddTransactionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { amount, type, category, merchant, note ->
                viewModel.addTransaction(amount, type, category, merchant, note)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun MonthlySummaryCard(monthlyExpenseCents: Long, monthlyIncomeCents: Long) {
    val diff = monthlyIncomeCents - monthlyExpenseCents
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本月收支差额", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                (if (diff >= 0) "+" else "−") + "¥" +
                    "%.2f".format(Locale.CHINA, kotlin.math.abs(diff) / 100.0),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.13f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryStat("已记录收入", "+¥" + "%.2f".format(Locale.CHINA, monthlyIncomeCents / 100.0),
                    Modifier.weight(1f))
                SummaryStat("已记录支出", "−¥" + "%.2f".format(Locale.CHINA, monthlyExpenseCents / 100.0),
                    Modifier.weight(1f))
            }
            Text("本机交易 · 不是账户余额，尚需对照官方账单",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun SummaryStat(label: String, amount: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
        Text(amount, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CategoryFilterRow(
    selected: TransactionCategory?,
    onSelect: (TransactionCategory?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("全部") }
        )
        TransactionCategory.entries.forEach { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(if (selected == cat) null else cat) },
                label = { Text(cat.displayName) }
            )
        }
    }
}

@Composable
private fun GroupedTransactionList(
    transactions: List<TransactionEntity>,
    onDelete: (TransactionEntity) -> Unit,
    onEdit: (TransactionEntity) -> Unit,
    onReview: (TransactionEntity, String) -> Unit,
) {
    // 按 dayKey 分组（同一天 = 同一组）。LinkedHashMap 保留插入顺序（即时间倒序）
    val groups = remember(transactions) {
        transactions.groupBy { dayKey(it.timestamp) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { (dayKey, dayItems) ->
            // 每组的头部：日期 + 当日合计
            item(key = "header_$dayKey") {
                DayHeader(dayKey = dayKey, items = dayItems)
            }
            // 该组内的交易项
            items(dayItems, key = { it.id }) { tx ->
                TransactionItem(transaction = tx, onDelete = { onDelete(tx) },
                                onEdit = { onEdit(tx) },
                                onReview = { status -> onReview(tx,status) })
            }
        }
    }
}

@Composable
private fun DayHeader(dayKey: String, items: List<TransactionEntity>) {
    // This daily summary is denominated in CNY. Non-CNY entries remain in
    // the list below with an explicit currency label, but are not falsely added.
    val valid=items.filter { it.currency == "CNY" &&
        it.remoteReviewStatus !in setOf("excluded","transfer","refund") }
    val expense = valid.filter { it.type == "EXPENSE" && it.category!="TRANSFER" }
        .sumOf { it.amountCents }
    val income = valid.filter { it.type == "INCOME" }.sumOf { it.amountCents }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(dayLabel(dayKey), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val total = if (valid.isEmpty() && items.any { it.currency != "CNY" })
                        "仅外币 · 未换算"
                    else if (expense > 0L) "支出 ¥" + "%.2f".format(Locale.CHINA, expense / 100.0)
                    else "收入 ¥" + "%.2f".format(Locale.CHINA, income / 100.0)
        Text(total, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TransactionItem(transaction: TransactionEntity, onDelete: () -> Unit,
                            onEdit: () -> Unit, onReview: (String) -> Unit) {
    val type = runCatching { TransactionType.valueOf(transaction.type) }.getOrDefault(TransactionType.EXPENSE)
    val category = runCatching { TransactionCategory.valueOf(transaction.category) }.getOrDefault(TransactionCategory.OTHER)
    var expanded by remember(transaction.id) { mutableStateOf(false) }
    val amount = "%.2f".format(Locale.CHINA, transaction.amountCents / 100.0)
    val currencyLabel = if (transaction.currency == "CNY") "¥" else transaction.currency + " "
    val sourceName = when (transaction.source) {
        "ALIPAY" -> "支付宝"
        "WECHAT" -> "微信"
        "BANK_SMS" -> "银行短信"
        "MANUAL" -> "手动"
        else -> "其他来源"
    }
    val title = transaction.merchant.ifBlank { category.displayName }
    val noteSummary = transaction.note
        .removePrefix("[自动抓取] ")
        .removePrefix("[银行短信] ")
        .trim()
    val amountColor = if (type == TransactionType.INCOME)
        MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(sourceName + " · " + category.displayName + " · " +
                        timeOnlyFormat.format(Date(transaction.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text((if (type == TransactionType.INCOME) "+" else "−") + currencyLabel + amount,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = amountColor, maxLines = 1)
            }
            if (noteSummary.isNotBlank() && !expanded) {
                Text(noteSummary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                if (transaction.remoteReviewStatus == "refund") {
                    Text("已关联退款 · 请在网站管理退款关系",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            onReview(if (transaction.remoteReviewStatus == "excluded")
                                "unverified" else "excluded")
                        }) {
                            Text(if (transaction.remoteReviewStatus == "excluded")
                                "恢复计入统计" else "不计入统计")
                        }
                    }
                }
                if (noteSummary.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(noteSummary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End) {
                    Text("修改会双向同步", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onEdit) { Text("编辑") }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error)
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "展开详情",
                        tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(filterActive: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (filterActive) "该分类下还没有记录" else "还没有账单",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!filterActive) {
                Text(
                    text = "点击右下角 + 添加第一笔",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NotificationPermissionBanner(onClick: () -> Unit) {
    PermissionBanner(
        title = "未开启通知监听",
        body = "点击此处开启「通知使用权」，App 才能自动抓取支付宝 / 微信付款记录",
        onClick = onClick,
    )
}

@Composable
private fun SmsPermissionBanner(onClick: () -> Unit) {
    PermissionBanner(
        title = "未开启短信权限",
        body = "点击此处授予短信权限，App 才能自动抓取银行交易短信",
        onClick = onClick,
    )
}

@Composable
private fun PermissionBanner(title: String, body: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// === 时间分组辅助 ===

private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val timeOnlyFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val monthDayFormat = SimpleDateFormat("MM月dd日", Locale.getDefault())

// 把毫秒时间戳转成 "yyyy-MM-dd" 字符串作为分组 key
private fun dayKey(timestampMs: Long): String = dayKeyFormat.format(Date(timestampMs))

// 把 dayKey 转成展示用的标签："今天" / "昨天" / "MM月dd日"
private fun dayLabel(dayKey: String): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val todayKey = dayKeyFormat.format(today.time)
    val yesterdayKey = dayKeyFormat.format(Date(today.timeInMillis - 86_400_000L))
    return when (dayKey) {
        todayKey -> "今天"
        yesterdayKey -> "昨天"
        else -> {
            // 解析回 Date，然后格式化为 MM月dd日
            runCatching { monthDayFormat.format(dayKeyFormat.parse(dayKey)!!) }.getOrDefault(dayKey)
        }
    }
}
