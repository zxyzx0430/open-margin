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
    "grok" -> R.drawable.ic_brand_x
    "perplexity" -> R.drawable.ic_brand_perplexity
    "cursor", "coding_ai" -> R.drawable.ic_brand_cursor
    "copilot" -> R.drawable.ic_brand_copilot
    "github" -> R.drawable.ic_brand_github
    "doubao" -> R.drawable.ic_brand_doubao
    "qwen" -> R.drawable.ic_brand_qwen
    "deepseek" -> R.drawable.ic_brand_deepseek
    "netease_music" -> R.drawable.ic_brand_netease_music
    "music_cn" -> R.drawable.ic_brand_tencent_qq
    "tencent_video" -> R.drawable.ic_brand_tencent_video
    "audio" -> R.drawable.ic_brand_apple_music
    "apple_tv" -> R.drawable.ic_brand_apple_tv
    "dropbox" -> R.drawable.ic_brand_dropbox
    "cloud_drive" -> R.drawable.ic_brand_onedrive
    "streaming" -> R.drawable.ic_brand_prime_video
    "1password" -> R.drawable.ic_brand_1password
    "bitwarden" -> R.drawable.ic_brand_bitwarden
    "raycast" -> R.drawable.ic_brand_raycast
    "obsidian" -> R.drawable.ic_brand_obsidian
    "figma" -> R.drawable.ic_brand_figma
    "adobe" -> R.drawable.ic_brand_adobe
    "productivity" -> R.drawable.ic_brand_notion
    "cloudflare", "server" -> R.drawable.ic_brand_cloudflare
    "aliyun" -> R.drawable.ic_brand_aliyun
    "aws" -> R.drawable.ic_brand_aws
    "digitalocean" -> R.drawable.ic_brand_digitalocean
    "vultr" -> R.drawable.ic_brand_vultr
    "wechat" -> R.drawable.ic_brand_wechat
    "alipay" -> R.drawable.ic_brand_alipay
    "88vip" -> R.drawable.ic_brand_taobao
    "amazon" -> R.drawable.ic_brand_amazon
    "baidu" -> R.drawable.ic_brand_baidu
    "meituan" -> R.drawable.ic_brand_meituan
    "reading" -> R.drawable.ic_brand_zhihu
    "gaming" -> R.drawable.ic_brand_xbox
    "starbucks" -> R.drawable.ic_brand_starbucks
    "mcdonalds" -> R.drawable.ic_brand_mcdonalds
    "kfc" -> R.drawable.ic_brand_kfc
    else -> null
}
