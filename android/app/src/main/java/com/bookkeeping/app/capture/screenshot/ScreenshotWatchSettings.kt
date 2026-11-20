package com.bookkeeping.app.capture.screenshot

import android.content.Context
import com.bookkeeping.app.BuildConfig

/**
 * MediaStore screenshot watching is opt-in and never available in the Play
 * channel. [BuildConfig.SCREENSHOT_WATCHER_AVAILABLE] is set from the Gradle
 * property `openMarginScreenshotWatcher`; Play builds pass false.
 */
object ScreenshotWatchSettings {
    private const val PREFS = "screenshot_watch"
    private const val KEY_ENABLED = "enabled"

    fun isAvailable(): Boolean = BuildConfig.SCREENSHOT_WATCHER_AVAILABLE

    fun isEnabled(context: Context): Boolean =
        isAvailable() && prefs(context).getBoolean(KEY_ENABLED, false)

    /** Returns false when the channel does not support the feature at all. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (!isAvailable() && enabled) return false
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled && isAvailable()).apply()
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
