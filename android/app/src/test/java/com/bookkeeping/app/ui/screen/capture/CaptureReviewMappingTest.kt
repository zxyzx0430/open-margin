package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReviewMappingTest {

    private fun candidate(
        packageName: String = CaptureCandidate.WECHAT,
        merchant: String = "星巴克",
        orderId: String? = "ORDER-9",
        kind: String = CaptureCandidate.KIND_PAYMENT,
        direction: String = CaptureCandidate.DIRECTION_EXPENSE,
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
        kind = kind,
        direction = direction,
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
        assertEquals(0.9f, entity.captureConfidence ?: -1f, 0.0001f)
        assertEquals(CaptureCandidate.KIND_PAYMENT, entity.captureKind)
        assertEquals(1_700_000_000_000L, entity.captureObservedAtMs ?: -1L)
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

    @Test
    fun mapsIncomingTransferToIncomeAndExcludesItFromSpending() {
        val entity = CaptureReviewMapping.toTransactionEntity(
            candidate(
                merchant = "张三",
                kind = CaptureCandidate.KIND_TRANSFER,
                direction = CaptureCandidate.DIRECTION_INCOME,
            ),
            merchant = "张三",
            category = TransactionCategory.OTHER,
        )
        assertEquals(TransactionType.INCOME.name, entity.type)
        assertEquals(TransactionCategory.TRANSFER.name, entity.category)
        assertEquals("transfer", entity.remoteReviewStatus)
        assertTrue(entity.note.contains("[转账]"))
        assertTrue(entity.note.contains("收入"))
    }

    @Test
    fun mapsOutgoingTransferAsTransferExpense() {
        val entity = CaptureReviewMapping.toTransactionEntity(
            candidate(kind = CaptureCandidate.KIND_TRANSFER),
            merchant = "李四",
            category = TransactionCategory.FOOD,
        )
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals(TransactionCategory.TRANSFER.name, entity.category)
        assertEquals("transfer", entity.remoteReviewStatus)
        assertTrue(entity.note.contains("支出"))
    }

    @Test
    fun mapsWithdrawalToTransferWithAFallbackMerchant() {
        val entity = CaptureReviewMapping.toTransactionEntity(
            candidate(merchant = "", orderId = null, kind = CaptureCandidate.KIND_WITHDRAWAL),
            merchant = "",
            category = TransactionCategory.OTHER,
        )
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals(TransactionCategory.TRANSFER.name, entity.category)
        assertEquals("transfer", entity.remoteReviewStatus)
        assertEquals("微信提现", entity.merchant)
        assertTrue(entity.note.contains("[提现]"))
    }

    @Test
    fun mapsRedPacketIncomeAndExpenseByDirection() {
        val income = CaptureReviewMapping.toTransactionEntity(
            candidate(merchant = "", kind = CaptureCandidate.KIND_RED_PACKET, direction = CaptureCandidate.DIRECTION_INCOME),
            merchant = "",
            category = TransactionCategory.OTHER,
        )
        assertEquals(TransactionType.INCOME.name, income.type)
        assertEquals(TransactionCategory.GIFT.name, income.category)
        assertEquals("微信红包", income.merchant)
        assertEquals("unverified", income.remoteReviewStatus)

        val expense = CaptureReviewMapping.toTransactionEntity(
            candidate(merchant = "", kind = CaptureCandidate.KIND_RED_PACKET),
            merchant = "",
            category = TransactionCategory.OTHER,
        )
        assertEquals(TransactionType.EXPENSE.name, expense.type)
        assertEquals(TransactionCategory.TRANSFER.name, expense.category)
        assertEquals("transfer", expense.remoteReviewStatus)
    }

    @Test
    fun mapsRefundToIncomeEvenWithoutAMerchant() {
        val entity = CaptureReviewMapping.toTransactionEntity(
            candidate(
                merchant = "",
                orderId = null,
                kind = CaptureCandidate.KIND_REFUND,
                direction = CaptureCandidate.DIRECTION_INCOME,
            ),
            merchant = "",
            category = TransactionCategory.FOOD,
        )
        assertEquals(TransactionType.INCOME.name, entity.type)
        assertEquals(TransactionCategory.OTHER.name, entity.category)
        assertEquals("refund", entity.remoteReviewStatus)
        assertEquals("微信退款", entity.merchant)
        assertTrue(entity.note.contains("[退款]"))
    }

    @Test
    fun mapsCompletedPayAfterUseAsExpense() {
        val entity = CaptureReviewMapping.toTransactionEntity(
            candidate(kind = CaptureCandidate.KIND_AUTO_PAY),
            merchant = "拼多多",
            category = TransactionCategory.SHOPPING,
        )
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals(TransactionCategory.SHOPPING.name, entity.category)
        assertTrue(entity.note.contains("[自动支付]"))
    }

    @Test
    fun defaultCategoryClassifiesPaymentMerchantInsteadOfFallingBackToOther() {
        assertEquals(
            TransactionCategory.FOOD,
            CaptureReviewMapping.defaultCategory(candidate(merchant = "星巴克")),
        )
        assertEquals(
            TransactionCategory.TRANSFER,
            CaptureReviewMapping.defaultCategory(
                candidate(kind = CaptureCandidate.KIND_TRANSFER),
            ),
        )
    }

    @Test
    fun screenshotStorageWarningsSurfaceTypedReasonsWithoutRawFailureDetails() {
        val expectedByReason = mapOf(
            CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_QUOTA to "截图未保留（空间不足）；释放空间后重新导入。",
            CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_IO to "截图保存失败；请重新选择图片后核对。",
            CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_INVALID to "图片无效，截图未保留；请换图重新导入。",
            CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_MISSING to "截图未提供或未保留；请重新导入原图。",
            "screenshot decode failed" to "截图无法读取；请重新选择图片。",
            "screenshot start failed" to "截图无法读取；请重新选择图片。",
            "screenshot error FixtureIOException: /private/fixture/path" to "截图无法读取；请重新选择图片。",
            "screenshot unsupported" to "图片格式不支持；请换一张截图。",
            "${CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_PREFIX} fixture failure" to "截图未能保留；请重新导入原图。",
        )

        expectedByReason.forEach { (reason, expected) ->
            val notice = CaptureReviewMapping.screenshotUnavailableNotice(candidate().copy(reason = reason))
            assertEquals(reason, expected, notice)
            assertFalse(notice.orEmpty().contains("/private"))
            assertFalse(notice.orEmpty().contains("FixtureIOException"))
        }
        assertEquals(null, CaptureReviewMapping.screenshotUnavailableNotice(candidate()))
    }

    @Test
    fun duplicateAndScreenshotQuotaOrIoWarningsCanBeShownTogether() {
        mapOf(
            CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_QUOTA to "截图未保留（空间不足）；释放空间后重新导入。",
            CaptureCandidateStore.SCREENSHOT_UNAVAILABLE_IO to "截图保存失败；请重新选择图片后核对。",
        ).forEach { (imageReason, expectedImageWarning) ->
            val duplicate = candidate().copy(
                reason = CaptureCandidate.REASON_LIKELY_DUPLICATE,
                matchedTransactionId = 42L,
                screenshotUnavailableReason = imageReason,
            )

            assertEquals(CaptureCandidate.REASON_LIKELY_DUPLICATE, duplicate.reason)
            assertEquals(
                "疑似与账本里已有的一笔重复，请核对后再决定（已有记录 #42）",
                likelyDuplicateNoticeText(duplicate),
            )
            val imageWarning = CaptureReviewMapping.screenshotUnavailableNotice(duplicate)
            assertEquals(expectedImageWarning, imageWarning)
            assertFalse(imageWarning.orEmpty().contains("IOException"))
            assertFalse(imageWarning.orEmpty().contains("/private"))
        }
    }
}
