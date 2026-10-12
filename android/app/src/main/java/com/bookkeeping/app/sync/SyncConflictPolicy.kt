package com.bookkeeping.app.sync

/**
 * Last-edit-wins only when UTC timestamps are clearly ordered and the
 * device clock is plausible. Otherwise the conflict remains visible.
 */
internal object SyncConflictPolicy {
    enum class Decision { KEEP_PHONE, KEEP_SERVER, HOLD }

    fun decide(
        phoneModifiedMs:Long,
        serverModifiedMs:Long,
        nowMs:Long,
        choice:String="auto",
    ):Decision {
        if(choice=="keep_phone")return Decision.KEEP_PHONE
        if(choice=="keep_server")return Decision.KEEP_SERVER
        if(phoneModifiedMs<=0 || serverModifiedMs<=0 ||
           phoneModifiedMs>nowMs+300_000 ||
           serverModifiedMs>nowMs+300_000)return Decision.HOLD
        if(phoneModifiedMs>serverModifiedMs+2000L)return Decision.KEEP_PHONE
        if(serverModifiedMs>phoneModifiedMs+2000L)return Decision.KEEP_SERVER
        return Decision.HOLD
    }
}
