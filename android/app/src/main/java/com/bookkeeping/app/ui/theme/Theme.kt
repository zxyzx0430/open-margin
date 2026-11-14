package com.bookkeeping.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onErrorContainer = QingExpense,
)

private val QingDarkColors = darkColorScheme(
    primary = QingDarkPrimary,
    onPrimary = QingDarkBackground,
    primaryContainer = QingPrimaryDeep,
    onPrimaryContainer = QingDarkText,
    secondary = QingMint,
    onSecondary = QingDarkBackground,
    secondaryContainer = QingDarkSurfaceVariant,
    onSecondaryContainer = QingDarkText,
    tertiary = QingMint,
    onTertiary = QingDarkBackground,
    background = QingDarkBackground,
    onBackground = QingDarkText,
    surface = QingDarkSurface,
    onSurface = QingDarkText,
    surfaceVariant = QingDarkSurfaceVariant,
    onSurfaceVariant = QingMintSoft,
    outline = QingDarkSurfaceVariant,
    error = ColorExpenseDark,
    onError = QingDarkBackground,
)

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
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = QingShapes,
        content = content,
    )
}
