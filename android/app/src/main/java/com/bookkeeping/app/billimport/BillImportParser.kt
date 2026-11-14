package com.bookkeeping.app.billimport

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Parses WeChat Pay / Alipay bill exports into a preview. It never writes to the
 * ledger; callers must show [BillImportPreview] and import only after explicit
 * user confirmation.
 */
object BillImportParser {

    private const val UTF8_BOM = "﻿"

    private val HEADER_ALIASES: Map<String, Set<String>> = mapOf(
        "time" to setOf(
            "交易时间", "交易创建时间", "付款时间", "创建时间", "时间",
            "transactiontime", "time", "date",
        ),
        "merchant" to setOf(
            "交易对方", "商户名称", "对方", "收款方", "商户",
            "merchant", "counterparty", "payee",
        ),
        "amount" to setOf(
            "金额", "交易金额", "发生金额", "金额元",
            "amount",
        ),
        "direction" to setOf(
            "收/支", "收支", "收/付款", "资金方向", "方向",
            "direction", "incomeexpense",
        ),
        "status" to setOf(
            "当前状态", "交易状态", "状态", "交易状态说明",
            "status",
        ),
        "orderId" to setOf(
            "交易单号", "交易订单号", "订单号", "商户单号", "商家订单号",
            "transactionid", "orderid", "orderno",
        ),
        "payment" to setOf(
            "支付方式", "收/付款方式", "付款方式", "收付款方式",
            "paymentmethod", "payment",
        ),
    )

    fun parse(
        bytes: ByteArray,
        fileName: String? = null,
        options: BillParserOptions = BillParserOptions(),
    ): BillImportPreview {
        val lowerName = fileName?.lowercase(Locale.ROOT).orEmpty()
        val xlsx = lowerName.endsWith(".xlsx") || isZip(bytes)
        if (xlsx) {
            val source = detectSource(fileName, emptyList())
            return try {
                val rows = XlsxReader.readRows(bytes, options.maxRows)
                buildPreview(rows, fileName, BillFormat.XLSX, options)
            } catch (failure: Exception) {
                errorPreview(
                    source = source,
                    format = BillFormat.XLSX,
                    message = "无法读取 XLSX：${failure.message ?: failure.javaClass.simpleName}",
                )
            }
        }
        return parseText(decode(bytes), fileName, options)
    }

    fun parseText(
        text: String,
        fileName: String? = null,
        options: BillParserOptions = BillParserOptions(),
    ): BillImportPreview {
        val delimiter = CsvParser.detectDelimiter(text)
        val lowerName = fileName?.lowercase(Locale.ROOT).orEmpty()
        val format = when {
            lowerName.endsWith(".txt") -> BillFormat.TEXT
            lowerName.endsWith(".tsv") || delimiter == '\t' -> BillFormat.TSV
            else -> BillFormat.CSV
        }
        val rows = CsvParser.parse(text, delimiter)
        return buildPreview(rows, fileName, format, options)
    }

    /** Stable key used both for in-file duplicates and for checking the ledger. */
    fun duplicateKey(row: BillImportRow): String {
        val order = row.orderId?.trim().orEmpty()
        return if (order.isNotEmpty()) {
            "order:$order"
        } else {
            "row:${row.occurredAtMs}|${row.amountCents}|${row.direction.name}|${normalizeMerchant(row.merchant)}"
        }
    }

