package com.bookkeeping.app.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.ui.components.QingChromeTier
import com.bookkeeping.app.ui.components.effectiveQingChromeTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QingV3TokensTest {
    @Test fun surfaceAndBrandPalette() {
        assertEquals(Color(0xFFF6F5F4), QingLightTokens.surfaceCanvas)
        assertEquals(Color.White, QingLightTokens.surfaceCard)
        assertEquals(Color(0xFFF0F2F0), QingLightTokens.surfaceElevated)
        assertEquals(Color.White, QingLightTokens.surfaceSheet)
        assertEquals(Color(0xFF0B0D0C), QingDarkTokens.surfaceCanvas)
        assertEquals(Color(0xFF141716), QingDarkTokens.surfaceCard)
        assertEquals(Color(0xFF1C211F), QingDarkTokens.surfaceElevated)
        assertEquals(Color(0xFF222825), QingDarkTokens.surfaceSheet)
        assertEquals(Color(0xFF24705C), QingLightTokens.accentPrimary)
        assertEquals(Color(0xFF8FD9BC), QingDarkTokens.accentPrimary)
        assertEquals(Color(0xFF6ED0A5), QingDarkTokens.semanticIncome)
        assertEquals(Color(0xFFFBBF24), QingDarkTokens.semanticWarning)
    }

    @Test fun radiiAndNumericTypography() {
        assertEquals(8.dp, QingRadiusTokens.radiusBadge)
        assertEquals(14.dp, QingRadiusTokens.radiusControl)
        assertEquals(22.dp, QingRadiusTokens.radiusCard)
        assertEquals(28.dp, QingRadiusTokens.radiusHero)
        assertEquals(999.dp, QingRadiusTokens.radiusPill)
        assertEquals(10.dp, QingRadiusTokens.innerRadius(22.dp, 12.dp))
        assertEquals(8.dp, QingRadiusTokens.innerRadius(22.dp, 20.dp))
        assertEquals(34.sp, QingTypeTokens.heroAmount.fontSize)
        assertEquals(36.sp, QingTypeTokens.receiptAmount.fontSize)
        assertEquals("tnum", QingTypeTokens.heroAmount.fontFeatureSettings)
        assertEquals("tnum", QingTypeTokens.rowAmount.fontFeatureSettings)
        assertEquals(14.sp, QingTypeTokens.minimumAmountSize)
    }

    @Test fun motionAndMaterialFallback() {
        assertEquals(QingSpringToken(1f, 900f), QingMotionTokens.motionPress)
        assertEquals(QingSpringToken(1f, 400f), QingMotionTokens.motionStandard)
        assertEquals(QingSpringToken(0.82f, 360f), QingMotionTokens.motionGesture)
        assertEquals(QingSpringToken(1f, 600f), QingMotionTokens.motionSnappy)
        assertTrue(QingMotionTokens.motionPress.spec<Float>(reduceMotion = true) is SnapSpec<*>)
        QingChromeTier.entries.forEach {
            assertEquals(QingChromeTier.SOLID, effectiveQingChromeTier(it))
        }
    }

    @Test fun amountColorPreferenceDoesNotMutateStatusPalette() {
        for (tokens in listOf(QingLightTokens, QingDarkTokens)) {
            assertEquals(tokens.semanticIncome to tokens.semanticExpense, amountColors(tokens, false))
            assertEquals(tokens.semanticExpense to tokens.semanticIncome, amountColors(tokens, true))
        }
    }
}
