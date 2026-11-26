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
        // AI & Developer Tools
        Rule(listOf("chatgpt", "openai", "gpt"), "chatgpt", "G", 0xFF23272D, "ai"),
        Rule(listOf("claude", "anthropic"), "claude", "C", 0xFFB85D3B, "ai"),
        Rule(listOf("gemini", "google ai"), "gemini", "G", 0xFF2563EB, "ai"),
        Rule(listOf("cursor", "copilot", "codex", "opencode", "code goat", "windsurf", "trae"), "coding_ai", "C", 0xFF2B313B, "code"),
        Rule(listOf("midjourney", "suno", "runway", "perplexity", "poe", "kimi", "豆包", "通义", "智谱", "deepseek"), "ai_service", "A", 0xFF4F46E5, "ai"),
        Rule(listOf("github"), "github", "G", 0xFF24292F, "code"),
        // Streaming Video & Music
        Rule(listOf("youtube", "油管"), "youtube", "Y", 0xFFB91C1C, "video"),
        Rule(listOf("netflix", "奈飞", "网飞"), "netflix", "N", 0xFFB21D26, "movie"),
        Rule(listOf("disney"), "disney", "D", 0xFF136F78, "movie"),
        Rule(listOf("bilibili", "b站", "哔哩哔哩"), "bilibili", "B", 0xFFD95375, "video"),
        Rule(listOf("爱奇艺", "iqiyi"), "iqiyi", "爱", 0xFF1F7A55, "video"),
        Rule(listOf("腾讯视频", "腾迅视频"), "tencent_video", "腾", 0xFF2563EB, "video"),
        Rule(listOf("优酷", "youku", "芒果tv", "apple tv", "prime video", "hbo", "max"), "streaming", "影", 0xFF1E293B, "movie"),
        Rule(listOf("spotify"), "spotify", "S", 0xFF15803D, "music"),
        Rule(listOf("网易云", "netease"), "netease_music", "网", 0xFFC23030, "music"),
        Rule(listOf("qq音乐", "qq music", "酷狗", "酷我", "汽水音乐"), "music_cn", "音", 0xFF1D8348, "music"),
        Rule(listOf("apple music", "喜马拉雅", "小宇宙"), "audio", "乐", 0xFFC83E4D, "music"),
        // Cloud, Storage & Productivity
        Rule(listOf("icloud", "apple one", "applecare"), "icloud", "i", 0xFF2B7CD3, "cloud"),
        Rule(listOf("google one", "google drive"), "google_one", "G", 0xFF2563EB, "cloud"),
        Rule(listOf("onedrive", "microsoft 365", "office 365", "dropbox", "百度网盘", "阿里云盘", "夸克网盘"), "cloud_drive", "云", 0xFF1E6091, "cloud"),
        Rule(listOf("notion", "obsidian", "raycast", "1password", "bitwarden", "figma", "adobe", "photoshop", "wps"), "productivity", "P", 0xFF334155, "productivity"),
        // Infrastructure & Telecom
        Rule(listOf("vps", "服务器", "云主机", "aws", "cloudflare", "aliyun", "阿里云", "腾讯云", "vultr", "digitalocean", "bandwagon", "搬瓦工", "域名", "domain"), "server", "V", 0xFF2B3540, "server"),
        Rule(listOf("话费", "手机", "流量", "移动", "联通", "电信", "港卡", "sim", "esim", "cls", "club sim", "宽带"), "telecom", "卡", 0xFF1E6B5E, "sim"),
        // Shopping, Reading, Fitness & Gaming
        Rule(listOf("88vip", "天猫"), "88vip", "8", 0xFFD95B26, "shopping"),
        Rule(listOf("京东plus", "jd plus", "山姆", "costco", "amazon prime"), "vip_shop", "会", 0xFF1D4ED8, "shopping"),
        Rule(listOf("微信读书", "kindle", "知乎", "得到"), "reading", "读", 0xFF2C6E9B, "book"),
        Rule(listOf("健身", "gym", "keep", "apple fitness"), "fitness", "健", 0xFF2F5E4E, "fitness"),
        Rule(listOf("xbox", "game pass", "playstation", "ps plus", "nintendo", "switch", "steam", "geforce"), "gaming", "游", 0xFF1F6E3D, "game"),
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
        TransactionCategory.BONUS -> "bonus"
        TransactionCategory.INVESTMENT -> "investment"
        TransactionCategory.INSURANCE -> "insurance"
        TransactionCategory.GIFT -> "gift"
        TransactionCategory.TRANSFER -> "transfer"
        TransactionCategory.OTHER -> "other"
    }
}
