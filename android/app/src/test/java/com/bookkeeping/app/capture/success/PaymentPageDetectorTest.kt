package com.bookkeeping.app.capture.success

import org.junit.Assert.*
import org.junit.Test

class PaymentPageDetectorTest {
    @Test fun nonTransparentLiteResultObservedOnPhoneRequiresCompleteSafePaymentEvidence() {
        val activity="com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI"
        assertTrue(PaymentPageDetector.eligibleActivity(PaymentApps.WECHAT,activity))
        assertNotNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,activity,"支付成功","¥12.34","返回商家")))
        for(texts in listOf(emptyList(),listOf("¥12.34","返回商家"),listOf("支付成功","完成"),
            listOf("支付成功","¥12.34","¥23.45"),listOf("收款成功","¥12.34"),
            listOf("支付成功","¥12.34","请输入支付密码"),listOf("支付成功","¥12.34","指纹验证"),
            listOf("账单详情","支付成功","¥12.34")))
            assertNull(texts.toString(),PaymentPageDetector.detect(page(PaymentApps.WECHAT,activity,*texts.toTypedArray())))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,activity,"支付成功","¥12.34").copy(editable=true)))
        assertFalse(PaymentPageDetector.eligibleActivity(PaymentApps.WECHAT,"com.tencent.mm.plugin.lite.ui.OtherLiteUI"))
    }
    @Test fun sharedPaymentResultsIgnorePromotionalAmountsAcrossMerchantEntrypoints() {
        val activities=listOf(PaymentApps.WECHAT to PaymentPageDetector.WECHAT_LITE_RESULT,
            PaymentApps.WECHAT to PaymentPageDetector.WECHAT_FRAMEWORK_RESULT,
            PaymentApps.ALIPAY to "com.alipay.android.msp.ui.views.MspContainerActivity")
        for((pkg,activity) in activities) {
            for(ads in listOf(listOf("领取15元优惠券","领取最高88元奖励"),
                listOf("立减优惠2.00元","红包3.00元","积分+10"),listOf("领取15元优惠券","领取15元优惠券"))) {
                assertNotNull("$pkg $ads",PaymentPageDetector.detect(page(pkg,activity,*(listOf("支付成功","合成商家","¥12.34","返回商家")+ads).toTypedArray())))
                assertNull("Promotion is not a paid total",PaymentPageDetector.detect(page(pkg,activity,*(listOf("支付成功","返回商家")+ads).toTypedArray())))
            }
            assertNull("Two genuine totals remain ambiguous",PaymentPageDetector.detect(page(pkg,activity,"支付成功","¥12.34","¥23.45","领取15元优惠券","返回商家")))
            assertNull("An unlabeled second payment is not an ad",PaymentPageDetector.detect(page(pkg,activity,"支付成功","¥12.34","另外支付23.45元","返回商家")))
            assertNull(PaymentPageDetector.detect(page(pkg,activity,"支付成功","¥12.34","领取15元优惠券","请输入支付密码")))
            assertNotNull("A labeled paid total still wins",PaymentPageDetector.detect(page(pkg,activity,"支付成功","实付款12.34元","订单金额20.00元","优惠金额7.66元","返回商家")))
        }
    }
    @Test fun liteSdkResultNeedsReadableSuccessAndStillRejectsAuthentication() {
        val lite="com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"
        assertTrue(PaymentPageDetector.eligibleActivity(PaymentApps.WECHAT,lite))
        assertNotNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,lite,"支付成功","¥12.34","返回商家")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,lite)))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,lite,"支付成功","¥12.34","请输入支付密码")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,lite,"收款成功","¥12.34")))
        assertFalse(PaymentPageDetector.eligibleActivity(PaymentApps.WECHAT,"com.tencent.mm.plugin.lite.ui.OtherLiteUI"))
    }
    @Test fun sharedFrameworkNeedsSuccessEvidenceBeforeAnyScreenshot() {
        val activity="com.tencent.mm.framework.app.UIPageFragmentActivity"
        assertTrue(PaymentPageDetector.eligibleActivity(PaymentApps.WECHAT,activity))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,activity)))
        assertNotNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,activity,"支付成功","¥0.01","完成")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,activity).copy(editable=true)))
    }
    @Test fun sensitiveOrUnreadableScreensAreRejectedBeforeAcquisition() {
        for((pkg,activity) in listOf(PaymentApps.WECHAT to PaymentPageDetector.WECHAT_FRAMEWORK_RESULT,
            PaymentApps.WECHAT to "com.tencent.mm.plugin.remittance.ui.RemittanceResultNewUI",
            PaymentApps.ALIPAY to "com.alipay.android.app.flybird.ui.window.FlyBirdWindowActivity")) {
            assertNull(PaymentPageDetector.detect(page(pkg,activity)))
            for(text in listOf("请输入支付密码","请输入付款密码","指纹验证","人脸验证","短信验证码","安全验证"))
                assertNull(text,PaymentPageDetector.detect(page(pkg,activity,"支付成功","¥12.34",text)))
        }
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,"com.tencent.mm.plugin.wallet.pwd.ui.WalletPasswordUI","支付成功","¥12.34")))
    }

    @Test fun senderPaymentSucceededIsCompletedEvenBeforeRecipientAccepts() {
        assertNotNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,wechat,"支付成功","待测试收款人确认收款","¥0.01")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,wechat,"待测试收款人确认收款","¥0.01")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,wechat,"确认收款","¥0.01")))
    }
    @Test fun readableIncomeReceiptDoesNotTriggerAutomaticExpenseCapture() {
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,wechat,"收款成功","¥12.34")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,wechat,"已收款","¥12.34")))
    }
    private val wechat = "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI"
    private val alipay = "com.alipay.android.app.flybird.ui.window.FlyBirdWindowActivity"
    private val alipayWeb = "com.alipay.mobile.nebulax.integration.mpaas.activity.NebulaActivity"
    private val taobao = "com.taobao.tao.tbmainfragment.TBMainActivity"
    private fun page(pkg: String = PaymentApps.ALIPAY, activity: String = alipay, vararg texts: String) =
        PaymentPage(pkg, 7, activity, texts.toList())

    @Test fun alipayCompletedCashierCapturesWithoutNotification() {
        assertNotNull(PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥0.01", "完成"))))
        assertNotNull(PaymentPageDetector.detect(page(texts = arrayOf("转账成功", "-123.45", "给测试收款人"))))
    }

    @Test fun alipayGenericWebViewNeedsBillStructure() {
        assertNull(PaymentPageDetector.detect(page(activity = alipayWeb, texts = arrayOf("支付成功", "¥12.34"))))
        assertNotNull(PaymentPageDetector.detect(page(activity = alipayWeb, texts = arrayOf("支付成功", "支付金额", "¥12.34", "返回商家"))))
        assertNull(PaymentPageDetector.detect(page(activity = alipayWeb, texts = arrayOf("账单详情", "当前状态", "交易成功", "-12.34", "付款时间", "2026年10月17日 20:40:00", "交易号", "2026101722001400001234567890"))))
    }

    @Test fun taobaoResultNeedsActualPaymentEvidence() {
        assertNotNull(PaymentPageDetector.detect(page(PaymentApps.TAOBAO, taobao, "支付成功", "实付款", "¥80.00", "订单金额", "¥100.00", "优惠", "¥20.00")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.TAOBAO, taobao, "订单详情", "买家已付款", "实付款", "¥80.00")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.TAOBAO, taobao, "提交订单", "实付款", "¥80.00", "确认付款")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.TAOBAO, taobao, "支付成功", "商品价格", "¥80.00")))
    }

    @Test fun unsupportedAppsAndChatPagesAreNotCandidates() {
        assertFalse(PaymentPageDetector.eligibleActivity("com.example.shop", "com.example.shop.PaymentResult"))
        assertNull(PaymentPageDetector.detect(page("com.example.shop", "com.example.shop.PaymentResult", "支付成功", "¥12.34")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT, "com.tencent.mm.ui.chatting.ChattingUI", "支付成功", "¥12.34")))
    }

    @Test fun walletCoreResultCanMatchReadableSuccessButNotAnUnreadableOrPasswordPage() {
        val activity="com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI"
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,activity,"支付成功","¥12.34")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,activity)))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT,activity,"请输入支付密码","¥12.34")))
    }

    @Test fun failuresPasswordsAndEditablePagesCannotCapture() {
        listOf("支付失败", "交易关闭", "请输入支付密码", "确认付款", "待支付", "退款中").forEach { prohibited ->
            assertNull(prohibited, PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥12.34", prohibited))))
        }
        assertNull(PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥12.34")).copy(editable = true)))
    }

    @Test fun monetaryAmbiguityAndZeroAmountsAreNotGuessed() {
        assertNull(PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥10.00", "¥20.00"))))
        assertNull(PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥0.00"))))
        assertNull(PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "完成"))))
    }

    @Test fun zeroActualPaidCannotFallBackToTheOriginalOrderPrice() {
        assertNull(PaymentPageDetector.detect(page(texts = arrayOf(
            "支付成功", "实付款", "¥0.00", "订单金额", "¥80.00"))))
    }

    @Test fun emptyTreesNeverAuthorizeAutomaticScreenshots() {
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT, "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT, "com.tencent.mm.plugin.remittance.ui.RemittanceResultNewUI")))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.WECHAT, wechat)))
        assertNull(PaymentPageDetector.detect(page()))
        assertNull(PaymentPageDetector.detect(page(PaymentApps.TAOBAO, taobao)))
    }

    @Test fun sameTransactionHasSameSignatureAcrossWindowsAndAmountFormatting() {
        val first = PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥12.34", "交易号", "2026101722001400001234567890")))!!
        val next = PaymentPageDetector.detect(page(activity = alipayWeb, texts = arrayOf("当前状态：交易成功", "付款金额：12.34元", "付款时间：2026年10月17日 20:40:00", "交易号：2026101722001400001234567890", "完成")).copy(windowId = 44))!!
        assertEquals(first.signature, next.signature)
        assertEquals("2026101722001400001234567890", next.transactionId)
    }

    @Test fun firstFrameIsImmediateAndDifferentTransactionsDoNotWaitForLeaving() {
        val first = PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥12.34", "交易号", "2026101722001400001234567890")))!!
        val second = PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥12.34", "交易号", "2026101722001400001234567891")))!!
        val gate = PaymentGate()
        assertTrue(gate.observe(first)); assertTrue(gate.claim(first))
        assertFalse(gate.observe(first)); assertFalse(gate.claim(first))
        assertTrue(gate.observe(second)); assertTrue(gate.claim(second))
        gate.observe(null)
        assertFalse(gate.observe(first)); assertFalse(gate.claim(first))
    }

    @Test fun repeatedKnownTransactionIsSuppressedWhenResultPagesAlternate() {
        val first = PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥12.34", "交易号", "2026101722001400001234567890")))!!
        val second = PaymentPageDetector.detect(page(texts = arrayOf("支付成功", "¥12.34", "交易号", "2026101722001400001234567891")))!!
        val gate = PaymentGate()
        assertTrue(gate.observe(first)); assertTrue(gate.claim(first))
        assertTrue(gate.observe(second)); assertTrue(gate.claim(second))
        assertFalse(gate.observe(first))
    }
    @Test fun rejectedAcquisitionCanRetryWithoutForgettingOtherCompletedPages() {
        val first=PaymentPageDetector.detect(page(texts=arrayOf("支付成功","¥12.34","完成")))!!
        val second=PaymentPageDetector.detect(page(texts=arrayOf("支付成功","¥12.34","完成")).copy(windowId=78))!!
        val gate=PaymentGate()
        assertTrue(gate.observe(first));assertTrue(gate.claim(first))
        assertTrue(gate.observe(second));assertTrue(gate.claim(second))
        gate.release(second)
        assertTrue(gate.observe(second));assertTrue(gate.claim(second))
        assertFalse(gate.observe(first))
    }
    @Test fun promotionRefreshDoesNotTurnTheSamePaymentWindowIntoAnotherBill() {
        val first=PaymentPageDetector.detect(page(texts=arrayOf("支付成功","¥12.34","领取15元优惠券","完成")))!!
        val refreshed=PaymentPageDetector.detect(page(texts=arrayOf("支付成功","¥12.34","领取18元优惠券","完成")))!!
        val anotherAmount=PaymentPageDetector.detect(page(texts=arrayOf("支付成功","¥23.45","领取18元优惠券","完成")))!!
        val gate=PaymentGate()
        assertTrue(gate.observe(first));assertTrue(gate.claim(first))
        assertFalse(gate.observe(refreshed))
        assertTrue(gate.observe(anotherAmount));assertTrue(gate.claim(anotherAmount))
    }
}


