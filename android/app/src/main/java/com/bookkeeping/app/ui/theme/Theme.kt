package com.bookkeeping.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val QingLightColors = lightColorScheme(
    primary = QingPrimary,
    onPrimary = QingSurface,
    primaryContainer = QingPrimaryContainer,
    onPrimaryContainer = QingPrimaryDeep,
    secondary = QingIncome,
    onSecondary = QingSurface,
    secondaryContainer = QingMintSoft,
    onSecondaryContainer = QingPrimaryDeep,
    tertiary = QingIncome,
    onTertiary = QingSurface,
    tertiaryContainer = QingMintSoft,
    onTertiaryContainer = QingPrimaryDeep,
    background = QingBackground,
    onBackground = QingText,
    surface = QingSurface,
    onSurface = QingText,
    surfaceVariant = QingSurfaceVariant,
    onSurfaceVariant = QingTextSecondary,
    outline = QingOutline,
    outlineVariant = QingOutline,
    error = QingExpense,
    onError = QingSurface,
    errorContainer = QingErrorContainer,
    onErrorContainer = QingExpenseStrong,
    surfaceTint = QingPrimary,
)

private val QingDarkColors = darkColorScheme(
    primary = QingDarkPrimary,
    onPrimary = QingDarkOnPrimary,
    primaryContainer = Color(0xFF1B4A3A),
    onPrimaryContainer = QingDarkPrimaryDeep,
    secondary = QingDarkMintSoft,
    onSecondary = QingDarkText,
    secondaryContainer = QingDarkSurfaceVariant,
    onSecondaryContainer = QingDarkText,
    tertiary = QingDarkIncome,
    onTertiary = QingDarkBackground,
    tertiaryContainer = QingDarkMintSoft,
    onTertiaryContainer = QingDarkPrimaryDeep,
    background = QingDarkBackground,
    onBackground = QingDarkText,
    surface = QingDarkSurface,
    onSurface = QingDarkText,
    surfaceVariant = QingDarkSurfaceVariant,
    onSurfaceVariant = QingDarkTextSecondary,
    outline = QingDarkOutline,
    outlineVariant = QingDarkOutline,
    error = QingDarkExpense,
    onError = QingDarkOnError,
    errorContainer = QingDarkErrorContainer,
    onErrorContainer = QingDarkOnErrorContainer,
    surfaceTint = QingDarkPrimary,
)

/** Semantic colours that Material's ColorScheme does not carry (income/warning). */
@Immutable
data class QingSemanticColors(
    val expense: Color,
    val expenseText: Color,
    val income: Color,
    val warning: Color,
    val warningText: Color,
    val warningContainer: Color,
    val errorContainer: Color,
    val mintSoft: Color,
    val primaryDeep: Color,
)

private val QingLightSemantic = QingSemanticColors(
    expense = QingExpense,
    expenseText = QingExpenseStrong,
    income = QingIncome,
    warning = QingWarning,
    warningText = QingWarningStrong,
    warningContainer = QingWarningContainer,
    errorContainer = QingErrorContainer,
    mintSoft = QingMintSoft,
    primaryDeep = QingPrimaryDeep,
)

private val QingDarkSemantic = QingSemanticColors(
    expense = QingDarkExpense,
    expenseText = QingDarkExpense,
    income = QingDarkIncome,
    warning = QingDarkWarning,
    warningText = QingDarkWarning,
    warningContainer = QingDarkWarningContainer,
    errorContainer = QingDarkErrorContainer,
    mintSoft = QingDarkMintSoft,
    primaryDeep = QingDarkPrimaryDeep,
)

val LocalQingSemanticColors = staticCompositionLocalOf { QingLightSemantic }

/** Spacing roles shared by the redesigned screens. */
object QingSpacing {
    val pageGutter = 16.dp
    val cardPadding = 16.dp
    val sectionGap = 12.dp
    val itemGap = 8.dp
    val cardRadius = 20.dp
}

private val QingShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun BookkeepingAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) QingDarkColors else QingLightColors
    val semanticColors = if (darkTheme) QingDarkSemantic else QingLightSemantic
    CompositionLocalProvider(LocalQingSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = QingShapes,
            content = content,
        )
    }
}