    private fun buildPreview(
        rows: List<List<String>>,
        fileName: String?,
        format: BillFormat,
        options: BillParserOptions,
    ): BillImportPreview {
        val header = findHeader(rows)
            ?: return errorPreview(
                source = detectSource(fileName, emptyList()),
                format = format,
                message = "未找到账单表头（需要交易时间、金额、商户/收支列）",
            )

        val source = detectSource(fileName, rows[header.rowIndex])
        val columns = header.columns
        val previewRows = mutableListOf<BillPreviewRow>()
        val seen = mutableMapOf<String, Int>()
        var dataRows = 0

        for (index in header.rowIndex + 1 until rows.size) {
            if (dataRows >= options.maxRows) {
                previewRows += BillPreviewRow(
                    kind = BillPreviewKind.ERROR,
                    rowNumber = index + 1,
                    rawFields = emptyList(),
                    message = "超过最大行数 ${options.maxRows}，已停止解析",
                )
                break
            }
            val raw = rows[index]
            if (raw.all { it.isBlank() }) continue
            dataRows++

            val rowNumber = index + 1
            val timeRaw = value(raw, columns.time)
            val merchantRaw = value(raw, columns.merchant)
            val amountRaw = value(raw, columns.amount)
            val directionRaw = value(raw, columns.direction)
            val statusRaw = value(raw, columns.status)
            val orderId = value(raw, columns.orderId).takeIf { it.isNotBlank() }
            val payment = value(raw, columns.payment).takeIf { it.isNotBlank() }

            val occurredAt = parseTime(timeRaw, options.timeZoneId)
            val amount = parseAmount(amountRaw)

            // WeChat/Alipay exports end with a "共 N 笔 / 合计" summary row.
            // Only skip it when it has no valid transaction time or amount.
            if (isSummaryRow(raw) && (occurredAt == null || amount == null)) continue

            val merchant = merchantRaw.trim()
            val direction = parseDirection(directionRaw, amount?.negative == true)
            val status = parseStatus(statusRaw)

            val parsed = if (occurredAt != null && amount != null) {
                BillImportRow(
                    source = source,
                    rowNumber = rowNumber,
                    occurredAtMs = occurredAt,
                    merchant = merchant.ifBlank { "未知商户" },
                    amountCents = amount.cents,
                    direction = direction,
                    status = status,
                    orderId = orderId,
                    paymentMethod = payment,
                    rawFields = raw,
                )
            } else {
                null
            }

            val error = when {
                timeRaw.isBlank() -> "交易时间为空"
                occurredAt == null -> "无法解析交易时间：$timeRaw"
                amountRaw.isBlank() -> "金额为空"
                amount == null || amount.cents <= 0L -> "无法解析金额：$amountRaw"
                merchant.isBlank() -> "商户为空，需人工确认"
                direction == BillDirection.NEUTRAL -> "不计收支，需人工确认"
                status != BillStatus.SUCCESS -> "状态需人工确认：${statusRaw.ifBlank { status.name }}"
                else -> null
            }
            if (error != null) {
                previewRows += BillPreviewRow(
                    kind = BillPreviewKind.ERROR,
                    rowNumber = rowNumber,
                    rawFields = raw,
                    parsed = parsed,
                    message = error,
                )
                continue
            }

            val valid = parsed ?: continue
            val key = duplicateKey(valid)
            val existing = options.existingDuplicateKeys.contains(key)
            val duplicateOf = seen[key]
            when {
                existing -> previewRows += BillPreviewRow(
                    kind = BillPreviewKind.DUPLICATE,
                    rowNumber = rowNumber,
                    rawFields = raw,
                    parsed = valid,
                    message = "账本中已存在相同账单",
                )
                duplicateOf != null -> previewRows += BillPreviewRow(
                    kind = BillPreviewKind.DUPLICATE,
                    rowNumber = rowNumber,
                    rawFields = raw,
                    parsed = valid,
                    message = "文件内重复，首次出现在第 $duplicateOf 行",
                    duplicateOfRow = duplicateOf,
                )
                else -> {
                    seen[key] = rowNumber
                    previewRows += BillPreviewRow(
                        kind = BillPreviewKind.VALID,
                        rowNumber = rowNumber,
                        rawFields = raw,
                        parsed = valid,
                    )
                }
            }
        }

        if (previewRows.none { it.kind == BillPreviewKind.VALID } &&
            previewRows.none { it.kind == BillPreviewKind.DUPLICATE }
        ) {
            previewRows += BillPreviewRow(
                kind = BillPreviewKind.ERROR,
                rowNumber = header.rowIndex + 1,
                rawFields = emptyList(),
                message = "没有可导入的数据行",
            )
        }
        return BillImportPreview(source, format, previewRows)
    }

