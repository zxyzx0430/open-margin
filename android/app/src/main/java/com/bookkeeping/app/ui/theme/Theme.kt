package com.bookkeeping.app.ui.theme

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class QingSemanticColors(
    val income: Color,
    val expenseText: Color,
    val warningContainer: Color,
    val warningText: Color,
    val errorContainer: Color,
)

val LocalQingSemanticColors = staticCompositionLocalOf {
    QingSemanticColors(
        income = QingIncomeBase,
        expenseText = QingExpenseBase,
        warningContainer = QingWarningContainerBase,
        warningText = QingWarningBase,
        errorContainer = QingErrorContainerBase,
    )
}

private val QingLightColors = lightColorScheme(
    primary = QingPrimaryBase,
    onPrimary = Color.White,
    primaryContainer = QingPrimaryContainerBase,
    onPrimaryContainer = QingPrimaryDeepBase,
    secondary = QingIncomeBase,
    onSecondary = Color.White,
    secondaryContainer = QingMintSoftBase,
    onSecondaryContainer = QingPrimaryDeepBase,
    tertiary = QingIncomeBase,
    onTertiary = Color.White,
    tertiaryContainer = QingMintSoftBase,
    onTertiaryContainer = QingPrimaryDeepBase,
    background = QingBackgroundBase,
    onBackground = QingTextBase,
    surface = Color.White,
    onSurface = QingTextBase,
    surfaceVariant = QingSurfaceVariantBase,
    onSurfaceVariant = QingTextSecondaryBase,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8F7),
    surfaceContainer = Color(0xFFF1F3F2),
    surfaceContainerHigh = Color(0xFFEBEEEC),
    surfaceContainerHighest = Color(0xFFE5E9E7),
    surfaceDim = Color(0xFFDDDFDD),
    surfaceBright = Color(0xFFFBFCFB),
    surfaceTint = QingPrimaryBase,
    outline = QingFieldOutlineBase,
    outlineVariant = QingOutlineBase,
    error = QingExpenseBase,
    onError = Color.White,
    errorContainer = QingErrorContainerBase,
    onErrorContainer = QingExpenseBase,
)

private val QingDarkColors = darkColorScheme(
    primary = QingDarkPrimaryBase,
    onPrimary = QingDarkBackgroundBase,
    primaryContainer = QingPrimaryDeepBase,
    onPrimaryContainer = QingDarkTextBase,
    secondary = QingMint,
    onSecondary = QingDarkBackgroundBase,
    secondaryContainer = QingDarkSurfaceVariantBase,
    onSecondaryContainer = QingDarkTextBase,
    tertiary = QingMint,
    onTertiary = QingDarkBackgroundBase,
    tertiaryContainer = QingDarkSurfaceVariantBase,
    onTertiaryContainer = QingDarkTextBase,
    background = QingDarkBackgroundBase,
    onBackground = QingDarkTextBase,
    surface = QingDarkSurfaceBase,
    onSurface = QingDarkTextBase,
    surfaceVariant = QingDarkSurfaceVariantBase,
    onSurfaceVariant = QingDarkTextSecondaryBase,
    surfaceContainerLowest = Color(0xFF0B0F0D),
    surfaceContainerLow = Color(0xFF141A17),
    surfaceContainer = QingDarkSurfaceBase,
    surfaceContainerHigh = Color(0xFF1F2924),
    surfaceContainerHighest = Color(0xFF26322C),
    surfaceDim = QingDarkBackgroundBase,
    surfaceBright = Color(0xFF2A3630),
    surfaceTint = QingDarkPrimaryBase,
    outline = QingDarkFieldOutlineBase,
    outlineVariant = QingDarkOutlineBase,
    error = ColorExpenseDark,
    onError = QingDarkBackgroundBase,
    errorContainer = QingDarkErrorContainerBase,
    onErrorContainer = ColorExpenseDark,
)

private val QingShapes = Shapes(
    extraSmall = RoundedCornerShape(QingRadiusTokens.radiusBadge),
    small = RoundedCornerShape(QingRadiusTokens.radiusControl),
    medium = RoundedCornerShape(QingRadiusTokens.radiusControl),
    large = RoundedCornerShape(QingRadiusTokens.radiusCard),
    extraLarge = RoundedCornerShape(QingRadiusTokens.radiusHero),
)

@Composable
fun BookkeepingAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) QingDarkTokens else QingLightTokens
    val prefs = rememberQingAppearancePreferences()
    var appearance by remember(prefs) { mutableStateOf(readQingAppearance(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            appearance = readQingAppearance(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val colorScheme = (if (darkTheme) QingDarkColors else QingLightColors).copy(
        background = tokens.surfaceCanvas, onBackground = tokens.textPrimary,
        surface = tokens.surfaceCard, onSurface = tokens.textPrimary,
        surfaceVariant = tokens.surfaceElevated, onSurfaceVariant = tokens.textSecondary,
        primary = tokens.accentPrimary, primaryContainer = tokens.accentContainer,
        secondary = tokens.semanticIncome, error = tokens.semanticExpense,
        surfaceContainerLowest = tokens.surfaceCanvas,
        surfaceContainerLow = tokens.surfaceCard,
        surfaceContainer = tokens.surfaceCard,
        surfaceContainerHigh = tokens.surfaceElevated,
        surfaceContainerHighest = tokens.surfaceSheet,
        surfaceDim = tokens.surfaceCanvas, surfaceBright = tokens.surfaceSheet,
        outlineVariant = tokens.strokeHairline,
    )
    CompositionLocalProvider(
        LocalQingDark provides darkTheme,
        LocalQingAppearance provides appearance,
        LocalQingSemanticColors provides QingSemanticColors(
            income = colorScheme.secondary,
            expenseText = colorScheme.error,
            warningContainer = if (darkTheme) Color(0xFF4A3510) else QingWarningContainerBase,
            warningText = tokens.semanticWarning,
            errorContainer = colorScheme.errorContainer,
        ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = QingShapes,
            content = content,
        )
    }
}

/** Canonical radii for V3 components; legacy Material shapes remain untouched in P0. */
object QingRadiusTokens {
    val radiusBadge = 8.dp
    val radiusControl = 14.dp
    val radiusCard = 22.dp
    val radiusHero = 28.dp
    val radiusPill = 999.dp

    fun innerRadius(outer: Dp, padding: Dp): Dp =
        (outer - padding).coerceAtLeast(radiusBadge)
}
