package com.bookkeeping.app.ocr

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

enum class BillStatus {
    PAID,
    REFUNDED,
    FAILED,
    PENDING,
    UNKNOWN,
}

data class ParsedBill(
    val amountCents: Long?,
    val merchantCandidates: List<String>,
    val timestampMs: Long?,
    val orderId: String?,
    val status: BillStatus,
    val rawText: String,
    val warnings: List<String> = emptyList(),
)

/**
 * Text-only bill parser. It deliberately does not guess when several distinct
 * amounts are present; the capture pipeline should ask the page detector or the
 * user instead of silently picking one.
 */
object BillParser {
    private val preferredAmount = Regex(
        """(?:实付|实际支付|支付金额|付款金额|已付款|支付成功)[^\d¥￥]{0,10}[¥￥]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    )
    private val preferredMarker = Regex("""实付|实际支付|支付金额|付款金额|已付款""")
    private val currencyAmount = Regex("""[¥￥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val yuanAmount = Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元""")
    private val amountNoise = Regex("""优惠|原价|立减|折扣|红包|积分|余额|满减|券|手续费|运费""")
    private val orderPattern = Regex(
        """(?:订单号|交易单号|商户单号|交易号|Order\s*(?:ID)?|Transaction\s*(?:ID)?)\s*[:：]?\s*([A-Za-z0-9_-]{6,})""",
        RegexOption.IGNORE_CASE,
    )
    private val fullDate = Regex("""(\d{4})[-/](\d{1,2})[-/](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val monthDay = Regex("""(\d{1,2})月(\d{1,2})日\s+(\d{1,2}):(\d{2})""")
    private val merchantPrefix = Regex(
        """^(?:商户(?:名称)?|收款方|收款商户|付款给|Merchant)\s*[:：]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val merchantNoise = Regex(
        """微信支付|支付宝|银行卡|余额|零钱|交易详情|订单号|支付成功|付款成功|交易成功|Transaction Details|Paid by|Payment|Card|Wallet|完成|成功|失败|退款|待支付|处理中|收银台|付款方式|支付方式""",
        RegexOption.IGNORE_CASE,
    )
    private val merchantBadPrefix = Regex("""^(?:收款方|商户|付款给|Merchant)\s*[:：]\s*""", RegexOption.IGNORE_CASE)
    private val merchantStoreSuffix = Regex("""[（(][^）)]*(?:店|门店|分店)[^）)]*[）)]$""")
    private val timeOnly = Regex("""\d{1,2}:\d{2}""")

    fun parse(
        lines: List<String>,
        rawText: String = lines.joinToString("\n"),
        nowMs: Long = System.currentTimeMillis(),
    ): ParsedBill {
        val normalizedLines = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val warnings = mutableListOf<String>()
        val amount = parseAmount(normalizedLines, warnings)
        val merchants = parseMerchants(normalizedLines)
        val order = normalizedLines.firstNotNullOfOrNull { line ->
            orderPattern.find(line)?.groupValues?.getOrNull(1)
        }
        val status = parseStatus(normalizedLines)
        val timestamp = parseTimestamp(normalizedLines, nowMs)
        if (amount == null) warnings += "amount_ambiguous_or_missing"
        if (merchants.isEmpty()) warnings += "merchant_missing"
        return ParsedBill(
            amountCents = amount,
            merchantCandidates = merchants,
            timestampMs = timestamp,
            orderId = order?.trim()?.takeIf { it.isNotEmpty() },
            status = status,
            rawText = rawText,
            warnings = warnings.distinct(),
        )
    }

    private fun parseAmount(lines: List<String>, warnings: MutableList<String>): Long? {
        val preferred = mutableListOf<Long>()
        val normal = mutableListOf<Long>()
        for (line in lines) {
            val hasPreferredMarker = preferredMarker.containsMatchIn(line)
            if (amountNoise.containsMatchIn(line) && !hasPreferredMarker) continue
            preferredAmount.findAll(line).forEach { match ->
                match.groupValues.getOrNull(1)?.toCentsOrNull()?.let(preferred::add)
            }
            currencyAmount.findAll(line).forEach { match ->
                match.groupValues.getOrNull(1)?.toCentsOrNull()?.let(normal::add)
            }
            yuanAmount.findAll(line).forEach { match ->
                match.groupValues.getOrNull(1)?.toCentsOrNull()?.let(normal::add)
            }
        }
        val preferredDistinct = preferred.distinct()
        if (preferredDistinct.size == 1) return preferredDistinct.single()
        if (preferredDistinct.size > 1) {
            warnings += "multiple_preferred_amounts"
            return null
        }
        val normalDistinct = normal.distinct()
        return when (normalDistinct.size) {
            0 -> null
            1 -> normalDistinct.single()
            else -> {
                warnings += "multiple_amounts"
                null
            }
        }
    }

    private fun parseMerchants(lines: List<String>): List<String> {
        val prefixed = lines.mapNotNull { line ->
            merchantPrefix.find(line)?.groupValues?.getOrNull(1)?.cleanMerchant()
        }
        if (prefixed.isNotEmpty()) return prefixed.distinct().take(5)
        return lines.asSequence()
            .filterNot { it.contains(Regex("""[¥￥]|\d+\.\d{2}|\d+\s*元""")) }
            .filterNot { merchantNoise.containsMatchIn(it) }
            .filterNot { it.matches(Regex("""[\d\s:：./月日-]+""")) }
            .filterNot { it.contains("今天") || it.contains("昨天") || timeOnly.containsMatchIn(it) }
            .mapNotNull { it.cleanMerchant() }
            .filter { it.length in 2..80 }
            .distinct()
            .take(5)
            .toList()
    }

    private fun String.cleanMerchant(): String? {
        var value = merchantBadPrefix.replace(this, "").trim()
        value = merchantStoreSuffix.replace(value, "").trim()
        value = value.trim(' ', '，', ',', '。', ':', '：', '-', '·')
        if (value.length !in 2..80) return null
        if (value.count { it.isDigit() } > value.length / 2) return null
        if (merchantNoise.containsMatchIn(value)) return null
        return value
    }

    private fun parseStatus(lines: List<String>): BillStatus {
        val text = lines.joinToString(" ")
        if (Regex("""退款(?:成功|完成)?""").containsMatchIn(text) &&
            !Regex("""退款失败""").containsMatchIn(text)
        ) {
            return BillStatus.REFUNDED
        }
        if (Regex("""支付失败|付款失败|交易失败|退款失败|未支付成功""").containsMatchIn(text)) {
            return BillStatus.FAILED
        }
        if (Regex("""待支付|等待付款|处理中|支付处理中""").containsMatchIn(text)) {
            return BillStatus.PENDING
        }
        if (Regex("""支付成功|付款成功|交易成功|已支付|已完成|支付完成""").containsMatchIn(text)) {
            return BillStatus.PAID
        }
        return BillStatus.UNKNOWN
    }

    private fun parseTimestamp(lines: List<String>, nowMs: Long): Long? {
        for (line in lines) {
            fullDate.find(line)?.let { match ->
                val (year, month, day, hour, minute, second) = match.destructured
                return buildTimestamp(
                    year.toInt(), month.toInt(), day.toInt(),
                    hour.toInt(), minute.toInt(), second.ifBlank { "0" }.toInt(),
                )
            }
            monthDay.find(line)?.let { match ->
                val (month, day, hour, minute) = match.destructured
                val now = Calendar.getInstance()
                val candidate = Calendar.getInstance().apply {
                    set(Calendar.YEAR, now.get(Calendar.YEAR))
                    set(Calendar.MONTH, month.toInt() - 1)
                    set(Calendar.DAY_OF_MONTH, day.toInt())
                    set(Calendar.HOUR_OF_DAY, hour.toInt())
                    set(Calendar.MINUTE, minute.toInt())
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                return if (candidate > nowMs + 24 * 60 * 60 * 1000L) {
                    Calendar.getInstance().apply {
                        timeInMillis = candidate
                        add(Calendar.YEAR, -1)
                    }.timeInMillis
                } else {
                    candidate
                }
            }
            // A bare HH:mm is accepted only when the line also contains a date-like
            // separator; otherwise it is usually a list timestamp and is too weak.
            if (line.contains("今天") || line.contains("昨天")) {
                timeOnly.find(line)?.let { match ->
                    val parts = match.value.split(":")
                    val calendar = Calendar.getInstance().apply {
                        if (line.contains("昨天")) add(Calendar.DAY_OF_MONTH, -1)
                        set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                        set(Calendar.MINUTE, parts[1].toInt())
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    return calendar.timeInMillis
                }
            }
        }
        return null
    }

    private fun buildTimestamp(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long? = try {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    } catch (_: Exception) {
        null
    }

    private fun String.toCentsOrNull(): Long? {
        val normalized = trim().replace(",", "")
        return try {
            BigDecimal(normalized)
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
        } catch (_: Exception) {
            null
        }
    }
}
