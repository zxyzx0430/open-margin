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
import com.bookkeeping.app.notification.ClassificationDirection
import com.bookkeeping.app.notification.ClassificationInput
import com.bookkeeping.app.notification.ClassificationKind
import com.bookkeeping.app.notification.ClassificationResult
import com.bookkeeping.app.notification.ClassificationSource
import com.bookkeeping.app.notification.ClassificationStatus
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
    private val STRONG_TRANSFER_HINTS = listOf(
        "转账", "转入", "转出", "汇款", "提现", "发给", "信用卡还款", "还款",
    )

    fun ledgerRowKey(row: BillImportRow): String =
        BillImportParser.duplicateKey(row)

    fun existingFallbackKeys(transactions: List<TransactionEntity>): Set<String> =
        transactions.mapNotNull { transaction ->
            if (transaction.syncDeleted || !transaction.sourceTransactionId.isNullOrBlank()) {
                return@mapNotNull null
            }
            val type = when (transaction.type) {
                TransactionType.EXPENSE.name -> TransactionType.EXPENSE
                TransactionType.INCOME.name -> TransactionType.INCOME
                else -> return@mapNotNull null
            }
            val source = billSource(transaction.source) ?: return@mapNotNull null
            fallbackKey(
                source = source,
                type = type,
                amountCents = transaction.amountCents,
                merchant = transaction.merchant,
                occurredAtMs = transaction.timestamp,
            )
        }.toSet()

    fun rowFallbackKey(row: BillImportRow): String? {
        val type = when (row.direction) {
            BillDirection.EXPENSE -> TransactionType.EXPENSE
            BillDirection.INCOME -> TransactionType.INCOME
            BillDirection.NEUTRAL -> return null
        }
        return fallbackKey(
            source = row.source,
            type = type,
            amountCents = row.amountCents,
            merchant = row.merchant,
            occurredAtMs = row.occurredAtMs,
        )
    }

    private fun fallbackKey(
        source: BillSource,
        type: TransactionType,
        amountCents: Long,
        merchant: String,
        occurredAtMs: Long,
    ): String = listOf(
        source.name,
        type.name,
        occurredAtMs.toString(),
        amountCents.toString(),
        normalizeMerchant(merchant),
    ).joinToString("|")

    private fun billSource(source: String): BillSource? = when (source) {
        TransactionSource.WECHAT.name -> BillSource.WECHAT
        TransactionSource.ALIPAY.name -> BillSource.ALIPAY
        else -> null
    }

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
    ): Boolean = findExistingMatch(row, transactions, existingKeys) != null

    /**
     * Return the existing ledger row for an import row. Order ID wins; without
     * one, amount/currency/source and a tight time window are required. Neutral
     * rows may match either direction because "不计收支" does not tell us which
     * side of the ledger the existing notification used.
     */
    fun findExistingMatch(
        row: BillImportRow,
        transactions: List<TransactionEntity>,
        existingKeys: Set<String> = existingRowKeys(transactions),
    ): TransactionEntity? {
        val rowOrder = row.orderId?.trim()?.takeIf { it.isNotEmpty() }
        val rowSource = when (row.source) {
            BillSource.WECHAT -> TransactionSource.WECHAT.name
            BillSource.ALIPAY -> TransactionSource.ALIPAY.name
            BillSource.UNKNOWN -> null
        }
        if (rowOrder != null && rowSource != null) {
            transactions.firstOrNull { tx ->
                !tx.syncDeleted &&
                    tx.source == rowSource &&
                    tx.sourceTransactionId?.trim() == rowOrder
            }?.let { return it }
        }

        val rowType = when (row.direction) {
            BillDirection.EXPENSE -> TransactionType.EXPENSE.name
            BillDirection.INCOME -> TransactionType.INCOME.name
            BillDirection.NEUTRAL -> null
        }
        val rowMerchant = normalizeMerchant(row.merchant)
        return transactions.firstOrNull { tx ->
            if (tx.syncDeleted) return@firstOrNull false
            if (tx.amountCents != row.amountCents) return@firstOrNull false
            if (tx.currency != row.currency) return@firstOrNull false
            if (rowSource != null && tx.source != rowSource) return@firstOrNull false
            if (rowType != null && tx.type != rowType) return@firstOrNull false
            val txOrder = tx.sourceTransactionId?.trim()?.takeIf { it.isNotEmpty() }
            if (rowOrder != null && txOrder != null) return@firstOrNull rowOrder == txOrder
            if (abs(tx.timestamp - row.occurredAtMs) > IMPORT_MATCH_WINDOW_MS) {
                return@firstOrNull false
            }
            // A notification/page record may have no order ID while the official
            // import row does. At payment time these are the same event; do not
            // let a fallback merchant name defeat deduplication.
            if (rowOrder != null || txOrder != null) {
                return@firstOrNull abs(tx.timestamp - row.occurredAtMs) <= ORDERLESS_MATCH_WINDOW_MS
            }
            val txMerchant = normalizeMerchant(tx.merchant)
            rowMerchant.isBlank() || txMerchant.isBlank() || rowMerchant == txMerchant
        }
    }

    /**
     * Fill gaps on an auto-captured row from the official bill. Never touches
     * manual rows, never overwrites an existing merchant/category, and never
     * changes amount, timestamp or direction.
     */
    fun enrichExisting(
        transaction: TransactionEntity,
        row: BillImportRow,
    ): TransactionEntity? {
        if (transaction.syncDeleted) return null
        if (transaction.source == TransactionSource.MANUAL.name) return null
        if (transaction.duplicateOfId != null) return null

        val merchant = if (isWeakMerchant(transaction.merchant) && !isWeakMerchant(row.merchant)) {
            row.merchant
        } else {
            transaction.merchant
        }
        val orderId = transaction.sourceTransactionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: row.orderId?.trim()?.takeIf { it.isNotEmpty() }
        val importedCategory = categoryFor(row.merchant, row.rawFields.joinToString(" "))
        val category = when {
            row.direction == BillDirection.NEUTRAL &&
                transaction.category == TransactionCategory.OTHER.name ->
                TransactionCategory.TRANSFER.name
            transaction.category == TransactionCategory.OTHER.name &&
                importedCategory != TransactionCategory.OTHER ->
                importedCategory.name
            else -> transaction.category
        }
        val note = enrichedNote(transaction.note, row)
        val icon = if (merchant != transaction.merchant) {
            MerchantIconCatalog.forMerchant(merchant)?.key ?: transaction.merchantIconKey
        } else {
            transaction.merchantIconKey
        }
        if (merchant == transaction.merchant &&
            orderId == transaction.sourceTransactionId &&
            category == transaction.category &&
            note == transaction.note &&
            icon == transaction.merchantIconKey
        ) {
            return null
        }
        return transaction.copy(
            merchant = merchant,
            sourceTransactionId = orderId,
            category = category,
            note = note,
            merchantIconKey = icon,
        )
    }

    private fun enrichedNote(existingNote: String, row: BillImportRow): String {
        if (existingNote.isBlank()) {
            return toTransactionEntity(row)?.note.orEmpty()
        }
        val additions = buildList {
            row.orderId?.trim()?.takeIf { it.isNotEmpty() && !existingNote.contains(it) }
                ?.let { add("单号 $it") }
            row.paymentMethod?.trim()?.takeIf { it.isNotEmpty() && !existingNote.contains(it) }
                ?.let(::add)
            row.sourceCategory?.trim()?.takeIf { it.isNotEmpty() && !existingNote.contains(it) }
                ?.let { add("分类 $it") }
        }
        if (additions.isEmpty()) return existingNote
        return (existingNote + " · 导入补齐：" + additions.joinToString(" · ")).take(500)
    }

    private fun isWeakMerchant(value: String): Boolean {
        val normalized = normalizeMerchant(value)
        return normalized.isBlank() ||
            normalized in GENERIC_PAYMENT_MERCHANTS ||
            value.contains("[扫描]") ||
            normalized == "未知商户" ||
            normalized == "其他" ||
            normalized == "账单" ||
            normalized == "页面"
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
                if (value == null ||
                    row.kind !in setOf(BillPreviewKind.VALID, BillPreviewKind.DUPLICATE) ||
                    row.duplicateOfRow != null
                ) {
                    return@map row
                }
                val existing = findExistingMatch(value, transactions, existingKeys)
                    ?: return@map row
                val enriched = enrichExisting(existing, value)
                row.copy(
                    kind = BillPreviewKind.DUPLICATE,
                    message = if (enriched != null) "$duplicateMessage，可补齐" else duplicateMessage,
                    enrichable = enriched != null,
                    matchedTransactionId = existing.id,
                )
            },
        )
    }

    fun classificationFor(row: BillImportRow): ClassificationResult {
        val merchantCategory = categoryFor(row.merchant)
        val direction = when (row.direction) {
            BillDirection.EXPENSE -> ClassificationDirection.EXPENSE
            BillDirection.INCOME -> ClassificationDirection.INCOME
            BillDirection.NEUTRAL -> ClassificationDirection.NEUTRAL
        }
        val kind = when {
            row.status == BillStatus.REFUND -> ClassificationKind.REFUND
            row.direction == BillDirection.NEUTRAL && isWithdrawal(row) -> ClassificationKind.WITHDRAWAL
            row.direction == BillDirection.NEUTRAL -> ClassificationKind.TRANSFER
            isStrongTransferRow(row) -> ClassificationKind.TRANSFER
            isRedPacketMention(row) &&
                (row.direction == BillDirection.INCOME ||
                    merchantCategory == TransactionCategory.OTHER) -> ClassificationKind.RED_PACKET
            else -> ClassificationKind.PAYMENT
        }
        val source = when (row.source) {
            BillSource.WECHAT -> ClassificationSource.WECHAT
            BillSource.ALIPAY -> ClassificationSource.ALIPAY
            BillSource.UNKNOWN -> ClassificationSource.OTHER
        }
        val status = when (row.status) {
            BillStatus.SUCCESS -> ClassificationStatus.COMPLETED
            BillStatus.REFUND -> ClassificationStatus.COMPLETED
            BillStatus.PENDING -> ClassificationStatus.PENDING
            BillStatus.FAILED -> ClassificationStatus.FAILED
            BillStatus.UNKNOWN -> ClassificationStatus.UNKNOWN
        }
        return CategoryClassifier.classify(
            ClassificationInput(
                merchantRaw = row.merchant,
                contextText = row.rawFields.joinToString(" "),
                direction = direction,
                source = source,
                kind = kind,
                status = status,
                sourceCategory = row.sourceCategory,
                amountCents = row.amountCents,
            ),
        )
    }

    fun toTransactionEntity(row: BillImportRow): TransactionEntity? {
        if (row.amountCents <= 0L) return null
        if (row.status !in setOf(BillStatus.SUCCESS, BillStatus.REFUND)) return null
        val neutral = row.direction == BillDirection.NEUTRAL
        val refund = row.status == BillStatus.REFUND
        val type = when {
            neutral && refund -> TransactionType.INCOME
            row.direction == BillDirection.INCOME -> TransactionType.INCOME
            row.direction == BillDirection.EXPENSE -> TransactionType.EXPENSE
            neutral -> TransactionType.EXPENSE
            else -> TransactionType.EXPENSE
        }
        val withdrawal = neutral && isWithdrawal(row)
        val transferRow = isTransferRow(row)
        val strongTransfer = isStrongTransferRow(row)
        val redPacketIncome = isRedPacketIncome(row)
        val redPacketExpense = row.direction == BillDirection.EXPENSE && isRedPacketMention(row)
        val source = when (row.source) {
            BillSource.WECHAT -> TransactionSource.WECHAT
            BillSource.ALIPAY -> TransactionSource.ALIPAY
            BillSource.UNKNOWN -> TransactionSource.OTHER
        }
        val merchantCategory = categoryFor(row.merchant)
        val category = when {
            // Alipay "不计收支" is an account-to-account move (investment,
            // repayment, withdrawal, red packet, ...). Keep it visible but out
            // of spending/income totals; do not guess a consumption category.
            neutral -> TransactionCategory.TRANSFER
            redPacketIncome -> TransactionCategory.GIFT
            withdrawal || strongTransfer -> TransactionCategory.TRANSFER
            (redPacketExpense || transferRow) &&
                merchantCategory == TransactionCategory.OTHER -> TransactionCategory.TRANSFER
            else -> classificationFor(row).category
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
            if (neutral) append(" · 不计收支")
            if (withdrawal) append(" · 提现")
            if (redPacketIncome) append(" · 红包")
            append(" 第")
            append(row.rowNumber)
            append("行")
            row.orderId?.let { append(" · 单号 ").append(it) }
            row.paymentMethod?.let { append(" · ").append(it) }
            row.sourceCategory?.let { append(" · 分类 ").append(it) }
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

    private fun isStrongTransferRow(row: BillImportRow): Boolean {
        val text = (row.rawFields + row.merchant + row.paymentMethod.orEmpty()).joinToString(" ")
        return STRONG_TRANSFER_HINTS.any { text.contains(it) }
    }
}
