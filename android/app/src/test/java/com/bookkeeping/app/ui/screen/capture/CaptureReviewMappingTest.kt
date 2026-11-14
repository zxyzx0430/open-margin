package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.capture.CaptureCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReviewMappingTest {

    private fun candidate(
        packageName: String = CaptureCandidate.WECHAT,
        merchant: String = "星巴克",
        orderId: String? = "ORDER-9",
    ) = CaptureCandidate(
        packageName = packageName,
        pageIdentity = "page-1",
        amountCents = 1_524L,
        merchantCandidates = listOf(merchant),
        orderId = orderId,
        capturedAtMs = 1_700_000_000_000L,
        screenshotPath = null,
        confidence = 0.9f,
        reason = "ok",
    )

    @Test
    fun mapsWechatCandidateToExpense() {
        val entity = CaptureReviewMapping.toTransactionEntity(
            candidate(),
            merchant = "星巴克",
            category = TransactionCategory.FOOD,
        )
        assertEquals(1_524L, entity.amountCents)
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals(TransactionSource.WECHAT.name, entity.source)
        assertEquals(TransactionCategory.FOOD.name, entity.category)
        assertEquals("starbucks", entity.merchantIconKey)
        assertTrue(entity.note.contains("[扫描]"))
        assertTrue(entity.note.contains("ORDER-9"))
    }

    @Test
    fun mapsAlipayAndUnknownMerchantWithoutInventingAName() {
        val entity = CaptureReviewMapping.toTransactionEntity(
            candidate(packageName = CaptureCandidate.ALIPAY, merchant = "", orderId = null),
            merchant = "",
            category = TransactionCategory.OTHER,
        )
        assertEquals(TransactionSource.ALIPAY.name, entity.source)
        assertEquals("未知商户", entity.merchant)
        assertEquals(null, entity.merchantIconKey)
    }
}
