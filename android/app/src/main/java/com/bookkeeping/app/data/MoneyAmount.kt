package com.bookkeeping.app.data

import java.math.BigDecimal

/** Exact decimal-yuan to integer-cents conversion. Never round via Double. */
object MoneyAmount {
    private val VALID = Regex("""\d+(?:\.\d{1,2})?""")

    fun cents(raw: String): Long? {
        val input = raw.trim()
        if (!VALID.matches(input)) return null
        return runCatching {
            BigDecimal(input).movePointRight(2).longValueExact()
        }.getOrNull()?.takeIf { it > 0L && it <= 100_000_000_000L }
    }
}
