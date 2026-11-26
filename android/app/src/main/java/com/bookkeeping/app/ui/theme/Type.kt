package com.bookkeeping.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
)

/** Named type roles so screens do not pick unrelated Material styles. */
object QingType {
    val pageTitle = Typography.headlineSmall
    val primaryKpi = Typography.headlineMedium
    val secondaryKpi = Typography.titleMedium
    val metricLabel = Typography.labelSmall
}

/** Opt-in V3 roles; do not migrate existing page typography in P0. */
object QingTypeTokens {
    val heroAmount = TextStyle(
        fontSize = 34.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.025).em, fontFeatureSettings = "tnum",
    )
    val receiptAmount = heroAmount.copy(fontSize = 36.sp)
    val title = TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.015).em,
    )
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
    val supporting = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.015.em,
    )
    val rowAmount = body.copy(
        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum",
    )
    val minimumAmountSize = 14.sp
    fun numeric(style: TextStyle): TextStyle = style.copy(fontFeatureSettings = "tnum")
}
