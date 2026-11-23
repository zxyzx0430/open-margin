package com.bookkeeping.app.ui.screen.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarNoteTest {
    @Test fun hidesMachineMetadataAndKeepsUserNotes() {
        listOf("[导入] 微信账单 第245行 · 单号 123", "[扫描] 微信", "[自动抓取] 支付成功", "[自动支付] 微信").forEach {
            assertEquals("", calendarUserNote(it))
        }
        assertEquals("午餐聚会", calendarUserNote(" 午餐聚会 "))
        assertEquals("午餐聚会", calendarUserNote("[导入] 微信账单 第245行 · 单号 123 · 备注：午餐聚会"))
    }
}
