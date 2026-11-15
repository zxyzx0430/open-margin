package com.bookkeeping.app.ui.screen.billimport

import com.bookkeeping.app.billimport.BillDirection
import com.bookkeeping.app.billimport.BillFormat
import com.bookkeeping.app.billimport.BillImportParser
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillImportRow
import com.bookkeeping.app.billimport.BillPreviewKind
import com.bookkeeping.app.billimport.BillPreviewRow
import com.bookkeeping.app.billimport.BillSource
import com.bookkeeping.app.billimport.BillStatus
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillImportMappingTest {

    private fun row(
        merchant: String = "星巴克",
        amountCents: Long = 3_800L,
        direction: BillDirection = BillDirection.EXPENSE,
        status: BillStatus = BillStatus.SUCCESS,
        orderId: String? = "ORDER-1",
        rowNumber: Int = 3,
        source: BillSource = BillSource.WECHAT,
        rawFields: List<String> = emptyList(),
    ) = BillImportRow(
        source = source,
        rowNumber = rowNumber,
        occurredAtMs = 1_700_000_000_000L,
        merchant = merchant,
        amountCents = amountCents,
        direction = direction,
        status = status,
        orderId = orderId,
        paymentMethod = "零钱",
        rawFields = rawFields,
    )

    @Test
    fun mapsExpenseToLedgerWithImportNoteAndSource() {
        val entity = BillImportMapping.toTransactionEntity(row())
        assertNotNull(entity)
        entity!!
        assertEquals(3_800L, entity.amountCents)
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals(TransactionSource.WECHAT.name, entity.source)
        assertEquals("星巴克", entity.merchant)
        assertEquals("starbucks", entity.merchantIconKey)
        assertEquals(1_700_000_000_000L, entity.timestamp)
        assertEquals(true, entity.note.contains("[导入]"))
        assertEquals(true, entity.note.contains("第3行"))
        assertEquals(true, entity.note.contains("ORDER-1"))
    }

    @Test
    fun mapsIncomeAndFallsBackForUnknownMerchant() {
        val entity = BillImportMapping.toTransactionEntity(
            row(merchant = "", direction = BillDirection.INCOME, orderId = null),
        )
        assertNotNull(entity)
        entity!!
        assertEquals(TransactionType.INCOME.name, entity.type)
        assertEquals("", entity.merchant)
        assertNull(entity.merchantIconKey)
    }

    @Test
    fun refundRowsAreKeptAsExcludedIncomeAndOriginalExpense() {
        val refundIncome = BillImportMapping.toTransactionEntity(
            row(
                merchant = "自来充-退款",
                amountCents = 145L,
                direction = BillDirection.INCOME,
                status = BillStatus.REFUND,
                orderId = "REFUND-1",
            ),
        )!!
        assertEquals(TransactionType.INCOME.name, refundIncome.type)
        assertEquals("refund", refundIncome.remoteReviewStatus)

        val originalExpense = BillImportMapping.toTransactionEntity(
            row(status = BillStatus.REFUND, orderId = "ORDER-2"),
        )!!
        assertEquals(TransactionType.EXPENSE.name, originalExpense.type)
        assertEquals("refund", originalExpense.remoteReviewStatus)
    }

    @Test
    fun withdrawalNeutralRowIsKeptAsTransferExpense() {
        val entity = BillImportMapping.toTransactionEntity(
            row(
                merchant = "零钱提现",
                direction = BillDirection.NEUTRAL,
                orderId = "WITHDRAW-1",
            ).copy(rawFields = listOf("零钱提现", "提现已到账")),
        )!!
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals("TRANSFER", entity.category)
        assertEquals("transfer", entity.remoteReviewStatus)
    }

    @Test
    fun rejectsFailedPendingAndNeutralRows() {
        assertNull(BillImportMapping.toTransactionEntity(row(status = BillStatus.FAILED)))
        assertNull(BillImportMapping.toTransactionEntity(row(status = BillStatus.PENDING)))
        assertNull(BillImportMapping.toTransactionEntity(row(direction = BillDirection.NEUTRAL)))
        assertNull(BillImportMapping.toTransactionEntity(row(amountCents = 0L)))
    }

    @Test
    fun existingLedgerKeysUseTheParserNormalization() {
        val transaction = TransactionEntity(
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "星巴克",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_000_000L,
        )
        val keys = BillImportMapping.existingRowKeys(listOf(transaction))
        val imported = row(orderId = null)
        assertEquals(true, keys.contains(BillImportParser.duplicateKey(imported)))
        assertEquals(true, keys.contains(BillImportMapping.ledgerRowKey(imported)))
    }

    @Test
    fun orderIdIsStructuredAndNamespacedBySource() {
        val entity = BillImportMapping.toTransactionEntity(row(orderId = "ORDER-1"))!!
        assertEquals("ORDER-1", entity.sourceTransactionId)
        val keys = BillImportMapping.existingRowKeys(listOf(entity))
        assertTrue(keys.contains(BillImportMapping.ledgerRowKey(row(orderId = "ORDER-1"))))
        assertFalse(
            keys.contains(
                BillImportMapping.ledgerRowKey(
                    row(orderId = "ORDER-1", source = BillSource.ALIPAY),
                ),
            ),
        )
    }

    @Test
    fun categoryUsesMerchantCatalogAndFallsBackToOther() {
        assertEquals("FOOD", BillImportMapping.categoryFor("星巴克").name)
        assertEquals("TRANSPORT", BillImportMapping.categoryFor("地铁").name)
        assertEquals("OTHER", BillImportMapping.categoryFor("完全未知的小店").name)
        assertEquals("FOOD", BillImportMapping.categoryFor("采蝶轩").name)
        assertEquals("UTILITIES", BillImportMapping.categoryFor("自来充").name)
        assertEquals("ENTERTAINMENT", BillImportMapping.categoryFor("杭州游卡").name)
        assertEquals("UTILITIES", BillImportMapping.categoryFor("中国移动").name)
        assertEquals("EDUCATION", BillImportMapping.categoryFor("五邑大学").name)
        assertEquals("SHOPPING", BillImportMapping.categoryFor("思雨宠物用品").name)
        assertEquals("FOOD", BillImportMapping.categoryFor("示例炸鸡店").name)
    }

    @Test
    fun merchantWinsOverIncidentalContextKeywords() {
        assertEquals("UTILITIES", BillImportMapping.categoryFor("中国电信", "充值").name)
        assertEquals(
            "SHOPPING",
            BillImportMapping.categoryFor("拼多多平台商户", "交通银行信用卡").name,
        )
        assertEquals(
            "OTHER",
            BillImportMapping.categoryFor("腾讯公益慈善基金会", "交通银行信用卡").name,
        )
        assertEquals("TRANSFER", BillImportMapping.categoryFor("发给示例联系人").name)
    }

    @Test
    fun transferToPersonIsExcludedFromSpending() {
        val entity = BillImportMapping.toTransactionEntity(
            row(merchant = "发给示例联系人", amountCents = 666L, orderId = "TRANSFER-1"),
        )!!
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals(TransactionCategory.TRANSFER.name, entity.category)
        assertEquals("transfer", entity.remoteReviewStatus)
    }

    @Test
    fun personTransferUsesRawTransferHintEvenWhenMerchantIsJustAName() {
        val entity = BillImportMapping.toTransactionEntity(
            row(
                merchant = "张三",
                rawFields = listOf("转账", "已转账"),
                orderId = "TRANSFER-2",
            ),
        )!!
        assertEquals(TransactionCategory.TRANSFER.name, entity.category)
        assertEquals("transfer", entity.remoteReviewStatus)
    }

    @Test
    fun redPacketUsesRawHintButDiscountIsNotTransfer() {
        val redPacket = BillImportMapping.toTransactionEntity(
            row(
                merchant = "李四",
                rawFields = listOf("微信红包"),
                orderId = "RED-1",
            ),
        )!!
        assertEquals(TransactionCategory.TRANSFER.name, redPacket.category)
        assertEquals("transfer", redPacket.remoteReviewStatus)

        val discounted = BillImportMapping.toTransactionEntity(
            row(
                merchant = "星巴克",
                rawFields = listOf("红包抵扣", "优惠"),
                orderId = "COFFEE-1",
            ),
        )!!
        assertEquals(TransactionCategory.FOOD.name, discounted.category)
        assertEquals("unverified", discounted.remoteReviewStatus)

        val redPacketMention = BillImportMapping.toTransactionEntity(
            row(
                merchant = "星巴克",
                rawFields = listOf("红包"),
                orderId = "COFFEE-2",
            ),
        )!!
        assertEquals(TransactionCategory.FOOD.name, redPacketMention.category)
        assertEquals("unverified", redPacketMention.remoteReviewStatus)
    }

    @Test
    fun receivedRedPacketCountsAsIncomeInsteadOfTransfer() {
        val entity = BillImportMapping.toTransactionEntity(
            row(
                merchant = "示例联系人",
                amountCents = 888L,
                direction = BillDirection.INCOME,
                rawFields = listOf("微信红包"),
                orderId = "RED-IN-1",
            ),
        )!!
        assertEquals(TransactionType.INCOME.name, entity.type)
        assertEquals(TransactionCategory.OTHER.name, entity.category)
        assertEquals("unverified", entity.remoteReviewStatus)
        assertTrue(entity.note.contains("红包"))
    }

    @Test
    fun previewMarksFuzzyNotificationDuplicatesLikeConfirmDoes() {
        val notification = TransactionEntity(
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "微信支付",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_020_000L,
        )
        val preview = BillImportPreview(
            source = BillSource.WECHAT,
            format = BillFormat.XLSX,
            rows = listOf(
                BillPreviewRow(
                    kind = BillPreviewKind.VALID,
                    rowNumber = 3,
                    rawFields = emptyList(),
                    parsed = row(orderId = "ORDER-1"),
                ),
            ),
        )
        val marked = BillImportMapping.markExistingRows(
            preview = preview,
            transactions = listOf(notification),
            duplicateMessage = "dup",
        )
        assertEquals(BillPreviewKind.DUPLICATE, marked.rows.single().kind)
        assertEquals("dup", marked.rows.single().message)
        assertEquals(0, marked.validRows.size)
    }

    @Test
    fun fuzzyDuplicateCatchesNotificationWithoutOrderId() {
        val notification = TransactionEntity(
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "星巴克",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_030_000L,
        )
        assertTrue(
            BillImportMapping.isAlreadyPresent(
                row(orderId = null),
                listOf(notification),
            ),
        )
    }

    @Test
    fun orderIdImportCatchesNotificationWithFallbackMerchant() {
        val notification = TransactionEntity(
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "微信支付",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_020_000L,
        )
        assertTrue(
            BillImportMapping.isAlreadyPresent(
                row(orderId = "ORDER-1", merchant = "星巴克"),
                listOf(notification),
            ),
        )
    }

    @Test
    fun differentMerchantOrOrderIsNotTreatedAsDuplicate() {
        val notification = TransactionEntity(
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "另一家咖啡",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_030_000L,
        )
        assertFalse(
            BillImportMapping.isAlreadyPresent(
                row(orderId = null),
                listOf(notification),
            ),
        )
        val ordered = notification.copy(
            merchant = "星巴克",
            sourceTransactionId = "ORDER-2",
        )
        assertFalse(
            BillImportMapping.isAlreadyPresent(
                row(orderId = "ORDER-1"),
                listOf(ordered),
            ),
        )
    }
}
