package com.bookkeeping.app.notification.accessibility

import android.content.Context

/**
 * Device-local accessibility diagnostics. Never records view text, merchant, amount,
 * card details, exception messages, or the current screen's package name.
 *
 * A system permission flag only says the service is allowed. [connectedInProcess]
 * becomes true exclusively when Android invokes onServiceConnected in this process,
 * and resets when the process restarts or the service unbinds.
 */
object AccessibilityCaptureHealth {
    private const val PREFS = "accessibility_capture_health"
    private const val EVENT_WRITE_INTERVAL_MS = 60_000L

    @Volatile private var connectedInProcess = false
    @Volatile private var lastPersistedEventMs = 0L

    data class Snapshot(
        val authorized: Boolean,
        val connected: Boolean,
        val connectedAtMs: Long,
        val disconnectedAtMs: Long,
        val disconnectedReason: String,
        val eventAtMs: Long,
        val interruptedAtMs: Long,
        val errorAtMs: Long,
        val errorStage: String,
        val errorKind: String,
        val errorCount: Int,
    ) {
        val state: AccessibilityConnectionState
            get() = AccessibilityConnectionState.resolve(authorized, connected)
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            authorized = AccessibilityCaptureService.isEnabled(context),
            connected = connectedInProcess,
            connectedAtMs = prefs.getLong("connected_at", 0L),
            disconnectedAtMs = prefs.getLong("disconnected_at", 0L),
            disconnectedReason = prefs.getString("disconnected_reason", "").orEmpty(),
            eventAtMs = prefs.getLong("event_at", 0L),
            interruptedAtMs = prefs.getLong("interrupted_at", 0L),
            errorAtMs = prefs.getLong("error_at", 0L),
            errorStage = prefs.getString("error_stage", "").orEmpty(),
            errorKind = prefs.getString("error_kind", "").orEmpty(),
            errorCount = prefs.getInt("error_count", 0),
        )
    }

    fun onConnected(context: Context) {
        connectedInProcess = true
        lastPersistedEventMs = 0L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("connected_at", System.currentTimeMillis())
            .apply()
    }

    fun onEvent(context: Context) {
        if (!connectedInProcess) return
        val now = System.currentTimeMillis()
        if (now - lastPersistedEventMs < EVENT_WRITE_INTERVAL_MS) return
        lastPersistedEventMs = now
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("event_at", now)
            .apply()
    }

    fun onDisconnected(context: Context, reason: String) {
        connectedInProcess = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("disconnected_at", System.currentTimeMillis())
            .putString("disconnected_reason", reason)
            .apply()
    }

    fun onInterrupted(context: Context) {
        // Android can interrupt accessibility feedback without disconnecting the service.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("interrupted_at", System.currentTimeMillis())
            .apply()
    }

    fun onFailure(context: Context, stage: String, throwable: Throwable) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("error_at", System.currentTimeMillis())
            .putString("error_stage", stage.take(48))
            .putString("error_kind", throwable.javaClass.simpleName.take(80))
            .putInt("error_count", (prefs.getInt("error_count", 0) + 1).coerceAtMost(1_000_000))
            .apply()
    }
}

/** Separates Android authorization from an actually bound service instance. */
enum class AccessibilityConnectionState {
    PERMISSION_OFF,
    AUTHORIZED_NOT_CONNECTED,
    CONNECTED;

    companion object {
        fun resolve(authorized: Boolean, connected: Boolean): AccessibilityConnectionState = when {
            !authorized -> PERMISSION_OFF
            connected -> CONNECTED
            else -> AUTHORIZED_NOT_CONNECTED
        }
    }
}
