package com.bookkeeping.app.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomSettingsRouterTest {
    private fun p(family: RomFamily) = DeviceRomProfile(family, "test", "test", "1")

    @Test fun startupCandidatesCoverAllRomFamiliesInOrder() {
        val expected = mapOf(
            RomFamily.HUAWEI to listOf("com.huawei.systemmanager", "com.huawei.systemmanager"),
            RomFamily.HONOR to listOf("com.hihonor.systemmanager"),
            RomFamily.COLOR_OS to listOf("com.coloros.safecenter", "com.oplus.safecenter", "com.oppo.safe"),
            RomFamily.VIVO to listOf("com.vivo.permissionmanager", "com.iqoo.secure"),
            RomFamily.XIAOMI to listOf("com.miui.securitycenter"),
            RomFamily.AOSP to emptyList(),
        )
        for ((family, packages) in expected) {
            val candidates = RomSettingsRouter.candidates(p(family), RomSettingsKind.AUTOSTART)
            assertEquals(packages, candidates.dropLast(1).mapNotNull { it.packageName })
            assertTrue(candidates.last().appDetails)
        }
    }

    @Test fun batteryCandidatesEndInStandardThenAppDetails() {
        for (family in RomFamily.entries) {
            val candidates = RomSettingsRouter.candidates(p(family), RomSettingsKind.BATTERY)
            assertEquals("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", candidates[candidates.lastIndex - 1].action)
            assertTrue(candidates.last().appDetails)
        }
    }

    @Test fun exceptionsAndRejectedIntentsFallThroughWithoutCrash() {
        val attempts = mutableListOf<RomSettingsTarget>()
        val winner = RomSettingsRouter.route(p(RomFamily.COLOR_OS), RomSettingsKind.AUTOSTART) { candidate ->
            attempts += candidate
            if (candidate.packageName != null) error("ActivityNotFound")
            candidate.appDetails
        }
        assertEquals(4, attempts.size)
        assertTrue(winner!!.appDetails)
    }

    @Test fun earliestSuccessfulCandidateWins() {
        val candidates = RomSettingsRouter.candidates(p(RomFamily.HUAWEI), RomSettingsKind.AUTOSTART)
        val winner = RomSettingsRouter.route(p(RomFamily.HUAWEI), RomSettingsKind.AUTOSTART) {
            it == candidates[1]
        }
        assertEquals(candidates[1], winner)
    }

    @Test fun noResolvableSettingsReturnsNullNotCrash() {
        assertNull(RomSettingsRouter.route(p(RomFamily.AOSP), RomSettingsKind.APP_DETAILS) { false })
    }
}
