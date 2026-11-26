package com.bookkeeping.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.ui.theme.LocalQingDark
import com.bookkeeping.app.ui.theme.QingMotionTokens
import com.bookkeeping.app.ui.theme.qingColors
import com.bookkeeping.app.ui.theme.rememberReduceMotion

@Composable
fun QingSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    val dark = LocalQingDark.current
    val colors = qingColors
    val reduced = rememberReduceMotion()
    val haptic = LocalHapticFeedback.current
    val position by animateFloatAsState(if (checked) 1f else 0f, QingMotionTokens.motionSnappy.spec(reduced), label = "switchPosition")
    val track by animateColorAsState(if (checked) colors.accentPrimary else if (dark) Color(0xFF2D3531) else Color(0xFFE2E6E4),
        QingMotionTokens.motionSnappy.spec(reduced), label = "switchTrack")
    val travel = with(LocalDensity.current) { 20.dp.toPx() }
    val touch = if (onCheckedChange != null) Modifier.sizeIn(minHeight = 48.dp, minWidth = 50.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCheckedChange(it)
        }) else Modifier
    Box(modifier.then(touch), contentAlignment = Alignment.Center) {
        Box(Modifier.size(50.dp, 30.dp).graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(CircleShape).background(track).padding(2.dp)) {
            Box(Modifier.align(Alignment.CenterStart).size(26.dp)
                .graphicsLayer { translationX = position * travel }
                .then(if (!dark) Modifier.shadow(1.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.10f)) else Modifier)
                .background(Color.White, CircleShape))
        }
    }
}
