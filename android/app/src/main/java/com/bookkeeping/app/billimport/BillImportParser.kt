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
 * Parses WeChat Pay / Alipay / Open Margin / Qianji / Yimu / Shark bill exports
 * into a preview. It never writes to the ledger; callers must show
 * [BillImportPreview] and import only after explicit user confirmation.
 */
object BillImportParser {

    private const val UTF8_BOM = "﻿"
    private val BOOKKEEPING_APP_SOURCES = setOf(BillSource.QIANJI, BillSource.YIMU, BillSource.SHARK)

    private val HEADER_ALIASES: Map<String, Set<String>> = mapOf(
        "time" to setOf(
            "交易时间", "交易创建时间", "付款时间", "创建时间", "时间", "日期",
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
            "收/支", "收支", "收支类型", "收/付款", "资金方向", "方向",
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
        "category" to setOf(
            "交易分类", "分类", "类别",
            "category",
        ),
        "subCategory" to setOf(
            "二级分类", "子类", "子分类",
            "subcategory",
        ),
        "description" to setOf(
            "商品说明", "商品", "商品名称",
            "description", "product",
        ),
        "account" to setOf(
            "账户", "账户1",
            "account", "account1",
        ),
        "account2" to setOf(
            "账户2", "转入账户", "目标账户",
            "account2",
        ),
        "source" to setOf(
            "来源",
            "source",
        ),
        "note" to setOf(
            "备注",
            "note",
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
            // Order IDs are namespaced per platform; the same string can be
            // reused by another payment provider.
            "order:${row.source.name}:$order"
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

        val headerRow = rows[header.rowIndex]
        val source = detectSource(fileName, headerRow)
        val columns = header.columns
        val openMarginExport = isOpenMarginExport(headerRow, columns)
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
            val isBookkeepingApp = !openMarginExport && source in BOOKKEEPING_APP_SOURCES
            val timeRaw = value(raw, columns.time)
            val merchantRaw = value(raw, columns.merchant)
            val amountRaw = value(raw, columns.amount)
            val directionRaw = value(raw, columns.direction)
            val statusRaw = value(raw, columns.status)
            val orderId = value(raw, columns.orderId).takeIf { it.isNotBlank() }
            val payment = value(raw, columns.payment).takeIf { it.isNotBlank() }
            val subCategory = cleanOptionalField(value(raw, columns.subCategory), isBookkeepingApp)
            val rawCategory = cleanOptionalField(value(raw, columns.category), isBookkeepingApp)
            val sourceCategory = rawCategory ?: subCategory
            val description = cleanOptionalField(value(raw, columns.description), isBookkeepingApp)
                ?: subCategory
            val account1 = cleanOptionalField(value(raw, columns.account), isBookkeepingApp)
            val account2 = cleanOptionalField(value(raw, columns.account2), isBookkeepingApp)
            val account = account1 ?: account2
            val explicitLedgerSource = value(raw, columns.source).takeIf { it.isNotBlank() }
            val note = cleanOptionalField(value(raw, columns.note), isBookkeepingApp)

            val occurredAt = parseTime(timeRaw, options.timeZoneId)
            val amount = parseAmount(amountRaw)

            // WeChat/Alipay exports end with a "共 N 笔 / 合计" summary row.
            // Only skip it when it has no valid transaction time or amount.
            if (isSummaryRow(raw) && (occurredAt == null || amount == null)) continue

            val merchant = when {
                openMarginExport -> merchantRaw.trim()
                isBookkeepingApp -> resolveBookkeepingMerchant(
                    merchantRaw = merchantRaw,
                    note = note,
                    subCategory = subCategory,
                    sourceCategory = sourceCategory,
                    account1 = account1,
                    account2 = account2,
                    directionRaw = directionRaw,
                )
                else -> merchantRaw.trim()
            }
            val direction = parseDirection(directionRaw, amount?.negative == true)
            val status = if (columns.status < 0 && (openMarginExport || isBookkeepingApp)) {
                BillStatus.SUCCESS
            } else {
                parseStatus(statusRaw)
            }
            val rowSource = if (openMarginExport) {
                detectRowSource(explicitLedgerSource, source)
            } else {
                source
            }
            val ledgerSource = explicitLedgerSource ?: when (rowSource) {
                BillSource.QIANJI -> "钱迹"
                BillSource.YIMU -> "一木记账"
                BillSource.SHARK -> "鲨鱼记账"
                else -> null
            }

            val parsed = if (occurredAt != null && amount != null) {
                BillImportRow(
                    source = rowSource,
                    rowNumber = rowNumber,
                    occurredAtMs = occurredAt,
                    merchant = if (openMarginExport) merchant else merchant.ifBlank { "未知商户" },
                    amountCents = amount.cents,
                    direction = direction,
                    status = status,
                    orderId = orderId,
                    paymentMethod = payment,
                    sourceCategory = sourceCategory,
                    description = description,
                    rawFields = raw,
                    isOpenMarginExport = openMarginExport,
                    ledgerSource = ledgerSource,
                    account = account,
                    note = note,
                )
            } else {
                null
            }

            val parseError = when {
                timeRaw.isBlank() -> "交易时间为空"
                occurredAt == null -> "无法解析交易时间：$timeRaw"
                amountRaw.isBlank() -> "金额为空"
                amount == null -> "无法解析金额：$amountRaw"
                merchant.isBlank() && !openMarginExport && !isBookkeepingApp -> "商户为空，需人工确认"
                else -> null
            }
            if (parseError != null) {
                previewRows += BillPreviewRow(
                    kind = BillPreviewKind.ERROR,
                    rowNumber = rowNumber,
                    rawFields = raw,
                    parsed = parsed,
                    message = parseError,
                )
                continue
            }

            val valid = parsed ?: continue
            val skipReason = when {
                valid.amountCents == 0L -> "0 元授权/非交易，已跳过"
                valid.status == BillStatus.FAILED -> "交易未成功，已跳过"
                valid.status == BillStatus.PENDING -> "交易处理中，已跳过"
                valid.status == BillStatus.UNKNOWN -> "状态未识别/非交易，已跳过"
                else -> null
            }
            if (skipReason != null) {
                previewRows += BillPreviewRow(
                    kind = BillPreviewKind.SKIPPED,
                    rowNumber = rowNumber,
                    rawFields = raw,
                    parsed = valid,
                    message = skipReason,
                )
                continue
            }

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
            previewRows.none { it.kind == BillPreviewKind.DUPLICATE } &&
            previewRows.none { it.kind == BillPreviewKind.SKIPPED }
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
        val category: Int,
        val subCategory: Int,
        val description: Int,
        val account: Int,
        val account2: Int,
        val source: Int,
        val note: Int,
    )

    private data class HeaderMatch(val rowIndex: Int, val columns: ColumnMap, val score: Int)

    private fun findHeader(rows: List<List<String>>): HeaderMatch? {
        var best: HeaderMatch? = null
        for (index in rows.indices) {
            val row = rows[index]
            if (row.isEmpty()) continue
            val rawDirection = findColumn(row, HEADER_ALIASES.getValue("direction"))
            val columns = ColumnMap(
                time = findColumn(row, HEADER_ALIASES.getValue("time")),
                merchant = findColumn(row, HEADER_ALIASES.getValue("merchant")),
                amount = findColumn(row, HEADER_ALIASES.getValue("amount")),
                direction = if (rawDirection >= 0) rawDirection else findColumn(row, setOf("类型", "type")),
                status = findColumn(row, HEADER_ALIASES.getValue("status")),
                orderId = findColumn(row, HEADER_ALIASES.getValue("orderId")),
                payment = findColumn(row, HEADER_ALIASES.getValue("payment")),
                category = findColumn(row, HEADER_ALIASES.getValue("category")),
                subCategory = findColumn(row, HEADER_ALIASES.getValue("subCategory")),
                description = findColumn(row, HEADER_ALIASES.getValue("description")),
                account = findColumn(row, HEADER_ALIASES.getValue("account")),
                account2 = findColumn(row, HEADER_ALIASES.getValue("account2")),
                source = findColumn(row, HEADER_ALIASES.getValue("source")),
                note = findColumn(row, HEADER_ALIASES.getValue("note")),
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

    private fun isOpenMarginExport(headerRow: List<String>, columns: ColumnMap): Boolean {
        if (columns.status >= 0) return false
        val headers = headerRow.map(::normalizeHeader).toSet()
        val hasTime = "时间" in headers || "time" in headers
        val hasType = "类型" in headers || "type" in headers
        val hasAmount = "金额" in headers || "amount" in headers
        val hasMerchant = "商户" in headers || "merchant" in headers
        val hasOpenMarginCols = ("来源" in headers && "备注" in headers) ||
            ("账户" in headers && "来源" in headers) ||
            ("source" in headers && "note" in headers)
        return hasTime && hasType && hasAmount && hasMerchant && hasOpenMarginCols
    }

    private fun detectRowSource(ledgerSource: String?, fallback: BillSource): BillSource {
        val text = ledgerSource?.trim().orEmpty()
        if (text.isEmpty()) return fallback
        return when (text.uppercase(Locale.ROOT)) {
            "微信", "WECHAT" -> BillSource.WECHAT
            "支付宝", "ALIPAY" -> BillSource.ALIPAY
            "钱迹", "QIANJI" -> BillSource.QIANJI
            "一木", "一木记账", "YIMU" -> BillSource.YIMU
            "鲨鱼", "鲨鱼记账", "SHARK" -> BillSource.SHARK
            else -> BillSource.UNKNOWN
        }
    }

    private fun cleanOptionalField(raw: String, stripPlaceholder: Boolean): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (stripPlaceholder && (trimmed == "/" || trimmed == "-")) return null
        return trimmed
    }

    private fun resolveBookkeepingMerchant(
        merchantRaw: String,
        note: String?,
        subCategory: String?,
        sourceCategory: String?,
        account1: String?,
        account2: String?,
        directionRaw: String,
    ): String {
        val explicit = merchantRaw.trim()
        if (explicit.isNotEmpty()) return explicit
        if (!note.isNullOrBlank()) return note
        if (!subCategory.isNullOrBlank()) return subCategory
        if (!sourceCategory.isNullOrBlank()) return sourceCategory
        if (!account1.isNullOrBlank() && !account2.isNullOrBlank()) return "$account1 -> $account2"
        return directionRaw.trim().ifBlank { "未知商户" }
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
        val headers = headerRow.map(::normalizeHeader).toSet()
        if ("商户单号" in headers) return BillSource.WECHAT
        if ("商家订单号" in headers || "交易订单号" in headers || "交易分类" in headers) {
            return BillSource.ALIPAY
        }
        if (isQianjiHeaders(headers)) return BillSource.QIANJI
        if (isYimuHeaders(headers)) return BillSource.YIMU
        if (isSharkHeaders(headers)) return BillSource.SHARK

        val name = fileName.orEmpty()
        val lower = name.lowercase(Locale.ROOT)
        if ("wechat" in lower || "微信" in name) return BillSource.WECHAT
        if ("alipay" in lower || "支付宝" in name) return BillSource.ALIPAY
        if ("qianji" in lower || "钱迹" in name) return BillSource.QIANJI
        if ("yimu" in lower || "一木" in name) return BillSource.YIMU
        if ("shark" in lower || "鲨鱼" in name) return BillSource.SHARK
        return BillSource.UNKNOWN
    }

    private fun isQianjiHeaders(headers: Set<String>): Boolean {
        if ("商户" in headers || "交易对方" in headers || "来源" in headers) return false
        if ("账户1" in headers || "账户2" in headers || "二级分类" in headers) return true
        return "时间" in headers && "分类" in headers && "类型" in headers && "金额" in headers
    }

    private fun isYimuHeaders(headers: Set<String>): Boolean {
        if ("商户" in headers || "交易对方" in headers || "来源" in headers) return false
        if ("收支类型" in headers) return true
        return "日期" in headers && "类别" in headers && "金额" in headers
    }

    private fun isSharkHeaders(headers: Set<String>): Boolean {
        if ("商户" in headers || "交易对方" in headers || "来源" in headers) return false
        if ("账户1" in headers || "账户2" in headers || "收支类型" in headers) return false
        return "日期" in headers && "类型" in headers && "分类" in headers && "金额" in headers
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
        return try {
            val cents = value.setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
            if (cents >= 0L) AmountParse(cents, negative) else null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun parseDirection(raw: String, negative: Boolean): BillDirection {
        val text = raw.trim()
        return when {
            text.contains("不计") || text.contains("转账") || text.contains("还款") -> BillDirection.NEUTRAL
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
            text.contains("成功") || text.contains("完成") || text.contains("收款") ||
                text.contains("已转账") || text.contains("已存入") ||
                text.contains("已到账") || text.contains("已收钱") ||
                text.contains("提现已到账") -> BillStatus.SUCCESS
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
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy/MM/dd HH:mm:ss.SSS",
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
            "yyyy-M-d",
            "yyyy/M/d",
            "yyyy年M月d日 HH:mm:ss",
            "yyyy年M月d日 HH:mm",
            "yyyy年M月d日",
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
        // Excel serials are local wall-clock values. Do not anchor them in the
        // target time zone: Asia/Shanghai had a +08:05:43 offset in 1899, and
        // using that historical offset makes every modern timestamp 343s early.
        val wall = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.CHINA)
        wall.clear()
        wall.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        val days = serial.toDouble()
        val wholeDays = kotlin.math.floor(days).toInt()
        val millisOfDay = Math.round((days - wholeDays) * 86_400_000.0).toInt()
        wall.add(Calendar.DAY_OF_MONTH, wholeDays)
        wall.add(Calendar.MILLISECOND, millisOfDay)

        val target = Calendar.getInstance(TimeZone.getTimeZone(timeZoneId), Locale.CHINA)
        target.clear()
        target.set(
            wall.get(Calendar.YEAR),
            wall.get(Calendar.MONTH),
            wall.get(Calendar.DAY_OF_MONTH),
            wall.get(Calendar.HOUR_OF_DAY),
            wall.get(Calendar.MINUTE),
            wall.get(Calendar.SECOND),
        )
        target.set(Calendar.MILLISECOND, wall.get(Calendar.MILLISECOND))
        return target.timeInMillis
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
