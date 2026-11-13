package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.repository.DuplicateReviewPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateReviewPolicyTest {

    private fun entity(
        id: Long = 1,
        amountCents: Long = 1230,
        type: String = "EXPENSE",
        currency: String = "CNY",
        timestamp: Long = 1_700_000_000_000L,
        source: String = TransactionSource.WECHAT.name,
        merchant: String = "麦当劳",
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        type = type,
        category = "FOOD",
        merchant = merchant,
        source = source,
        timestamp = timestamp,
        currency = currency,
    )

    @Test fun sameAmountDirectionCurrencyWithinWindowIsFlagged() {
        assertTrue(DuplicateReviewPolicy.shouldFlag(entity(), entity(id = 2)))
        assertTrue(
            DuplicateReviewPolicy.shouldFlag(
                entity(timestamp = 1_700_000_000_000L),
                entity(id = 2, timestamp = 1_700_000_000_000L + DuplicateReviewPolicy.WINDOW_MS),
            )
        )
    }

    @Test fun differentAmountDirectionOrCurrencyIsNotFlagged() {
        assertFalse(DuplicateReviewPolicy.shouldFlag(entity(), entity(amountCents = 1231)))
        assertFalse(DuplicateReviewPolicy.shouldFlag(entity(), entity(type = "INCOME")))
        assertFalse(DuplicateReviewPolicy.shouldFlag(entity(), entity(currency = "USD")))
    }

    @Test fun outsideWindowOrMissingExistingIsNotFlagged() {
        assertFalse(
            DuplicateReviewPolicy.shouldFlag(
                entity(timestamp = 1_700_000_000_000L),
                entity(
                    id = 2,
                    timestamp = 1_700_000_000_000L + DuplicateReviewPolicy.WINDOW_MS + 1,
                ),
            )
        )
        assertFalse(DuplicateReviewPolicy.shouldFlag(null, entity(id = 2)))
    }

    @Test fun sourceAndMerchantMayDifferBecauseItIsOnlyAPossibleDuplicate() {
        assertTrue(
            DuplicateReviewPolicy.shouldFlag(
                entity(source = "WECHAT", merchant = "麦当劳"),
                entity(id = 2, source = "BANK_SMS", merchant = ""),
            )
        )
    }
}
