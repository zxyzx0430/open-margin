package com.bookkeeping.app.capture.screenshot

import android.content.Context
import com.bookkeeping.app.BuildConfig

/**
 * MediaStore screenshot watching is opt-in and never available in the Play
 * channel. [BuildConfig.SCREENSHOT_WATCHER_AVAILABLE] is set from the Gradle
 * property `openMarginScreenshotWatcher`; Play builds pass false.
 *
 * The last scanned MediaStore `_ID` lives here too: it is what lets the fallback
 * scan resume exactly where it stopped after the content observer has silently
 * missed changes (MIUI drops callbacks), and what keeps the first scan from
 * importing the whole album.
 */
object ScreenshotWatchSettings {
    private const val PREFS = "screenshot_watch"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SCANNED_ID = "last_scanned_media_id"

    fun isAvailable(): Boolean = BuildConfig.SCREENSHOT_WATCHER_AVAILABLE

    fun isEnabled(context: Context): Boolean =
        isAvailable() && prefs(context).getBoolean(KEY_ENABLED, false)

    /** Returns false when the channel does not support the feature at all. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (!isAvailable() && enabled) return false
        val wasEnabled = isEnabled(context)
        val on = enabled && isAvailable()
        val editor = prefs(context).edit().putBoolean(KEY_ENABLED, on)
        // 重新打开 = 只处理打开之后的新截图：清掉基线，下一次扫描重新取当前最大 _ID。
        // 关闭期间的截图不会被补记，和设置卡片「开启后只处理新截图」一致。
        if (on && !wasEnabled) editor.remove(KEY_LAST_SCANNED_ID)
        editor.apply()
        return true
    }

    /**
     * Highest MediaStore image `_ID` the watcher has already looked at, or null
     * when this install never scanned. Null is not "scan everything": the first
     * scan takes the current maximum as its baseline and never backfills.
     */
    fun lastScannedId(context: Context): Long? {
        val prefs = prefs(context)
        return if (prefs.contains(KEY_LAST_SCANNED_ID)) {
            prefs.getLong(KEY_LAST_SCANNED_ID, 0L)
        } else {
            null
        }
    }

    fun setLastScannedId(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_LAST_SCANNED_ID, id).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
