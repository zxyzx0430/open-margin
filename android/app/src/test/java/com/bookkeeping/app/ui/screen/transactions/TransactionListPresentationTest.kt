package com.bookkeeping.app.ui.screen.transactions

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionListPresentationTest {
    @Test
    fun accountReplacesTheSourceLabel() {
        assertEquals(
            "餐饮 · 微信 · 12:00",
            transactionSubtitle("餐饮", "手动", "微信", "12:00"),
        )
    }

    @Test
    fun emptyAccountFallsBackToTheSourceLabel() {
        assertEquals(
            "餐饮 · 手动 · 12:00",
            transactionSubtitle("餐饮", "手动", null, "12:00"),
        )
        assertEquals(
            "餐饮 · 手动 · 12:00",
            transactionSubtitle("餐饮", "手动", "  ", "12:00"),
        )
    }

    @Test
    fun sameSourceAndAccountAppearOnlyOnce() {
        assertEquals(
            "餐饮 · 微信 · 12:00",
            transactionSubtitle("餐饮", "微信", "微信", "12:00"),
        )
    }
}
