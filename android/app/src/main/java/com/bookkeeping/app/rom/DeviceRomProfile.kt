package com.bookkeeping.app.rom

enum class RomFamily { HUAWEI, HONOR, COLOR_OS, VIVO, XIAOMI, AOSP }

/** Pure Kotlin ROM identification; safe on the local JVM and unknown firmware. */
data class DeviceRomProfile(
    val family: RomFamily,
    val manufacturer: String,
    val brand: String,
    val version: String,
    val harmonyCompatible: Boolean = false,
) {
    val usesVendorBatteryPolicy get() = family != RomFamily.AOSP
    val mayFilterAccessibilityEvents get() = family == RomFamily.VIVO
    /** All devices use page recognition as the first-class capture mode. */
    val captureStrategy get() = "page_recognition_primary+notification+media_store_watermark" +
        if (mayFilterAccessibilityEvents) "+event_filter_fallback" else ""

    companion object {
        fun detect(manufacturer: String?, brand: String?,
                   properties: Map<String, String> = emptyMap(), systemName: String? = null): DeviceRomProfile {
            val maker = manufacturer.orEmpty().trim()
            val brandName = brand.orEmpty().trim()
            val device = (maker + " " + brandName).lowercase()
            val system = systemName.orEmpty().lowercase()
            val family = when {
                listOf("honor", "hihonor", "magicos").any { it in device || it in system } -> RomFamily.HONOR
                listOf("huawei", "harmony", "emui", "ohos").any { it in device || it in system } -> RomFamily.HUAWEI
                listOf("oneplus", "oppo", "realme", "oplus", "coloros").any { it in device || it in system } -> RomFamily.COLOR_OS
                listOf("vivo", "iqoo", "originos", "funtouch").any { it in device || it in system } -> RomFamily.VIVO
                listOf("xiaomi", "redmi", "poco", "miui", "hyperos").any { it in device || it in system } -> RomFamily.XIAOMI
                else -> RomFamily.AOSP
            }
            val keys = when (family) {
                RomFamily.HUAWEI -> listOf("ro.build.version.harmony", "hw_sc.build.platform.version", "ro.build.version.emui")
                RomFamily.HONOR -> listOf("ro.build.version.magic", "ro.build.version.emui")
                RomFamily.COLOR_OS -> listOf("ro.build.version.opporom", "ro.build.version.coloros")
                RomFamily.VIVO -> listOf("ro.vivo.os.version")
                RomFamily.XIAOMI -> listOf("ro.mi.os.version.name", "ro.miui.ui.version.name")
                RomFamily.AOSP -> emptyList()
            }
            val version = keys.firstNotNullOfOrNull { properties[it]?.takeIf(String::isNotBlank) }
                ?: systemName?.takeIf(String::isNotBlank) ?: "unknown"
            val harmony = family == RomFamily.HUAWEI &&
                (system.contains("harmony") || system.contains("ohos") ||
                 !properties["ro.build.version.harmony"].isNullOrBlank() ||
                 !properties["hw_sc.build.platform.version"].isNullOrBlank())
            return DeviceRomProfile(family, maker, brandName, version, harmony)
        }
    }
}
