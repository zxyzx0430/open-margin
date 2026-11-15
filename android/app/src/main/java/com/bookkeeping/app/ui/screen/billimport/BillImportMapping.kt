package com.bookkeeping.app.ui.screen.billimport

import com.bookkeeping.app.billimport.BillDirection
import com.bookkeeping.app.billimport.BillImportParser
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillImportRow
import com.bookkeeping.app.billimport.BillPreviewKind
import com.bookkeeping.app.billimport.BillSource
import com.bookkeeping.app.billimport.BillStatus
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import com.bookkeeping.app.notification.CategoryClassifier
import java.util.Locale
import kotlin.math.abs

/**
 * Pure mapping between official bill rows and the local ledger. The import
 * screen only writes rows after an explicit confirmation; duplicate keys use
 * the same normalized format as the parser.
 */
object BillImportMapping {
    private const val IMPORT_MATCH_WINDOW_MS = 120_000L
    private const val ORDERLESS_MATCH_WINDOW_MS = 30_000L
    private val GENERIC_PAYMENT_MERCHANTS = setOf("微信支付", "支付宝", "微信", "云闪付")
    private val TRANSFER_HINTS = listOf(
        "转账", "转入", "转出", "汇款", "提现", "发给", "微信红包", "信用卡还款",
    )

    fun ledgerRowKey(row: BillImportRow): String =
        BillImportParser.duplicateKey(row)

    fun existingRowKeys(transactions: List<TransactionEntity>): Set<String> =
        transactions.mapNotNull { transaction ->
            val direction = when (transaction.type) {
                TransactionType.EXPENSE.name -> BillDirection.EXPENSE
                TransactionType.INCOME.name -> BillDirection.INCOME
                else -> return@mapNotNull null
            }
            val source = when (transaction.source) {
                TransactionSource.WECHAT.name -> BillSource.WECHAT
                TransactionSource.ALIPAY.name -> BillSource.ALIPAY
                else -> BillSource.UNKNOWN
            }
            ledgerRowKey(
                BillImportRow(
                    source = source,
                    rowNumber = 0,
                    occurredAtMs = transaction.timestamp,
                    merchant = transaction.merchant,
                    amountCents = transaction.amountCents,
                    direction = direction,
                    currency = transaction.currency,
                    status = BillStatus.SUCCESS,
                    orderId = transaction.sourceTransactionId,
                ),
            )
        }.toSet()

