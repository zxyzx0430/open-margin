package com.bookkeeping.app.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRomProfileTest {
    @Test fun recognizesHuaweiHarmonyAndEmui() {
        val harmony = DeviceRomProfile.detect("HUAWEI", "HUAWEI", mapOf("ro.build.version.harmony" to "4.0"))
        assertEquals(RomFamily.HUAWEI, harmony.family)
        assertTrue(harmony.harmonyCompatible)
        assertEquals("4.0", harmony.version)
        val emui = DeviceRomProfile.detect("HUAWEI", "P30", mapOf("ro.build.version.emui" to "EmotionUI_12"))
        assertEquals(RomFamily.HUAWEI, emui.family)
        assertFalse(emui.harmonyCompatible)
    }

    @Test fun recognizesHonorBeforeHuawei() {
        val honor = DeviceRomProfile.detect("HONOR", "HONOR", mapOf("ro.build.version.magic" to "8.0"))
        assertEquals(RomFamily.HONOR, honor.family)
        assertEquals("8.0", honor.version)
        assertTrue(honor.usesVendorBatteryPolicy)
    }

    @Test fun recognizesColorOsVariants() {
        for (name in listOf("OPPO", "OnePlus", "realme", "OPLUS")) {
            assertEquals(RomFamily.COLOR_OS, DeviceRomProfile.detect(name, name).family)
        }
        val version = DeviceRomProfile.detect("OPPO", "OnePlus", mapOf("ro.build.version.opporom" to "V15"))
        assertEquals("V15", version.version)
    }

    @Test fun recognizesVivoAndIqooWithoutDroppingAccessibility() {
        for (name in listOf("vivo", "iQOO")) {
            val p = DeviceRomProfile.detect(name, name)
            assertEquals(RomFamily.VIVO, p.family)
            assertTrue(p.mayFilterAccessibilityEvents)
            assertTrue(p.captureStrategy.contains("page_recognition_primary"))
        }
    }

    @Test fun recognizesMiuiHyperOsAndRedmi() {
        for (name in listOf("Xiaomi", "Redmi", "POCO", "HyperOS", "MIUI")) {
            assertEquals(RomFamily.XIAOMI, DeviceRomProfile.detect(name, name).family)
        }
    }

    @Test fun unknownFirmwareFallsBackToAosp() {
        val p = DeviceRomProfile.detect("Google", "Pixel 10")
        assertEquals(RomFamily.AOSP, p.family)
        assertFalse(p.usesVendorBatteryPolicy)
        assertTrue(p.captureStrategy.contains("page_recognition_primary"))
    }

    @Test fun systemNameAlsoIdentifiesRom() {
        assertEquals(RomFamily.HUAWEI, DeviceRomProfile.detect(null, null, systemName = "HarmonyOS 4").family)
        assertEquals(RomFamily.HONOR, DeviceRomProfile.detect(null, null, systemName = "MagicOS 8").family)
        assertEquals(RomFamily.VIVO, DeviceRomProfile.detect(null, null, systemName = "OriginOS 5").family)
    }
}
