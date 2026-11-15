package com.bookkeeping.app.notification.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageActivityGuardTest {

    @Test
    fun wechatChatListAndChatPagesAreBlocked() {
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.ui.LauncherUI",
                "微信(4) 通讯录 发现 我",
            ),
        )
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.ui.chatting.ChattingUI",
                "615 宿舍 柯子凯: [群收款] 活动收款",
            ),
        )
    }

    @Test
    fun chatListWithPaymentRowPreviewIsStillBlocked() {
        // The "微信支付" service row preview can contain a completed payment.
        // That must not turn the whole conversation list into a payment page.
        val chatList = "微信 通讯录 发现 我 微信支付 已支付 ¥2.00 " +
            "615 宿舍 昨天 神经研究所邑大分所 昨天"
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.ui.LauncherUI",
                chatList,
            ),
        )
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "android.widget.TextView",
                chatList,
            ),
        )
    }

    @Test
    fun searchMiniProgramAndArticlePagesAreBlocked() {
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.fts.ui.FTSMainUI",
                "搜索 2.00 清除 微信支付 已支付 ¥2.00",
            ),
        )
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI",
                "群收款 你无需支付 他人已支付 ¥54.70",
            ),
        )
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                "公众号文章 已支付 ¥2.00 阅读原文 写留言",
            ),
        )
    }

    @Test
    fun webviewMerchantPaymentResultIsAllowed() {
        assertFalse(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                "支付成功 ¥12.00 商户名称 麦当劳 交易单号 4200001234567890",
            ),
        )
    }

    @Test
    fun chatConversationWithTransferBubbleIsBlocked() {
        val conversation = "615 宿舍 微信转账 ¥2.00 已收款 表情 发送"
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "android.widget.TextView",
                conversation,
            ),
        )
    }

    @Test
    fun wechatPaymentPagesAreAllowed() {
        assertFalse(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI",
                "支付成功 ￥2.00 麦当劳",
            ),
        )
        assertFalse(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.wallet.balance.ui.WalletBalanceDetailUI",
                "账单详情 交易成功 ￥12.00",
            ),
        )
    }

    @Test
    fun containerPagesAllowOnlyStrongPaymentResults() {
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI",
                "小程序订单 已支付 ¥2.00",
            ),
        )
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                "客服会话 消费 ¥2.00",
            ),
        )
        assertFalse(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI",
                "支付成功 ¥2.00 完成",
            ),
        )
        assertFalse(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                "支付成功 ￥12.00 返回商家",
            ),
        )
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.brandservice.ui.BrandServiceUI",
                "支付成功 ￥12.00 麦当劳",
            ),
        )
    }

    @Test
    fun groupCollectionNeedsOwnStrongPayment() {
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "android.widget.TextView",
                "群收款 ¥54.70 他人已支付",
            ),
        )
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "android.widget.TextView",
                "群收款 ¥54.70",
            ),
        )
        assertFalse(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI",
                "群收款 支付成功 ¥54.70",
            ),
        )
    }

    @Test
    fun onlyActivityClassNamesMayReplaceCurrentActivity() {
        assertTrue(
            PageActivityGuard.isActivityClassName(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.ui.LauncherUI",
            ),
        )
        assertTrue(
            PageActivityGuard.isActivityClassName(
                PaymentPageDetector.PKG_ALIPAY,
                "com.eg.android.AlipayGphone.HomeActivity",
            ),
        )
        assertFalse(
            PageActivityGuard.isActivityClassName(
                PaymentPageDetector.PKG_WECHAT,
                "android.widget.FrameLayout",
            ),
        )
        assertFalse(
            PageActivityGuard.isActivityClassName(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.ui.base.FullScreenDialog",
            ),
        )
        assertFalse(
            PageActivityGuard.isActivityClassName(
                PaymentPageDetector.PKG_WECHAT,
                "com.tencent.mm.ui.widget.MMEditText",
            ),
        )
    }

    @Test
    fun alipayTabShellDoesNotBlockStrongResultPage() {
        assertFalse(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_ALIPAY,
                "com.eg.android.AlipayGphone.BillActivity",
                "首页 理财 生活 消息 我的 支付成功 ￥12.00",
            ),
        )
    }

    @Test
    fun alipayHomeIsBlockedButBillPageIsAllowed() {
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_ALIPAY,
                "com.eg.android.AlipayGphone.HomeActivity",
                "首页 口碑 朋友 我的",
            ),
        )
        assertTrue(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_ALIPAY,
                "android.widget.TextView",
                "首页 理财 生活 消息 我的 已支付 ¥2.00",
            ),
        )
        assertFalse(
            PageActivityGuard.isChatOrHomePage(
                PaymentPageDetector.PKG_ALIPAY,
                "com.eg.android.AlipayGphone.BillActivity",
                "账单详情 交易成功 ￥12.00",
            ),
        )
    }
}
