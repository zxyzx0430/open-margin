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
        if (old.isEmpty() || old == next) return true
        return lastUploadedId <= 0 && lastPulledRevision <= 0 && lastSuccessMs <= 0
    }
}
