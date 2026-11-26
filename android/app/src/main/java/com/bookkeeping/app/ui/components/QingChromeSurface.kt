package com.bookkeeping.app.ui.components

import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import com.bookkeeping.app.ui.theme.LocalQingDark
import android.view.View
import android.app.ActivityManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.ui.theme.QingRadiusTokens
import com.bookkeeping.app.ui.theme.qingColors

enum class QingChromeTier { SOLID, TINTED, BLUR, ENHANCED }

fun effectiveQingChromeTier(
    requested: QingChromeTier,
    sdk: Int = 0,
    lowRam: Boolean = true,
    reducedTransparency: Boolean = false,
    hardwareAccelerated: Boolean = true,
    hasBackdrop: Boolean = false,
): QingChromeTier = when {
    requested == QingChromeTier.SOLID || lowRam || reducedTransparency || !hardwareAccelerated -> QingChromeTier.SOLID
    sdk < 31 || !hasBackdrop || requested == QingChromeTier.TINTED -> QingChromeTier.TINTED
    else -> requested
}

/** Only a chrome layer is eligible for expensive backdrop capture. */
@Composable
fun rememberEffectiveQingChromeTier(requested: QingChromeTier): QingChromeTier {
    val context = LocalContext.current
    val view = LocalView.current
    val resolver = context.contentResolver
    val memory = remember(context) { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
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
    var accelerated by remember(view) { mutableStateOf(view.isHardwareAccelerated) }
    DisposableEffect(view) {
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { accelerated = v.isHardwareAccelerated }
            override fun onViewDetachedFromWindow(v: View) { accelerated = false }
        }
        view.addOnAttachStateChangeListener(listener)
        accelerated = view.isHardwareAccelerated
        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }
    return effectiveQingChromeTier(requested, Build.VERSION.SDK_INT, memory.isLowRamDevice, reduced,
        accelerated, hasBackdrop = true)
}

@RequiresApi(31)
private fun nativeBlur(radius: Float): RenderEffect =
    android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).asComposeRenderEffect()

/** The recorded body is sampled at the chrome's actual root coordinates. Only
 * this background canvas receives native RenderEffect; foreground icons/text
 * and all content cards remain sharp and opaque. */
@Composable
fun QingChromeSurface(
    modifier: Modifier = Modifier,
    requestedTier: QingChromeTier = QingChromeTier.SOLID,
    backdrop: GraphicsLayer? = null,
    backdropOrigin: Offset = Offset.Zero,
    resolvedTier: QingChromeTier? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = qingColors
    val capability = resolvedTier ?: rememberEffectiveQingChromeTier(requestedTier)
    val tier = if (backdrop == null && capability >= QingChromeTier.BLUR) QingChromeTier.TINTED else capability
    val shape = RoundedCornerShape(QingRadiusTokens.radiusPill)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val blurRadius = with(LocalDensity.current) { (if (tier == QingChromeTier.ENHANCED) 20.dp else 12.dp).toPx() }
    val blur = remember(tier, blurRadius) {
        if (Build.VERSION.SDK_INT >= 31 && tier >= QingChromeTier.BLUR) nativeBlur(blurRadius) else null
    }
    val opacity = when {
        backdrop == null && capability == QingChromeTier.ENHANCED -> 0.86f
        backdrop == null && capability == QingChromeTier.BLUR -> 0.92f
        else -> when (tier) {
        QingChromeTier.SOLID -> 1f
        QingChromeTier.TINTED -> 0.92f
        QingChromeTier.BLUR -> 0.78f
        QingChromeTier.ENHANCED -> 0.63f
        }
    }
    Box(
        modifier = modifier.onGloballyPositioned { origin = it.positionInRoot() }
            .shadow(3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.08f))
            .clip(shape).border(0.5.dp, colors.strokeHairline, shape),
    ) {
        if (blur != null && backdrop != null) {
            Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = blur; clip = true }) {
                drawRect(colors.surfaceCanvas)
                val delta = origin - backdropOrigin
                translate(-delta.x, -delta.y) { drawLayer(backdrop) }
            }
        }
        Box(Modifier.matchParentSize().background(colors.surfaceSheet.copy(alpha = opacity)))
        if (capability == QingChromeTier.ENHANCED) {
            val dark = LocalQingDark.current
            val shine = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.45f)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(shine, Color.Transparent, Color.Transparent))))
        }
        content()
    }
}

val LocalQingChromeOverlap = staticCompositionLocalOf { 0.dp }
