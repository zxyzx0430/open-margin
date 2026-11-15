package com.bookkeeping.app.notification.accessibility

/**
 * Pure guard that keeps chat/contact/home screens out of payment-card parsing.
 *
 * A chat list can contain a "微信支付" row whose preview says "已支付 ¥2.00".
 * That single row is enough to satisfy the page-level payment checks, and the
 * surrounding chat names then get parsed as merchants. Page shape, not payment
 * keywords, must decide whether a screen is a chat surface.
 *
 * Mini-program and H5 containers are shared by real payment result pages, so
 * they are only exempted when the page itself carries a strong success status
 * and an amount. Chat, search, service-account and article pages never are.
 */
object PageActivityGuard {
    private val WECHAT_CHAT_ACTIVITIES = listOf(
        "launcherui", "chattingui", "conversation", "contact",
        "sns", "finder", "voip", "fts", "search", "brandservice",
    )
    private val WECHAT_CONTAINER_ACTIVITIES = listOf(
        "wxalite", "webview", "appbrand",
    )
    private val ALIPAY_HOME_ACTIVITIES = listOf(
        "launcherui", "homeactivity", "home", "message", "chat",
        "contact", "social", "friend", "discovery", "search",
    )
    private val STRONG_SUCCESS = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "付款完成",
        "扣款成功", "消费成功", "转账成功", "收款成功", "已收钱", "已收款",
    )
    private val AMOUNT = Regex("""[¥￥]\s*\d{1,9}(?:\.\d{1,2})?|\d{1,9}\.\d{2}\s*元""")

    fun isChatOrHomePage(
        packageName: String,
        activity: String,
        pageText: String,
    ): Boolean {
        if (containsGroupCollectionWithoutOwnPayment(pageText)) return true
        val activityName = activity.lowercase()
        return when (packageName) {
            PaymentPageDetector.PKG_WECHAT -> when {
                WECHAT_CHAT_ACTIVITIES.any { activityName.contains(it) } -> true
                WECHAT_CONTAINER_ACTIVITIES.any { activityName.contains(it) } ->
                    !looksLikeStrongPaymentResult(pageText)
                looksLikeWechatTabShell(pageText) -> true
                looksLikeWechatConversation(pageText) -> true
                looksLikeWechatContactProfile(pageText) -> true
                looksLikeWechatSearch(pageText) -> true
                looksLikeWechatArticle(pageText) -> true
                else -> false
            }
            PaymentPageDetector.PKG_ALIPAY -> when {
                ALIPAY_HOME_ACTIVITIES.any { activityName.contains(it) } -> true
                // Sub-pages keep the tab bar, so a strong result page wins over it.
                looksLikeAlipayTabShell(pageText) -> !looksLikeStrongPaymentResult(pageText)
                else -> false
            }
            else -> false
        }
    }

    /**
     * Window-state events also report dialogs, popups and input methods. Only
     * activity-like class names may replace the remembered page activity.
     */
    fun isActivityClassName(packageName: String, className: String): Boolean {
        if (!className.startsWith(packageName)) return false
        val simple = className.substringAfterLast('.')
        if (simple.isEmpty()) return false
        if (listOf("Dialog", "Popup", "View", "Layout", "InputMethod", "Keyboard", "Toast")
                .any { simple.contains(it, ignoreCase = true) }
        ) {
            return false
        }
        return simple.endsWith("Activity") || simple.endsWith("UI") || simple.endsWith("Ui")
    }

    /** A real result page: strong success status plus a currency amount. */
    private fun looksLikeStrongPaymentResult(text: String): Boolean =
        STRONG_SUCCESS.any { text.contains(it) } && AMOUNT.containsMatchIn(text)

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

    private fun containsGroupCollectionWithoutOwnPayment(text: String): Boolean {
        val otherPayer = listOf(
            "你无需支付", "无需支付",
            "他人已支付", "对方已支付", "由对方支付",
        ).any { text.contains(it) }
        if (otherPayer) return true
        val groupCollection = text.contains("群收款") || text.contains("活动收款")
        return groupCollection && !looksLikeStrongPaymentResult(text)
    }

    /** Alipay main tab bar: "首页 / 理财 / 生活 / 消息 / 我的". */
    private fun looksLikeAlipayTabShell(text: String): Boolean =
        text.contains("首页") &&
            text.contains("我的") &&
            listOf("理财", "生活", "消息", "朋友").any { text.contains(it) }
}
