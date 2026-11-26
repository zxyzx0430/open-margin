package com.bookkeeping.app.ui.components

import androidx.annotation.DrawableRes
import com.bookkeeping.app.R

/**
 * Authentic multi-color official brand vector drawable resources keyed by [MerchantIconCatalog] rule key.
 */
@DrawableRes
fun brandLogoDrawableRes(key: String?): Int? = when (key) {
    "chatgpt", "codex" -> R.drawable.ic_brand_chatgpt
    "google_one" -> R.drawable.ic_brand_google_one
    "youtube" -> R.drawable.ic_brand_youtube
    "netflix" -> R.drawable.ic_brand_netflix
    "spotify" -> R.drawable.ic_brand_spotify
    "icloud" -> R.drawable.ic_brand_icloud
    "disney" -> R.drawable.ic_brand_disney
    "bilibili" -> R.drawable.ic_brand_bilibili
    "claude" -> R.drawable.ic_brand_claude
    "gemini" -> R.drawable.ic_brand_gemini
    "github" -> R.drawable.ic_brand_github
    "netease_music" -> R.drawable.ic_brand_netease_music
    "music_cn", "tencent_video" -> R.drawable.ic_brand_tencent_qq
    "audio" -> R.drawable.ic_brand_apple_music
    "cloud_drive" -> R.drawable.ic_brand_onedrive
    "streaming" -> R.drawable.ic_brand_prime_video
    "cloudflare", "server" -> R.drawable.ic_brand_cloudflare
    "wechat" -> R.drawable.ic_brand_wechat
    "alipay" -> R.drawable.ic_brand_alipay
    "88vip" -> R.drawable.ic_brand_taobao
    "baidu" -> R.drawable.ic_brand_baidu
    "productivity" -> R.drawable.ic_brand_notion
    "reading" -> R.drawable.ic_brand_zhihu
    "gaming" -> R.drawable.ic_brand_xbox
    "starbucks" -> R.drawable.ic_brand_starbucks
    "mcdonalds" -> R.drawable.ic_brand_mcdonalds
    "kfc" -> R.drawable.ic_brand_kfc
    else -> null
}