    fun categoryFor(merchant: String, context: String = ""): TransactionCategory {
        // The official bill has a structured merchant column. Classify that
        // first; raw row fields often contain payment-method or order text
        // ("交通银行", "充值") that must not override a known merchant.
        val fromMerchant = CategoryClassifier.classify(merchant)
        if (fromMerchant != TransactionCategory.OTHER) return fromMerchant
        if (merchant.isBlank() || merchant in GENERIC_PAYMENT_MERCHANTS) {
            val fromContext = CategoryClassifier.classify("", context)
            if (fromContext != TransactionCategory.OTHER) return fromContext
        }
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

    /** True when an import row is already represented by the local ledger. */
    fun isAlreadyPresent(
        row: BillImportRow,
        transactions: List<TransactionEntity>,
        existingKeys: Set<String> = existingRowKeys(transactions),
    ): Boolean {
        if (ledgerRowKey(row) in existingKeys) return true
        val rowType = when (row.direction) {
            BillDirection.EXPENSE -> TransactionType.EXPENSE.name
            BillDirection.INCOME -> TransactionType.INCOME.name
            BillDirection.NEUTRAL ->
                if (isWithdrawal(row)) TransactionType.EXPENSE.name else return false
        }
        val rowSource = when (row.source) {
            BillSource.WECHAT -> TransactionSource.WECHAT.name
            BillSource.ALIPAY -> TransactionSource.ALIPAY.name
            BillSource.UNKNOWN -> null
        }
        val rowOrder = row.orderId?.trim()?.takeIf { it.isNotEmpty() }
        val rowMerchant = normalizeMerchant(row.merchant)
        return transactions.any { tx ->
            if (tx.amountCents != row.amountCents) return@any false
            if (tx.currency != row.currency) return@any false
            if (tx.type != rowType) return@any false
            if (rowSource != null && tx.source != rowSource) return@any false
            val txOrder = tx.sourceTransactionId?.trim()?.takeIf { it.isNotEmpty() }
            if (rowOrder != null && txOrder != null) return@any rowOrder == txOrder
            if (abs(tx.timestamp - row.occurredAtMs) > IMPORT_MATCH_WINDOW_MS) return@any false
            // A notification/page record may have no order ID while the official
            // import row does. At payment time these are the same event; do not
            // let a fallback merchant name defeat deduplication.
            if (rowOrder != null || txOrder != null) {
                return@any abs(tx.timestamp - row.occurredAtMs) <= ORDERLESS_MATCH_WINDOW_MS
            }
            val txMerchant = normalizeMerchant(tx.merchant)
            rowMerchant.isBlank() || txMerchant.isBlank() || rowMerchant == txMerchant
        }
    }

    private fun normalizeMerchant(value: String): String =
        value
            .replace(Regex("\\s+"), "")
            .replace(Regex("[（(][^）)]*[）)]"), "")
            .lowercase(Locale.ROOT)

    /**
     * Apply the same fuzzy duplicate rule used by confirm-time filtering to
     * the preview. Otherwise a notification without an order ID can show as
     * one importable row and then be dropped at confirm time.
     */
    fun markExistingRows(
        preview: BillImportPreview,
        transactions: List<TransactionEntity>,
        duplicateMessage: String,
    ): BillImportPreview {
        val existingKeys = existingRowKeys(transactions)
        return preview.copy(
            rows = preview.rows.map { row ->
                val value = row.parsed
                if (row.kind == BillPreviewKind.VALID && value != null &&
                    isAlreadyPresent(value, transactions, existingKeys)
                ) {
                    row.copy(kind = BillPreviewKind.DUPLICATE, message = duplicateMessage)
                } else {
                    row
                }
            },
        )
    }

    fun toTransactionEntity(row: BillImportRow): TransactionEntity? {
        if (row.amountCents <= 0L) return null
        if (row.status !in setOf(BillStatus.SUCCESS, BillStatus.REFUND)) return null
        val type = when (row.direction) {
            BillDirection.EXPENSE -> TransactionType.EXPENSE
            BillDirection.INCOME -> TransactionType.INCOME
            BillDirection.NEUTRAL -> if (isWithdrawal(row)) TransactionType.EXPENSE else return null
        }
        val refund = row.status == BillStatus.REFUND
        val withdrawal = row.direction == BillDirection.NEUTRAL && isWithdrawal(row)
        val transferRow = isTransferRow(row)
        val redPacketIncome = isRedPacketIncome(row)
        val source = when (row.source) {
            BillSource.WECHAT -> TransactionSource.WECHAT
            BillSource.ALIPAY -> TransactionSource.ALIPAY
            BillSource.UNKNOWN -> TransactionSource.OTHER
        }
        val context = row.rawFields.joinToString(" ")
        val merchantCategory = categoryFor(row.merchant)
        val category = when {
            redPacketIncome -> TransactionCategory.OTHER
            withdrawal -> TransactionCategory.TRANSFER
            transferRow && merchantCategory == TransactionCategory.OTHER ->
                TransactionCategory.TRANSFER
            else -> categoryFor(row.merchant, context)
        }
        val transfer = category == TransactionCategory.TRANSFER
        val note = buildString {
            append("[导入] ")
            append(
                when (row.source) {
                    BillSource.WECHAT -> "微信账单"
                    BillSource.ALIPAY -> "支付宝账单"
                    BillSource.UNKNOWN -> "账单"
                },
            )
            if (refund) append(" · 退款")
            if (withdrawal) append(" · 提现")
            if (redPacketIncome) append(" · 红包")
            append(" 第")
            append(row.rowNumber)
            append("行")
            row.orderId?.let { append(" · 单号 ").append(it) }
            row.paymentMethod?.let { append(" · ").append(it) }
        }
        return TransactionEntity(
            amountCents = row.amountCents,
            type = type.name,
            category = category.name,
            merchant = row.merchant,
            note = note,
            source = source.name,
            timestamp = row.occurredAtMs,
            currency = row.currency,
            merchantIconKey = MerchantIconCatalog.forMerchant(row.merchant)?.key,
            remoteReviewStatus = when {
                refund -> "refund"
                redPacketIncome -> "unverified"
                transfer -> "transfer"
                else -> "unverified"
            },
            sourceTransactionId = row.orderId?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** A received red packet is real income, not an account-to-account move. */
    fun isRedPacketIncome(row: BillImportRow): Boolean =
        row.direction == BillDirection.INCOME && isRedPacketMention(row)

    private fun isWithdrawal(row: BillImportRow): Boolean =
        (row.rawFields + row.merchant + row.paymentMethod.orEmpty())
            .any { it.contains("提现") }

    private fun isRedPacketMention(row: BillImportRow): Boolean {
        val text = (row.rawFields + row.merchant + row.paymentMethod.orEmpty()).joinToString(" ")
        return text.contains("红包") &&
            !text.contains("抵扣") &&
            !text.contains("优惠") &&
            !text.contains("封面")
    }

    private fun isTransferRow(row: BillImportRow): Boolean {
        val text = (row.rawFields + row.merchant + row.paymentMethod.orEmpty()).joinToString(" ")
        if (TRANSFER_HINTS.any { text.contains(it) }) return true
        return isRedPacketMention(row)
    }
}
