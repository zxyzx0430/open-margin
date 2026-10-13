package com.bookkeeping.app.sync

/** The old API accepted count includes ignored-before-ledger-start events. */
internal object LegacyUploadAckPolicy {
    enum class Decision { ADVANCE_CURSOR, RETRY, REJECTED_BY_LEDGER_START }

    fun decide(expected: Int, accepted: Int, ignoredBeforeStart: Int): Decision = when {
        accepted != expected || ignoredBeforeStart < 0 -> Decision.RETRY
        ignoredBeforeStart > 0 -> Decision.REJECTED_BY_LEDGER_START
        else -> Decision.ADVANCE_CURSOR
    }
}
