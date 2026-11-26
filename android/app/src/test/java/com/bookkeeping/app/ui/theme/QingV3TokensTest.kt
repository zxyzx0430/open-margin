package com.bookkeeping.app.ui.theme

import com.bookkeeping.app.ui.components.largeTitleProgress
import androidx.compose.animation.core.SnapSpec
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    @Test fun materialCapabilityAndReduceMotion() {
        assertEquals(QingChromeTier.BLUR, effectiveQingChromeTier(QingChromeTier.BLUR, 31, false, hasBackdrop = true))
        assertEquals(QingChromeTier.ENHANCED, effectiveQingChromeTier(QingChromeTier.ENHANCED, 35, false, hasBackdrop = true))
        assertEquals(QingChromeTier.TINTED, effectiveQingChromeTier(QingChromeTier.ENHANCED, 30, false, hasBackdrop = true))
        assertEquals(QingChromeTier.TINTED, effectiveQingChromeTier(QingChromeTier.BLUR, 35, false))
        assertEquals(QingChromeTier.SOLID, effectiveQingChromeTier(QingChromeTier.BLUR, 35, true, hasBackdrop = true))
        assertEquals(QingChromeTier.SOLID, effectiveQingChromeTier(QingChromeTier.BLUR, 35, false, reducedTransparency = true, hasBackdrop = true))
        assertTrue(reduceMotionForScale(0f))
        assertTrue(!reduceMotionForScale(0.5f))
    }

    @Test fun walletPreviewOrderAndPalette() {
        val ids = listOf(10L, 20L, 30L, 40L)
        assertEquals(listOf(10L, 20L, 30L, 40L), com.bookkeeping.app.ui.screen.subscriptions.walletDeckOrder(ids, null))
        assertEquals(listOf(10L, 30L, 40L, 20L), com.bookkeeping.app.ui.screen.subscriptions.walletDeckOrder(ids, 20L))
        assertEquals(5, WalletSubscriptionPalette.size)
        WalletSubscriptionPalette.forEach { color ->
            val l = androidx.compose.ui.graphics.Color.White.luminance()
            assertTrue((l + 0.05f) / (color.luminance() + 0.05f) >= 7f)
        }
    }
    @org.junit.Test
    fun largeTitleCollapseAndReducedMotion() {
        org.junit.Assert.assertEquals(0f, largeTitleProgress(0, 0, 42f, false), 0.001f)
        org.junit.Assert.assertEquals(0.5f, largeTitleProgress(0, 21, 42f, false), 0.001f)
        org.junit.Assert.assertEquals(1f, largeTitleProgress(0, 42, 42f, false), 0.001f)
        org.junit.Assert.assertEquals(1f, largeTitleProgress(1, 0, 42f, false), 0.001f)
        org.junit.Assert.assertEquals(0f, largeTitleProgress(0, 41, 42f, true), 0.001f)
        org.junit.Assert.assertEquals(1f, largeTitleProgress(0, 42, 42f, true), 0.001f)
    }
    @Test fun walletMembershipLimitAndReplacement() {
        val available = (1L..8L).toList()
        assertEquals(listOf(1L, 2L, 3L), com.bookkeeping.app.ui.screen.subscriptions.walletDeckMembership(available, null))
        assertEquals(emptyList<Long>(), com.bookkeeping.app.ui.screen.subscriptions.walletDeckMembership(available, emptyList()))
        assertEquals(listOf(1L, 3L), com.bookkeeping.app.ui.screen.subscriptions.walletDeckMembership(available, listOf(1L, 1L, 99L, 3L)))
        val six = (1L..6L).toList()
        assertEquals(six, com.bookkeeping.app.ui.screen.subscriptions.walletDeckAdd(six, 7L))
        assertEquals(listOf(1L, 7L, 3L, 4L, 5L, 6L), com.bookkeeping.app.ui.screen.subscriptions.walletDeckReplace(six, 2L, 7L))
        assertEquals(six, com.bookkeeping.app.ui.screen.subscriptions.walletDeckReplace(six, 2L, 3L))
        assertEquals(six, com.bookkeeping.app.ui.screen.subscriptions.walletDeckMembership(available, available))
        assertEquals(6, com.bookkeeping.app.ui.screen.subscriptions.walletDeckOrder(available, 2L).size)
    }
    @Test fun ledgerRowLazyIndexAccountsForDayHeaders() {
        val groups = listOf(listOf(1L, 2L, 3L), listOf(4L, 5L), listOf(6L))
        assertEquals(6, com.bookkeeping.app.ui.screen.transactions.ledgerLazyRowIndex(groups, 1L, 5))
        assertEquals(8, com.bookkeeping.app.ui.screen.transactions.ledgerLazyRowIndex(groups, 3L, 5))
        assertEquals(10, com.bookkeeping.app.ui.screen.transactions.ledgerLazyRowIndex(groups, 4L, 5))
        assertEquals(13, com.bookkeeping.app.ui.screen.transactions.ledgerLazyRowIndex(groups, 6L, 5))
        assertEquals(null, com.bookkeeping.app.ui.screen.transactions.ledgerLazyRowIndex(groups, 99L, 5))
    }
}
