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
import com.bookkeeping.app.data.merchant.MerchantIconCatalog

/**
 * Pure mapping between official bill rows and the local ledger. The import
 * screen only writes rows after an explicit confirmation; duplicate keys use
 * the same normalized format as the parser.
 */
object BillImportMapping {

    fun ledgerRowKey(row: BillImportRow): String =
        BillImportParser.duplicateKey(row.copy(orderId = null))

    fun existingRowKeys(transactions: List<TransactionEntity>): Set<String> =
        transactions.mapNotNull { transaction ->
            val direction = when (transaction.type) {
                TransactionType.EXPENSE.name -> BillDirection.EXPENSE
                TransactionType.INCOME.name -> BillDirection.INCOME
                else -> return@mapNotNull null
            }
            ledgerRowKey(
                BillImportRow(
                    source = BillSource.UNKNOWN,
                    rowNumber = 0,
                    occurredAtMs = transaction.timestamp,
                    merchant = transaction.merchant,
                    amountCents = transaction.amountCents,
                    direction = direction,
                    currency = transaction.currency,
                    status = BillStatus.SUCCESS,
                ),
            )
        }.toSet()

    fun categoryFor(merchant: String): TransactionCategory {
        val icon = MerchantIconCatalog.forMerchant(merchant)?.systemIcon
        return when (icon) {
            "coffee", "restaurant" -> TransactionCategory.FOOD
            "store", "shopping" -> TransactionCategory.SHOPPING
            "transport" -> TransactionCategory.TRANSPORT
            "transfer" -> TransactionCategory.TRANSFER
            "salary" -> TransactionCategory.SALARY
            "home" -> TransactionCategory.HOUSING
            "bolt" -> TransactionCategory.UTILITIES
            "health" -> TransactionCategory.HEALTH
            "education" -> TransactionCategory.EDUCATION
            else -> TransactionCategory.OTHER
        }
    }

    fun toTransactionEntity(row: BillImportRow): TransactionEntity? {
        if (row.amountCents <= 0L) return null
        if (row.status != BillStatus.SUCCESS) return null
        val type = when (row.direction) {
            BillDirection.EXPENSE -> TransactionType.EXPENSE
            BillDirection.INCOME -> TransactionType.INCOME
            BillDirection.NEUTRAL -> return null
        }
        val source = when (row.source) {
            BillSource.WECHAT -> TransactionSource.WECHAT
            BillSource.ALIPAY -> TransactionSource.ALIPAY
            BillSource.UNKNOWN -> TransactionSource.OTHER
        }
        val note = buildString {
            append("[导入] ")
            append(
                when (row.source) {
                    BillSource.WECHAT -> "微信账单"
                    BillSource.ALIPAY -> "支付宝账单"
                    BillSource.UNKNOWN -> "账单"
                },
            )
            append(" 第")
            append(row.rowNumber)
            append("行")
            row.orderId?.let { append(" · 单号 ").append(it) }
            row.paymentMethod?.let { append(" · ").append(it) }
        }
        return TransactionEntity(
            amountCents = row.amountCents,
            type = type.name,
            category = categoryFor(row.merchant).name,
            merchant = row.merchant,
            note = note,
            source = source.name,
            timestamp = row.occurredAtMs,
            currency = row.currency,
            merchantIconKey = MerchantIconCatalog.forMerchant(row.merchant)?.key,
        )
    }
}
