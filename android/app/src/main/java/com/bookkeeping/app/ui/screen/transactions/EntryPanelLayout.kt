package com.bookkeeping.app.ui.screen.transactions

import android.view.View
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
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
    canDismiss: Boolean = true,
    headerTrailing: @Composable RowScope.() -> Unit = {},
    onPanelPositioned: ((LayoutCoordinates, View) -> Unit)? = null,
    onUserInteraction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 保存中不允许下滑把面板藏起来：否则写入失败时用户看不到也改不了。
    val latestCanDismiss by rememberUpdatedState(canDismiss)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || latestCanDismiss },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        val host = LocalView.current
        Column(
            modifier = modifier
                .then(if (onUserInteraction != null) Modifier.pointerInput(onUserInteraction) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        onUserInteraction()
                    }
                } else Modifier)
                .then(if (onPanelPositioned != null) Modifier.onGloballyPositioned {
                    onPanelPositioned(it, host)
                } else Modifier)
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
                IconButton(onClick = onDismiss, enabled = canDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
            content()
        }
    }
}
