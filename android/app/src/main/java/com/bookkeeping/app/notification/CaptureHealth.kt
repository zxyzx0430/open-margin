package com.bookkeeping.app.notification

import android.content.Context
import android.os.SystemClock

enum class CaptureConnectionState {
    PERMISSION_OFF,
    CONNECTED,
    DISCONNECTED,
    UNKNOWN,
}

/** Pure state rules, kept free of Android types so they can be unit-tested. */
object CaptureHealthPolicy {
    const val STARTUP_GRACE_MS: Long = 15_000L

    fun withinStartupGrace(serviceStartedAtMs: Long, nowMs: Long): Boolean =
        serviceStartedAtMs > 0L &&
            nowMs >= serviceStartedAtMs &&
            nowMs - serviceStartedAtMs < STARTUP_GRACE_MS

    fun resolve(
        authorized: Boolean,
        connected: Boolean,
        hasDisconnectedRecord: Boolean,
        withinStartupGrace: Boolean,
    ): CaptureConnectionState = when {
        !authorized -> CaptureConnectionState.PERMISSION_OFF
        connected -> CaptureConnectionState.CONNECTED
        hasDisconnectedRecord -> CaptureConnectionState.DISCONNECTED
        withinStartupGrace -> CaptureConnectionState.UNKNOWN
        else -> CaptureConnectionState.DISCONNECTED
    }
}

data class CaptureHealthSnapshot(
    val authorized: Boolean,
    val connected: Boolean,
    val connectedAtMs: Long,
    val connectedElapsedMs: Long,
    val disconnectedAtMs: Long,
    val disconnectedElapsedMs: Long,
    val disconnectedReason: String,
    val lastSuccessAtMs: Long,
    val lastFailureAtMs: Long,
    val failureStage: String,
    val failureKind: String,
    val failureCount: Int,
    val state: CaptureConnectionState,
)

/**
 * Device-local capture diagnostics. Stores only timestamps, a bounded reason/stage
 * label and an exception class name; never notification text, amounts or merchants.
 */
object CaptureHealth {
    private const val PREFS = "capture_health"

    @Volatile private var connectedInProcess = false

    fun snapshot(
        context: Context,
        serviceStartedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): CaptureHealthSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val authorized = NotificationListenerHelper.isEnabled(context)
        val disconnectedAt = prefs.getLong(KEY_DISCONNECTED_AT, 0L)
        val withinGrace = CaptureHealthPolicy.withinStartupGrace(serviceStartedAtMs, nowMs)
        return CaptureHealthSnapshot(
            authorized = authorized,
            connected = connectedInProcess,
            connectedAtMs = prefs.getLong(KEY_CONNECTED_AT, 0L),
            connectedElapsedMs = prefs.getLong(KEY_CONNECTED_ELAPSED, 0L),
            disconnectedAtMs = disconnectedAt,
            disconnectedElapsedMs = prefs.getLong(KEY_DISCONNECTED_ELAPSED, 0L),
            disconnectedReason = prefs.getString(KEY_DISCONNECTED_REASON, "").orEmpty(),
            lastSuccessAtMs = prefs.getLong(KEY_LAST_SUCCESS_AT, 0L),
            lastFailureAtMs = prefs.getLong(KEY_LAST_FAILURE_AT, 0L),
            failureStage = prefs.getString(KEY_FAILURE_STAGE, "").orEmpty(),
            failureKind = prefs.getString(KEY_FAILURE_KIND, "").orEmpty(),
            failureCount = prefs.getInt(KEY_FAILURE_COUNT, 0),
            state = CaptureHealthPolicy.resolve(
                authorized = authorized,
                connected = connectedInProcess,
                hasDisconnectedRecord = disconnectedAt > 0L,
                withinStartupGrace = withinGrace,
            ),
        )
    }

    fun onListenerConnected(context: Context) {
        connectedInProcess = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CONNECTED_AT, System.currentTimeMillis())
            .putLong(KEY_CONNECTED_ELAPSED, SystemClock.elapsedRealtime())
            // A reconnect makes the previous disconnect time obsolete; keeping it
            // would show a stale "disconnected at" after the next process restart.
            .remove(KEY_DISCONNECTED_AT)
            .remove(KEY_DISCONNECTED_ELAPSED)
            .remove(KEY_DISCONNECTED_REASON)
            .apply()
    }

    fun onListenerDisconnected(context: Context, reason: String) {
        connectedInProcess = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DISCONNECTED_AT, System.currentTimeMillis())
            .putLong(KEY_DISCONNECTED_ELAPSED, SystemClock.elapsedRealtime())
            .putString(KEY_DISCONNECTED_REASON, reason.take(48))
            .remove(KEY_CONNECTED_ELAPSED)
            .apply()
    }

    fun onTransactionSaved(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
            .apply()
    }

    fun onFailure(context: Context, stage: String, throwable: Throwable) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_FAILURE_AT, System.currentTimeMillis())
            .putString(KEY_FAILURE_STAGE, stage.take(48))
            .putString(KEY_FAILURE_KIND, throwable.javaClass.simpleName.take(80))
            .putInt(KEY_FAILURE_COUNT, (prefs.getInt(KEY_FAILURE_COUNT, 0) + 1).coerceAtMost(1_000_000))
            .commit()
    }

    private const val KEY_CONNECTED_AT = "connected_at"
    private const val KEY_CONNECTED_ELAPSED = "connected_elapsed"
    private const val KEY_DISCONNECTED_AT = "disconnected_at"
    private const val KEY_DISCONNECTED_ELAPSED = "disconnected_elapsed"
    private const val KEY_DISCONNECTED_REASON = "disconnected_reason"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_LAST_FAILURE_AT = "last_failure_at"
    private const val KEY_FAILURE_STAGE = "failure_stage"
    private const val KEY_FAILURE_KIND = "failure_kind"
    private const val KEY_FAILURE_COUNT = "failure_count"
}
