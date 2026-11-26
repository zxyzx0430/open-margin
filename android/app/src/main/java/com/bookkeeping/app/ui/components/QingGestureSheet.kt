package com.bookkeeping.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors

@OptIn(ExperimentalMaterial3Api::class)
internal fun qingSheetCanChange(target: SheetValue, canDismiss: Boolean): Boolean =
    target != SheetValue.Hidden || canDismiss

/** One modal physics model. Material3 nested scroll hands downward overscroll
 * from the content to the panel and settles with the release velocity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QingGestureSheet(
    onDismissRequest: () -> Unit,
    canDismiss: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestCanDismiss by rememberUpdatedState(canDismiss)
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { qingSheetCanChange(it, latestCanDismiss) },
    )
    ModalBottomSheet(
        onDismissRequest = { if (latestCanDismiss) latestDismiss() },
        sheetState = state,
        shape = RoundedCornerShape(topStart = QingRadiusTokens.radiusHero, topEnd = QingRadiusTokens.radiusHero),
        containerColor = qingColors.surfaceSheet,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = { QingSheetGrabber() },
        content = content,
    )
}
