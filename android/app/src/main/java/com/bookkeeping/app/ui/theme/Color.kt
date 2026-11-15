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
val QingWarning: Color @Composable get() = if (LocalQingDark.current) Color(0xFFF0C36A) else QingWarningBase
val QingWarningContainer: Color @Composable get() = if (LocalQingDark.current) Color(0xFF4A3510) else QingWarningContainerBase
val QingErrorContainer: Color @Composable get() = MaterialTheme.colorScheme.errorContainer
