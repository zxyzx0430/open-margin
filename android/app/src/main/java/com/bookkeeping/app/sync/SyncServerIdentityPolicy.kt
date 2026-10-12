package com.bookkeeping.app.sync

/** URLs do not identify a database: restored or reinstalled servers need
 * explicit verification before an old phone uploads anything to them.
 */
internal object SyncServerIdentityPolicy {
    enum class Decision {
        ACCEPT, MISSING_ID, DIFFERENT_SERVER, CURSOR_AHEAD, UNKNOWN_OLD_CURSOR
    }

    fun evaluate(expectedId: String?, cursor: Long,
                 remoteId: String, headRevision: Long): Decision = when {
        remoteId.length < 16 || headRevision < 0 -> Decision.MISSING_ID
        !expectedId.isNullOrBlank() && expectedId != remoteId ->
            Decision.DIFFERENT_SERVER
        cursor > headRevision -> Decision.CURSOR_AHEAD
        expectedId.isNullOrBlank() && cursor > 0 -> Decision.UNKNOWN_OLD_CURSOR
        else -> Decision.ACCEPT
    }
}
