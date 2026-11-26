package com.bookkeeping.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainChromeVisibilityTest {
    private val tabs = BottomNavItem.ALL.map { it.route }.toSet()

    @Test fun onlyStableMainTabsShowChrome() {
        tabs.forEach { assertTrue(shouldShowMainChrome(it, listOf(it), tabs)) }
        assertTrue(shouldShowMainChrome("calendar", listOf("transactions", "calendar"), tabs))
    }

    @Test fun detailAndOtherLeafRoutesNeverShowChrome() {
        assertFalse(shouldShowMainChrome(AppRoutes.TRANSACTION_DETAIL, listOf("transactions"), tabs))
        assertFalse(shouldShowMainChrome(AppRoutes.TRANSACTION_DETAIL, listOf(AppRoutes.TRANSACTION_DETAIL), tabs))
        assertFalse(shouldShowMainChrome("transactions", listOf("transactions", AppRoutes.TRANSACTION_DETAIL), tabs))
        assertFalse(shouldShowMainChrome(AppRoutes.BILL_IMPORT, emptyList(), tabs))
        assertFalse(shouldShowMainChrome("transactions", listOf(AppRoutes.BILL_IMPORT), tabs))
        assertFalse(shouldShowMainChrome(null, emptyList(), tabs))
    }
}
