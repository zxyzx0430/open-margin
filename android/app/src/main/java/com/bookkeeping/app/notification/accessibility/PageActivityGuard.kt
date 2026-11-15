package com.bookkeeping.app.notification.accessibility

/**
 * Pure guard that keeps chat/contact/home screens out of payment-card parsing.
 *
 * A chat list can contain a "微信支付" row whose preview says "已支付 ¥2.00".
 * That single row is enough to satisfy the page-level payment checks, and the
 * surrounding chat names then get parsed as merchants. Page shape, not payment
 * keywords, must decide whether a screen is a chat surface.
 */
object PageActivityGuard {
    private val WECHAT_CHAT_ACTIVITIES = listOf(
        "launcherui", "chattingui", "conversation", "contact",
        "sns", "finder", "voip", "fts", "search", "brandservice",
    )
    private val ALIPAY_HOME_ACTIVITIES = listOf(
        "launcherui", "homeactivity", "home", "message", "chat",
        "contact", "social", "friend", "discovery", "search",
    )

    fun isChatOrHomePage(
        packageName: String,
        activity: String,
        pageText: String,
    ): Boolean {
        val activityName = activity.lowercase()
        return when (packageName) {
            PaymentPageDetector.PKG_WECHAT -> {
                WECHAT_CHAT_ACTIVITIES.any { activityName.contains(it) } ||
                    looksLikeWechatTabShell(pageText) ||
                    looksLikeWechatConversation(pageText) ||
                    looksLikeWechatContactProfile(pageText) ||
                    looksLikeWechatSearch(pageText) ||
                    looksLikeWechatArticle(pageText) ||
                    containsGroupCollectionOrNegative(pageText)
            }
            PaymentPageDetector.PKG_ALIPAY -> {
                ALIPAY_HOME_ACTIVITIES.any { activityName.contains(it) } ||
                    looksLikeAlipayTabShell(pageText) ||
                    containsGroupCollectionOrNegative(pageText)
            }
            else -> false
        }
    }

    /** Main tab bar labels ("微信 / 通讯录 / 发现"). Payment details never show them. */
    private fun looksLikeWechatTabShell(text: String): Boolean =
        text.contains("微信") && text.contains("通讯录") && text.contains("发现")

    private fun looksLikeWechatConversation(text: String): Boolean =
        text.contains("表情") && (text.contains("按住") || text.contains("语音") || text.contains("发送"))

    private fun looksLikeWechatContactProfile(text: String): Boolean =
        text.contains("微信号") && (text.contains("发消息") || text.contains("朋友圈"))

    private fun looksLikeWechatSearch(text: String): Boolean =
        text.contains("搜索") && text.contains("取消") && text.contains("清除")

    /** Official-account article; WebView itself is also used by merchant payment results. */
    private fun looksLikeWechatArticle(text: String): Boolean =
        text.contains("阅读原文") ||
            (text.contains("写留言") && text.contains("赞赏"))

    private fun containsGroupCollectionOrNegative(text: String): Boolean =
        listOf(
            "你无需支付", "无需支付",
            "他人已支付", "对方已支付", "由对方支付",
        ).any { text.contains(it) }

    /** Alipay main tab bar: "首页 / 理财 / 生活 / 消息 / 我的". */
    private fun looksLikeAlipayTabShell(text: String): Boolean =
        text.contains("首页") &&
            text.contains("我的") &&
            listOf("理财", "生活", "消息", "朋友").any { text.contains(it) }
}
