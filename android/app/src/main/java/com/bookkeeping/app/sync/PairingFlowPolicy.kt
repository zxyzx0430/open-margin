package com.bookkeeping.app.sync

/**
 * Pairing decisions that must be identical for scanned and typed credentials.
 *
 * A URL is not a ledger identity: a restored/reinstalled server can reuse the
 * same Funnel hostname with a new database. Any historical successful sync
 * therefore pins the phone to one URL + server_id pair. Pairing to anything
 * else needs an explicit, user-visible migration instead of silently
 * re-pointing the sync cursor.
 */
internal object PairingFlowPolicy {
    /** Snapshot of the persisted sync state that proves a ledger was used before. */
    data class History(
        val url: String = "",
        val serverId: String? = null,
        val lastUploadedId: Long = 0L,
        val lastPulledRevision: Long = 0L,
        val lastSuccessMs: Long = 0L,
        val lastResultCode: String? = null,
    )

    enum class PreProbe { ALLOWED, MIGRATION_REQUIRED }

    fun hasSuccessfulSync(history: History): Boolean =
        history.lastUploadedId > 0L ||
            history.lastPulledRevision > 0L ||
            history.lastSuccessMs > 0L ||
            !history.serverId.isNullOrBlank() ||
            history.lastResultCode == SyncResultCode.OK.preferenceValue

    /**
     * Guards the read-only probe itself. With no history any endpoint is fine
     * (first-time setup). Once anything succeeded, the URL must match and, for
     * a scanned payload, the QR server_id must match too. An empty stored URL
     * is treated as a mismatch rather than a wildcard: otherwise clearing the
     * address field would launder an old cursor into a new ledger.
     */
    fun evaluatePreProbe(
        history: History,
        proposedUrl: String,
        scannedServerId: String?,
    ): PreProbe {
        if (!hasSuccessfulSync(history)) return PreProbe.ALLOWED

        val previousUrl = history.url.trim().trimEnd('/')
        val nextUrl = proposedUrl.trim().trimEnd('/')
        if (previousUrl.isEmpty() || previousUrl != nextUrl) {
            return PreProbe.MIGRATION_REQUIRED
        }

        if (scannedServerId != null) {
            val knownServerId = history.serverId?.trim().orEmpty()
            if (knownServerId.isEmpty() || knownServerId != scannedServerId.trim()) {
                return PreProbe.MIGRATION_REQUIRED
            }
        }
        return PreProbe.ALLOWED
    }

    /**
     * The probe trusts the identity it was asked to expect, so a scanned
     * payload must additionally match the server_id the server returned.
     * Typed input has no QR identity to compare (the existing stored identity
     * already goes into the probe).
     */
    fun evaluateProbe(
        scannedServerId: String?,
        probeResult: WindowsConnectionPolicy.Result,
        probeServerId: String,
    ): WindowsConnectionPolicy.Result {
        if (probeResult != WindowsConnectionPolicy.Result.READY) return probeResult
        if (scannedServerId != null &&
            scannedServerId.trim() != probeServerId.trim()) {
            return WindowsConnectionPolicy.Result.WRONG_LEDGER
        }
        return WindowsConnectionPolicy.Result.READY
    }
}
