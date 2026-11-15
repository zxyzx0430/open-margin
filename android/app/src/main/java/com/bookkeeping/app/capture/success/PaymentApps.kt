package com.bookkeeping.app.capture.success

data class PaymentApp(val packageName: String, val label: String)

/** Supported adapters are an explicit allow-list, not permission to inspect every installed app. */
object PaymentApps {
    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val TAOBAO = "com.taobao.taobao"
    val entries = listOf(
        PaymentApp(WECHAT, "微信"),
        PaymentApp(ALIPAY, "支付宝"),
        PaymentApp(TAOBAO, "淘宝"),
    )
    fun supported(packageName: String) = entries.any { it.packageName == packageName }
}
