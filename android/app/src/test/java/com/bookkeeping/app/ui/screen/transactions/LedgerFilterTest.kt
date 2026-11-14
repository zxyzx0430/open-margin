package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerFilterTest {
    private fun tx(
        amount: Long = 1234,
        type: String = "EXPENSE",
        category: String = "FOOD",
        merchant: String = "星巴克",
        note: String = "",
        timestamp: Long = 1_000_000L,
    ) = TransactionEntity(
        amountCents = amount,
        type = type,
        category = category,
        merchant = merchant,
        note = note,
        source = "MANUAL",
        timestamp = timestamp,
    )

    @Test fun parsesYuanIntoExactCents() {
        assertEquals(1234L, LedgerFilter.parseAmountCents("12.34"))
        assertEquals(1200L, LedgerFilter.parseAmountCents("12"))
        assertNull(LedgerFilter.parseAmountCents("12.345"))
        assertNull(LedgerFilter.parseAmountCents("abc"))
        assertNull(LedgerFilter.parseAmountCents(""))
    }

    @Test fun matchesAmountMerchantNoteAndCategory() {
        val row = tx()
        assertTrue(LedgerFilter.matches(row, "12.34", null, null, null, null))
        assertTrue(LedgerFilter.matches(row, "星巴克", null, null, null, null))
        assertTrue(LedgerFilter.matches(row, "FOOD", null, null, null, null))
        assertTrue(LedgerFilter.matches(row, "咖啡", null, null, null, null).not())
    }

    @Test fun filtersTypeCategoryAndTimeRange() {
        val row = tx(timestamp = 2_000L)
        assertTrue(LedgerFilter.matches(row, "", TransactionType.EXPENSE, TransactionCategory.FOOD, 1_000L, 3_000L))
        assertFalse(LedgerFilter.matches(row, "", TransactionType.INCOME, null, null, null))
        assertFalse(LedgerFilter.matches(row, "", null, TransactionCategory.TRANSPORT, null, null))
        assertFalse(LedgerFilter.matches(row, "", null, null, 3_000L, 4_000L))
        assertFalse(LedgerFilter.matches(row, "", null, null, null, 2_000L))
    }

    @Test fun textQueryUsesNoteToo() {
        val row = tx(note = "午餐报销")
        assertTrue(LedgerFilter.matches(row, "报销", null, null, null, null))
    }
}
