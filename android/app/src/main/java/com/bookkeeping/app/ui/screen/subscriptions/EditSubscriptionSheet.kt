package com.bookkeeping.app.ui.screen.subscriptions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookkeeping.app.data.MoneyAmount
import com.bookkeeping.app.data.local.entity.SubscriptionCadence
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.data.subscription.RecurringCandidate
import com.bookkeeping.app.data.subscription.SubscriptionFxRatesCalculator
import com.bookkeeping.app.data.subscription.SubscriptionProjection
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.i18n.CalendarDateText
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.formatCny
import com.bookkeeping.app.ui.theme.QingExpense
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

private val SUPPORTED_CURRENCIES = listOf("CNY", "USD", "HKD", "EUR", "JPY", "GBP")

private val CADENCE_OPTIONS = listOf(
    SubscriptionCadence.MONTHLY to "每月",
    SubscriptionCadence.YEARLY to "每年",
    SubscriptionCadence.QUARTERLY to "每季",
    SubscriptionCadence.WEEKLY to "每周",
)

private val PAYMENT_CHANNEL_PRESETS = listOf(
    "微信",
    "支付宝",
    "银行卡",
    "云闪付",
    "京东白条",
    "Apple Pay",
    "未指定",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSubscriptionSheet(
    existing: SubscriptionEntity? = null,
    candidatePrefill: RecurringCandidate? = null,
    fxRates: Map<String, Double> = SubscriptionFxRatesCalculator.DEFAULT_RATES,
    onDismiss: () -> Unit,
    onSave: (SubscriptionEntity) -> Unit,
    onDelete: ((Long) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditing = existing != null && existing.id > 0L

    var name by remember(existing?.id, candidatePrefill) {
        mutableStateOf(existing?.name ?: candidatePrefill?.merchant.orEmpty())
    }
    var amountText by remember(existing?.id, candidatePrefill) {
        val cents = existing?.amountCents ?: candidatePrefill?.amountCents
        mutableStateOf(
            cents?.let {
                val whole = it / 100L
                val frac = (it % 100L).toInt()
                if (frac == 0) whole.toString() else "$whole.${frac.toString().padStart(2, '0')}"
            } ?: "",
        )
    }
    var currency by remember(existing?.id, candidatePrefill) {
        mutableStateOf(
            (existing?.currency ?: candidatePrefill?.currency ?: "CNY")
                .uppercase(Locale.US)
                .takeIf { it in SUPPORTED_CURRENCIES } ?: "CNY",
        )
    }
    var cadence by remember(existing?.id, candidatePrefill) {
        mutableStateOf(existing?.cadence ?: candidatePrefill?.cadence ?: SubscriptionCadence.MONTHLY)
    }
    var nextDueMs by remember(existing?.id, candidatePrefill) {
        mutableLongStateOf(
            SubscriptionProjection.startOfDayMs(
                existing?.nextDueMs
                    ?: candidatePrefill?.suggestedNextDueMs
                    ?: System.currentTimeMillis(),
            ),
        )
    }
    val initialChannel = remember(existing?.id, candidatePrefill) {
        val raw = (existing?.paymentChannel ?: candidatePrefill?.suggestedChannel.orEmpty()).trim()
        if (raw == "花呗" || raw == "蚂蚁花呗" || raw.equals("Huabei", ignoreCase = true) || raw.contains("花呗")) "支付宝" else raw
    }
    var paymentChannel by remember(existing?.id, candidatePrefill) {
        mutableStateOf(initialChannel)
    }
    var customChannelText by remember(existing?.id, candidatePrefill) {
        mutableStateOf(
            if (initialChannel.isNotEmpty() && initialChannel !in PAYMENT_CHANNEL_PRESETS) {
                initialChannel
            } else {
                ""
            },
        )
    }
    var autoRenew by remember(existing?.id, candidatePrefill) {
        mutableStateOf(existing?.autoRenew ?: true)
    }
    var notifyEnabled by remember(existing?.id, candidatePrefill) {
        mutableStateOf(existing?.notifyEnabled ?: true)
    }
    var notifyDaysBefore by remember(existing?.id, candidatePrefill) {
        mutableIntStateOf(existing?.notifyDaysBefore ?: 3)
    }
    var note by remember(existing?.id, candidatePrefill) {
        mutableStateOf(existing?.note.orEmpty())
    }

    var showDueDatePicker by remember { mutableStateOf(false) }
    var showCurrencyMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var amountError by remember { mutableStateOf<String?>(null) }

    val parsedAmountCents = remember(amountText) { MoneyAmount.cents(amountText) }
    val previewCnyCents = remember(parsedAmountCents, currency, fxRates) {
        if (parsedAmountCents == null || currency == "CNY") null
        else SubscriptionFxRatesCalculator.estimateCnyCents(parsedAmountCents, currency, fxRates)
    }
    val dueShortLabel = remember(nextDueMs) {
        SimpleDateFormat("M/d", Locale.CHINA).format(Date(nextDueMs))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = QingSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header (matches 新建账单)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isEditing) "编辑订阅" else "新建订阅",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = QingText,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = tr("关闭"),
                        tint = QingTextSecondary,
                    )
                }
            }

            // 1. Hero Amount + Currency + Embedded Date Pill (matches 新建账单)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = QingSurfaceVariant.copy(alpha = 0.55f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(role = Role.Button) { showCurrencyMenu = true },
                                shape = RoundedCornerShape(10.dp),
                                color = QingSurface,
                            ) {
                                Text(
                                    text = "${currencyPrefix(currency)} $currency",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = QingPrimaryDeep,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = showCurrencyMenu,
                                onDismissRequest = { showCurrencyMenu = false },
                            ) {
                                SUPPORTED_CURRENCIES.forEach { code ->
                                    DropdownMenuItem(
                                        text = { Text("${currencyPrefix(code)} $code") },
                                        onClick = {
                                            currency = code
                                            showCurrencyMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            if (amountText.isEmpty()) {
                                Text(
                                    text = "0.00",
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontFeatureSettings = "tnum",
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = QingTextSecondary.copy(alpha = 0.45f),
                                )
                            }
                            BasicTextField(
                                value = amountText,
                                onValueChange = { raw ->
                                    val cleaned = raw.filter { it.isDigit() || it == '.' }
                                    if (cleaned.count { it == '.' } <= 1) {
                                        amountText = cleaned
                                        amountError = null
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                textStyle = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = QingText,
                                    fontFeatureSettings = "tnum",
                                ),
                                cursorBrush = SolidColor(QingPrimary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) { showDueDatePicker = true },
                            shape = RoundedCornerShape(10.dp),
                            color = QingSurface,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CalendarMonth,
                                    contentDescription = null,
                                    tint = QingPrimary,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = dueShortLabel,
                                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = QingText,
                                )
                            }
                        }
                    }
                }
                if (amountError != null) {
                    Text(
                        text = amountError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = QingExpense,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                } else if (previewCnyCents != null) {
                    Text(
                        text = "约折合人民币 ${formatCny(previewCnyCents)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = QingTextSecondary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            // 2. Borderless soft Name input
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = QingSurfaceVariant.copy(alpha = 0.55f),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                    ) {
                        if (name.isEmpty()) {
                            Text(
                                text = "订阅名称（如 ChatGPT Plus、淘宝 88VIP）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = QingTextSecondary,
                            )
                        }
                        BasicTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                nameError = null
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = QingText,
                                fontWeight = FontWeight.Medium,
                            ),
                            cursorBrush = SolidColor(QingPrimary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (nameError != null) {
                    Text(
                        text = nameError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = QingExpense,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            // 3. Cadence row
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "周期",
                    style = MaterialTheme.typography.labelMedium,
                    color = QingTextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CADENCE_OPTIONS.forEach { (value, label) ->
                        SubscriptionChoiceChip(
                            label = label,
                            selected = cadence == value,
                            onClick = { cadence = value },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 4. Payment channel chips (matches 新建账单 账户 row)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "扣款渠道",
                    style = MaterialTheme.typography.labelMedium,
                    color = QingTextSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PAYMENT_CHANNEL_PRESETS.forEach { preset ->
                        val isUnspecified = preset == "未指定"
                        val selected = if (isUnspecified) {
                            customChannelText.isBlank() &&
                                (paymentChannel.isBlank() || paymentChannel == "未指定")
                        } else {
                            customChannelText.isBlank() && paymentChannel == preset
                        }
                        SubscriptionChoiceChip(
                            label = preset,
                            selected = selected,
                            onClick = {
                                customChannelText = ""
                                paymentChannel = if (isUnspecified) "" else preset
                            },
                        )
                    }
                }
            }

            // 5. Auto-renew + Notify settings (borderless soft card)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = QingSurfaceVariant.copy(alpha = 0.45f),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Switch) { autoRenew = !autoRenew }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自动续费",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = QingText,
                            )
                            Text(
                                text = "扣款日过后自动推进到下一期",
                                style = MaterialTheme.typography.labelSmall,
                                color = QingTextSecondary,
                            )
                        }
                        Switch(
                            checked = autoRenew,
                            onCheckedChange = { autoRenew = it },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = QingOutline.copy(alpha = 0.5f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Switch) { notifyEnabled = !notifyEnabled }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "到期提醒",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = QingText,
                            )
                            Text(
                                text = if (notifyEnabled) {
                                    "提前 $notifyDaysBefore 天发送本地通知"
                                } else {
                                    "已关闭本地提醒"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = QingTextSecondary,
                            )
                        }
                        Switch(
                            checked = notifyEnabled,
                            onCheckedChange = { notifyEnabled = it },
                        )
                    }
                    if (notifyEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "提前提醒天数",
                                style = MaterialTheme.typography.bodySmall,
                                color = QingTextSecondary,
                            )
                            NotifyDaysStepper(
                                days = notifyDaysBefore,
                                onChange = { notifyDaysBefore = it },
                            )
                        }
                    }
                }
            }

            // 6. Borderless soft Note input
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = QingSurfaceVariant.copy(alpha = 0.55f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (note.isEmpty()) {
                        Text(
                            text = "备注（可选）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = QingTextSecondary,
                        )
                    }
                    BasicTextField(
                        value = note,
                        onValueChange = { note = it },
                        maxLines = 3,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = QingText),
                        cursorBrush = SolidColor(QingPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Save button
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    val cents = MoneyAmount.cents(amountText)
                    if (trimmedName.isEmpty()) {
                        nameError = "请输入订阅名称"
                    }
                    if (cents == null || cents <= 0L) {
                        amountError = "请输入有效金额"
                    }
                    if (trimmedName.isEmpty() || cents == null || cents <= 0L) return@Button

                    val finalChannel = customChannelText.trim().ifEmpty { paymentChannel.trim() }
                    val nowMs = System.currentTimeMillis()
                    val normalizedDue = SubscriptionProjection.startOfDayMs(nextDueMs)
                    val entity = (existing ?: SubscriptionEntity(
                        syncUuid = UUID.randomUUID().toString(),
                        name = trimmedName,
                        amountCents = cents,
                        currency = currency,
                        cadence = cadence,
                        nextDueMs = normalizedDue,
                        startMs = normalizedDue,
                        updatedAtMs = nowMs,
                    )).copy(
                        name = trimmedName,
                        amountCents = cents,
                        currency = currency,
                        cnyEstimateCents = SubscriptionFxRatesCalculator.estimateCnyCents(cents, currency, fxRates),
                        cadence = cadence,
                        nextDueMs = normalizedDue,
                        startMs = existing?.startMs ?: normalizedDue,
                        paymentChannel = finalChannel,
                        autoRenew = autoRenew,
                        notifyEnabled = notifyEnabled,
                        notifyDaysBefore = notifyDaysBefore.coerceIn(0, 30),
                        note = note.trim(),
                        updatedAtMs = nowMs,
                        dirty = true,
                    )
                    onSave(entity)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = QingPrimary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = if (isEditing) "保存修改" else "添加订阅",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (isEditing && onDelete != null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = QingExpense,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "彻底删除",
                            color = QingExpense,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }

    if (showDueDatePicker) {
        SubscriptionDueDateDialog(
            initialTimestampMs = nextDueMs,
            onDismiss = { showDueDatePicker = false },
            onConfirm = { picked ->
                nextDueMs = picked
                showDueDatePicker = false
            },
        )
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("彻底删除订阅？") },
            text = { Text("确定彻底删除「${existing.name}」？删除后将从提醒与同步队列中移除，已记入账本的历史流水会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete?.invoke(existing.id)
                        onDismiss()
                    },
                ) {
                    Text(tr("删除"), color = QingExpense)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(tr("取消"))
                }
            },
        )
    }
}

