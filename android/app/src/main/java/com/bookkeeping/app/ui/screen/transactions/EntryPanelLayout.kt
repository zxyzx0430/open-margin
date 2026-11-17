package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 手动记账面板：和「导入 / 导出账单」用同一个 Material3 ModalBottomSheet。
 *
 * 不用自绘覆盖层的原因：自绘层画在页面内容区里，遮罩和面板都只盖住内容区，
 * 底栏仍然露在外面，看起来就是页面内弹窗；ModalBottomSheet 是窗口级底部面板，
 * 遮罩盖住整个界面、可下滑关闭、高度跟随内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryPanelLayout(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerTrailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                headerTrailing()
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
            content()
        }
    }
}
