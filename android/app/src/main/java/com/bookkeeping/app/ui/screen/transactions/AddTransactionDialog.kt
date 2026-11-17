package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.MoneyAmount
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.CategoryClassifier
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.QuickEntryBar
import com.bookkeeping.app.ui.components.QuickEntryRule
import com.bookkeeping.app.voice.VoiceTransactionParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 新建账单里账户一栏的常用值；account 本身是自由文本，存进库里的就是这里的中文。 */
private val ACCOUNT_PRESETS = listOf("微信", "支付宝", "现金", "银行卡")

private enum class QuickEntryPreviewField { AMOUNT, CATEGORY, MERCHANT, ACCOUNT }

/** 点「保存」时算好的待写入内容；重复检查是异步的，命中后还要拿它继续记账。 */
private data class PendingSave(
    val amountCents: Long,
    val type: TransactionType,
    val currency: String,
    val category: TransactionCategory,
    val merchant: String,
    val note: String,
    val timestamp: Long,
    val account: String,
    /** 这句话对时间的精确程度，决定疑似重复检查的同商户窗口。 */
    val timePrecision: TimePrecision,
)

// "新建账单"底部面板
// 所有数据从外部传入、所有事件通过回调传出；表单状态只在面板存活期间存在。
// 顶部那句「一句话记账」只做预填（解析结果写进表单），保存仍然走 onConfirm。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    onDismiss: () -> Unit,
    initial: TransactionEntity? = null,
    onConfirm: suspend (
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
        timestamp: Long,
        account: String,
    ) -> Long,
    onSaved: (TransactionEntity) -> Unit = {},
    // 语音识别结果带进来的原文：为空就是普通新建。填了就在打开时先解析一次，
    // 让 ASR 那边只管把文本传进来，不用重复写「填哪个字段」的逻辑。
    initialQuickEntry: String = "",
    // 保存前的疑似重复检查。由调用方接到 DAO 的只读查询上；
    // 为 null 表示不检查（例如预留给编辑路径）。命中只会多问一句，不阻止保存。
    checkDuplicate: (suspend (
        amountCents: Long,
        type: TransactionType,
        currency: String,
        merchant: String,
        timestampMs: Long,
        precision: TimePrecision,
    ) -> DuplicateCandidate?)? = null,
) {
    val scope = rememberCoroutineScope()
    // 用户输入的本地 UI 状态（对话框关闭就丢弃，不存到 ViewModel）
    var amountText by remember(initial?.id) {
        mutableStateOf(initial?.let {
            (it.amountCents / 100).toString() + "." +
                (it.amountCents % 100).toString().padStart(2,'0')
        } ?: "")
    }
    var amountInputError by remember(initial?.id) { mutableStateOf<String?>(null) }
    var selectedType by remember(initial?.id) {
        mutableStateOf(runCatching { TransactionType.valueOf(initial?.type ?: "EXPENSE") }
            .getOrDefault(TransactionType.EXPENSE))
    }
    var selectedCategory by remember(initial?.id) {
        mutableStateOf(runCatching { TransactionCategory.valueOf(initial?.category ?: "FOOD") }
            .getOrDefault(TransactionCategory.OTHER))
    }
    var merchant by remember(initial?.id) { mutableStateOf(initial?.merchant ?: "") }
    var note by remember(initial?.id) { mutableStateOf(initial?.note ?: "") }
    // 账户：空串 = 「不填」，写库时转成 null。编辑已有账单时回填原值。
    var account by remember(initial?.id) { mutableStateOf(initial?.account.orEmpty()) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var amountTouched by remember(initial?.id) { mutableStateOf(initial != null) }
    var typeTouched by remember(initial?.id) { mutableStateOf(initial != null) }
    var categoryTouched by remember(initial?.id) { mutableStateOf(initial != null) }
    var merchantTouched by remember(initial?.id) { mutableStateOf(initial != null) }
    var noteTouched by remember(initial?.id) { mutableStateOf(initial != null) }
    var accountTouched by remember(initial?.id) { mutableStateOf(initial != null) }
    var timestampTouched by remember(initial?.id) { mutableStateOf(initial != null) }
    val defaultTimestamp = remember(initial?.id) {
        initial?.timestamp ?: System.currentTimeMillis()
    }
    var timestamp by remember(initial?.id) { mutableLongStateOf(defaultTimestamp) }
    var showDatePicker by remember { mutableStateOf(false) }
    // 一句话记账的原文：解析失败要能改两个字重提，所以提交后不清空。
    var quickEntry by remember(initial?.id, initialQuickEntry) { mutableStateOf(initialQuickEntry) }
    var quickEntryNotice by remember(initial?.id) { mutableStateOf<QuickEntryNotice?>(null) }
    var quickEntryPreview by remember(initial?.id) { mutableStateOf<QuickEntryPrefill?>(null) }
    var lastAutoPrefill by remember(initial?.id) { mutableStateOf<QuickEntryPrefill?>(null) }
    var quickEntryTimePrecision by remember(initial?.id) { mutableStateOf(TimePrecision.NONE) }
    val amountFocusRequester = remember(initial?.id) { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // 保存路径的三个状态：正在查 / 查到的疑似重复 / 待写入内容（命中后还要用它继续记账）。
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var duplicateHit by remember { mutableStateOf<DuplicateCandidate?>(null) }
    var pendingSave by remember { mutableStateOf<PendingSave?>(null) }

    // 解析一句话 → 回填字段。只写解析出来的那一部分，且只覆盖用户没亲手改过的字段。
    // 分类额外走现有商户记忆：用户规则优先，之后才用解析器分类。
    val applyQuickEntry: (String) -> QuickEntryPrefill = { raw ->
        val parsedAt = System.currentTimeMillis()
        val prefill = QuickEntryPrefill.from(
            VoiceTransactionParser.parse(raw, parsedAt),
            parsedAt,
            categoryResolver = { merchant, _ -> CategoryClassifier.rememberedCategory(merchant) },
        )
        val previous = lastAutoPrefill
        if (!amountTouched) {
            if (prefill.amountText != null) {
                amountText = prefill.amountText
                amountInputError = null
            } else if (previous?.amountText != null) {
                amountText = ""
                amountInputError = null
            }
        }
        if (!typeTouched) prefill.type?.let { selectedType = it }
        if (!categoryTouched) prefill.category?.let { selectedCategory = it }
        if (!merchantTouched) {
            merchant = prefill.merchant ?: if (previous?.merchant != null) "" else merchant
        }
        if (!noteTouched) note = prefill.note ?: if (previous?.note != null) "" else note
        if (!accountTouched) account = prefill.account ?: if (previous?.account != null) "" else account
        if (!timestampTouched) {
            timestamp = prefill.timestampMs
                ?: if (previous?.timestampMs != null) defaultTimestamp else timestamp
        }
        quickEntryNotice = prefill.notice
        quickEntryTimePrecision = prefill.timePrecision
        lastAutoPrefill = prefill
        quickEntryPreview = prefill.copy(
            amountText = prefill.amountText.takeUnless { amountTouched },
            category = prefill.category.takeUnless { categoryTouched },
            merchant = prefill.merchant.takeUnless { merchantTouched },
            account = prefill.account.takeUnless { accountTouched },
        )
        prefill
    }

    // 真正写入：只有持久化成功后才关闭面板；失败时保留表单与原文。
    val submitSave: (PendingSave) -> Unit = { payload ->
        duplicateHit = null
        pendingSave = null
        saveError = null
        saving = true
        scope.launch {
            val savedId = try {
                onConfirm(
                    payload.amountCents,
                    payload.type,
                    payload.category,
                    payload.merchant,
                    payload.note,
                    payload.timestamp,
                    payload.account,
                )
            } catch (cancellation: CancellationException) {
                saving = false
                throw cancellation
            } catch (_: Exception) {
                saving = false
                saveError = tr("保存失败，请重试")
                return@launch
            }
            saving = false
            val saved = TransactionEntity(
                id = savedId,
                amountCents = payload.amountCents,
                type = payload.type.name,
                category = payload.category.name,
                merchant = payload.merchant,
                note = payload.note,
                account = payload.account.trim().ifBlank { null },
                source = initial?.source ?: "MANUAL",
                timestamp = payload.timestamp,
            )
            onDismiss()
            onSaved(saved)
        }
    }

    // 手填了金额就用表单值；只写了一句话就解析一次。解析不出金额返回 null，绝不猜。
    // 手填没有「说了哪天/几点」这句话：时间取「现在」或用户改的日期，保守按精确档查重
    // （表单只有日期选择器，没有改时分的入口，判断不了就默认精确）。
    val entryCurrency = initial?.currency?.trim()?.takeIf { it.isNotEmpty() } ?: "CNY"
    val buildPayload: () -> PendingSave? = {
        if (QuickEntryRule.canSubmit(quickEntry)) applyQuickEntry(quickEntry)
        val typed = MoneyAmount.cents(amountText)
        when {
            typed != null -> PendingSave(
                amountCents = typed,
                type = selectedType,
                currency = entryCurrency,
                category = selectedCategory,
                merchant = merchant,
                note = note,
                timestamp = timestamp,
                account = account,
                timePrecision = if (
                    !timestampTouched && quickEntryTimePrecision != TimePrecision.NONE
                ) quickEntryTimePrecision else TimePrecision.PRECISE,
            )
            else -> null
        }
    }

    // 「确认记账」：先查疑似重复。手动新建和语音（同一句话预填）都走这一条路径。
    val requestSave: () -> Unit = requestSave@{
        if (checking || saving) return@requestSave
        saveError = null
        // 先落到本地 val：suspend lambda 里不再依赖对可空参数的智能转换。
        val checker = checkDuplicate
        val payload = buildPayload()
        when {
            payload == null -> Unit
            checker == null -> submitSave(payload)
            else -> {
                pendingSave = payload
                checking = true
                scope.launch {
                    // 读库失败按「没命中」处理；协程取消原样抛出，避免弹窗已关还继续保存。
                    val hit = try {
                        checker(
                            payload.amountCents,
                            payload.type,
                            payload.currency,
                            payload.merchant,
                            payload.timestamp,
                            payload.timePrecision,
                        )
                    } catch (cancellation: CancellationException) {
                        checking = false
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                    checking = false
                    if (hit == null) submitSave(payload) else duplicateHit = hit
                }
            }
        }
    }

    // 一句话停顿约 500ms 后自动预填；清空原文不会清掉已填表单。
    LaunchedEffect(quickEntry) {
        if (!QuickEntryRule.canSubmit(quickEntry)) return@LaunchedEffect
        delay(500)
        applyQuickEntry(quickEntry)
    }
    LaunchedEffect(amountFocusRequester) { amountFocusRequester.requestFocus() }

    val amountIsNegative = amountText.contains('-') || amountText.contains('−') ||
        (amountText.toDoubleOrNull()?.let { it < 0.0 } == true)
    val amountError = when {
        amountIsNegative -> tr("金额不能为负数，请用支出/收入切换方向")
        amountInputError != null -> amountInputError
        amountText.isNotEmpty() && MoneyAmount.cents(amountText) == null -> tr("请输入有效金额")
        else -> null
    }
    val amountValid = amountError == null && MoneyAmount.cents(amountText) != null
    val dismissQuickEntryPreview: (QuickEntryPreviewField) -> Unit = { field ->
        quickEntryPreview = when (field) {
            QuickEntryPreviewField.AMOUNT -> quickEntryPreview?.copy(amountText = null)
            QuickEntryPreviewField.CATEGORY -> quickEntryPreview?.copy(category = null)
            QuickEntryPreviewField.MERCHANT -> quickEntryPreview?.copy(merchant = null)
            QuickEntryPreviewField.ACCOUNT -> quickEntryPreview?.copy(account = null)
        }
        when (field) {
            QuickEntryPreviewField.AMOUNT -> {
                amountTouched = true
                amountText = ""
                amountInputError = null
            }
            QuickEntryPreviewField.CATEGORY -> {
                categoryTouched = true
                selectedCategory = TransactionCategory.OTHER
            }
            QuickEntryPreviewField.MERCHANT -> {
                merchantTouched = true
                merchant = ""
            }
            QuickEntryPreviewField.ACCOUNT -> {
                accountTouched = true
                account = ""
            }
        }
    }

    EntryPanelLayout(
        title = if (initial == null) tr("新建账单") else tr("编辑账单"),
        onDismiss = { if (!saving) onDismiss() },
        fillPanelHeight = false,
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickEntryBar(
                value = quickEntry,
                onValueChange = { raw ->
                    quickEntry = raw
                    quickEntryNotice = null
                    quickEntryPreview = null
                },
                onSubmit = { raw -> applyQuickEntry(raw) },
                previewContent = {
                    quickEntryPreview?.let { preview ->
                        QuickEntryPreviewRow(
                            prefill = preview,
                            onDismiss = dismissQuickEntryPreview,
                        )
                    }
                },
                warning = quickEntryNotice?.let {
                    tr(
                        if (it == QuickEntryNotice.AMOUNT_MISSING) {
                            "没认出金额，请手填"
                        } else {
                            it.message
                        },
                    )
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedType == TransactionType.EXPENSE,
                    onClick = {
                        selectedType = TransactionType.EXPENSE
                        typeTouched = true
                        if (selectedCategory == TransactionCategory.SALARY) {
                            selectedCategory = TransactionCategory.FOOD
                            categoryTouched = true
                            quickEntryPreview = quickEntryPreview?.copy(category = null)
                        }
                    },
                    label = { Text(tr("支出")) },
                )
                FilterChip(
                    selected = selectedType == TransactionType.INCOME,
                    onClick = {
                        selectedType = TransactionType.INCOME
                        typeTouched = true
                        if (selectedCategory == TransactionCategory.FOOD) {
                            selectedCategory = TransactionCategory.SALARY
                            categoryTouched = true
                            quickEntryPreview = quickEntryPreview?.copy(category = null)
                        }
                    },
                    label = { Text(tr("收入")) },
                )
            }

            // 金额优先；日期按钮保持单行并使用固有宽度。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        if (raw.contains('-') || raw.contains('−')) {
                            amountInputError = tr("金额不能为负数，请用支出/收入切换方向")
                        } else {
                            val candidate = raw.filter { c -> c.isDigit() || c == '.' }
                            if (candidate.hasMoreThanTwoFractionDigits()) {
                                amountInputError = tr("金额最多保留两位小数")
                            } else {
                                amountText = candidate
                                amountTouched = true
                                quickEntryPreview = quickEntryPreview?.copy(amountText = null)
                                amountInputError = null
                            }
                        }
                    },
                    label = { Text(tr("金额（元）"), maxLines = 1, softWrap = false) },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    singleLine = true,
                    isError = amountError != null,
                    supportingText = {
                        if (amountError != null) {
                            Text(amountError, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(amountFocusRequester),
                )

                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.widthIn(min = 88.dp, max = 112.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(imageVector = Icons.Outlined.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formatCompactDate(timestamp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box {
                OutlinedButton(
                    onClick = { categoryMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CategoryIcon(category = selectedCategory, size = 24.dp)
                        Text(
                            text = tr(selectedCategory.displayName),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(tr("分类"), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                DropdownMenu(
                    expanded = categoryMenuExpanded,
                    onDismissRequest = { categoryMenuExpanded = false },
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    TransactionCategory.entries.forEach { cat ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CategoryIcon(category = cat, size = 24.dp)
                                    Text(tr(cat.displayName), maxLines = 1)
                                }
                            },
                            onClick = {
                                selectedCategory = cat
                                categoryTouched = true
                                quickEntryPreview = quickEntryPreview?.copy(category = null)
                                categoryMenuExpanded = false
                            },
                        )
                    }
                }
            }

            // 账户 chips 横向滚动，英文界面下最后一个选项仍可完整查看和点击。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = tr("账户"),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    FilterChip(
                        selected = account.isBlank(),
                        onClick = {
                            account = ""
                            accountTouched = true
                            quickEntryPreview = quickEntryPreview?.copy(account = null)
                        },
                        label = {
                            Text(
                                tr("不填"),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                    ACCOUNT_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = account == preset,
                            onClick = {
                                account = preset
                                accountTouched = true
                                quickEntryPreview = quickEntryPreview?.copy(account = null)
                            },
                            label = {
                                Text(
                                    tr(preset),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                        )
                    }
                    // 编辑自动采集的账单时，常用值之外的自定义账户仍可见并保持选中。
                    val custom = account.trim()
                    if (custom.isNotEmpty() && custom !in ACCOUNT_PRESETS) {
                        FilterChip(
                            selected = true,
                            onClick = {
                                account = ""
                                accountTouched = true
                                quickEntryPreview = quickEntryPreview?.copy(account = null)
                            },
                            label = {
                                Text(
                                    text = custom,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = merchant,
                onValueChange = {
                    merchant = it
                    merchantTouched = true
                    quickEntryPreview = quickEntryPreview?.copy(merchant = null)
                },
                label = {
                    Text(
                        text = tr("商户/对方（可选）"),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = note,
                onValueChange = {
                    note = it
                    noteTouched = true
                },
                label = { Text(tr("备注（可选）"), maxLines = 1, softWrap = false) },
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            saveError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (!saving) onDismiss() }, enabled = !saving) {
                    Text(tr("取消"), maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = requestSave,
                    enabled = !checking && !saving &&
                        (amountValid || (amountText.isBlank() && QuickEntryRule.canSubmit(quickEntry))),
                ) {
                    Text(
                        when {
                            checking -> tr("正在检查…")
                            saving -> tr("正在保存…")
                            else -> tr("保存")
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }

    // 疑似重复：只提示，不合并、不静默去重。取消回到表单，数据一个都不丢。
    duplicateHit?.let { hit ->
        AlertDialog(
            onDismissRequest = {
                duplicateHit = null
                pendingSave = null
            },
            title = { Text(tr("可能重复")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("账本里已有一笔相似的账单。"))
                    Text("${tr("时间")}：${formatDateTime(hit.timestampMs)}")
                    Text("${tr("商户")}：${hit.merchant.ifBlank { "—" }}")
                    Text("${tr("金额")}：¥${QuickEntryPrefill.centsToText(hit.amountCents)}")
                    Text("${tr("来源")}：${saveDuplicateSourceLabel(hit.source)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingSave?.let(submitSave) }) {
                    Text(tr("仍然记这一笔"))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    duplicateHit = null
                    pendingSave = null
                }) { Text(tr("取消")) }
            },
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToUtc(timestamp),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        timestamp = applyUtcDate(it, timestamp)
                        timestampTouched = true
                    }
                    showDatePicker = false
                }) { Text(tr("确定")) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(tr("取消")) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun QuickEntryPreviewRow(
    prefill: QuickEntryPrefill,
    onDismiss: (QuickEntryPreviewField) -> Unit,
) {
    if (
        prefill.amountText == null && prefill.category == null &&
        prefill.merchant == null && prefill.account == null
    ) return

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prefill.amountText?.let { amount ->
            val compactAmount = amount.removeSuffix(".00")
            QuickEntryPreviewChip("¥$compactAmount") {
                onDismiss(QuickEntryPreviewField.AMOUNT)
            }
        }
        prefill.category?.let { category ->
            QuickEntryPreviewChip(tr(category.displayName)) {
                onDismiss(QuickEntryPreviewField.CATEGORY)
            }
        }
        prefill.merchant?.let { value ->
            QuickEntryPreviewChip(value) { onDismiss(QuickEntryPreviewField.MERCHANT) }
        }
        prefill.account?.let { value ->
            QuickEntryPreviewChip(tr(value)) { onDismiss(QuickEntryPreviewField.ACCOUNT) }
        }
    }
}

@Composable
private fun QuickEntryPreviewChip(label: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.widthIn(max = 150.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = tr("移除"),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun formatCompactDate(timestampMs: Long): String =
    SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestampMs))

/** 疑似重复弹框里的对方时间：日期 + 时分，够用来判断是不是同一笔。 */
private fun formatDateTime(timestampMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMs))

/** 对方记录的来源；口径与详情页一致（自动采集 / 手动）。 */
private fun saveDuplicateSourceLabel(source: String): String = when (source) {
    "ALIPAY" -> tr("支付宝")
    "WECHAT" -> tr("微信")
    "BANK_SMS" -> tr("银行短信")
    "MANUAL" -> tr("手动")
    else -> tr("其他来源")
}

private fun String.hasMoreThanTwoFractionDigits(): Boolean =
    contains('.') && substringAfter('.').count { it.isDigit() } > 2

private fun localDateToUtc(timestampMs: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = timestampMs }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH),
            local.get(Calendar.DAY_OF_MONTH),
        )
    }.timeInMillis
}

private fun applyUtcDate(utcDateMs: Long, keepTimeFromMs: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcDateMs
    }
    val local = Calendar.getInstance().apply { timeInMillis = keepTimeFromMs }
    local.set(
        utc.get(Calendar.YEAR),
        utc.get(Calendar.MONTH),
        utc.get(Calendar.DAY_OF_MONTH),
    )
    return local.timeInMillis
}
