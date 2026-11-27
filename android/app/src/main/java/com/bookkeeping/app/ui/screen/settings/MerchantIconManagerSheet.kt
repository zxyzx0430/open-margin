package com.bookkeeping.app.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.CustomMerchantIconStore
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.MerchantAvatar
import com.bookkeeping.app.ui.components.MerchantIconPickerSheet
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.qingColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantIconManagerSheet(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = qingColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val customStore = remember(context) { CustomMerchantIconStore.get(context) }
    val overridesMap by customStore.overrides.collectAsState()
    val overridesList = remember(overridesMap) { overridesMap.values.toList() }

    val recentMerchants by viewModel.recentMerchants.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshRecentMerchants() }

    var refreshStatus by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showAddMerchantField by remember { mutableStateOf(false) }
    var customMerchantInput by remember { mutableStateOf("") }
    var pickerMerchant by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceSheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tr("商户图标管理"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = tr("关闭"))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(QingPrimaryContainer)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = tr("一键刷新老账本图标"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = QingPrimaryDeep,
                )
                Text(
                    text = tr("用最新内置品牌库与你的自定义图标批量更新所有历史账单"),
                    style = MaterialTheme.typography.bodySmall,
                    color = QingPrimaryDeep.copy(alpha = 0.78f),
                )
                Button(
                    onClick = {
                        refreshing = true
                        refreshStatus = null
                        viewModel.refreshAllLedgerIcons { count ->
                            refreshing = false
                            refreshStatus = if (count > 0) {
                                tr("已更新 %d 笔历史账单图标").format(count)
                            } else {
                                tr("所有历史账单已是最新图标")
                            }
                        }
                    },
                    enabled = !refreshing,
                    colors = ButtonDefaults.buttonColors(containerColor = QingPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (refreshing) tr("正在刷新...") else tr("立即刷新全部老账单"))
                }
                refreshStatus?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = QingPrimaryDeep,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            OutlinedButton(
                onClick = { showAddMerchantField = !showAddMerchantField },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("为商户自定义图标"))
            }

            if (showAddMerchantField) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceCard)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = customMerchantInput,
                            onValueChange = { customMerchantInput = it },
                            placeholder = { Text(tr("输入或点选下方商户名")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        Button(
                            onClick = {
                                val target = customMerchantInput.trim()
                                if (target.isNotEmpty()) {
                                    pickerMerchant = target
                                    customMerchantInput = ""
                                    showAddMerchantField = false
                                }
                            },
                            enabled = customMerchantInput.trim().isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = QingPrimary),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(tr("选图标"))
                        }
                    }
                    if (recentMerchants.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 150.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(recentMerchants.take(15), key = { it }) { m ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            pickerMerchant = m
                                            showAddMerchantField = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    MerchantAvatar(
                                        merchant = m,
                                        merchantIconKey = null,
                                        category = TransactionCategory.OTHER,
                                        size = 28.dp,
                                        cornerRadius = 8.dp,
                                    )
                                    Text(
                                        text = m,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = tr("已自定义商户（%d）").format(overridesList.size),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
            )

            if (overridesList.isEmpty()) {
                Text(
                    text = tr("还没有自定义图标，可在账单详情点击商户头像或上方按钮添加"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(overridesList, key = { it.merchant }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceCard)
                                .clickable { pickerMerchant = entry.merchant }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                MerchantAvatar(
                                    merchant = entry.merchant,
                                    merchantIconKey = entry.brandKey,
                                    merchantIconPath = entry.imageFileName,
                                    category = TransactionCategory.OTHER,
                                    size = 36.dp,
                                    cornerRadius = 10.dp,
                                )
                                Text(
                                    text = entry.merchant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
                                },
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
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

    pickerMerchant?.let { target ->
        MerchantIconPickerSheet(
            merchantName = target,
            onDismiss = { pickerMerchant = null },
            onApplyBrand = { brandKey, applyAll ->
                customStore.setBrandIcon(target, brandKey)
                viewModel.applyCustomMerchantIcon(target, brandKey, null, applyAll)
                pickerMerchant = null
            },
            onApplyImage = { fileName, applyAll ->
                customStore.setImageIcon(target, fileName)
                viewModel.applyCustomMerchantIcon(target, null, fileName, applyAll)
                pickerMerchant = null
            },
            onResetDefault = { applyAll ->
                customStore.remove(target)
                viewModel.applyCustomMerchantIcon(target, null, null, applyAll)
                pickerMerchant = null
            },
        )
    }
}
