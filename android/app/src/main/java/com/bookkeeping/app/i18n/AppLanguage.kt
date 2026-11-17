package com.bookkeeping.app.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.bookkeeping.app.notification.CaptureNotifier
import com.bookkeeping.app.notification.KeepAliveService
import java.util.Locale

/**
 * User-selected Android UI language. Persistence is independent of bookkeeping
 * database and sync settings. No transaction/category values are rewritten.
 *
 * A localized Activity context is attached before Activity.onCreate so both
 * string resources and Compose Material components use the selected locale.
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
        // commit before Activity.recreate(), otherwise attachBaseContext can
        // read the old value and silently show the wrong UI after navigation.
        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, selected).commit()
        if (ok) {
            active = selected
            syncApplicationLocale(context, selected)
            KeepAliveService.ensureChannel(context)
            CaptureNotifier.ensureChannel(context)
        }
        return ok
    }

    fun wrap(context: Context): Context {
        val selected = read(context)
        active = selected
        syncApplicationLocale(context, selected)
        val desired = Locale.forLanguageTag(if (selected == ENGLISH) "en" else "zh-CN")
        val config = Configuration(context.resources.configuration)
        config.setLocale(desired)
        config.setLayoutDirection(desired)
        return context.createConfigurationContext(config)
    }

    private fun syncApplicationLocale(context: Context, language: String) {
        // The system resolves the notification header's app name from this locale.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
        val tags = if (language == ENGLISH) "en" else "zh-CN"
        if (localeManager.applicationLocales.toLanguageTags() != tags) {
            localeManager.applicationLocales = LocaleList.forLanguageTags(tags)
        }
    }

    fun current(): String = active

    fun tr(context: Context, chinese: String): String =
        if (read(context) == ENGLISH) EnglishUiText.translate(chinese) else chinese

    fun tr(chinese: String): String =
        if (active == ENGLISH) EnglishUiText.translate(chinese) else chinese
}

/** English UI strings are presentation-only; never translate parser keywords. */
fun tr(chinese: String): String = AppLanguage.tr(chinese)
