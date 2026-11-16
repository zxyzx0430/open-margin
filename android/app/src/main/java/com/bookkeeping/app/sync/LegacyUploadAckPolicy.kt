package com.bookkeeping.app.sync

/**
 * v1 批量上传的回执策略。
 *
 * 账本要的是完整性：账本起始日不再是写入闸门，服务端会接收全部记录，
 * `ignored_before_start` 恒为 0，该字段只为兼容旧版服务端而保留。
 *
 * 只有「回执不可信」才允许重试：accepted 与发送条数不一致，或字段非法（负数）。
 * `ignored_before_start > 0` 说明对面是旧服务端、确实丢了几条历史记录，
 * 但那是服务端的数据缺失，不是本地记录有问题：游标必须照常前进，
 * 否则一条旧账会永久冻住整条同步链（曾经的真实故障）。
 */
internal object LegacyUploadAckPolicy {
    enum class Decision { ADVANCE_CURSOR, RETRY }

    fun decide(expected: Int, accepted: Int, ignoredBeforeStart: Int): Decision = when {
        accepted != expected || ignoredBeforeStart < 0 -> Decision.RETRY
        else -> Decision.ADVANCE_CURSOR
    }
}
