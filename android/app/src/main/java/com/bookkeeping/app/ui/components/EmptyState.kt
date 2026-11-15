package com.bookkeeping.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingMintSoft
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingTextSecondary

/**
 * Compact inline empty state. Stays left-aligned inside a card instead of a
 * tall centered block, so the filter row above remains the visual anchor.
 */
@Composable
fun LedgerEmptyState(
    filterActive: Boolean,
    hasAnyTransactions: Boolean = true,
    onClearFilters: (() -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val title = when {
        filterActive -> tr("没有符合条件的账单")
        hasAnyTransactions -> tr("本月还没有账单")
        else -> tr("还没有账单")
    }
    val body = when {
        filterActive -> tr("换个关键词或清空筛选试试")
        hasAnyTransactions -> tr("查看全部账单，或切换月份范围")
        else -> tr("点击右上角 + 添加第一笔")
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = QingSurface),
        border = BorderStroke(1.dp, QingOutline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(QingMintSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
                        contentDescription = null,
                        tint = QingPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                    )
                }
            }
            when {
                filterActive && onClearFilters != null -> {
                    FilledTonalButton(
                        onClick = onClearFilters,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = QingMintSoft,
                            contentColor = QingPrimary,
                        ),
                    ) {
                        Text(tr("清空筛选"))
                    }
                }
                hasAnyTransactions && onShowAll != null -> {
                    OutlinedButton(
                        onClick = onShowAll,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("查看全部账单"))
                    }
                }
            }
        }
    }
}
