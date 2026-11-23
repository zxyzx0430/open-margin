package com.bookkeeping.app.rom

enum class RomSettingsKind { AUTOSTART, BATTERY, APP_DETAILS }
data class RomSettingsTarget(
    val packageName: String? = null, val activityName: String? = null,
    val action: String? = null, val appDetails: Boolean = false,
)

/** Order is deliberate: private OEM activities, standard Android, app details. */
object RomSettingsRouter {
    private fun c(pkg: String, cls: String) = RomSettingsTarget(pkg, cls)
    private val details = RomSettingsTarget(appDetails = true)
    fun candidates(p: DeviceRomProfile, kind: RomSettingsKind): List<RomSettingsTarget> {
        if (kind == RomSettingsKind.APP_DETAILS) return listOf(details)
        val vendor = if (kind == RomSettingsKind.AUTOSTART) when (p.family) {
            RomFamily.HUAWEI -> listOf(
                c("com.huawei.systemmanager", ".startupmgr.ui.StartupNormalAppListActivity"),
                c("com.huawei.systemmanager", ".appcontrol.activity.StartupAppControlActivity"))
            RomFamily.HONOR -> listOf(
                c("com.hihonor.systemmanager", ".startupmgr.ui.StartupNormalAppListActivity"))
            RomFamily.COLOR_OS -> listOf(
                c("com.coloros.safecenter", ".permission.startup.StartupAppListActivity"),
                c("com.oplus.safecenter", ".permission.startup.StartupAppListActivity"),
                c("com.oppo.safe", ".permission.startup.StartupAppListActivity"))
            RomFamily.VIVO -> listOf(
                c("com.vivo.permissionmanager", ".activity.BgStartUpManagerActivity"),
                c("com.iqoo.secure", ".ui.phoneoptimize.BgStartUpManager"))
            RomFamily.XIAOMI -> listOf(
                c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            RomFamily.AOSP -> emptyList()
        } else when (p.family) {
            RomFamily.HUAWEI -> listOf(c("com.huawei.systemmanager", ".power.ui.HwPowerManagerActivity"))
            RomFamily.HONOR -> listOf(c("com.hihonor.systemmanager", ".power.ui.HwPowerManagerActivity"))
            RomFamily.COLOR_OS -> listOf(
                c("com.coloros.oppoguardelf", ".powermanager.fuelgaue.PowerUsageModelActivity"),
                c("com.oplus.battery", ".ui.powermanager.PowerConsumptionActivity"))
            RomFamily.VIVO -> listOf(
                c("com.vivo.abe", ".ui.BgStartUpManagerActivity"),
                c("com.vivo.permissionmanager", ".activity.BgStartUpManagerActivity"))
            RomFamily.XIAOMI -> listOf(c("com.miui.securitycenter", "com.miui.powercenter.PowerHideModeActivity"))
            RomFamily.AOSP -> emptyList()
        }
        val standard = if (kind == RomSettingsKind.BATTERY) {
            listOf(
                RomSettingsTarget(action = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
                RomSettingsTarget(action = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
            )
        } else emptyList()
        return vendor + standard + details
    }
    /** False, missing activities and exceptions all fall through safely. */
    fun route(p: DeviceRomProfile, kind: RomSettingsKind, launch: (RomSettingsTarget) -> Boolean): RomSettingsTarget? =
        candidates(p, kind).firstOrNull { runCatching { launch(it) }.getOrDefault(false) }
}
