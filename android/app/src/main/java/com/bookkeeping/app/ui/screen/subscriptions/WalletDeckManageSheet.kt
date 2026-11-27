package com.bookkeeping.app.ui.screen.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.SubscriptionEntity
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.QingSheetGrabber
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors

/** Deck preferences only. Adding/removing a card never edits a subscription. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletDeckManageSheet(
    available: List<SubscriptionEntity>,
    deckIds: List<Long>,
    onChange: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var replacing by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }
    val chosen = deckIds.mapNotNull { id -> available.firstOrNull { it.id == id } }
    val candidates = available.filterNot { it.id in deckIds }
    val choosing = adding || replacing != null
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = QingRadiusTokens.radiusHero, topEnd = QingRadiusTokens.radiusHero),
        containerColor = qingColors.surfaceSheet, dragHandle = { QingSheetGrabber() }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (replacing != null) tr("替换卡片") else if (adding) tr("添加卡片") else tr("管理卡片"),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = {
                        if (choosing) { adding = false; replacing = null } else onDismiss()
                    }) { Text(tr(if (choosing) "返回" else "完成")) }
                }
                Text(tr("最多展示 6 张卡片，不影响订阅记录"), style = MaterialTheme.typography.bodySmall,
                    color = qingColors.textSecondary)
            }
            if (choosing) {
                items(candidates, key = { it.id }) { sub ->
                    TextButton(onClick = {
                        val old = replacing
                        onChange(if (old != null) walletDeckReplace(deckIds, old, sub.id) else walletDeckAdd(deckIds, sub.id))
                        adding = false
                        replacing = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SubscriptionBadgeAvatar(sub.name, sub.active, 32.dp)
                            Text(sub.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatSubscriptionOriginalCurrency(sub.amountCents, sub.currency))
                        }
                    }
                }
                if (candidates.isEmpty()) item { Text(tr("没有其他可添加的订阅"), color = qingColors.textSecondary) }
            } else {
                items(chosen, key = { it.id }) { sub ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubscriptionBadgeAvatar(sub.name, sub.active, 32.dp)
                        Text(sub.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { replacing = sub.id }, enabled = candidates.isNotEmpty()) { Text(tr("替换")) }
                        TextButton(onClick = { onChange(deckIds.filterNot { it == sub.id }) }) { Text(tr("移除")) }
                    }
                }
                item {
                    TextButton(onClick = { adding = true },
                        enabled = deckIds.size < WALLET_DECK_MAX && candidates.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) { Text(tr("添加卡片")) }
                }
            }
        }
    }
}
