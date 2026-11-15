package com.bookkeeping.app.capture.success

import java.text.Normalizer
import java.util.Locale

/** Decimal input stays in integer cents, including at the storage boundary. */
object Money {
    const val MAX_CENTS: Long = 99_999_999_999L

    private val decimalInput = Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?")

    fun parseCents(input: String): Long? {
        val value = normalize(input).trim()
            .removePrefix("人民币").removePrefix("RMB").removePrefix("CNY")
            .trim().removePrefix("¥").trim().removeSuffix("元").trim()
        // Reject malformed comma grouping, exponent notation, negatives and fractional cents.
        if (!decimalInput.matches(value)) return null
        val parts = value.replace(",", "").split('.')
        val yuan = parts[0].toLongOrNull() ?: return null
        if (yuan > MAX_CENTS / 100) return null
        val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L
        return (yuan * 100 + fraction).takeIf { it <= MAX_CENTS }
    }

    fun format(cents: Long): String = java.math.BigDecimal.valueOf(cents, 2)
        .setScale(2).toPlainString()

    /** Reading format; fixed-scale exchange/export values keep using [format]. */
    fun display(cents: Long): String = java.math.BigDecimal.valueOf(cents, 2)
        .stripTrailingZeros().toPlainString()
}

internal fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .replace('￥', '¥').replace('\u00a0', ' ')

internal fun provider(origin: String): String? {
    val value = normalize(origin).trim().lowercase(Locale.ROOT)
    return when {
        value == "com.tencent.mm" || value == "微信" || value == "微信支付" -> "wechat"
        value == "com.eg.android.alipaygphone" || value == "支付宝" -> "alipay"
        value in bankSenders -> "bank:$value"
        else -> null
    }
}

internal val bankSenders = setOf(
    "95588", "95533", "95599", "95566", "95555", "95559", "95561",
    "95595", "95508", "95528", "95501", "95568", "95511", "95580",
)
