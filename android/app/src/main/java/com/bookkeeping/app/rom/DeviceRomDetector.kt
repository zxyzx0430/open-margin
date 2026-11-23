package com.bookkeeping.app.rom

import android.os.Build

/** Vendor system properties are read only, reflectively, and never required. */
object DeviceRomDetector {
    private val keys = listOf(
        "ro.build.version.harmony", "hw_sc.build.platform.version", "ro.build.version.emui",
        "ro.build.version.magic", "ro.build.version.opporom", "ro.build.version.coloros",
        "ro.vivo.os.version", "ro.mi.os.version.name", "ro.miui.ui.version.name",
    )
    fun current(): DeviceRomProfile {
        val values = keys.mapNotNull { key ->
            property(key)?.takeIf(String::isNotBlank)?.let { key to it }
        }.toMap()
        val ohos = runCatching {
            Class.forName("ohos.system.version.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "ro.build.version.harmony") as? String
        }.getOrNull()
        return DeviceRomProfile.detect(Build.MANUFACTURER, Build.BRAND,
            values + (if (ohos.isNullOrBlank()) emptyMap() else mapOf("ro.build.version.harmony" to ohos)),
            Build.DISPLAY)
    }
    private fun property(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()
}
