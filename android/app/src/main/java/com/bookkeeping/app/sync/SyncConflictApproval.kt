package com.bookkeeping.app.sync

/** Explicit conflict choices are valid only for the exact two versions shown
 * to the user. Another edit on either side invalidates that consent.
 */
internal object SyncConflictApproval {
    fun valid(
        choice: String?,
        approvedServerRevision: Long,
        currentServerRevision: Long,
        approvedLocalModifiedMs: Long,
        currentLocalModifiedMs: Long,
    ): Boolean = choice in setOf("keep_phone","keep_server") &&
        approvedServerRevision == currentServerRevision &&
        approvedLocalModifiedMs == currentLocalModifiedMs
}