    private data class ColumnMap(
        val time: Int,
        val merchant: Int,
        val amount: Int,
        val direction: Int,
        val status: Int,
        val orderId: Int,
        val payment: Int,
    )

    private data class HeaderMatch(val rowIndex: Int, val columns: ColumnMap, val score: Int)

    private fun findHeader(rows: List<List<String>>): HeaderMatch? {
        var best: HeaderMatch? = null
        for (index in rows.indices) {
            val row = rows[index]
            if (row.isEmpty()) continue
            val columns = ColumnMap(
                time = findColumn(row, HEADER_ALIASES.getValue("time")),
                merchant = findColumn(row, HEADER_ALIASES.getValue("merchant")),
                amount = findColumn(row, HEADER_ALIASES.getValue("amount")),
                direction = findColumn(row, HEADER_ALIASES.getValue("direction")),
                status = findColumn(row, HEADER_ALIASES.getValue("status")),
                orderId = findColumn(row, HEADER_ALIASES.getValue("orderId")),
                payment = findColumn(row, HEADER_ALIASES.getValue("payment")),
            )
            if (columns.time < 0 || columns.amount < 0) continue
            val score = listOf(columns.time, columns.merchant, columns.amount, columns.direction, columns.status)
                .count { it >= 0 }
            if (score < 3) continue
            if (best == null || score > best.score) {
                best = HeaderMatch(index, columns, score)
            }
        }
        return best
    }

    private fun findColumn(row: List<String>, aliases: Set<String>): Int {
        for (index in row.indices) {
            if (normalizeHeader(row[index]) in aliases) return index
        }
        return -1
    }

    private fun value(row: List<String>, index: Int): String =
        if (index < 0) "" else row.getOrNull(index)?.trim().orEmpty()

    private fun detectSource(fileName: String?, headerRow: List<String>): BillSource {
        val name = fileName.orEmpty()
        val lower = name.lowercase(Locale.ROOT)
        if ("wechat" in lower || "微信" in name) return BillSource.WECHAT
        if ("alipay" in lower || "支付宝" in name) return BillSource.ALIPAY
        val headers = headerRow.map(::normalizeHeader).toSet()
        if ("商户单号" in headers) return BillSource.WECHAT
        if ("商家订单号" in headers || "交易订单号" in headers || "交易分类" in headers) {
            return BillSource.ALIPAY
        }
        return BillSource.UNKNOWN
    }

    private fun errorPreview(source: BillSource, format: BillFormat, message: String): BillImportPreview =
        BillImportPreview(
            source = source,
            format = format,
            rows = listOf(
                BillPreviewRow(
                    kind = BillPreviewKind.ERROR,
                    rowNumber = 1,
                    rawFields = emptyList(),
                    message = message,
                ),
            ),
        )

    private fun decode(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) 3 else 0
        val payload = if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { utf8.decode(ByteBuffer.wrap(payload)).toString() }
            .getOrElse {
                runCatching { String(payload, Charset.forName("GBK")) }
                    .getOrElse { String(payload, Charsets.UTF_8) }
            }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    private data class AmountParse(val cents: Long, val negative: Boolean)

