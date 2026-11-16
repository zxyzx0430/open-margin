package com.bookkeeping.app.sync

/** A sync cursor belongs to exactly one server. Reusing it elsewhere may skip
 *  remote edits or resurrect records deleted on the original server.
 *  URL changes after any successful upload/download need an explicit migration.
 */
internal object SyncEndpointPolicy {
    fun canChangeServer(
        previousUrl: String,
        proposedUrl: String,
        lastUploadedId: Long,
        lastPulledRevision: Long,
        lastSuccessMs: Long,
    ): Boolean {
        val old = previousUrl.trim().trimEnd('/')
        val next = proposedUrl.trim().trimEnd('/')
        val historyExists = lastUploadedId > 0 || lastPulledRevision > 0 || lastSuccessMs > 0
        // Without history the field may be empty (first-time setup). With
        // history an empty previous URL must not act as a wildcard: clearing
        // the address field would otherwise bypass the migration guard.
        if (!historyExists) return true
        return old.isNotEmpty() && old == next
    }
}
