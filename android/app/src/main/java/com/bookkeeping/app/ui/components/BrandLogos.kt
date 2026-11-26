package com.bookkeeping.app.ui.components

import androidx.annotation.DrawableRes
import com.bookkeeping.app.R

/**
 * Official brand vector drawable resources keyed by [MerchantIconCatalog] rule key.
 */
@DrawableRes
fun brandLogoDrawableRes(key: String?): Int? = when (key) {
    "chatgpt" -> R.drawable.ic_brand_chatgpt
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
    "audio" -> R.drawable.ic_brand_apple_music
    "cloudflare" -> R.drawable.ic_brand_cloudflare
    "wechat" -> R.drawable.ic_brand_wechat
    "alipay" -> R.drawable.ic_brand_alipay
    "88vip" -> R.drawable.ic_brand_taobao
    "baidu" -> R.drawable.ic_brand_baidu
    else -> null
}
