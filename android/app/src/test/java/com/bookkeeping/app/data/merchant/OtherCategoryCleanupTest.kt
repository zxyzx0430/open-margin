package com.bookkeeping.app.data.merchant

import com.bookkeeping.app.data.local.entity.TransactionEntity
import org.junit.Assert.*
import org.junit.Test

class OtherCategoryCleanupTest {
    private fun row(id: Long, merchant: String, category: String = "OTHER", deleted: Boolean = false) =
        TransactionEntity(id = id, merchant = merchant, category = category, syncDeleted = deleted, amountCents = id * 100, type = "EXPENSE", source = "MANUAL", timestamp = id)

    @Test fun groupsAndSortsWithoutMergingEmptyMerchants() {
        val groups = otherMerchantGroups(listOf(row(1, "Shop (A)"), row(2, " shop "), row(3, ""), row(4, " "), row(5, "Shop", "FOOD"), row(6, "Shop", deleted = true)))
        assertEquals(listOf(2, 1, 1), groups.map { it.rows.size })
        assertEquals(300L, groups.first().rows.sumOf { it.amountCents })
        assertEquals(setOf(3L, 4L), groups.drop(1).map { it.singleId }.toSet())
    }

    @Test fun matchesCurrentOtherOnlyAndEmptyById() {
        assertTrue(matchesOtherMerchant(row(1, "SHOP (A)"), "shop"))
        assertFalse(matchesOtherMerchant(row(1, "shop", "FOOD"), "shop"))
        assertFalse(matchesOtherMerchant(row(1, "shop", deleted = true), "shop"))
        assertTrue(matchesOtherMerchant(row(2, ""), "", 2))
        assertFalse(matchesOtherMerchant(row(3, ""), "", 2))
        assertFalse(matchesOtherMerchant(row(2, ""), ""))
    }
    @Test fun categoryUpdateMarksSyncMetadata() {
        val first = markCategorized(row(1, "shop"), "FOOD", 123, "mutation-1")
        val second = markCategorized(row(2, "shop"), "FOOD", 123, "mutation-2")
        assertEquals("FOOD", first.category)
        assertTrue(first.syncDirty)
        assertEquals(123L, first.syncModifiedMs)
        assertNotEquals(first.syncMutationId, second.syncMutationId)
    }
}