@Composable
private fun SubscriptionChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        },
        border = if (selected) null else BorderStroke(1.dp, QingOutline),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = QingSurface,
            labelColor = QingTextSecondary,
            selectedContainerColor = QingPrimaryContainer,
            selectedLabelColor = QingPrimaryDeep,
        ),
    )
}

private fun currencyPrefix(currency: String): String = when (currency.uppercase(Locale.US)) {
    "CNY" -> "¥ "
    "USD" -> "$ "
    "HKD" -> "HK$ "
    "EUR" -> "€ "
    "JPY" -> "¥ "
    "GBP" -> "£ "
    else -> "$currency "
}

/**
 * Date picker dialog tailored for subscription renewal dates (supports past and future dates).
 */
@Composable
private fun SubscriptionDueDateDialog(
    initialTimestampMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val initialCal = remember(initialTimestampMs) {
        Calendar.getInstance().apply { timeInMillis = initialTimestampMs }
    }
    var selectedYear by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.YEAR)) }
    var selectedMonth by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.MONTH) + 1) }
    var selectedDay by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.DAY_OF_MONTH)) }
    var displayYear by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.YEAR)) }
    var displayMonth by remember(initialTimestampMs) { mutableIntStateOf(initialCal.get(Calendar.MONTH) + 1) }

    val candidateMs = remember(selectedYear, selectedMonth, selectedDay) {
        Calendar.getInstance().apply {
            clear()
            set(selectedYear, selectedMonth - 1, selectedDay, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(20.dp),
            color = QingSurface,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "选择下次扣款日",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = QingText,
                        )
                        Text(
                            text = "%04d-%02d-%02d".format(Locale.CHINA, selectedYear, selectedMonth, selectedDay),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = QingPrimary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickPresetDatePill("今天") {
                            val cal = Calendar.getInstance()
                            selectedYear = cal.get(Calendar.YEAR)
                            selectedMonth = cal.get(Calendar.MONTH) + 1
                            selectedDay = cal.get(Calendar.DAY_OF_MONTH)
                            displayYear = selectedYear
                            displayMonth = selectedMonth
                        }
                        QuickPresetDatePill("下月") {
                            val cal = Calendar.getInstance().apply { add(Calendar.MONTH, 1) }
                            selectedYear = cal.get(Calendar.YEAR)
                            selectedMonth = cal.get(Calendar.MONTH) + 1
                            selectedDay = cal.get(Calendar.DAY_OF_MONTH)
                            displayYear = selectedYear
                            displayMonth = selectedMonth
                        }
                    }
                }

                // Month header
                val monthMeta = remember(displayYear, displayMonth) {
                    val cal = Calendar.getInstance().apply {
                        clear()
                        set(displayYear, displayMonth - 1, 1)
                    }
                    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val firstOffset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                    firstOffset to days
                }
                val (firstOffset, daysInMonth) = monthMeta

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = {
                            val cal = Calendar.getInstance().apply {
                                set(displayYear, displayMonth - 1, 1)
                                add(Calendar.MONTH, -1)
                            }
                            displayYear = cal.get(Calendar.YEAR)
                            displayMonth = cal.get(Calendar.MONTH) + 1
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = tr("上个月"))
                    }
                    Text(
                        text = CalendarDateText.monthHeading(displayYear, displayMonth),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = QingText,
                    )
                    IconButton(
                        onClick = {
                            val cal = Calendar.getInstance().apply {
                                set(displayYear, displayMonth - 1, 1)
                                add(Calendar.MONTH, 1)
                            }
                            displayYear = cal.get(Calendar.YEAR)
                            displayMonth = cal.get(Calendar.MONTH) + 1
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = tr("下个月"))
                    }
                }

                val weekdays = if (AppLanguage.current() == AppLanguage.ENGLISH) {
                    listOf("M", "T", "W", "T", "F", "S", "S")
                } else {
                    listOf("一", "二", "三", "四", "五", "六", "日")
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    weekdays.forEach { label ->
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            color = QingTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                val todayCal = remember { Calendar.getInstance() }
                val todayYear = todayCal.get(Calendar.YEAR)
                val todayMonth = todayCal.get(Calendar.MONTH) + 1
                val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)
                val rows = (firstOffset + daysInMonth + 6) / 7

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (col in 0 until 7) {
                                val dayNum = row * 7 + col - firstOffset + 1
                                if (dayNum !in 1..daysInMonth) {
                                    Box(modifier = Modifier.weight(1f).aspectRatio(1.15f))
                                } else {
                                    val isSelected = displayYear == selectedYear &&
                                        displayMonth == selectedMonth &&
                                        dayNum == selectedDay
                                    val isToday = displayYear == todayYear &&
                                        displayMonth == todayMonth &&
                                        dayNum == todayDay
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1.15f)
                                            .padding(2.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) QingPrimary else Color.Transparent,
                                            )
                                            .then(
                                                if (isToday && !isSelected) {
                                                    Modifier.border(
                                                        1.dp,
                                                        QingPrimary.copy(alpha = 0.6f),
                                                        CircleShape,
                                                    )
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .clickable {
                                                selectedYear = displayYear
                                                selectedMonth = displayMonth
                                                selectedDay = dayNum
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = dayNum.toString(),
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                                            color = when {
                                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                                isToday -> QingPrimary
                                                else -> QingText
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = QingOutline)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(tr("取消"))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(candidateMs) },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(tr("确定"))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPresetDatePill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(QingSurfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = QingTextSecondary,
        )
    }
}
