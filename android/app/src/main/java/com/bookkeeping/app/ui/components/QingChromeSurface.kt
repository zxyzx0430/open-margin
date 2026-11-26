package com.bookkeeping.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors
import androidx.compose.ui.unit.dp

enum class QingChromeTier { SOLID, TINTED, BLUR, ENHANCED }

/** P0-P3 are always opaque. P4 alone may enable other material tiers. */
fun effectiveQingChromeTier(requested: QingChromeTier): QingChromeTier = when (requested) {
    QingChromeTier.SOLID, QingChromeTier.TINTED, QingChromeTier.BLUR,
    QingChromeTier.ENHANCED -> QingChromeTier.SOLID
}

/** Chrome only (navigation/top bars), never a financial content card. */
@Composable
fun QingChromeSurface(
    modifier: Modifier = Modifier,
    requestedTier: QingChromeTier = QingChromeTier.SOLID,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = qingColors
    val shape = RoundedCornerShape(QingRadiusTokens.radiusPill)
    val fill = when (effectiveQingChromeTier(requestedTier)) {
        QingChromeTier.SOLID, QingChromeTier.TINTED, QingChromeTier.BLUR,
        QingChromeTier.ENHANCED -> colors.surfaceSheet
    }
    Box(
        modifier = modifier.clip(shape).background(fill)
            .border(0.5.dp, colors.strokeHairline, shape),
        content = content,
    )
}
