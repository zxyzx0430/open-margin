package com.bookkeeping.app.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bookkeeping.app.notification.CaptureNotifier
import com.bookkeeping.app.notification.KeepAliveService

/**
 * User-selected Android UI language. Persistence is independent of bookkeeping
 * database and sync settings. No transaction/category values are rewritten.
 *
 * AppCompatDelegate applies the selected locale to Android resources (launcher
 * label, recents title, notification app name), while the active value remains
 * available for Compose text translated through tr().
 */
object AppLanguage {
    private const val PREFS = "open_margin_ui_language"
    private const val KEY = "language"
    const val CHINESE = "zh"
    const val ENGLISH = "en"

    @Volatile private var active = CHINESE

    fun normalize(value: String?): String = if (value == ENGLISH) ENGLISH else CHINESE

    fun read(context: Context): String =
        normalize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, CHINESE))

    /** Whether the user or first-run initialization has persisted a language. */
    fun hasStoredChoice(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    fun set(context: Context, language: String): Boolean {
        val selected = normalize(language)
        // Persist first so startup restoration always agrees with AndroidX/system state.
        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, selected).commit()
        if (ok) {
            active = selected
            applyApplicationLocale(selected)
            // setApplicationLocales recreates the Activity. Suppress its default
            // window transition so the background is never exposed as a black flash.
            context.findActivity()?.let { activity ->
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
            }
            // Channel names are user-visible; rebuild them in the new language.
            KeepAliveService.ensureChannel(context)
            CaptureNotifier.ensureChannel(context)
        }
        return ok
    }

    /** Restore the persisted choice before the first Activity is created. */
    fun restoreApplicationLocale(context: Context) {
        val selected = read(context)
        active = selected
        applyApplicationLocale(selected)
    }

    private fun applyApplicationLocale(language: String) {
        val tags = if (language == ENGLISH) "en" else "zh-CN"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
    }

    fun current(): String = active

    fun tr(context: Context, chinese: String): String =
        if (read(context) == ENGLISH) EnglishUiText.translate(chinese) else chinese

    fun tr(chinese: String): String =
        if (active == ENGLISH) EnglishUiText.translate(chinese) else chinese

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}

/** English UI strings are presentation-only; never translate parser keywords. */
fun tr(chinese: String): String = AppLanguage.tr(chinese)
