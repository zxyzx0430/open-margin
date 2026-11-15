package com.bookkeeping.app.notification

import java.util.Locale

/**
 * Stable merchant key for rule matching. The display name is kept separately;
 * only payment-channel prefixes, card tails, parenthetical suffixes and branch
 * suffixes are removed. Business words such as "美团买药" are preserved.
 */
object MerchantNormalizer {
    private val CHANNEL_PREFIX = Regex("^(支付宝|微信支付|财付通|银联|云闪付)[-—\\s:：]+")
    private val PARENTHETICAL = Regex("[（(][^）)]*[）)]")
    private val CARD_TAIL = Regex("(?i)(?:[*xX]{2,}|[-\\s])\\d{3,4}$")
    private val BRANCH_SUFFIX = Regex("(?:第[0-9一二三四五六七八九十]+店|分店|门店|广场店|分号)$")

    fun normalize(raw: String): String =
        raw
            .trim()
            .replace(CHANNEL_PREFIX, "")
            .replace(PARENTHETICAL, "")
            .replace(CARD_TAIL, "")
            .replace(BRANCH_SUFFIX, "")
            .replace(Regex("\\s+"), "")
            .lowercase(Locale.ROOT)

    /** Keep the original merchant for display; do not rewrite user-visible names. */
    fun displayName(raw: String): String = raw.trim()
}