    private fun parseAmount(raw: String): AmountParse? {
        var text = normalizeNumericText(raw).trim()
        if (text.isEmpty()) return null
        var negative = false
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true
            text = text.substring(1, text.length - 1)
        }
        text = text
            .replace("¥", "")
            .replace("￥", "")
            .replace("元", "")
            .replace(",", "")
            .replace("，", "")
            .replace("CNY", "", ignoreCase = true)
            .replace("RMB", "", ignoreCase = true)
            .trim()
        if (text.startsWith("-")) {
            negative = !negative
            text = text.removePrefix("-")
        } else if (text.startsWith("+")) {
            text = text.removePrefix("+")
        }
        var value = text.toBigDecimalOrNull() ?: return null
        if (value < BigDecimal.ZERO) {
            negative = !negative
            value = value.abs()
        }
        if (value == BigDecimal.ZERO) return null
        return try {
            val cents = value.setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
            if (cents > 0L) AmountParse(cents, negative) else null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun parseDirection(raw: String, negative: Boolean): BillDirection {
        val text = raw.trim()
        return when {
            text.contains("不计") -> BillDirection.NEUTRAL
            text.contains("支出") || text.contains("付款") || text == "支" -> BillDirection.EXPENSE
            text.contains("收入") || text.contains("收款") || text == "收" -> BillDirection.INCOME
            text.isBlank() && negative -> BillDirection.EXPENSE
            text.isBlank() -> BillDirection.INCOME
            else -> BillDirection.NEUTRAL
        }
    }

    private fun parseStatus(raw: String): BillStatus {
        val text = raw.trim()
        return when {
            text.contains("退款") -> BillStatus.REFUND
            text.contains("失败") || text.contains("关闭") || text.contains("取消") -> BillStatus.FAILED
            text.contains("待") || text.contains("处理中") || text.contains("等待") -> BillStatus.PENDING
            text.contains("成功") || text.contains("完成") || text.contains("收款") -> BillStatus.SUCCESS
            else -> BillStatus.UNKNOWN
        }
    }

    private fun parseTime(raw: String, timeZoneId: String): Long? {
        val text = normalizeNumericText(raw).trim()
        if (text.isEmpty()) return null
        text.toBigDecimalOrNull()
            ?.takeIf { it >= BigDecimal.valueOf(20_000L) && it <= BigDecimal.valueOf(80_000L) }
            ?.let { return excelSerialToMillis(it, timeZoneId) }
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-M-d H:mm:ss",
            "yyyy/M/d H:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy-M-d H:mm",
            "yyyy/M/d H:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "yyyy/MM/dd",
            "yyyy年M月d日 HH:mm:ss",
            "yyyy年M月d日 HH:mm",
        )
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone(timeZoneId)
                isLenient = false
            }
            val position = ParsePosition(0)
            val date = format.parse(text, position) ?: continue
            if (position.index == text.length) return date.time
        }
        return null
    }

    private fun excelSerialToMillis(serial: BigDecimal, timeZoneId: String): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(timeZoneId), Locale.CHINA)
        calendar.clear()
        calendar.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        val days = serial.toDouble()
        return calendar.timeInMillis + (days * 86_400_000.0).toLong()
    }

    private fun normalizeHeader(value: String): String =
        value
            .replace(UTF8_BOM, "")
            .replace(' ', ' ')
            .replace('　', ' ')
            .replace('（', '(')
            .replace('）', ')')
            .replace(Regex("\\s+"), "")
            .replace(Regex("\\([^)]*\\)"), "")
            .lowercase(Locale.ROOT)

    private fun normalizeMerchant(value: String): String =
        value
            .replace(Regex("\\s+"), "")
            .replace(Regex("[（(][^）)]*[）)]"), "")
            .lowercase(Locale.ROOT)

    private fun isSummaryRow(raw: List<String>): Boolean {
        val joined = raw.joinToString(" ").trim()
        if (joined.isEmpty()) return false
        return listOf("共", "合计", "总计", "已显示", "导出时间", "说明").any { joined.contains(it) }
    }

    private fun normalizeNumericText(value: String): String = buildString(value.length) {
        for (char in value) {
            append(
                when (char) {
                    in '０'..'９' -> '0' + (char - '０')
                    '．' -> '.'
                    '－' -> '-'
                    '＋' -> '+'
                    '，' -> ','
                    else -> char
                },
            )
        }
    }
}
