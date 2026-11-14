package com.bookkeeping.app.ui.screen.billimport

import com.bookkeeping.app.billimport.BillDirection
import com.bookkeeping.app.billimport.BillImportParser
import com.bookkeeping.app.billimport.BillImportRow
import com.bookkeeping.app.billimport.BillSource
import com.bookkeeping.app.billimport.BillStatus
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
}
