package com.bookkeeping.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.CustomMerchantIconStore
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.MerchantAvatar
import com.bookkeeping.app.ui.components.MerchantIconPickerSheet
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.qingColors
import kotlinx.coroutines.launch

private val PLACEHOLDER_MERCHANTS = setOf(
    "转入",
    "转出",
    "未知商户",
    "未知",
    "微信支付",
    "支付宝",
    "微信转账",
    "支付宝转账",
    "红包",
    "微信红包",
    "群收款",
    "零钱提现",
    "零钱充值",
    "信用卡还款",
    "余额宝",
    "理财通",
)

@Composable
fun MerchantIconsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val colors = qingColors
    val customStore = remember(context) { CustomMerchantIconStore.get(context) }
    val overridesMap by customStore.overrides.collectAsState()
    val overridesList = remember(overridesMap) { overridesMap.values.toList() }

    val recentMerchants by viewModel.recentMerchants.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshRecentMerchants() }

    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var pickerMerchant by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val trimmedQuery = query.trim()
    val queryLower = trimmedQuery.lowercase()

    val normalizedOverrideKeys = remember(overridesMap) {
        overridesMap.keys.map { it.trim().lowercase() }.toSet()
    }

    val filteredOverrides = remember(overridesList, queryLower) {
        if (queryLower.isEmpty()) overridesList
        else overridesList.filter { it.merchant.lowercase().contains(queryLower) }
    }

    val ledgerMerchants = remember(recentMerchants, normalizedOverrideKeys) {
        recentMerchants
            .map { it.trim() }
            .filter { name ->
                name.isNotEmpty() &&
                    name !in PLACEHOLDER_MERCHANTS &&
                    name.lowercase() !in normalizedOverrideKeys
            }
            .distinctBy { it.lowercase() }
    }

    val filteredLedgerMerchants = remember(ledgerMerchants, queryLower) {
        if (queryLower.isEmpty()) ledgerMerchants
        else ledgerMerchants.filter { it.lowercase().contains(queryLower) }
    }

    val hasExactMatch = remember(trimmedQuery, overridesList, ledgerMerchants) {
        if (trimmedQuery.isEmpty()) true
        else {
            overridesList.any { it.merchant.equals(trimmedQuery, ignoreCase = true) } ||
                ledgerMerchants.any { it.equals(trimmedQuery, ignoreCase = true) }
        }
    }

    Scaffold(
        containerColor = colors.surfaceCanvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = tr("返回"),
                        tint = colors.textPrimary,
                    )
                }
                Text(
                    text = tr("商户图标管理"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceCard)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = tr("搜索商户或输入新商户名"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                        ),
                        cursorBrush = SolidColor(QingPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { query = "" },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = tr("清空搜索"),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (trimmedQuery.isEmpty()) {
                    item(key = "refresh_card") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(QingPrimaryContainer.copy(alpha = 0.72f))
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = tr("一键刷新老账本图标"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = QingPrimaryDeep,
                                )
                                Text(
                                    text = tr("用最新品牌库和自定义图标更新历史账单"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = QingPrimaryDeep.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = {
                                    refreshing = true
                                    viewModel.refreshAllLedgerIcons { count ->
                                        refreshing = false
                                        notify(
                                            if (count > 0) tr("已更新 %d 笔历史账单图标").format(count)
                                            else tr("所有历史账单已是最新图标"),
                                        )
                                    }
                                },
                                enabled = !refreshing,
                                colors = ButtonDefaults.buttonColors(containerColor = QingPrimary),
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (refreshing) tr("刷新中") else tr("刷新"),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                if (trimmedQuery.isNotEmpty() && !hasExactMatch) {
                    item(key = "quick_create") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.surfaceCard)
                                .clickable(role = Role.Button) {
                                    pickerMerchant = trimmedQuery
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(QingPrimaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = QingPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                text = tr("为「%s」自定义图标").format(trimmedQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = colors.textSecondary,
                            )
                        }
                    }
                }

                if (filteredOverrides.isNotEmpty()) {
                    item(key = "custom_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = tr("已自定义（%d）").format(filteredOverrides.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(colors.surfaceCard),
                            ) {
                                filteredOverrides.forEachIndexed { index, entry ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(start = 66.dp),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(role = Role.Button) { pickerMerchant = entry.merchant }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        MerchantAvatar(
                                            merchant = entry.merchant,
                                            merchantIconKey = entry.brandKey,
                                            merchantIconPath = entry.imageFileName,
                                            category = TransactionCategory.OTHER,
                                            size = 38.dp,
                                            cornerRadius = 11.dp,
                                        )
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = entry.merchant,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium,
                                                color = colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = if (!entry.imageFileName.isNullOrBlank()) tr("相册图片") else tr("品牌图标"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.textSecondary,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                customStore.remove(entry.merchant)
                                                viewModel.applyCustomMerchantIcon(
                                                    merchant = entry.merchant,
                                                    iconKey = null,
                                                    iconPath = null,
                                                    applyToAllHistory = true,
                                                )
                                                notify(tr("已恢复默认图标"))
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = tr("删除"),
                                                tint = colors.textSecondary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (filteredLedgerMerchants.isNotEmpty()) {
                    item(key = "ledger_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = tr("账本中的商户（%d）").format(filteredLedgerMerchants.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(colors.surfaceCard),
                            ) {
                                filteredLedgerMerchants.forEachIndexed { index, merchant ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(start = 66.dp),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(role = Role.Button) { pickerMerchant = merchant }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        MerchantAvatar(
                                            merchant = merchant,
                                            merchantIconKey = null,
                                            category = TransactionCategory.OTHER,
                                            size = 38.dp,
                                            cornerRadius = 11.dp,
                                        )
                                        Text(
                                            text = merchant,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = tr("更换"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary,
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (filteredOverrides.isEmpty() && filteredLedgerMerchants.isEmpty() && hasExactMatch) {
                    item(key = "empty_state") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(colors.surfaceCard)
                                .padding(horizontal = 20.dp, vertical = 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (trimmedQuery.isEmpty()) {
                                    tr("账本里还没有商户记录，可在上方搜索框输入商户名添加")
                                } else {
                                    tr("没有匹配的商户")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }

    pickerMerchant?.let { target ->
        MerchantIconPickerSheet(
            merchantName = target,
            onDismiss = { pickerMerchant = null },
            onApplyBrand = { brandKey, applyAll ->
                customStore.setBrandIcon(target, brandKey)
                viewModel.applyCustomMerchantIcon(target, brandKey, null, applyAll) { updated ->
                    notify(
                        if (updated > 0) tr("已更新 %d 笔历史账单图标").format(updated)
                        else tr("已保存"),
                    )
                }
                pickerMerchant = null
            },
            onApplyImage = { fileName, applyAll ->
                customStore.setImageIcon(target, fileName)
                viewModel.applyCustomMerchantIcon(target, null, fileName, applyAll) { updated ->
                    notify(
                        if (updated > 0) tr("已更新 %d 笔历史账单图标").format(updated)
                        else tr("已保存"),
                    )
                }
                pickerMerchant = null
            },
            onResetDefault = { applyAll ->
                customStore.remove(target)
                viewModel.applyCustomMerchantIcon(target, null, null, applyAll) { updated ->
                    notify(
                        if (updated > 0) tr("已更新 %d 笔历史账单图标").format(updated)
                        else tr("已恢复默认图标"),
                    )
                }
                pickerMerchant = null
            },
        )
    }
}
