package com.bookkeeping.app.data.diagnostics

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ServiceHealthEntry(
    val occurredAtMs: Long,
    val channel: String,
    val event: String,
    val summary: String,
    val detail: String? = null,
)

/**
 * Lightweight persistent ring-buffer (max 80 entries, newest first) for capture
 * service lifecycle and skip/throttle telemetry that never belongs in the
 * per-bill Room diagnostic table.
 *
 * All operations are best-effort, thread-safe, and never throw.
 */
object ServiceHealthLog {
    const val PREFS_NAME = "service_health_log"
    private const val KEY_ENTRIES = "entries"
    const val MAX_ENTRIES = 80
    const val SKIP_RATE_LIMIT_MS = 10_000L
    const val DAY_MS = 24L * 60L * 60L * 1_000L

    const val CHANNEL_NOTIFICATION = "notification"
    const val CHANNEL_ACCESSIBILITY = "accessibility"
    const val CHANNEL_SCREENSHOT = "screenshot"
    const val CHANNEL_APP = "app"

    const val EVENT_CONNECTED = "connected"
    const val EVENT_DISCONNECTED = "disconnected"
    const val EVENT_INTERRUPTED = "interrupted"
    const val EVENT_DESTROYED = "destroyed"
    const val EVENT_REBIND_SCHEDULED = "rebind_scheduled"
    const val EVENT_REBIND_REQUESTED = "rebind_requested"
    const val EVENT_REBIND_FAILED = "rebind_failed"
    const val EVENT_SKIPPED = "skipped"
    const val EVENT_THROTTLED = "throttled"
    const val EVENT_OCR_FALLBACK = "ocr_fallback"
    const val EVENT_HELD_FOR_REVIEW = "held_for_review"
    const val EVENT_SCAN_MISSED = "scan_missed"
    const val EVENT_CANDIDATE_INTAKE = "candidate_intake"
    const val EVENT_COLD_START = "cold_start"
    const val EVENT_FOREGROUND = "foreground"
    const val EVENT_ERROR = "error"

    private val lock = Any()
    private val recentRateLimitedKeys = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 64
    }

    fun record(
        context: Context,
        channel: String,
        event: String,
        summary: String,
        detail: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            recordToPrefs(prefs, channel, event, summary, detail, nowMs)
        }
    }

    internal fun recordToPrefs(
        prefs: SharedPreferences,
        channel: String,
        event: String,
        summary: String,
        detail: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            val normalizedChannel = channel.trim().ifEmpty { CHANNEL_APP }.take(32)
            val normalizedEvent = event.trim().ifEmpty { "event" }.take(48)
            val normalizedSummary = summary.trim().take(160)
            if (normalizedSummary.isEmpty()) return
            val normalizedDetail = detail?.trim()?.takeIf { it.isNotEmpty() }?.take(240)

            synchronized(lock) {
                if (shouldRateLimitLocked(normalizedChannel, normalizedEvent, normalizedSummary, normalizedDetail, nowMs)) {
                    return
                }
                val current = parseEntries(prefs.getString(KEY_ENTRIES, null))
                val next = buildList(MAX_ENTRIES) {
                    add(
                        ServiceHealthEntry(
                            occurredAtMs = nowMs,
                            channel = normalizedChannel,
                            event = normalizedEvent,
                            summary = normalizedSummary,
                            detail = normalizedDetail,
                        ),
                    )
                    for (item in current) {
                        if (size >= MAX_ENTRIES) break
                        add(item)
                    }
                }
                prefs.edit().putString(KEY_ENTRIES, encodeEntries(next)).apply()
            }
        }
    }

    fun entries(context: Context): List<ServiceHealthEntry> = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        entriesFromPrefs(prefs)
    }.getOrDefault(emptyList())

    internal fun entriesFromPrefs(prefs: SharedPreferences): List<ServiceHealthEntry> = runCatching {
        synchronized(lock) {
            parseEntries(prefs.getString(KEY_ENTRIES, null))
        }
    }.getOrDefault(emptyList())

    fun disconnectCountLast24h(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Int = countDisconnectsLast24h(entries(context), nowMs)

    internal fun countDisconnectsLast24h(
        entries: List<ServiceHealthEntry>,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val cutoff = nowMs - DAY_MS
        return entries.count { entry ->
            entry.occurredAtMs >= cutoff &&
                entry.channel == CHANNEL_NOTIFICATION &&
                entry.event == EVENT_DISCONNECTED
        }
    }

    fun clear(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            clearPrefs(prefs)
        }
    }

    internal fun clearPrefs(prefs: SharedPreferences) {
        runCatching {
            synchronized(lock) {
                recentRateLimitedKeys.clear()
                prefs.edit().remove(KEY_ENTRIES).apply()
            }
        }
    }

    internal fun resetRateLimiterForTests() {
        synchronized(lock) {
            recentRateLimitedKeys.clear()
        }
    }

    private fun isRateLimitedEvent(
        channel: String,
        event: String,
        summary: String,
    ): Boolean {
        if (event == EVENT_SKIPPED ||
            event == EVENT_THROTTLED ||
            event == EVENT_FOREGROUND
        ) {
            return true
        }
        // Empty catch-up scans ("scanned=0") can fire on every resume; throttle identical empty scans.
        if (channel == CHANNEL_SCREENSHOT &&
            event == EVENT_SCAN_MISSED &&
            summary.contains("scanned=0")
        ) {
            return true
        }
        return false
    }

    private fun shouldRateLimitLocked(
        channel: String,
        event: String,
        summary: String,
        detail: String?,
        nowMs: Long,
    ): Boolean {
        if (!isRateLimitedEvent(channel, event, summary)) return false
        val key = "$channel|$event|$summary|${detail.orEmpty()}"
        val lastAt = recentRateLimitedKeys[key]
        if (lastAt != null && nowMs >= lastAt && nowMs - lastAt < SKIP_RATE_LIMIT_MS) {
            return true
        }
        recentRateLimitedKeys[key] = nowMs
        return false
    }

    internal fun parseEntries(raw: String?): List<ServiceHealthEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(minOf(array.length(), MAX_ENTRIES)) {
                for (i in 0 until array.length()) {
                    if (size >= MAX_ENTRIES) break
                    val obj = array.optJSONObject(i) ?: continue
                    val occurredAtMs = obj.optLong("occurredAtMs", 0L)
                    val channel = obj.optString("channel", "").trim()
                    val event = obj.optString("event", "").trim()
                    val summary = obj.optString("summary", "").trim()
                    if (occurredAtMs <= 0L || channel.isEmpty() || event.isEmpty() || summary.isEmpty()) {
                        continue
                    }
                    val detail = obj.optString("detail", "").trim().takeIf {
                        it.isNotEmpty() && !obj.isNull("detail")
                    }
                    add(
                        ServiceHealthEntry(
                            occurredAtMs = occurredAtMs,
                            channel = channel,
                            event = event,
                            summary = summary,
                            detail = detail,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    internal fun encodeEntries(entries: List<ServiceHealthEntry>): String {
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach { entry ->
            val obj = JSONObject().apply {
                put("occurredAtMs", entry.occurredAtMs)
                put("channel", entry.channel)
                put("event", entry.event)
                put("summary", entry.summary)
                if (entry.detail != null) {
                    put("detail", entry.detail)
                } else {
                    put("detail", JSONObject.NULL)
                }
            }
            array.put(obj)
        }
        return array.toString()
    }
}
