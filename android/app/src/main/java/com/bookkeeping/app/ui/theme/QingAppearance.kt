package com.bookkeeping.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import com.bookkeeping.app.ui.components.QingChromeTier

data class QingAppearance(
    val redIncome: Boolean = false,
    val chromeTier: QingChromeTier = QingChromeTier.SOLID,
)

val LocalQingAppearance = compositionLocalOf { QingAppearance() }

@Composable
fun rememberQingAppearancePreferences(): SharedPreferences {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        context.getSharedPreferences("qing_appearance", Context.MODE_PRIVATE)
    }
}

fun readQingAppearance(prefs: SharedPreferences): QingAppearance = QingAppearance(
    redIncome = prefs.getBoolean("red_income", false),
    chromeTier = runCatching {
        QingChromeTier.valueOf(prefs.getString("chrome_tier", null) ?: "SOLID")
    }.getOrDefault(QingChromeTier.SOLID),
)

fun setQingRedIncome(prefs: SharedPreferences, enabled: Boolean) {
    prefs.edit().putBoolean("red_income", enabled).apply()
}

fun setQingChromeTier(prefs: SharedPreferences, tier: QingChromeTier) {
    prefs.edit().putString("chrome_tier", tier.name).apply()
}

fun amountColors(tokens: QingColorTokens, redIncome: Boolean): Pair<Color, Color> =
    if (redIncome) tokens.semanticExpense to tokens.semanticIncome
    else tokens.semanticIncome to tokens.semanticExpense

val QingAmountIncome: Color
    @Composable get() = amountColors(qingColors, LocalQingAppearance.current.redIncome).first
val QingAmountExpense: Color
    @Composable get() = amountColors(qingColors, LocalQingAppearance.current.redIncome).second
