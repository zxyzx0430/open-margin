package com.bookkeeping.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Raw palette. Screens must use the theme-aware properties below instead of
// referencing these directly, so light and dark stay in sync.
internal val QingPrimaryBase = Color(0xFF24705C)
internal val QingPrimaryDeepBase = Color(0xFF0E5336)
internal val QingPrimaryContainerBase = Color(0xFFE8F5EE)
val QingMint = Color(0xFF97E0B9)
internal val QingMintSoftBase = Color(0xFFDCF0E7)
internal val QingBackgroundBase = Color(0xFFF6F5F4)
internal val QingSurfaceVariantBase = Color(0xFFF0F2F0)
internal val QingTextBase = Color(0xFF151918)
internal val QingTextSecondaryBase = Color(0xFF5E605F)
internal val QingOutlineBase = Color(0xFFE2E5E2)
// Input borders and other interactive outlines need 3:1 against their surface;
// the lighter divider color is kept separately in outlineVariant.
internal val QingFieldOutlineBase = Color(0xFF7E8C84)
// Slightly deeper than the reference red so 11-12sp status labels meet AA.
internal val QingExpenseBase = Color(0xFFB23A3A)
internal val QingIncomeBase = Color(0xFF1F7A55)
internal val QingWarningBase = Color(0xFF8A5A12)
internal val QingWarningContainerBase = Color(0xFFFFF4DE)
internal val QingErrorContainerBase = Color(0xFFFDECEC)

internal val QingDarkPrimaryBase = Color(0xFF8FD9BC)
internal val QingDarkBackgroundBase = Color(0xFF101412)
internal val QingDarkSurfaceBase = Color(0xFF18201D)
internal val QingDarkSurfaceVariantBase = Color(0xFF25302B)
internal val QingDarkTextBase = Color(0xFFE8F0EB)
internal val QingDarkTextSecondaryBase = Color(0xFFA9BDB3)
internal val QingDarkOutlineBase = Color(0xFF3A4A43)
internal val QingDarkFieldOutlineBase = Color(0xFF71837A)
internal val QingDarkErrorContainerBase = Color(0xFF4A1A18)
internal val ColorExpenseDark = Color(0xFFFF8A80)

val LocalQingDark = staticCompositionLocalOf { false }

// Theme-aware roles used by screens and components.
val QingPrimary: Color @Composable get() = MaterialTheme.colorScheme.primary
val QingPrimaryDeep: Color @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
val QingPrimaryContainer: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
val QingMintSoft: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
val QingBackground: Color @Composable get() = MaterialTheme.colorScheme.background
val QingSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
val QingSurfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val QingText: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val QingTextSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val QingOutline: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val QingExpense: Color @Composable get() = MaterialTheme.colorScheme.error
val QingIncome: Color @Composable get() = MaterialTheme.colorScheme.secondary
val QingWarning: Color @Composable get() = qingColors.semanticWarning
val QingWarningContainer: Color @Composable get() = if (LocalQingDark.current) Color(0xFF4A3510) else QingWarningContainerBase
val QingErrorContainer: Color @Composable get() = MaterialTheme.colorScheme.errorContainer

/** V3 palette. Existing Material roles stay frozen until the page migration. */
data class QingColorTokens(
    val surfaceCanvas: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val surfaceSheet: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentPrimary: Color,
    val accentContainer: Color,
    val semanticIncome: Color,
    val semanticExpense: Color,
    val semanticWarning: Color,
    val strokeHairline: Color,
)

val QingLightTokens = QingColorTokens(
    surfaceCanvas = QingBackgroundBase,
    surfaceCard = Color.White,
    surfaceElevated = QingSurfaceVariantBase,
    surfaceSheet = Color.White,
    textPrimary = QingTextBase,
    textSecondary = QingTextSecondaryBase,
    accentPrimary = QingPrimaryBase,
    accentContainer = QingPrimaryContainerBase,
    semanticIncome = QingIncomeBase,
    semanticExpense = QingExpenseBase,
    semanticWarning = QingWarningBase,
    strokeHairline = QingTextBase.copy(alpha = 0.07f),
)

val QingDarkTokens = QingColorTokens(
    surfaceCanvas = Color(0xFF0B0D0C),
    surfaceCard = Color(0xFF141716),
    surfaceElevated = Color(0xFF1C211F),
    surfaceSheet = Color(0xFF222825),
    textPrimary = Color(0xFFECF2EE),
    textSecondary = Color(0xFFA3B3AB),
    accentPrimary = QingDarkPrimaryBase,
    accentContainer = QingPrimaryDeepBase,
    semanticIncome = Color(0xFF6ED0A5),
    semanticExpense = ColorExpenseDark,
    semanticWarning = Color(0xFFFBBF24),
    strokeHairline = Color.White.copy(alpha = 0.08f),
)

val qingColors: QingColorTokens
    @Composable get() = if (LocalQingDark.current) QingDarkTokens else QingLightTokens

/** The two hero gradients are the only approved decorative gradients. */
val QingLightHeroEnd = Color(0xFFF4FAF7)
val QingDarkHeroStart = Color(0xFF17211E)
val QingDarkHeroEnd = Color(0xFF121716)
