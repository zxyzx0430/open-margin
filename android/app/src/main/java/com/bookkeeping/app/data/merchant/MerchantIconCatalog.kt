package com.bookkeeping.app.data.merchant

import com.bookkeeping.app.data.local.entity.SubscriptionCadence
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

data class MerchantSuggestion(
    val displayName: String,
    val spec: MerchantIconSpec,
    val defaultCurrency: String? = null,
    val defaultCadence: String? = null,
    val searchTerms: List<String> = emptyList(),
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
        Rule(listOf("chatgpt", "openai", "gpt"), "chatgpt", "G", 0xFF23272E, "ai"),
        Rule(listOf("codex"), "codex", "C", 0xFF23272E, "ai"),
        Rule(listOf("claude", "anthropic"), "claude", "C", 0xFFC2613A, "ai"),
        Rule(listOf("gemini", "google ai"), "gemini", "G", 0xFF2563EB, "ai"),
        Rule(listOf("cursor", "copilot", "opencode", "code goat", "windsurf", "trae"), "coding_ai", "C", 0xFF2B313B, "code"),
        Rule(listOf("midjourney", "suno", "runway", "perplexity", "poe", "kimi", "豆包", "通义", "智谱", "deepseek"), "ai_service", "A", 0xFF4F46E5, "ai"),
        Rule(listOf("github"), "github", "G", 0xFF24292F, "code"),
        // Streaming Video & Music
        Rule(listOf("youtube", "油管"), "youtube", "Y", 0xFFC81E1E, "video"),
        Rule(listOf("netflix", "奈飞", "网飞"), "netflix", "N", 0xFFB91C1C, "movie"),
        Rule(listOf("disney"), "disney", "D", 0xFF0D5C63, "movie"),
        Rule(listOf("bilibili", "b站", "哔哩哔哩"), "bilibili", "B", 0xFFE05279, "video"),
        Rule(listOf("爱奇艺", "iqiyi"), "iqiyi", "爱", 0xFF1F8A5B, "video"),
        Rule(listOf("腾讯视频", "腾迅视频"), "tencent_video", "腾", 0xFF2563EB, "video"),
        Rule(listOf("优酷", "youku", "芒果tv", "apple tv", "prime video", "hbo", "max"), "streaming", "影", 0xFF1E293B, "movie"),
        Rule(listOf("spotify"), "spotify", "S", 0xFF169C46, "music"),
        Rule(listOf("网易云", "netease"), "netease_music", "网", 0xFFC82828, "music"),
        Rule(listOf("qq音乐", "qq music", "酷狗", "酷我", "汽水音乐"), "music_cn", "音", 0xFF1D8E4E, "music"),
        Rule(listOf("apple music", "喜马拉雅", "小宇宙"), "audio", "乐", 0xFFC83E4D, "music"),
        // Cloud, Storage & Productivity
        Rule(listOf("icloud", "apple one", "applecare"), "icloud", "i", 0xFF2F80ED, "cloud"),
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

    private data class PresetDef(
        val displayName: String,
        val ruleKey: String,
        val defaultCurrency: String? = null,
        val defaultCadence: String? = null,
        val extraTerms: List<String> = emptyList(),
    )

    private val presetDefs = listOf(
        // AI & Dev
        PresetDef("ChatGPT", "chatgpt", "USD", SubscriptionCadence.MONTHLY, listOf("chatgpt", "openai", "gpt")),
        PresetDef("Claude", "claude", "USD", SubscriptionCadence.MONTHLY, listOf("claude", "anthropic")),
        PresetDef("Gemini", "gemini", "USD", SubscriptionCadence.MONTHLY, listOf("gemini", "google ai")),
        PresetDef("Cursor", "coding_ai", "USD", SubscriptionCadence.MONTHLY, listOf("cursor")),
        PresetDef("GitHub", "github", "USD", SubscriptionCadence.MONTHLY, listOf("github", "copilot")),
        PresetDef("Codex", "coding_ai", "USD", SubscriptionCadence.MONTHLY, listOf("codex", "windsurf", "trae")),
        PresetDef("Midjourney", "ai_service", "USD", SubscriptionCadence.MONTHLY, listOf("midjourney", "mj")),
        PresetDef("Perplexity", "ai_service", "USD", SubscriptionCadence.MONTHLY, listOf("perplexity")),
        PresetDef("Poe", "ai_service", "USD", SubscriptionCadence.MONTHLY, listOf("poe")),
        PresetDef("Suno", "ai_service", "USD", SubscriptionCadence.MONTHLY, listOf("suno")),
        PresetDef("Runway", "ai_service", "USD", SubscriptionCadence.MONTHLY, listOf("runway")),
        PresetDef("Kimi", "ai_service", "CNY", SubscriptionCadence.MONTHLY, listOf("kimi")),
        PresetDef("DeepSeek", "ai_service", "CNY", SubscriptionCadence.MONTHLY, listOf("deepseek")),
        PresetDef("豆包", "ai_service", "CNY", SubscriptionCadence.MONTHLY, listOf("豆包", "doubao")),
        PresetDef("通义千问", "ai_service", "CNY", SubscriptionCadence.MONTHLY, listOf("通义", "qwen")),
        PresetDef("智谱清言", "ai_service", "CNY", SubscriptionCadence.MONTHLY, listOf("智谱", "zhipu")),
        // Streaming Video & Music
        PresetDef("YouTube", "youtube", "USD", SubscriptionCadence.MONTHLY, listOf("youtube", "yt", "油管")),
        PresetDef("Netflix", "netflix", "USD", SubscriptionCadence.MONTHLY, listOf("netflix", "nf", "奈飞", "网飞")),
        PresetDef("Disney+", "disney", "USD", SubscriptionCadence.MONTHLY, listOf("disney")),
        PresetDef("Apple TV", "streaming", "USD", SubscriptionCadence.MONTHLY, listOf("apple tv")),
        PresetDef("Prime Video", "streaming", "USD", SubscriptionCadence.MONTHLY, listOf("prime video")),
        PresetDef("HBO Max", "streaming", "USD", SubscriptionCadence.MONTHLY, listOf("hbo", "max")),
        PresetDef("哔哩哔哩", "bilibili", "CNY", SubscriptionCadence.MONTHLY, listOf("bilibili", "b站", "哔哩哔哩")),
        PresetDef("爱奇艺", "iqiyi", "CNY", SubscriptionCadence.MONTHLY, listOf("爱奇艺", "iqiyi")),
        PresetDef("腾讯视频", "tencent_video", "CNY", SubscriptionCadence.MONTHLY, listOf("腾讯视频")),
        PresetDef("优酷", "streaming", "CNY", SubscriptionCadence.MONTHLY, listOf("优酷", "youku")),
        PresetDef("芒果TV", "streaming", "CNY", SubscriptionCadence.MONTHLY, listOf("芒果tv", "mango")),
        PresetDef("Spotify", "spotify", "USD", SubscriptionCadence.MONTHLY, listOf("spotify")),
        PresetDef("网易云音乐", "netease_music", "CNY", SubscriptionCadence.MONTHLY, listOf("网易云", "netease")),
        PresetDef("QQ音乐", "music_cn", "CNY", SubscriptionCadence.MONTHLY, listOf("qq音乐", "qq music")),
        PresetDef("酷狗音乐", "music_cn", "CNY", SubscriptionCadence.MONTHLY, listOf("酷狗", "kugou")),
        PresetDef("汽水音乐", "music_cn", "CNY", SubscriptionCadence.MONTHLY, listOf("汽水音乐")),
        PresetDef("Apple Music", "audio", "CNY", SubscriptionCadence.MONTHLY, listOf("apple music")),
        PresetDef("喜马拉雅", "audio", "CNY", SubscriptionCadence.MONTHLY, listOf("喜马拉雅")),
        PresetDef("小宇宙", "audio", "CNY", SubscriptionCadence.MONTHLY, listOf("小宇宙")),
        // Cloud, Storage & Productivity
        PresetDef("iCloud", "icloud", "CNY", SubscriptionCadence.MONTHLY, listOf("icloud")),
        PresetDef("Apple One", "icloud", "USD", SubscriptionCadence.MONTHLY, listOf("apple one")),
        PresetDef("AppleCare", "icloud", "CNY", SubscriptionCadence.YEARLY, listOf("applecare")),
        PresetDef("Google One", "google_one", "USD", SubscriptionCadence.MONTHLY, listOf("google one", "google drive")),
        PresetDef("OneDrive", "cloud_drive", "CNY", SubscriptionCadence.YEARLY, listOf("onedrive")),
        PresetDef("Microsoft 365", "cloud_drive", "CNY", SubscriptionCadence.YEARLY, listOf("microsoft 365", "office 365")),
        PresetDef("Dropbox", "cloud_drive", "USD", SubscriptionCadence.MONTHLY, listOf("dropbox")),
        PresetDef("百度网盘", "cloud_drive", "CNY", SubscriptionCadence.MONTHLY, listOf("百度网盘")),
        PresetDef("阿里云盘", "cloud_drive", "CNY", SubscriptionCadence.MONTHLY, listOf("阿里云盘")),
        PresetDef("夸克网盘", "cloud_drive", "CNY", SubscriptionCadence.MONTHLY, listOf("夸克网盘")),
        PresetDef("Notion", "productivity", "USD", SubscriptionCadence.MONTHLY, listOf("notion")),
        PresetDef("Obsidian", "productivity", "USD", SubscriptionCadence.MONTHLY, listOf("obsidian")),
        PresetDef("1Password", "productivity", "USD", SubscriptionCadence.YEARLY, listOf("1password")),
        PresetDef("Bitwarden", "productivity", "USD", SubscriptionCadence.YEARLY, listOf("bitwarden")),
        PresetDef("Raycast", "productivity", "USD", SubscriptionCadence.MONTHLY, listOf("raycast")),
        PresetDef("Figma", "productivity", "USD", SubscriptionCadence.MONTHLY, listOf("figma")),
        PresetDef("Adobe", "productivity", "USD", SubscriptionCadence.MONTHLY, listOf("adobe", "photoshop")),
        PresetDef("WPS", "productivity", "CNY", SubscriptionCadence.YEARLY, listOf("wps")),
        // Infrastructure & Telecom
        PresetDef("Cloudflare", "server", "USD", SubscriptionCadence.MONTHLY, listOf("cloudflare")),
        PresetDef("AWS", "server", "USD", SubscriptionCadence.MONTHLY, listOf("aws")),
        PresetDef("阿里云", "server", "CNY", SubscriptionCadence.MONTHLY, listOf("阿里云", "aliyun")),
        PresetDef("腾讯云", "server", "CNY", SubscriptionCadence.MONTHLY, listOf("腾讯云")),
        PresetDef("Vultr", "server", "USD", SubscriptionCadence.MONTHLY, listOf("vultr", "vps")),
        PresetDef("DigitalOcean", "server", "USD", SubscriptionCadence.MONTHLY, listOf("digitalocean", "vps")),
        PresetDef("搬瓦工", "server", "USD", SubscriptionCadence.YEARLY, listOf("搬瓦工", "bandwagon", "vps")),
        PresetDef("手机话费", "telecom", "CNY", SubscriptionCadence.MONTHLY, listOf("话费", "手机", "流量", "移动", "联通", "电信")),
        PresetDef("宽带", "telecom", "CNY", SubscriptionCadence.MONTHLY, listOf("宽带")),
        PresetDef("eSIM", "telecom", "HKD", SubscriptionCadence.MONTHLY, listOf("esim", "港卡", "club sim")),
        // Shopping, Reading, Fitness & Gaming
        PresetDef("88VIP", "88vip", "CNY", SubscriptionCadence.YEARLY, listOf("88vip", "淘宝")),
        PresetDef("京东", "vip_shop", "CNY", SubscriptionCadence.YEARLY, listOf("京东plus", "jd plus", "京东")),
        PresetDef("山姆", "vip_shop", "CNY", SubscriptionCadence.YEARLY, listOf("山姆")),
        PresetDef("Costco", "vip_shop", "CNY", SubscriptionCadence.YEARLY, listOf("costco")),
        PresetDef("Amazon Prime", "vip_shop", "USD", SubscriptionCadence.YEARLY, listOf("amazon prime")),
        PresetDef("微信读书", "reading", "CNY", SubscriptionCadence.MONTHLY, listOf("微信读书")),
        PresetDef("Kindle", "reading", "USD", SubscriptionCadence.MONTHLY, listOf("kindle")),
        PresetDef("知乎", "reading", "CNY", SubscriptionCadence.MONTHLY, listOf("知乎")),
        PresetDef("得到", "reading", "CNY", SubscriptionCadence.YEARLY, listOf("得到")),
        PresetDef("Keep", "fitness", "CNY", SubscriptionCadence.MONTHLY, listOf("keep")),
        PresetDef("健身房", "fitness", "CNY", SubscriptionCadence.MONTHLY, listOf("健身", "gym")),
        PresetDef("Xbox", "gaming", "HKD", SubscriptionCadence.MONTHLY, listOf("xbox", "game pass")),
        PresetDef("PlayStation", "gaming", "HKD", SubscriptionCadence.YEARLY, listOf("playstation", "ps plus")),
        PresetDef("Nintendo Switch", "gaming", "HKD", SubscriptionCadence.YEARLY, listOf("nintendo", "switch")),
        PresetDef("Steam", "gaming", "CNY", SubscriptionCadence.MONTHLY, listOf("steam")),
        // Everyday Merchants (for AddTransactionDialog)
        PresetDef("星巴克", "starbucks", extraTerms = listOf("星巴克", "starbucks")),
        PresetDef("麦当劳", "mcdonalds", extraTerms = listOf("麦当劳", "mcdonald")),
        PresetDef("肯德基", "kfc", extraTerms = listOf("肯德基", "kfc")),
        PresetDef("美团", "meituan", extraTerms = listOf("美团", "meituan")),
        PresetDef("盒马", "hema", extraTerms = listOf("盒马", "hema")),
        PresetDef("海底捞", "haidilao", extraTerms = listOf("海底捞")),
        PresetDef("滴滴出行", "didi", extraTerms = listOf("滴滴", "didi")),
    )

    private val suggestions: List<MerchantSuggestion> by lazy {
        presetDefs.mapNotNull { def ->
            val spec = byKey[def.ruleKey] ?: return@mapNotNull null
            val ruleTerms = rules.firstOrNull { it.key == def.ruleKey }?.needles.orEmpty()
            MerchantSuggestion(
                displayName = def.displayName,
                spec = spec,
                defaultCurrency = def.defaultCurrency,
                defaultCadence = def.defaultCadence,
                searchTerms = (def.extraTerms + ruleTerms + def.ruleKey).distinct(),
            )
        }
    }

    fun suggestMerchants(query: String, limit: Int = 5): List<MerchantSuggestion> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        if (suggestions.any { it.displayName.equals(needle, ignoreCase = true) }) {
            return emptyList()
        }
        val scored = suggestions.mapNotNull { item ->
            val nameLower = item.displayName.lowercase()
            val score = when {
                nameLower.startsWith(needle) -> 0
                item.searchTerms.any { it.lowercase().startsWith(needle) } -> 1
                nameLower.contains(needle) -> 2
                item.searchTerms.any { it.lowercase().contains(needle) } -> 3
                else -> -1
            }
            if (score >= 0) score to item else null
        }
        return scored
            .sortedBy { it.first }
            .map { it.second }
            .distinctBy { it.displayName }
            .take(limit)
    }

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
