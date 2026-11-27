package com.bookkeeping.app.ui.components

import com.bookkeeping.app.ui.theme.qingPressFeedback

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.bookkeeping.app.ui.theme.QingMotionTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.ui.theme.QingLightHeroEnd
import com.bookkeeping.app.ui.theme.QingDarkHeroStart
import com.bookkeeping.app.ui.theme.QingDarkHeroEnd
import com.bookkeeping.app.ui.theme.LocalQingDark
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors

/**
 * Opaque V3 card. Static groups have no shadow; interactive objects have a
 * minimal light-mode shadow and a standard accessible click target.
 * Press feedback is passive; static groups and content remain opaque.
 */
@Composable
fun WalletCardSurface(
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = qingColors
    val dark = LocalQingDark.current
    val radius = if (hero) QingRadiusTokens.radiusHero else QingRadiusTokens.radiusCard
    val shape = RoundedCornerShape(radius)
    val shadow = if (!dark && onClick != null) {
        Modifier.shadow(
            elevation = 1.dp,
            shape = shape,
            clip = false,
            ambientColor = colors.textPrimary.copy(alpha = 0.06f),
            spotColor = colors.textPrimary.copy(alpha = 0.06f),
        )
    } else Modifier
    val edge = if (dark) {
        Modifier.walletTopHighlight(radius, colors.strokeHairline)
    } else Modifier.border(0.5.dp, colors.strokeHairline, shape)
    val interactionSource = remember { MutableInteractionSource() }
    val interaction = if (onClick != null) {
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
    } else Modifier
    val fill = if (hero) {
        Modifier.background(Brush.verticalGradient(if (dark) listOf(QingDarkHeroStart, QingDarkHeroEnd)
            else listOf(colors.surfaceCard, QingLightHeroEnd)))
    } else Modifier.background(colors.surfaceCard)
    Box(
        modifier = modifier.qingPressFeedback(QingMotionTokens.cardPressedScale, enabled = onClick != null).then(shadow).clip(shape)
            .then(fill).then(edge).then(interaction),
        content = content,
    )
}

private fun Modifier.walletTopHighlight(
    radius: Dp,
    color: androidx.compose.ui.graphics.Color,
): Modifier = drawWithContent {
    drawContent()
    val stroke = 0.5.dp.toPx()
    if (size.width > stroke && size.height > stroke) {
        val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
        clipRect(bottom = r.coerceAtLeast(stroke)) {
            drawRoundRect(
                color = color,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius((r - stroke / 2f).coerceAtLeast(0f)),
                style = Stroke(stroke),
            )
        }
    }
}
