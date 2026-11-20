package com.bookkeeping.app.capture.screenshot

/** Pure permission-state policy for the MediaStore screenshot watcher. */
internal object ScreenshotWatchPermissionPolicy {
    enum class Access { FULL, PARTIAL, NONE }
    enum class State { UNSUPPORTED, OFF, PERMISSION_MISSING, PERMISSION_PARTIAL, ACTIVE }

    fun state(available: Boolean, enabled: Boolean, access: Access): State {
        if (!available) return State.UNSUPPORTED
        return when (access) {
            Access.PARTIAL -> State.PERMISSION_PARTIAL
            Access.NONE -> if (enabled) State.PERMISSION_MISSING else State.OFF
            Access.FULL -> if (enabled) State.ACTIVE else State.OFF
        }
    }

    fun mayWatch(access: Access): Boolean = access == Access.FULL
}
