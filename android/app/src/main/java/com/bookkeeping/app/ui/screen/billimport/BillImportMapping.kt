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
import java.util.Locale

/**
 * Pure mapping between official bill rows and the local ledger. The import
 * screen only writes rows after an explicit confirmation; duplicate keys use
 * the same normalized format as the parser.
 */
object BillImportMapping {

    fun ledgerRowKey(row: BillImportRow): String =
        BillImportParser.duplicateKey(row)

    /**
     * Secondary key for rows whose provider supplies an order ID while the
     * existing notification/page record does not. It intentionally excludes
     * rows that already have an order ID: those must be matched by order ID so
     * two genuinely different purchases with the same amount/time/merchant are
     * not collapsed.
     */
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

    private fun normalizeMerchant(value: String): String =
        value
            .replace(Regex("\\s+"), "")
            .replace(Regex("[（(][^）)]*[）)]"), "")
            .lowercase(Locale.ROOT)

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

    fun categoryFor(merchant: String): TransactionCategory {
        val icon = MerchantIconCatalog.forMerchant(merchant)?.systemIcon
        val catalogCategory = when (icon) {
            "coffee", "restaurant" -> TransactionCategory.FOOD
            "store", "shopping" -> TransactionCategory.SHOPPING
            "transport" -> TransactionCategory.TRANSPORT
            "transfer" -> TransactionCategory.TRANSFER
            "salary" -> TransactionCategory.SALARY
            "home" -> TransactionCategory.HOUSING
            "bolt" -> TransactionCategory.UTILITIES
            "health" -> TransactionCategory.HEALTH
            "education" -> TransactionCategory.EDUCATION
            else -> null
        }
        if (catalogCategory != null) return catalogCategory

        val text = merchant.trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return TransactionCategory.OTHER
        return when {
            text.containsAny("发给", "转账", "红包", "提现", "零钱", "银行") ->
                TransactionCategory.TRANSFER
            text.containsAny("大学", "学院", "学校", "学费", "背单词", "书店", "教育", "培训") ->
                TransactionCategory.EDUCATION
            text.containsAny("电信", "移动", "联通", "自来水", "自来充", "水费", "电费", "燃气", "话费", "宽带", "充电") ->
                TransactionCategory.UTILITIES
            text.containsAny("停车", "etc", "联合电子", "高速", "加油", "地铁", "公交", "打车", "滴滴", "铁路", "航空", "机场") ->
                TransactionCategory.TRANSPORT
            text.containsAny("医院", "药房", "医药", "药店", "口腔", "诊所", "体检") ->
                TransactionCategory.HEALTH
            text.containsAny("游卡", "游戏", "哔哩", "b站", "电影", "影城", "ktv", "网吧", "娱乐") ->
                TransactionCategory.ENTERTAINMENT
            text.containsAny(
                "餐厅", "餐饮", "食堂", "面包", "烘焙", "采蝶轩", "蜜雪", "咖啡", "coffee",
                "luckin", "瑞幸", "奶茶", "茶饮", "烤鸡", "炸鸡", "鸡腿", "炒饭", "米线",
                "羊肉粉", "水果", "零食", "赵一鸣", "烧柠", "饭店", "小吃", "早餐", "早点",
                "面馆", "火锅", "烧烤",
            ) -> TransactionCategory.FOOD
            text.containsAny("超市", "生鲜", "百货", "商场", "便利店", "美宜佳", "宠物用品", "印象小店") ->
                TransactionCategory.SHOPPING
            else -> TransactionCategory.OTHER
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }


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
            sourceTransactionId = row.orderId?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
