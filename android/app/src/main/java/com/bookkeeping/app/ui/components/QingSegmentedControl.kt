package com.bookkeeping.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.ui.theme.LocalQingDark
import com.bookkeeping.app.ui.theme.QingMotionTokens
import com.bookkeeping.app.ui.theme.qingColors
import com.bookkeeping.app.ui.theme.rememberReduceMotion

@Composable
fun QingSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(labels.size in 2..4)
    val dark = LocalQingDark.current
    val colors = qingColors
    val reduced = rememberReduceMotion()
    val shape = RoundedCornerShape(7.dp)
    val selected = selectedIndex.coerceIn(labels.indices)
    val position by animateFloatAsState(selected.toFloat(), QingMotionTokens.motionStandard.spec(reduced), label = "segmentPosition")
    BoxWithConstraints(modifier.height(36.dp).clip(RoundedCornerShape(10.dp))
        .background(colors.surfaceElevated).padding(3.dp)) {
        val slot = maxWidth / labels.size
        val slotPx = with(LocalDensity.current) { slot.toPx() }
        Box(Modifier.width(slot).fillMaxHeight()
            .graphicsLayer { translationX = position * slotPx }
            .then(if (!dark) Modifier.shadow(1.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.06f)) else Modifier)
            .clip(shape).background(if (dark) Color(0xFF2A312E) else Color.White)
            .drawWithContent {
                drawContent()
                if (dark) {
                    val stroke = 0.5.dp.toPx()
                    clipRect(bottom = 7.dp.toPx()) {
                        drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(stroke/2, stroke/2),
                            Size(size.width-stroke, size.height-stroke), CornerRadius(7.dp.toPx()), style = Stroke(stroke))
                    }
                }
            })
        Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                val color by animateColorAsState(if (selected == index) colors.textPrimary else colors.textSecondary,
                    QingMotionTokens.motionStandard.spec(reduced), label = "segmentText")
                Box(Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = color, style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
