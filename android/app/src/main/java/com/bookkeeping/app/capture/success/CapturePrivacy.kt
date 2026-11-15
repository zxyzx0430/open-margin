package com.bookkeeping.app.capture.success

/** Applies before pixels are requested, for both automatic capture and accidental shakes. */
object CapturePrivacy {
    private val sensitiveActivity=Regex("password|passwd|pwd|verification|verify|authentication|biometric|fingerprint|faceid|securekeyboard",RegexOption.IGNORE_CASE)
    private val sensitiveLabels=listOf("密码","验证码","安全验证","身份验证","指纹验证","人脸验证","面容验证","刷脸验证","指纹支付","刷脸支付","验证指纹","验证身份","确认支付","确认付款","立即付款")
    private val systemPackage = Regex("(?:^|[.])(?:inputmethod|keyboard|launcher|permissioncontroller)(?:[.]|$)",RegexOption.IGNORE_CASE)

    fun sensitive(page:PaymentPage):Boolean = page.editable || sensitiveActivity.containsMatchIn(page.activity) ||
        page.texts.any { text -> sensitiveLabels.any(text::contains) }

    fun manualAllowed(page:PaymentPage):Boolean {
        if(!manualPackageAllowed(page.packageName) || sensitive(page))return false
        // A genuinely unreadable shared container can also be an authentication screen.
        // Only established read-only bill activities can be explicitly shaken with no text.
        return page.texts.any(String::isNotBlank) || page.packageName==PaymentApps.WECHAT && page.activity in setOf(
            "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
            "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI")
    }

    /** Explicit user capture is application-independent; system/input surfaces stay excluded. */
    fun manualPackageAllowed(pkg:String?):Boolean = !pkg.isNullOrBlank() && pkg.contains('.') &&
        pkg !in setOf("net.ankio.ledger","com.android.systemui","com.android.settings","android") &&
        !systemPackage.containsMatchIn(pkg)
}
