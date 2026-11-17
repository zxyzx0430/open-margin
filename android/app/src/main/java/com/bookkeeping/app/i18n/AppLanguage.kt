package com.bookkeeping.app.i18n

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * User-selected Android UI language. Persistence is independent of bookkeeping
 * database and sync settings. No transaction/category values are rewritten.
 *
 * AppCompatDelegate applies the selected locale to Android resources, while
 * the active value remains available for Compose text translated through tr().
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

    fun set(context: Context, language: String): Boolean {
        val selected = normalize(language)
        // Persist first so startup restoration always agrees with AndroidX/system state.
        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, selected).commit()
        if (ok) {
            active = selected
            applyApplicationLocale(selected)
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
}

/** English UI strings are presentation-only; never translate parser keywords. */
fun tr(chinese: String): String = AppLanguage.tr(chinese)
