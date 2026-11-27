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
        Rule(listOf("瑞幸", "luckin"), "luckin", "瑞", 0xFF1D4ED8, "coffee"),
        Rule(listOf("库迪", "cotti"), "cotti", "库", 0xFF9F1239, "coffee"),
        Rule(listOf("蜜雪冰城", "mixue"), "mixue", "雪", 0xFFE11D48, "coffee"),
        Rule(listOf("盒马", "hema"), "hema", "盒", 0xFF1B8DD6, "store"),
        Rule(listOf("海底捞"), "haidilao", "海", 0xFFD94A3A, "restaurant"),
        Rule(listOf("麦当劳", "mcdonald"), "mcdonalds", "M", 0xFFDA291C, "restaurant"),
        Rule(listOf("肯德基", "kfc"), "kfc", "K", 0xFFB5121B, "restaurant"),
        Rule(listOf("塔斯汀", "tastien"), "tastien", "塔", 0xFFB91C1C, "restaurant"),
        Rule(listOf("达美乐", "domino"), "dominos", "达", 0xFF006491, "restaurant"),
        Rule(listOf("美团", "meituan"), "meituan", "美", 0xFFFFC300, "store"),
        Rule(listOf("美宜佳"), "meiyijia", "美", 0xFFE11D48, "store"),
        Rule(listOf("seven eleven", "7-eleven", "711"), "seven_eleven", "7", 0xFF008060, "store"),
        Rule(listOf("mannings", "万宁"), "mannings", "万", 0xFFFF6A00, "store"),
        Rule(listOf("京东", "jd.com", "京东商城"), "jd", "京", 0xFFD71A1B, "shopping"),
        Rule(listOf("1688", "阿里巴巴（中国）"), "alibaba_1688", "16", 0xFFFF6A00, "shopping"),
        Rule(listOf("淘宝", "taobao"), "taobao", "淘", 0xFFFF6A00, "shopping"),
        Rule(listOf("拼多多", "pinduoduo"), "pinduoduo", "拼", 0xFFE02E24, "shopping"),
        Rule(listOf("石头科技", "roborock"), "roborock", "石", 0xFFDC2626, "shopping"),
        Rule(listOf("抖音", "douyin"), "douyin", "抖", 0xFF18181B, "video"),
        Rule(listOf("滴滴", "didi"), "didi", "滴", 0xFFF5A623, "transport"),
        Rule(listOf("哈啰", "hellobike"), "hellobike", "哈", 0xFF0099FF, "transport"),
        Rule(listOf("货拉拉", "huolala"), "huolala", "货", 0xFFFF6A00, "transport"),
        Rule(listOf("丰巢", "fcbox"), "fcbox", "巢", 0xFFF59E0B, "shopping"),
        Rule(listOf("携程", "ctrip"), "ctrip", "携", 0xFF2563EB, "transport"),
        Rule(listOf("铁路12306", "中铁网络", "12306"), "railway_12306", "铁", 0xFF0284C7, "transport"),
        Rule(listOf("广州地铁"), "gz_metro", "穗", 0xFFB91C1C, "transport"),
        Rule(listOf("mtr", "港铁"), "mtr", "港", 0xFF9F1239, "transport"),
        Rule(listOf("地铁", "metro"), "metro", "地", 0xFF3B82F6, "transport"),
        Rule(listOf("公交", "bus"), "bus", "公", 0xFF2563EB, "transport"),
        Rule(listOf("中国邮政", "ems"), "china_post", "邮", 0xFF15803D, "shopping"),
        Rule(listOf("中国银行", "中行"), "boc", "中", 0xFFB91C1C, "transfer"),
        Rule(listOf("工商银行", "工行"), "icbc", "工", 0xFFC81E1E, "transfer"),
        Rule(listOf("微信", "wechat"), "wechat", "微", 0xFF07C160, "transfer"),
        Rule(listOf("支付宝", "alipay", "花呗", "余额宝"), "alipay", "支", 0xFF1677FF, "transfer"),
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
        Rule(listOf("grok", "xai", "x premium", "twitter"), "grok", "X", 0xFF181A1F, "ai"),
        Rule(listOf("perplexity"), "perplexity", "P", 0xFF1FB8CD, "ai"),
        Rule(listOf("cursor"), "cursor", "C", 0xFF23272E, "code"),
        Rule(listOf("copilot"), "copilot", "C", 0xFF24292F, "code"),
        Rule(listOf("豆包", "doubao"), "doubao", "豆", 0xFF3C8CFF, "ai"),
        Rule(listOf("通义", "qwen"), "qwen", "问", 0xFF615CED, "ai"),
        Rule(listOf("opencode", "code goat", "windsurf", "trae"), "coding_ai", "C", 0xFF2B313B, "code"),
        Rule(listOf("deepseek", "深度求索"), "deepseek", "D", 0xFF4D6BFE, "ai"),
        Rule(listOf("midjourney", "mj"), "midjourney", "M", 0xFF1E293B, "ai"),
        Rule(listOf("poe"), "poe", "P", 0xFF5B4FE9, "ai"),
        Rule(listOf("suno"), "suno", "S", 0xFFFF6B35, "ai"),
        Rule(listOf("runway"), "runway", "R", 0xFF111827, "ai"),
        Rule(listOf("kimi"), "kimi", "K", 0xFF111827, "ai"),
        Rule(listOf("智谱", "zhipu"), "zhipu", "智", 0xFF2563EB, "ai"),
        Rule(listOf("github"), "github", "G", 0xFF24292F, "code"),
        // Streaming Video & Music
        Rule(listOf("youtube", "油管"), "youtube", "Y", 0xFFC81E1E, "video"),
        Rule(listOf("netflix", "奈飞", "网飞"), "netflix", "N", 0xFFB91C1C, "movie"),
        Rule(listOf("disney"), "disney", "D", 0xFF0D5C63, "movie"),
        Rule(listOf("bilibili", "b站", "哔哩哔哩"), "bilibili", "B", 0xFFE05279, "video"),
        Rule(listOf("爱奇艺", "iqiyi"), "iqiyi", "爱", 0xFF1F8A5B, "video"),
        Rule(listOf("腾讯视频", "腾迅视频"), "tencent_video", "腾", 0xFF2563EB, "video"),
        Rule(listOf("apple tv"), "apple_tv", "tv", 0xFF1D1F23, "movie"),
        Rule(listOf("prime video"), "prime_video", "P", 0xFF00A8E1, "movie"),
        Rule(listOf("hbo", "hbo max"), "hbo_max", "H", 0xFF1E1B4B, "movie"),
        Rule(listOf("优酷", "youku"), "youku", "优", 0xFF0099FF, "video"),
        Rule(listOf("芒果tv", "mango"), "mango_tv", "芒", 0xFFFF6A00, "video"),
        Rule(listOf("spotify"), "spotify", "S", 0xFF169C46, "music"),
        Rule(listOf("网易云", "netease"), "netease_music", "网", 0xFFC82828, "music"),
        Rule(listOf("qq音乐", "qq music"), "qq_music", "Q", 0xFF1D8E4E, "music"),
        Rule(listOf("酷狗", "kugou"), "kugou", "酷", 0xFF2563EB, "music"),
        Rule(listOf("汽水音乐"), "qishui", "汽", 0xFF10B981, "music"),
        Rule(listOf("酷我"), "music_cn", "音", 0xFF1D8E4E, "music"),
        Rule(listOf("apple music"), "apple_music", "M", 0xFFFA243C, "music"),
        Rule(listOf("喜马拉雅"), "ximalaya", "喜", 0xFFFF4D2E, "music"),
        Rule(listOf("小宇宙"), "xiaoyuzhou", "宇", 0xFF38BDF8, "music"),
        // Cloud, Storage & Productivity
        Rule(listOf("icloud", "apple one", "applecare"), "icloud", "i", 0xFF2F80ED, "cloud"),
        Rule(listOf("google one", "google drive"), "google_one", "G", 0xFF2563EB, "cloud"),
        Rule(listOf("dropbox"), "dropbox", "D", 0xFF0061FF, "cloud"),
        Rule(listOf("onedrive"), "onedrive", "O", 0xFF0078D4, "cloud"),
        Rule(listOf("microsoft 365", "office 365"), "microsoft_365", "M", 0xFF2563EB, "cloud"),
        Rule(listOf("百度网盘"), "baidu", "百", 0xFF2563EB, "cloud"),
        Rule(listOf("阿里云盘"), "aliyun_drive", "云", 0xFF4F46E5, "cloud"),
        Rule(listOf("夸克网盘", "夸克", "动景"), "quark", "夸", 0xFF2563EB, "cloud"),
        Rule(listOf("飞书", "feishu", "lark"), "feishu", "飞", 0xFF00D6B9, "productivity"),
        Rule(listOf("1password"), "1password", "1", 0xFF0572EC, "productivity"),
        Rule(listOf("bitwarden"), "bitwarden", "B", 0xFF175DDC, "productivity"),
        Rule(listOf("raycast"), "raycast", "R", 0xFFFF6363, "productivity"),
        Rule(listOf("obsidian"), "obsidian", "O", 0xFF7C3AED, "productivity"),
        Rule(listOf("figma"), "figma", "F", 0xFF1E1E1E, "productivity"),
        Rule(listOf("adobe", "photoshop"), "adobe", "A", 0xFFDA1F26, "productivity"),
        Rule(listOf("notion"), "notion", "N", 0xFF1F2937, "productivity"),
        Rule(listOf("wps"), "wps", "W", 0xFFEF4444, "productivity"),
        // Infrastructure & Telecom
        Rule(listOf("cloudflare"), "cloudflare", "C", 0xFFF38020, "server"),
        Rule(listOf("aliyun", "阿里云"), "aliyun", "阿", 0xFFFF6A00, "server"),
        Rule(listOf("腾讯云"), "tencent_cloud", "腾", 0xFF006EFF, "server"),
        Rule(listOf("aws"), "aws", "A", 0xFF232F3E, "server"),
        Rule(listOf("digitalocean"), "digitalocean", "D", 0xFF0080FF, "server"),
        Rule(listOf("vultr"), "vultr", "V", 0xFF007BFC, "server"),
        Rule(listOf("porkbun"), "porkbun", "P", 0xFFEF7E7D, "server"),
        Rule(listOf("vps", "服务器", "云主机", "bandwagon", "搬瓦工", "域名", "domain"), "server", "V", 0xFF2B3540, "server"),
        Rule(listOf("中国电信"), "china_telecom", "电", 0xFF0066CC, "sim"),
        Rule(listOf("中国移动", "china mobile"), "china_mobile", "移", 0xFF0085D0, "sim"),
        Rule(listOf("中国联通"), "china_unicom", "联", 0xFFE60012, "sim"),
        Rule(listOf("话费", "手机", "流量", "移动", "联通", "电信", "港卡", "sim", "esim", "cls", "club sim", "宽带"), "telecom", "卡", 0xFF1E6B5E, "sim"),
        // Shopping, Reading, Fitness & Gaming
        Rule(listOf("88vip", "天猫"), "88vip", "8", 0xFFD95B26, "shopping"),
        Rule(listOf("amazon prime"), "amazon", "A", 0xFF146EB4, "shopping"),
        Rule(listOf("山姆"), "sams_club", "山", 0xFF0067A0, "shopping"),
        Rule(listOf("costco"), "costco", "C", 0xFFE31837, "shopping"),
        Rule(listOf("小红书"), "xiaohongshu", "红", 0xFFFF2442, "shopping"),
        Rule(listOf("微信读书"), "weread", "读", 0xFF2563EB, "book"),
        Rule(listOf("kindle"), "kindle", "K", 0xFF1E293B, "book"),
        Rule(listOf("知乎"), "zhihu", "知", 0xFF0066FF, "book"),
        Rule(listOf("得到"), "dedao", "得", 0xFFFF6B00, "book"),
        Rule(listOf("墨墨", "maimemo"), "maimemo", "墨", 0xFF27272A, "education"),
        Rule(listOf("三国杀", "杭州游卡", "游卡"), "sanguosha", "杀", 0xFFB91C1C, "game"),
        Rule(listOf("keep"), "keep", "K", 0xFF4338CA, "fitness"),
        Rule(listOf("健身", "gym", "apple fitness"), "fitness", "健", 0xFF2F5E4E, "fitness"),
        Rule(listOf("xbox", "game pass"), "xbox", "X", 0xFF107C10, "game"),
        Rule(listOf("playstation", "ps plus"), "playstation", "P", 0xFF003791, "game"),
        Rule(listOf("nintendo", "switch"), "nintendo", "N", 0xFFE60012, "game"),
        Rule(listOf("steam", "valve"), "steam", "S", 0xFF1B2838, "game"),
        Rule(listOf("geforce"), "gaming", "游", 0xFF1F6E3D, "game"),
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
        PresetDef("Cursor", "cursor", "USD", SubscriptionCadence.MONTHLY, listOf("cursor")),
        PresetDef("GitHub", "github", "USD", SubscriptionCadence.MONTHLY, listOf("github")),
        PresetDef("GitHub Copilot", "copilot", "USD", SubscriptionCadence.MONTHLY, listOf("copilot")),
        PresetDef("Grok", "grok", "USD", SubscriptionCadence.MONTHLY, listOf("grok", "xai", "x", "twitter")),
        PresetDef("Perplexity", "perplexity", "USD", SubscriptionCadence.MONTHLY, listOf("perplexity")),
        PresetDef("Codex", "codex", "USD", SubscriptionCadence.MONTHLY, listOf("codex")),
        PresetDef("Midjourney", "midjourney", "USD", SubscriptionCadence.MONTHLY, listOf("midjourney", "mj")),
        PresetDef("Poe", "poe", "USD", SubscriptionCadence.MONTHLY, listOf("poe")),
        PresetDef("Suno", "suno", "USD", SubscriptionCadence.MONTHLY, listOf("suno")),
        PresetDef("Runway", "runway", "USD", SubscriptionCadence.MONTHLY, listOf("runway")),
        PresetDef("Kimi", "kimi", "CNY", SubscriptionCadence.MONTHLY, listOf("kimi")),
        PresetDef("DeepSeek", "deepseek", "CNY", SubscriptionCadence.MONTHLY, listOf("deepseek")),
        PresetDef("豆包", "doubao", "CNY", SubscriptionCadence.MONTHLY, listOf("豆包", "doubao")),
        PresetDef("通义千问", "qwen", "CNY", SubscriptionCadence.MONTHLY, listOf("通义", "qwen")),
        PresetDef("智谱清言", "zhipu", "CNY", SubscriptionCadence.MONTHLY, listOf("智谱", "zhipu")),
        // Streaming Video & Music
        PresetDef("YouTube", "youtube", "USD", SubscriptionCadence.MONTHLY, listOf("youtube", "yt", "油管")),
        PresetDef("Netflix", "netflix", "USD", SubscriptionCadence.MONTHLY, listOf("netflix", "nf", "奈飞", "网飞")),
        PresetDef("Disney+", "disney", "USD", SubscriptionCadence.MONTHLY, listOf("disney")),
        PresetDef("Apple TV", "apple_tv", "USD", SubscriptionCadence.MONTHLY, listOf("apple tv")),
        PresetDef("Prime Video", "prime_video", "USD", SubscriptionCadence.MONTHLY, listOf("prime video")),
        PresetDef("HBO Max", "hbo_max", "USD", SubscriptionCadence.MONTHLY, listOf("hbo", "max")),
        PresetDef("哔哩哔哩", "bilibili", "CNY", SubscriptionCadence.MONTHLY, listOf("bilibili", "b站", "哔哩哔哩")),
        PresetDef("爱奇艺", "iqiyi", "CNY", SubscriptionCadence.MONTHLY, listOf("爱奇艺", "iqiyi")),
        PresetDef("腾讯视频", "tencent_video", "CNY", SubscriptionCadence.MONTHLY, listOf("腾讯视频")),
        PresetDef("优酷", "youku", "CNY", SubscriptionCadence.MONTHLY, listOf("优酷", "youku")),
        PresetDef("芒果TV", "mango_tv", "CNY", SubscriptionCadence.MONTHLY, listOf("芒果tv", "mango")),
        PresetDef("Spotify", "spotify", "USD", SubscriptionCadence.MONTHLY, listOf("spotify")),
        PresetDef("网易云音乐", "netease_music", "CNY", SubscriptionCadence.MONTHLY, listOf("网易云", "netease")),
        PresetDef("QQ音乐", "qq_music", "CNY", SubscriptionCadence.MONTHLY, listOf("qq音乐", "qq music")),
        PresetDef("酷狗音乐", "kugou", "CNY", SubscriptionCadence.MONTHLY, listOf("酷狗", "kugou")),
        PresetDef("汽水音乐", "qishui", "CNY", SubscriptionCadence.MONTHLY, listOf("汽水音乐")),
        PresetDef("Apple Music", "apple_music", "CNY", SubscriptionCadence.MONTHLY, listOf("apple music")),
        PresetDef("喜马拉雅", "ximalaya", "CNY", SubscriptionCadence.MONTHLY, listOf("喜马拉雅")),
        PresetDef("小宇宙", "xiaoyuzhou", "CNY", SubscriptionCadence.MONTHLY, listOf("小宇宙")),
        // Cloud, Storage & Productivity
        PresetDef("iCloud", "icloud", "CNY", SubscriptionCadence.MONTHLY, listOf("icloud")),
        PresetDef("Apple One", "icloud", "USD", SubscriptionCadence.MONTHLY, listOf("apple one")),
        PresetDef("AppleCare", "icloud", "CNY", SubscriptionCadence.YEARLY, listOf("applecare")),
        PresetDef("Google One", "google_one", "USD", SubscriptionCadence.MONTHLY, listOf("google one", "google drive")),
        PresetDef("OneDrive", "onedrive", "CNY", SubscriptionCadence.YEARLY, listOf("onedrive")),
        PresetDef("Microsoft 365", "microsoft_365", "CNY", SubscriptionCadence.YEARLY, listOf("microsoft 365", "office 365")),
        PresetDef("Dropbox", "dropbox", "USD", SubscriptionCadence.MONTHLY, listOf("dropbox")),
        PresetDef("百度网盘", "baidu", "CNY", SubscriptionCadence.MONTHLY, listOf("百度网盘", "baidu")),
        PresetDef("阿里云盘", "aliyun_drive", "CNY", SubscriptionCadence.MONTHLY, listOf("阿里云盘")),
        PresetDef("夸克网盘", "quark", "CNY", SubscriptionCadence.MONTHLY, listOf("夸克网盘")),
        PresetDef("Notion", "notion", "USD", SubscriptionCadence.MONTHLY, listOf("notion")),
        PresetDef("Obsidian", "obsidian", "USD", SubscriptionCadence.MONTHLY, listOf("obsidian")),
        PresetDef("1Password", "1password", "USD", SubscriptionCadence.YEARLY, listOf("1password")),
        PresetDef("Bitwarden", "bitwarden", "USD", SubscriptionCadence.YEARLY, listOf("bitwarden")),
        PresetDef("Raycast", "raycast", "USD", SubscriptionCadence.MONTHLY, listOf("raycast")),
        PresetDef("Figma", "figma", "USD", SubscriptionCadence.MONTHLY, listOf("figma")),
        PresetDef("Adobe", "adobe", "USD", SubscriptionCadence.MONTHLY, listOf("adobe", "photoshop")),
        PresetDef("WPS", "wps", "CNY", SubscriptionCadence.YEARLY, listOf("wps")),
        // Infrastructure & Telecom
        PresetDef("Cloudflare", "cloudflare", "USD", SubscriptionCadence.MONTHLY, listOf("cloudflare")),
        PresetDef("AWS", "aws", "USD", SubscriptionCadence.MONTHLY, listOf("aws")),
        PresetDef("阿里云", "aliyun", "CNY", SubscriptionCadence.MONTHLY, listOf("阿里云", "aliyun")),
        PresetDef("腾讯云", "tencent_cloud", "CNY", SubscriptionCadence.MONTHLY, listOf("腾讯云")),
        PresetDef("Vultr", "vultr", "USD", SubscriptionCadence.MONTHLY, listOf("vultr", "vps")),
        PresetDef("DigitalOcean", "digitalocean", "USD", SubscriptionCadence.MONTHLY, listOf("digitalocean", "vps")),
        PresetDef("搬瓦工", "server", "USD", SubscriptionCadence.YEARLY, listOf("搬瓦工", "bandwagon", "vps")),
        PresetDef("手机话费", "telecom", "CNY", SubscriptionCadence.MONTHLY, listOf("话费", "手机", "流量", "移动", "联通", "电信")),
        PresetDef("宽带", "telecom", "CNY", SubscriptionCadence.MONTHLY, listOf("宽带")),
        PresetDef("eSIM", "telecom", "HKD", SubscriptionCadence.MONTHLY, listOf("esim", "港卡", "club sim")),
        // Shopping, Reading, Fitness & Gaming
        PresetDef("88VIP", "88vip", "CNY", SubscriptionCadence.YEARLY, listOf("88vip", "淘宝")),
        PresetDef("京东", "jd", "CNY", SubscriptionCadence.YEARLY, listOf("京东plus", "jd plus", "京东")),
        PresetDef("山姆", "sams_club", "CNY", SubscriptionCadence.YEARLY, listOf("山姆")),
        PresetDef("Costco", "costco", "CNY", SubscriptionCadence.YEARLY, listOf("costco")),
        PresetDef("Amazon Prime", "amazon", "USD", SubscriptionCadence.YEARLY, listOf("amazon prime")),
        PresetDef("微信读书", "weread", "CNY", SubscriptionCadence.MONTHLY, listOf("微信读书")),
        PresetDef("Kindle", "kindle", "USD", SubscriptionCadence.MONTHLY, listOf("kindle")),
        PresetDef("知乎", "zhihu", "CNY", SubscriptionCadence.MONTHLY, listOf("知乎")),
        PresetDef("得到", "dedao", "CNY", SubscriptionCadence.YEARLY, listOf("得到")),
        PresetDef("Keep", "keep", "CNY", SubscriptionCadence.MONTHLY, listOf("keep")),
        PresetDef("健身房", "fitness", "CNY", SubscriptionCadence.MONTHLY, listOf("健身", "gym")),
        PresetDef("Xbox", "xbox", "HKD", SubscriptionCadence.MONTHLY, listOf("xbox", "game pass")),
        PresetDef("PlayStation", "playstation", "HKD", SubscriptionCadence.YEARLY, listOf("playstation", "ps plus")),
        PresetDef("Nintendo Switch", "nintendo", "HKD", SubscriptionCadence.YEARLY, listOf("nintendo", "switch")),
        PresetDef("Steam", "steam", "CNY", SubscriptionCadence.MONTHLY, listOf("steam")),
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
