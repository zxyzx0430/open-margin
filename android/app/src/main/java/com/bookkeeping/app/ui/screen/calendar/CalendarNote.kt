package com.bookkeeping.app.ui.screen.calendar

/** Capture and import notes are generated metadata, not user-authored remarks. */
internal fun calendarUserNote(note: String): String {
    val value = note.trim()
    if (Regex("^\\[(导入|扫描|自动抓取|自动支付|银行短信待确认|支付通知待确认|退款|转账|红包|提现|账单)]").containsMatchIn(value)) {
        return Regex("(?:^| · )备注[：:]\\s*(.+)$").find(value)?.groupValues?.get(1)?.trim().orEmpty()
    }
    return value
}
