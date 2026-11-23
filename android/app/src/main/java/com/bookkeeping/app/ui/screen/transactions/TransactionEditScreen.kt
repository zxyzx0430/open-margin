package com.bookkeeping.app.ui.screen.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.bookkeeping.app.capture.screenshot.ReceiptEvidenceStore
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.DateTimePickerTab
import com.bookkeeping.app.ui.components.LedgerDateTimePickerDialog
import com.bookkeeping.app.ui.screen.capture.CaptureImagePreview
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏「编辑账单」页：详情页点「编辑」后替换整页显示，可改全部业务字段。
 *
 * 只读：记录 ID、服务器同步状态、本地改动标记、重复标记。
 * 底部固定动作：左「删除」（二次确认留在调用方），右「取消」「保存」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditScreen(
    initial: TransactionEntity,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSave: (TransactionEditFields) -> Unit,
) {
    // 状态都以 initial.id 为 key：同步把新行刷进来时不会覆盖用户正在编辑的内容。
    var amountText by remember(initial.id) {
        mutableStateOf(TransactionEditRules.formatAmount(initial.amountCents))
    }
    var amountInputError by remember(initial.id) { mutableStateOf<String?>(null) }
    var selectedType by remember(initial.id) {
        mutableStateOf(
            runCatching { TransactionType.valueOf(initial.type) }
                .getOrDefault(TransactionType.EXPENSE)
        )
    }
    var selectedCategory by remember(initial.id) {
        mutableStateOf(
            runCatching { TransactionCategory.valueOf(initial.category) }
                .getOrDefault(TransactionCategory.OTHER)
        )
    }
    var selectedSource by remember(initial.id) {
        mutableStateOf(
            runCatching { TransactionSource.valueOf(initial.source) }
                .getOrDefault(TransactionSource.OTHER)
        )
    }
    var merchant by remember(initial.id) { mutableStateOf(initial.merchant) }
    var note by remember(initial.id) { mutableStateOf(initial.note) }
    var account by remember(initial.id) { mutableStateOf(initial.account ?: "") }
    var timestamp by remember(initial.id) { mutableLongStateOf(initial.timestamp) }

    val storedReviewStatus = initial.remoteReviewStatus
    var selectedReviewStatus by remember(initial.id) {
        mutableStateOf(TransactionEditRules.initialReviewStatus(storedReviewStatus))
    }
    var reviewStatusTouched by remember(initial.id) { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val evidenceImagePath = remember(initial.id, initial.evidenceImageName) {
        ReceiptEvidenceStore(context).readablePath(initial.evidenceImageName)
    }
    var previewingEvidence by remember(initial.id) { mutableStateOf(false) }

    // possible_duplicate 由抓取流程自动标记，不在下拉框里；用户没动过就原样保留。
    val reviewStatus = if (reviewStatusTouched || storedReviewStatus in
        TransactionEditRules.SELECTABLE_REVIEW_STATUSES
    ) {
        selectedReviewStatus
    } else {
        storedReviewStatus
    }

    val amountError = amountInputError ?: TransactionEditRules.amountError(amountText)
    val amountCents = TransactionEditRules.amountCents(amountText)
    val fields = amountCents?.let {
        TransactionEditFields(
            amountCents = it,
            type = selectedType.name,
            category = selectedCategory.name,
            merchant = merchant,
            note = note,
            account = account,
            source = selectedSource.name,
            timestampMs = timestamp,
            reviewStatus = reviewStatus,
        )
    }
    val errors = fields?.let {
        TransactionEditRules.errorsOf(it, System.currentTimeMillis())
    } ?: TransactionEditErrors(amount = amountError ?: "请输入有效金额")
    val canSave = fields != null && amountError == null && errors.isValid &&
        TransactionEditRules.hasChanges(initial, fields)

    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("编辑账单"), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr("返回"),
                        )
                    }
                },
                actions = {
                    if (evidenceImagePath != null) {
                        TextButton(onClick = { previewingEvidence = true }) {
                            Text(tr("查看原图"))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onDelete) {
                            Text(tr("删除"), color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text(tr("取消")) }
                        Button(
                            onClick = { fields?.let(onSave) },
                            enabled = canSave,
                        ) { Text(tr("保存")) }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 收支类型
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel(tr("收支类型"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedType == TransactionType.EXPENSE,
                        onClick = { selectedType = TransactionType.EXPENSE },
                        label = { Text(tr("支出")) },
                    )
                    FilterChip(
                        selected = selectedType == TransactionType.INCOME,
                        onClick = { selectedType = TransactionType.INCOME },
                        label = { Text(tr("收入")) },
                    )
                }
            }

            // 金额
            OutlinedTextField(
                value = amountText,
                onValueChange = { raw ->
                    if (raw.contains('-') || raw.contains('−')) {
                        // 保留上一个金额，但把被拒绝的负号明确告诉用户。
                        amountInputError = "金额不能为负数，请用支出/收入切换方向"
                    } else {
                        val candidate = raw.filter { char -> char.isDigit() || char == '.' }
                        if (candidate.substringAfter('.', "").count { it.isDigit() } > 2) {
                            // 不静默截断小数位，直接拒绝这次输入。
                            amountInputError = "金额最多保留两位小数"
                        } else {
                            amountText = candidate
                            amountInputError = null
                        }
                    }
                },
                label = { Text(tr("金额（元）")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = amountError != null,
                supportingText = {
                    if (amountError != null) {
                        Text(tr(amountError), color = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            ChoiceField(
                label = tr("分类"),
                selected = selectedCategory,
                options = TransactionCategory.entries,
                optionLabel = { tr(it.displayName) },
                onSelect = { selectedCategory = it },
                error = errors.category,
            )

            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text(tr("商户/对方（可选）")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(tr("备注（可选）")) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            // 账户：自由文本 + 常用值快捷填入（取值与自动抓取写入的中文一致）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text(tr("账户（可选）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransactionEditRules.ACCOUNT_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = account.trim() == preset,
                            onClick = { account = preset },
                            label = { Text(tr(preset)) },
                        )
                    }
                }
            }

            ChoiceField(
                label = tr("来源"),
                selected = selectedSource,
                options = TransactionSource.entries,
                optionLabel = { sourceLabel(it) },
                onSelect = { selectedSource = it },
                error = errors.source,
            )

            // 发生时间：日期 + 时间两个入口
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel(tr("发生时间"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${tr("日期")}：${formatEditDate(timestamp)}")
                    }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${tr("时间")}：${formatEditTime(timestamp)}")
                    }
                }
                errors.timestamp?.let {
                    Text(
                        text = tr(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            ChoiceField(
                label = tr("审核状态"),
                selected = reviewStatus,
                options = TransactionEditRules.SELECTABLE_REVIEW_STATUSES,
                optionLabel = { reviewStatusLabel(it) },
                onSelect = {
                    selectedReviewStatus = it
                    reviewStatusTouched = true
                },
                error = errors.reviewStatus,
            )

            // 只读信息：证明这条记录是谁、同步到哪一步、有没有待上传改动。
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = tr("记录信息"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReadOnlyField(
                tr("记录 ID 后 6 位"),
                "#" + initial.id.toString().takeLast(6),
            )
            ReadOnlyField(
                tr("服务器同步"),
                if (initial.remoteEventId != null) tr("已同步到服务器") else tr("尚未同步到服务器"),
            )
            ReadOnlyField(
                tr("本地改动"),
                if (initial.syncDirty || initial.remoteEventId == null) {
                    tr("有本地改动未上传")
                } else {
                    tr("没有待上传的本地改动")
                },
            )
            initial.duplicateOfId?.let { originalId ->
                ReadOnlyField(
                    tr("疑似重复原记录"),
                    "#" + originalId.toString().takeLast(6),
                )
            }
        }
    }

    if (showDatePicker || showTimePicker) {
        LedgerDateTimePickerDialog(
            initialTimestampMs = timestamp,
            initialTab = if (showTimePicker) DateTimePickerTab.TIME else DateTimePickerTab.DATE,
            onDismiss = {
                showDatePicker = false
                showTimePicker = false
            },
            onConfirm = { pickedMs ->
                timestamp = pickedMs
                showDatePicker = false
                showTimePicker = false
            },
        )
    }

    if (previewingEvidence && evidenceImagePath != null) {
        CaptureImagePreview(
            path = evidenceImagePath,
            onDismiss = { previewingEvidence = false },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReadOnlyField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 单选下拉：分类 / 来源 / 审核状态共用，样式与新建账单对话框一致。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceField(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    error: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            isError = error != null,
            supportingText = {
                if (error != null) {
                    Text(tr(error), color = MaterialTheme.colorScheme.error)
                }
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun sourceLabel(source: TransactionSource): String = when (source) {
    TransactionSource.WECHAT -> tr("微信")
    TransactionSource.ALIPAY -> tr("支付宝")
    TransactionSource.BANK_SMS -> tr("银行短信")
    TransactionSource.MANUAL -> tr("手动")
    TransactionSource.OTHER -> tr("其他")
}

private fun reviewStatusLabel(status: String): String = when (status) {
    "confirmed" -> tr("已确认")
    "excluded" -> tr("不计入")
    "transfer" -> tr("转账")
    "refund" -> tr("退款")
    "possible_duplicate" -> tr("待确认重复")
    else -> tr("未核实")
}

private fun formatEditDate(timestampMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMs))

private fun formatEditTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
