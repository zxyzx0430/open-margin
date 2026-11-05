package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.sync.UploadPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract test for extraction -> upload. Every field the server is allowed to
// receive must survive; raw note text and bank/account hints must not be mapped.
class UploadPayloadTest {

    private fun entity(
        id: Long = 42,
        amountCents: Long = 1230,
        type: String = TransactionType.EXPENSE.name,
        category: String = TransactionCategory.FOOD.name,
        merchant: String = "麦当劳",
        source: String = TransactionSource.WECHAT.name,
        timestamp: Long = 1_700_000_000_000L,
        currency: String = "CNY",
        reviewStatus: String = "confirmed",
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        type = type,
        category = category,
        merchant = merchant,
        note = "raw notification or SMS text must stay local",
        source = source,
        account = "bank card 1234 must stay local",
        timestamp = timestamp,
        currency = currency,
        remoteReviewStatus = reviewStatus,
    )

    @Test fun v1MapsEveryAllowedField() {
        val fields = UploadPayload.v1Event(entity(), "installation-uuid")
        assertEquals("android:installation-uuid:42", fields.eventId)
        assertEquals(1_700_000_000_000L, fields.timestampMs)
        assertEquals(1230L, fields.amountCents)
        assertEquals("CNY", fields.currency)
        assertEquals("EXPENSE", fields.direction)
        assertEquals("WECHAT", fields.source)
        assertEquals("FOOD", fields.category)
        assertEquals("麦当劳", fields.merchant)
    }

    @Test fun v1PreservesForeignCurrencyAndUppercasesSource() {
        val fields = UploadPayload.v1Event(
            entity(source = "alipay", currency = "USD", category = "SHOPPING"),
            "install",
        )
        assertEquals("USD", fields.currency)
        assertEquals("ALIPAY", fields.source)
        assertEquals("SHOPPING", fields.category)
    }

    @Test fun v1TruncatesMerchantAtServerLimitAndKeepsUnicode() {
        val merchant = "店".repeat(200)
        val fields = UploadPayload.v1Event(entity(merchant = merchant), "install")
        assertEquals(140, fields.merchant.length)
        assertEquals("店".repeat(140), fields.merchant)
    }

    @Test fun v2MapsReviewStatusAndOmitsEventIdentity() {
        val fields = UploadPayload.v2Record(entity(reviewStatus = "excluded"))
        assertEquals(1230L, fields.amountCents)
        assertEquals("EXPENSE", fields.direction)
        assertEquals("WECHAT", fields.source)
        assertEquals("excluded", fields.reviewStatus)
        // event_id is supplied by the sync operation, not the record payload.
        assertFalse(fields.toString().contains("installation"))
    }

    @Test fun v2DropsUnknownReviewStatus() {
        assertNull(UploadPayload.v2Record(entity(reviewStatus = "refund")).reviewStatus)
    }

    @Test fun uploadableRejectsInvalidMoneyDirectionAndSource() {
        assertTrue(UploadPayload.isUploadable(entity()))
        assertFalse(UploadPayload.isUploadable(entity(amountCents = 0)))
        assertFalse(UploadPayload.isUploadable(entity(amountCents = -1)))
        assertFalse(UploadPayload.isUploadable(entity(type = "TRANSFER")))
        assertFalse(UploadPayload.isUploadable(entity(source = "CASH")))
    }
}
