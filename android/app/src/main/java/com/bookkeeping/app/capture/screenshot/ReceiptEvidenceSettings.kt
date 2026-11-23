package com.bookkeeping.app.capture.screenshot

import android.content.Context

/**
 * 扫描原图归档保留策略。
 *
 * 默认保留 90 天（配合 100MB LRU 配额上限），也可切为永久保留。
 * 与 `page_capture/` 的 7 天待确认队列独立。
 */
object ReceiptEvidenceSettings {
    enum class RetentionMode(val key: String) {
        NINETY_DAYS("90d"),
        FOREVER("forever");

        companion object {
            fun fromKey(key: String?): RetentionMode =
                entries.firstOrNull { it.key == key } ?: NINETY_DAYS
        }
    }

    private const val PREFS = "receipt_evidence_settings"
    private const val KEY_RETENTION_MODE = "retention_mode"

    fun retentionMode(context: Context): RetentionMode =
        RetentionMode.fromKey(prefs(context).getString(KEY_RETENTION_MODE, RetentionMode.NINETY_DAYS.key))

    fun setRetentionMode(context: Context, mode: RetentionMode) {
        prefs(context).edit().putString(KEY_RETENTION_MODE, mode.key).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
