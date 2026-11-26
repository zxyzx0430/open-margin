package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors

@Composable
fun QingSheetGrabber() {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 4.dp).background(qingColors.textSecondary.copy(alpha = 0.28f),
            RoundedCornerShape(QingRadiusTokens.radiusPill)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QingConfirmationSheet(onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null) {
    QingGestureSheet(onDismissRequest = onDismissRequest) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.titleLarge) { title?.invoke() }
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text?.invoke() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
