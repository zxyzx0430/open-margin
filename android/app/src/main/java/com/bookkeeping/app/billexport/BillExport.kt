package com.bookkeeping.app.billexport

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure Kotlin CSV formatting for exporting the local ledger. */
internal object BillExport {
    val headers = listOf("时间", "类型", "金额（元）", "分类", "商户", "账户", "来源", "备注")

    data class Record(
        val timestampMs: Long,
        val type: String,
        val amountCents: Long,
        val category: String,
        val merchant: String,
        val account: String?,
        val source: String,
        val note: String,
    )

    private val typeLabels = mapOf("EXPENSE" to "支出", "INCOME" to "收入")
    private val categoryLabels = mapOf(
        "FOOD" to "餐饮",
        "TRANSPORT" to "交通",
        "SHOPPING" to "购物",
        "ENTERTAINMENT" to "娱乐",
        "HOUSING" to "住房",
        "UTILITIES" to "水电",
        "HEALTH" to "医疗",
        "EDUCATION" to "教育",
        "SALARY" to "工资",
        "BONUS" to "奖金",
        "INVESTMENT" to "理财",
        "INSURANCE" to "保险",
        "GIFT" to "红包",
        "TRANSFER" to "转账",
        "OTHER" to "其他",
    )
    private val sourceLabels = mapOf(
        "MANUAL" to "手动",
        "ALIPAY" to "支付宝",
        "WECHAT" to "微信",
        "BANK_SMS" to "银行短信",
        "OTHER" to "其他来源",
    )

    fun rows(records: Iterable<Record>, timeZone: TimeZone = TimeZone.getDefault()): List<List<String>> {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            this.timeZone = timeZone
        }
        return records.map { record ->
            listOf(
                timeFormat.format(Date(record.timestampMs)),
                typeLabels[record.type] ?: record.type,
                formatAmount(record.amountCents),
                categoryLabels[record.category] ?: record.category,
                record.merchant,
                record.account.orEmpty(),
                sourceLabels[record.source] ?: record.source,
                record.note,
            )
        }
    }

    /** Returns a UTF-8 CSV string with BOM, Chinese headers, and RFC 4180 row endings. */
    fun csv(records: Iterable<Record>, timeZone: TimeZone = TimeZone.getDefault()): String = buildString {
        append('\uFEFF')
        append(headers.joinToString(",", transform = ::escapeCsv))
        append("\r\n")
        rows(records, timeZone).forEach { row ->
            append(row.joinToString(",", transform = ::escapeCsv))
            append("\r\n")
        }
    }

    fun csvBytes(records: Iterable<Record>, timeZone: TimeZone = TimeZone.getDefault()): ByteArray =
        csv(records, timeZone).toByteArray(Charsets.UTF_8)

    fun escapeCsv(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        return if (needsQuotes) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    /** Integer cents converted without floating point and without locale-specific grouping. */
    fun formatAmount(amountCents: Long): String = BigDecimal.valueOf(amountCents, 2).toPlainString()

    fun defaultFileName(date: Date = Date(), timeZone: TimeZone = TimeZone.getDefault()): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply {
            this.timeZone = timeZone
        }
        return "清衡账单-${dateFormat.format(date)}.csv"
    }
}
