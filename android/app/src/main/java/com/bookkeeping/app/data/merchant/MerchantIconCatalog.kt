package com.bookkeeping.app.data.merchant

import com.bookkeeping.app.data.local.entity.TransactionCategory

/**
 * Pure merchant-icon metadata. The UI maps [systemIcon] to a local vector and
 * [initials] to a fallback avatar. No brand assets are bundled here; unknown
 * merchants fall back to their category icon.
 */
data class MerchantIconSpec(
    val key: String,
    val initials: String,
    val colorArgb: Long,
    val systemIcon: String? = null,
)

object MerchantIconCatalog {
    private data class Rule(
        val needles: List<String>,
        val key: String,
        val initials: String,
        val colorArgb: Long,
        val systemIcon: String? = null,
    )

    private val rules = listOf(
        Rule(listOf("星巴克", "starbucks"), "starbucks", "星", 0xFF00704A, "coffee"),
        Rule(listOf("盒马", "hema"), "hema", "盒", 0xFF1B8DD6, "store"),
        Rule(listOf("海底捞"), "haidilao", "海", 0xFFD94A3A, "restaurant"),
        Rule(listOf("麦当劳", "mcdonald"), "mcdonalds", "M", 0xFFDA291C, "restaurant"),
        Rule(listOf("肯德基", "kfc"), "kfc", "K", 0xFFB5121B, "restaurant"),
        Rule(listOf("美团", "meituan"), "meituan", "美", 0xFFFFC300, "store"),
        Rule(listOf("京东", "jd.com", "京东商城"), "jd", "京", 0xFFD71A1B, "shopping"),
        Rule(listOf("淘宝", "taobao"), "taobao", "淘", 0xFFFF6A00, "shopping"),
        Rule(listOf("拼多多", "pinduoduo"), "pinduoduo", "拼", 0xFFE02E24, "shopping"),
        Rule(listOf("滴滴", "didi"), "didi", "滴", 0xFFF5A623, "transport"),
        Rule(listOf("地铁", "metro"), "metro", "地", 0xFF3B82F6, "transport"),
        Rule(listOf("公交", "bus"), "bus", "公", 0xFF2563EB, "transport"),
        Rule(listOf("微信", "wechat"), "wechat", "微", 0xFF07C160, "transfer"),
        Rule(listOf("支付宝", "alipay"), "alipay", "支", 0xFF1677FF, "transfer"),
        Rule(listOf("工资", "薪资", "salary"), "salary", "薪", 0xFF5E9C3A, "salary"),
        Rule(listOf("房租", "租金"), "rent", "房", 0xFF6B7BD6, "home"),
        Rule(listOf("电费", "水费", "燃气"), "utilities", "电", 0xFF1F9D8A, "bolt"),
        Rule(listOf("医院", "药房", "医疗"), "health", "医", 0xFF8D6E63, "health"),
        Rule(listOf("学费", "教育", "书店"), "education", "学", 0xFFD99A2B, "education"),
    )

    private val byKey: Map<String, MerchantIconSpec> = rules.associate { rule ->
        rule.key to MerchantIconSpec(rule.key, rule.initials, rule.colorArgb, rule.systemIcon)
    }

    fun forKey(key: String?): MerchantIconSpec? =
        key?.let(byKey::get)

    fun forMerchant(merchant: String): MerchantIconSpec? {
        val normalized = merchant.trim().lowercase()
        if (normalized.isEmpty()) return null
        val rule = rules.firstOrNull { rule -> rule.needles.any { normalized.contains(it) } }
        return rule?.let { MerchantIconSpec(it.key, it.initials, it.colorArgb, it.systemIcon) }
    }

    fun forTransaction(merchant: String, merchantIconKey: String?): MerchantIconSpec? =
        forKey(merchantIconKey) ?: forMerchant(merchant)

    fun categoryIconName(category: TransactionCategory): String = when (category) {
        TransactionCategory.FOOD -> "restaurant"
        TransactionCategory.TRANSPORT -> "transport"
        TransactionCategory.SHOPPING -> "shopping"
        TransactionCategory.ENTERTAINMENT -> "entertainment"
        TransactionCategory.HOUSING -> "home"
        TransactionCategory.UTILITIES -> "bolt"
        TransactionCategory.HEALTH -> "health"
        TransactionCategory.EDUCATION -> "education"
        TransactionCategory.SALARY -> "salary"
        TransactionCategory.TRANSFER -> "transfer"
        TransactionCategory.OTHER -> "other"
    }
}
