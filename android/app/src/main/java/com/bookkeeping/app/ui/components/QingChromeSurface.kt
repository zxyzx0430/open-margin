package com.bookkeeping.app.ui.components

import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.bookkeeping.app.ui.theme.LocalQingDark
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors

enum class QingChromeTier { SOLID, TINTED, ENHANCED }

/** Preserve users' old Standard preference without retaining a blur path. */
internal fun parseQingChromeTier(stored: String?): QingChromeTier =
    if (stored == "BLUR") QingChromeTier.TINTED
    else QingChromeTier.entries.firstOrNull { it.name == stored } ?: QingChromeTier.ENHANCED

fun effectiveQingChromeTier(
    requested: QingChromeTier,
    reducedTransparency: Boolean = false,
): QingChromeTier = if (reducedTransparency) QingChromeTier.SOLID else requested

internal fun qingChromeOpacity(tier: QingChromeTier): Float = when (tier) {
    QingChromeTier.SOLID -> 1f
    QingChromeTier.TINTED -> 0.92f
    QingChromeTier.ENHANCED -> 0.85f
}

/** Observe only accessibility preferences; scrolling never changes material. */
@Composable
fun rememberEffectiveQingChromeTier(requested: QingChromeTier): QingChromeTier {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val accessibility = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    // Android has no universal reduce-transparency setting; observe known OEM keys
    // and conservatively fall back for touch exploration as well.
    val keys = remember { listOf("reduce_transparency", "accessibility_reduce_transparency", "high_contrast_text") }
    fun readReduced(): Boolean = accessibility.isTouchExplorationEnabled || keys.any { key ->
        runCatching { Settings.Secure.getInt(resolver, key, 0) != 0 || Settings.System.getInt(resolver, key, 0) != 0 }.getOrDefault(false)
    }
    var reduced by remember(context) { mutableStateOf(readReduced()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = readReduced() }
        }
        keys.forEach { key ->
            runCatching { resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer) }
            runCatching { resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer) }
        }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { reduced = readReduced() }
        accessibility.addTouchExplorationStateChangeListener(listener)
        reduced = readReduced()
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
            accessibility.removeTouchExplorationStateChangeListener(listener)
        }
    }
    return effectiveQingChromeTier(requested, reducedTransparency = reduced)
}

/** A single translucent surface directly over the live page. No capture,
 * blur, RenderEffect, or idle/scroll state. Foreground stays fully opaque. */
@Composable
fun QingChromeSurface(
    modifier: Modifier = Modifier,
    requestedTier: QingChromeTier = QingChromeTier.ENHANCED,
    resolvedTier: QingChromeTier? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = qingColors
    val tier = resolvedTier ?: rememberEffectiveQingChromeTier(requestedTier)
    val shape = RoundedCornerShape(QingRadiusTokens.radiusPill)
    val opacity = qingChromeOpacity(tier)
    Box(
        modifier = modifier.shadow(3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.08f))
            .clip(shape).border(0.5.dp, colors.strokeHairline, shape),
    ) {
        Box(Modifier.matchParentSize().background(colors.surfaceSheet.copy(alpha = opacity)))
        if (tier == QingChromeTier.ENHANCED) {
            val dark = LocalQingDark.current
            val shine = Color.White.copy(alpha = if (dark) 0.06f else 0.12f)
            val highlight = remember(shine) {
                Brush.verticalGradient(listOf(shine, Color.Transparent, Color.Transparent))
            }
            Box(Modifier.matchParentSize().background(highlight))
        }
        content()
    }
}

val LocalQingChromeOverlap = staticCompositionLocalOf { 0.dp }
