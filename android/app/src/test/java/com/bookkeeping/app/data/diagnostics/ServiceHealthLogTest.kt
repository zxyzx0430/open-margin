package com.bookkeeping.app.data.diagnostics

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServiceHealthLogTest {

    @Before
    fun setUp() {
        ServiceHealthLog.resetRateLimiterForTests()
    }

    @Test
    fun `ring buffer caps at 80 entries and keeps newest first`() {
        val prefs = InMemorySharedPreferences()
        val base = 1_800_000_000_000L

        for (i in 1..95) {
            ServiceHealthLog.recordToPrefs(
                prefs = prefs,
                channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
                event = ServiceHealthLog.EVENT_CONNECTED,
                summary = "event-$i",
                detail = "detail-$i",
                nowMs = base + i * 1_000L,
            )
        }

        val entries = ServiceHealthLog.entriesFromPrefs(prefs)
        assertEquals(ServiceHealthLog.MAX_ENTRIES, entries.size)
        assertEquals("event-95", entries.first().summary)
        assertEquals("detail-95", entries.first().detail)
        assertEquals("event-16", entries.last().summary)
    }

    @Test
    fun `disconnectCountLast24h counts only notification disconnects within 24 hours`() {
        val prefs = InMemorySharedPreferences()
        val now = 1_800_100_000_000L
        val hour = 60L * 60L * 1_000L

        // Older than 24h (excluded)
        ServiceHealthLog.recordToPrefs(
            prefs = prefs,
            channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
            event = ServiceHealthLog.EVENT_DISCONNECTED,
            summary = "old disconnect",
            nowMs = now - 25 * hour,
        )
        // Accessibility disconnect (excluded)
        ServiceHealthLog.recordToPrefs(
            prefs = prefs,
            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
            event = ServiceHealthLog.EVENT_DISCONNECTED,
            summary = "acc disconnect",
            nowMs = now - 2 * hour,
        )
        // Two notification disconnects within 24h (included)
        ServiceHealthLog.recordToPrefs(
            prefs = prefs,
            channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
            event = ServiceHealthLog.EVENT_DISCONNECTED,
            summary = "disconnect 1",
            nowMs = now - 23 * hour,
        )
        ServiceHealthLog.recordToPrefs(
            prefs = prefs,
            channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
            event = ServiceHealthLog.EVENT_DISCONNECTED,
            summary = "disconnect 2",
            nowMs = now - 1 * hour,
        )

        val count = ServiceHealthLog.countDisconnectsLast24h(
            entries = ServiceHealthLog.entriesFromPrefs(prefs),
            nowMs = now,
        )
        assertEquals(2, count)
    }

    @Test
    fun `repetitive skip events are rate limited within 10 seconds`() {
        val prefs = InMemorySharedPreferences()
        val base = 1_800_000_000_000L

        ServiceHealthLog.recordToPrefs(
            prefs = prefs,
            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
            event = ServiceHealthLog.EVENT_SKIPPED,
            summary = "已跳过账单列表页",
            detail = "com.tencent.mm",
            nowMs = base,
        )
        // Identical skip 3s later -> suppressed
        ServiceHealthLog.recordToPrefs(
            prefs = prefs,
            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
            event = ServiceHealthLog.EVENT_SKIPPED,
            summary = "已跳过账单列表页",
            detail = "com.tencent.mm",
            nowMs = base + 3_000L,
        )
        // Different skip -> recorded
        ServiceHealthLog.recordToPrefs(
            prefs = prefs,
            channel = ServiceHealthLog.CHANNEL_NOTIFICATION,
            event = ServiceHealthLog.EVENT_SKIPPED,
            summary = "已忽略非交易通知",
            nowMs = base + 4_000L,
        )
        // Identical skip 11s later -> allowed again
        ServiceHealthLog.recordToPrefs(
            prefs = prefs,
            channel = ServiceHealthLog.CHANNEL_ACCESSIBILITY,
            event = ServiceHealthLog.EVENT_SKIPPED,
            summary = "已跳过账单列表页",
            detail = "com.tencent.mm",
            nowMs = base + 11_000L,
        )

        val entries = ServiceHealthLog.entriesFromPrefs(prefs)
        assertEquals(3, entries.size)
        assertEquals("已跳过账单列表页", entries[0].summary)
        assertEquals("已忽略非交易通知", entries[1].summary)
        assertEquals("已跳过账单列表页", entries[2].summary)
    }

    @Test
    fun `clearPrefs removes all stored entries and corrupt json degrades safely`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("entries", "not-valid-json").apply()
        assertTrue(ServiceHealthLog.entriesFromPrefs(prefs).isEmpty())

        ServiceHealthLog.recordToPrefs(
            prefs = prefs,
            channel = ServiceHealthLog.CHANNEL_APP,
            event = ServiceHealthLog.EVENT_COLD_START,
            summary = "应用进程冷启动",
            nowMs = 1_800_000_000_000L,
        )
        assertEquals(1, ServiceHealthLog.entriesFromPrefs(prefs).size)

        ServiceHealthLog.clearPrefs(prefs)
        assertTrue(ServiceHealthLog.entriesFromPrefs(prefs).isEmpty())
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = REMOVE }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                pending.forEach { (k, v) ->
                    if (v === REMOVE) data.remove(k) else data[k] = v
                }
            }
        }

        private object REMOVE
    }
}
