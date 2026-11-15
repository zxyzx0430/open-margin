package com.bookkeeping.app.ui.screen.billimport

import com.bookkeeping.app.billimport.BillDirection
import com.bookkeeping.app.billimport.BillImportParser
import com.bookkeeping.app.billimport.BillImportRow
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
    fun rejectsFailedRefundPendingAndNeutralRows() {
        assertNull(BillImportMapping.toTransactionEntity(row(status = BillStatus.FAILED)))
        assertNull(BillImportMapping.toTransactionEntity(row(status = BillStatus.REFUND)))
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
    }

    @Test
    fun categoryRulesCoverRealWechatMerchants() {
        assertEquals(TransactionCategory.FOOD, BillImportMapping.categoryFor("星巴克"))
        assertEquals(TransactionCategory.FOOD, BillImportMapping.categoryFor("采蝶轩"))
        assertEquals(TransactionCategory.FOOD, BillImportMapping.categoryFor("蜜雪冰城"))
        assertEquals(TransactionCategory.FOOD, BillImportMapping.categoryFor("luckin coffee"))
        assertEquals(TransactionCategory.SHOPPING, BillImportMapping.categoryFor("禧迎迎生鲜超市(岭南店)"))
        assertEquals(TransactionCategory.SHOPPING, BillImportMapping.categoryFor("美宜佳便利店"))
        assertEquals(TransactionCategory.SHOPPING, BillImportMapping.categoryFor("岭南印象小店"))
        assertEquals(TransactionCategory.EDUCATION, BillImportMapping.categoryFor("五邑大学"))
        assertEquals(TransactionCategory.EDUCATION, BillImportMapping.categoryFor("墨墨背单词"))
        assertEquals(TransactionCategory.UTILITIES, BillImportMapping.categoryFor("中国电信"))
        assertEquals(TransactionCategory.UTILITIES, BillImportMapping.categoryFor("自来充"))
        assertEquals(TransactionCategory.TRANSPORT, BillImportMapping.categoryFor("P云停车平台"))
        assertEquals(TransactionCategory.TRANSPORT, BillImportMapping.categoryFor("广东联合电子服务股份有限公司"))
        assertEquals(TransactionCategory.HEALTH, BillImportMapping.categoryFor("国控国大(江门)医药有限公司"))
        assertEquals(TransactionCategory.ENTERTAINMENT, BillImportMapping.categoryFor("杭州游卡"))
        assertEquals(TransactionCategory.ENTERTAINMENT, BillImportMapping.categoryFor("哔哩哔哩"))
        assertEquals(TransactionCategory.TRANSFER, BillImportMapping.categoryFor("发给示例联系人"))
        assertEquals(TransactionCategory.OTHER, BillImportMapping.categoryFor("腾讯公益慈善基金会"))
    }

    @Test
    fun fallbackKeyMatchesOrderlessNotificationRecord() {
        val amount = 840L
        val timestamp = 1_764_000_000_000L
        val notification = TransactionEntity(
            amountCents = amount,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.SHOPPING.name,
            merchant = "拼多多平台商户",
            source = TransactionSource.WECHAT.name,
            timestamp = timestamp,
            sourceTransactionId = null,
        )
        val billRow = BillImportRow(
            source = BillSource.WECHAT,
            rowNumber = 17,
            occurredAtMs = timestamp,
            merchant = "拼多多平台商户",
            amountCents = amount,
            direction = BillDirection.EXPENSE,
            status = BillStatus.SUCCESS,
            orderId = "4200003208202611145734008044",
        )

        val keys = BillImportMapping.existingFallbackKeys(listOf(notification))
        val rowKey = BillImportMapping.rowFallbackKey(billRow)

        assertNotNull(rowKey)
        assertTrue(rowKey in keys)
    }

    @Test
    fun fallbackKeyDoesNotCollapseRowsThatAlreadyHaveOrderIds() {
        val imported = TransactionEntity(
            amountCents = 840L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.SHOPPING.name,
            merchant = "拼多多平台商户",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_764_000_000_000L,
            sourceTransactionId = "another-order-id",
        )

        assertTrue(BillImportMapping.existingFallbackKeys(listOf(imported)).isEmpty())
    }

    @Test
    fun neutralRowsHaveNoFallbackKey() {
        val neutral = BillImportRow(
            source = BillSource.WECHAT,
            rowNumber = 1,
            occurredAtMs = 1_764_000_000_000L,
            merchant = "示例银行(0000)",
            amountCents = 50_000L,
            direction = BillDirection.NEUTRAL,
            status = BillStatus.UNKNOWN,
        )
        assertNull(BillImportMapping.rowFallbackKey(neutral))
    }

    @Test
    fun merchantNormalizationIgnoresWhitespaceAndParentheticalSuffix() {
        val notification = TransactionEntity(
            amountCents = 100L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.OTHER.name,
            merchant = "示例联系人 (示例商户)",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_764_000_000_000L,
            sourceTransactionId = null,
        )
        val billRow = BillImportRow(
            source = BillSource.WECHAT,
            rowNumber = 1,
            occurredAtMs = 1_764_000_000_000L,
            merchant = "示例联系人(示例商户)",
            amountCents = 100L,
            direction = BillDirection.EXPENSE,
            status = BillStatus.SUCCESS,
        )
        val key = BillImportMapping.rowFallbackKey(billRow)
        assertNotNull(key)
        assertTrue(key in BillImportMapping.existingFallbackKeys(listOf(notification)))
    }

    @Test
    fun fallbackKeyRequiresSameDirectionAndAmount() {
        val notification = TransactionEntity(
            amountCents = 100L,
            type = TransactionType.INCOME.name,
            category = TransactionCategory.TRANSFER.name,
            merchant = "示例联系人",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_764_000_000_000L,
            sourceTransactionId = null,
        )
        val billRow = BillImportRow(
            source = BillSource.WECHAT,
            rowNumber = 1,
            occurredAtMs = 1_764_000_000_000L,
            merchant = "示例联系人",
            amountCents = 100L,
            direction = BillDirection.EXPENSE,
            status = BillStatus.SUCCESS,
        )
        val key = BillImportMapping.rowFallbackKey(billRow)
        assertNotNull(key)
        assertFalse(key in BillImportMapping.existingFallbackKeys(listOf(notification)))
    }
}
