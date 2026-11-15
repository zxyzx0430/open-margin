package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingMintSoft
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingTextSecondary

@Composable
fun LedgerEmptyState(
    filterActive: Boolean,
    hasAnyTransactions: Boolean = true,
    onClearFilters: (() -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = 32.dp, vertical = 24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(QingMintSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
                contentDescription = null,
                tint = QingPrimary,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = when {
                filterActive -> tr("没有符合条件的账单")
                hasAnyTransactions -> tr("本月还没有账单")
                else -> tr("还没有账单")
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                filterActive -> tr("换个关键词或清空筛选试试")
                hasAnyTransactions -> tr("查看全部账单，或切换月份范围")
                else -> tr("点击右上角 + 添加第一笔")
            },
            style = MaterialTheme.typography.bodySmall,
            color = QingTextSecondary,
            textAlign = TextAlign.Center,
        )
        if (filterActive && onClearFilters != null) {
            TextButton(onClick = onClearFilters) { Text(tr("清空筛选")) }
        } else if (hasAnyTransactions && onShowAll != null) {
            TextButton(onClick = onShowAll) { Text(tr("查看全部账单")) }
        }
    }
}
