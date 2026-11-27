package com.bookkeeping.app.ui.components

import androidx.annotation.DrawableRes
import com.bookkeeping.app.R

/**
 * Authentic multi-color official brand vector drawable resources keyed by [MerchantIconCatalog] rule key.
 */
@DrawableRes
fun brandLogoDrawableRes(key: String?): Int? = when (key) {
    "chatgpt", "codex" -> R.drawable.ic_brand_chatgpt
    "claude" -> R.drawable.ic_brand_claude
    "gemini" -> R.drawable.ic_brand_gemini
    "grok" -> R.drawable.ic_brand_grok
    "perplexity" -> R.drawable.ic_brand_perplexity
    "cursor" -> R.drawable.ic_brand_cursor
    "copilot" -> R.drawable.ic_brand_copilot
    "github" -> R.drawable.ic_brand_github
    "doubao" -> R.drawable.ic_brand_doubao
    "qwen" -> R.drawable.ic_brand_qwen
    "deepseek" -> R.drawable.ic_brand_deepseek
    "midjourney" -> R.drawable.ic_brand_midjourney
    "poe" -> R.drawable.ic_brand_poe
    "suno" -> R.drawable.ic_brand_suno
    "runway" -> R.drawable.ic_brand_runway
    "kimi" -> R.drawable.ic_brand_kimi
    "zhipu" -> R.drawable.ic_brand_zhipu
    "youtube" -> R.drawable.ic_brand_youtube
    "netflix" -> R.drawable.ic_brand_netflix
    "disney" -> R.drawable.ic_brand_disney
    "apple_tv" -> R.drawable.ic_brand_apple_tv
    "prime_video" -> R.drawable.ic_brand_prime_video
    "hbo_max" -> R.drawable.ic_brand_hbo_max
    "bilibili" -> R.drawable.ic_brand_bilibili
    "iqiyi" -> R.drawable.ic_brand_iqiyi
    "tencent_video" -> R.drawable.ic_brand_tencent_video
    "youku" -> R.drawable.ic_brand_youku
    "mango_tv" -> R.drawable.ic_brand_mango_tv
    "spotify" -> R.drawable.ic_brand_spotify
    "netease_music" -> R.drawable.ic_brand_netease_music
    "qq_music" -> R.drawable.ic_brand_qq_music
    "kugou" -> R.drawable.ic_brand_kugou
    "qishui" -> R.drawable.ic_brand_qishui
    "apple_music" -> R.drawable.ic_brand_apple_music
    "ximalaya" -> R.drawable.ic_brand_ximalaya
    "xiaoyuzhou" -> R.drawable.ic_brand_xiaoyuzhou
    "icloud" -> R.drawable.ic_brand_icloud
    "google_one" -> R.drawable.ic_brand_google_one
    "dropbox" -> R.drawable.ic_brand_dropbox
    "onedrive" -> R.drawable.ic_brand_onedrive
    "microsoft_365" -> R.drawable.ic_brand_microsoft_365
    "baidu" -> R.drawable.ic_brand_baidu
    "aliyun_drive" -> R.drawable.ic_brand_aliyun_drive
    "quark" -> R.drawable.ic_brand_quark
    "1password" -> R.drawable.ic_brand_onepassword
    "bitwarden" -> R.drawable.ic_brand_bitwarden
    "raycast" -> R.drawable.ic_brand_raycast
    "obsidian" -> R.drawable.ic_brand_obsidian
    "figma" -> R.drawable.ic_brand_figma
    "adobe" -> R.drawable.ic_brand_adobe
    "notion" -> R.drawable.ic_brand_notion
    "wps" -> R.drawable.ic_brand_wps
    "cloudflare" -> R.drawable.ic_brand_cloudflare
    "aliyun" -> R.drawable.ic_brand_aliyun
    "tencent_cloud" -> R.drawable.ic_brand_tencent_cloud
    "aws" -> R.drawable.ic_brand_aws
    "digitalocean" -> R.drawable.ic_brand_digitalocean
    "vultr" -> R.drawable.ic_brand_vultr
    "wechat" -> R.drawable.ic_brand_wechat
    "alipay" -> R.drawable.ic_brand_alipay
    "xiaohongshu" -> R.drawable.ic_brand_xiaohongshu
    "88vip", "taobao" -> R.drawable.ic_brand_taobao
    "jd" -> R.drawable.ic_brand_jd
    "sams_club" -> R.drawable.ic_brand_sams_club
    "costco" -> R.drawable.ic_brand_costco
    "pinduoduo" -> R.drawable.ic_brand_pinduoduo
    "amazon" -> R.drawable.ic_brand_amazon
    "weread" -> R.drawable.ic_brand_weread
    "kindle" -> R.drawable.ic_brand_kindle
    "zhihu" -> R.drawable.ic_brand_zhihu
    "dedao" -> R.drawable.ic_brand_dedao
    "keep" -> R.drawable.ic_brand_keep
    "xbox" -> R.drawable.ic_brand_xbox
    "playstation" -> R.drawable.ic_brand_playstation
    "nintendo" -> R.drawable.ic_brand_nintendo
    "steam" -> R.drawable.ic_brand_steam
    "starbucks" -> R.drawable.ic_brand_starbucks
    "mcdonalds" -> R.drawable.ic_brand_mcdonalds
    "kfc" -> R.drawable.ic_brand_kfc
    "meituan" -> R.drawable.ic_brand_meituan
    "hema" -> R.drawable.ic_brand_hema
    "haidilao" -> R.drawable.ic_brand_haidilao
    "didi" -> R.drawable.ic_brand_didi
    else -> null
}
