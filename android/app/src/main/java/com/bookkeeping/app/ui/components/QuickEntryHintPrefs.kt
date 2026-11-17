package com.bookkeeping.app.ui.components

import android.content.Context
import android.content.SharedPreferences

/**
 * 首次进账单页的「长按 1 秒」提示只弹一次。
 *
 * 单独的 SharedPreferences 文件：不跟同步状态、账本数据混在一起，
 * 清除账本或重新登录同步都不会让它再弹一遍。
 */
object QuickEntryHintPrefs {
    const val PREFS = "quick_entry_prefs"
    const val KEY_VOICE_HINT_SEEN = "voice_hint_seen"

    fun shouldShowVoiceHint(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_VOICE_HINT_SEEN, false)

    fun markVoiceHintSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_VOICE_HINT_SEEN, true).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
