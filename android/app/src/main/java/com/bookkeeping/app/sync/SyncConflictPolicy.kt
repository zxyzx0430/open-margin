package com.bookkeeping.app.sync

/**
 * Server revisions, not independent wall clocks, arbitrate writes.
 * Automatic push is valid only when baseRevision matches the current server revision.
 * Divergent revisions require explicit, per-transaction confirmation.
 */
internal object SyncConflictPolicy {
    enum class Decision { KEEP_PHONE, KEEP_SERVER, HOLD }

    fun decide(
        phoneModifiedMs:Long,
        serverModifiedMs:Long,
        nowMs:Long,
        choice:String="auto",
        serverDeleted:Boolean=false,
        phoneDeleted:Boolean=false,
    ):Decision = when(choice) {
        "keep_phone" -> Decision.KEEP_PHONE
        "keep_server" -> Decision.KEEP_SERVER
        else -> Decision.HOLD
    }
}
