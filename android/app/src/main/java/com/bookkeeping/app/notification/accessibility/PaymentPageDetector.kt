package com.bookkeeping.app.notification.accessibility

/**
 * Pure rules for a supported WeChat/Alipay payment-success page.
 *
 * This detector only decides whether the current snapshot is safe to capture.
 * It intentionally does not insert anything; the capture candidate is reviewed
 * by the normal ledger/deduplication path later.
 */
data class PaymentPageDetection(
    val isSuccessPage: Boolean,
    val amountCents: Long?,
    val orderId: String?,
    val merchantCandidates: List<String>,
    val confidence: Float,
    val reason: String,
    val pageIdentity: String,
)

object PaymentPageDetector {
    const val PKG_WECHAT = "com.tencent.mm"
    const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

    private val SUCCESS = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "已支付",
        "payment successful", "payment complete",
    )
    private val REJECT = listOf(
        "支付失败", "付款失败", "交易失败", "交易关闭", "支付关闭",
        "待支付", "等待付款", "请输入", "支付密码", "验证码",
        "password", "verification code", "退款失败", "已取消", "订单已关闭",
    )
    private val MARKETING_AMOUNT = listOf(
        "优惠", "折扣", "立减", "红包", "积分", "券", "原价", "节省",
    )
    private val PAYMENT_METHOD = listOf(
        "零钱", "零钱通", "银行卡", "储蓄卡", "信用卡", "余额", "花呗",
        "支付方式", "付款方式", "paid by", "debit card", "credit card",
        "bank card", "boc debit",
    )
    private val PAGE_CHROME = listOf(
        "账单详情", "交易详情", "payment details", "transaction details",
        "完成", "返回", "微信支付", "支付宝", "查看更多", "复制", "联系商家",
        "wechat pay", "alipay",
    )
    private val ORDER = Regex(
        """(?:订单号|交易单号|商户单号|订单编号|交易号|Order(?:\s*(?:ID|No\.?))?|Transaction(?:\s*(?:ID|No\.?))?)\s*[:：]?\s*([A-Za-z0-9_-]{6,})""",
        RegexOption.IGNORE_CASE,
    )
    private val TIME = Regex(
        """(?:今天|昨天|星期.|周.|\d{1,2}[:：]\d{2}|\d{4}[-/年]\d{1,2}[-/月]\d{1,2}|\d{1,2}月\d{1,2}日)""",
    )
    private val ONLY_PUNCT_OR_NUMBERS = Regex("""[\d\s:：./¥￥,，-]+""")

    fun detect(
        packageName: String,
        snapshot: ScreenSnapshot,
        capturedAtMs: Long,
    ): PaymentPageDetection {
        if (packageName != PKG_WECHAT && packageName != PKG_ALIPAY) {
            return reject(packageName, snapshot, "unsupported package")
        }
        val nodes = snapshot.root.walk()
        if (nodes.any { it.password }) return reject(packageName, snapshot, "password field")
        if (nodes.any { it.editable }) return reject(packageName, snapshot, "editable field")

        val texts = nodes.map { it.combinedText().trim() }
            .filter { it.isNotEmpty() }
        val fullText = texts.joinToString(" ")
        val lower = fullText.lowercase()

        if (!SUCCESS.any { lower.contains(it.lowercase()) }) {
            return reject(packageName, snapshot, "no success status")
        }
        if (REJECT.any { lower.contains(it.lowercase()) }) {
            return reject(packageName, snapshot, "failure or sensitive page")
        }

        val amountTexts = texts.mapNotNull { text ->
            AmountText.parseCents(text)?.let { text to it }
        }.filterNot { (text, _) ->
            MARKETING_AMOUNT.any { marker -> text.contains(marker, ignoreCase = true) }
        }
        val amounts = amountTexts.map { it.second }.distinct()
        if (amounts.size != 1) {
            return reject(
                packageName,
                snapshot,
                if (amounts.isEmpty()) "no amount" else "ambiguous amount",
            )
        }
        val amountCents = amounts.single()
        if (amountCents <= 0L) return reject(packageName, snapshot, "non-positive amount")

        val orderId = ORDER.findAll(fullText)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .firstOrNull { value -> value.any { it.isDigit() } }
        val merchants = merchantCandidates(texts)
        val confidence = when {
            orderId != null && merchants.isNotEmpty() -> 0.9f
            orderId != null -> 0.85f
            merchants.isNotEmpty() -> 0.8f
            else -> 0.7f
        }
        return PaymentPageDetection(
            isSuccessPage = true,
            amountCents = amountCents,
            orderId = orderId,
            merchantCandidates = merchants,
            confidence = confidence,
            reason = "ok",
            pageIdentity = pageIdentity(packageName, snapshot, fullText),
        )
    }

    private fun merchantCandidates(texts: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        for (raw in texts) {
            val text = raw.trim()
            if (text.length !in 2..40) continue
            if (ONLY_PUNCT_OR_NUMBERS.matches(text)) continue
            if (AmountText.parseCents(text) != null) continue
            if (TIME.containsMatchIn(text)) continue
            if (SUCCESS.any { text.contains(it, ignoreCase = true) }) continue
            if (REJECT.any { text.contains(it, ignoreCase = true) }) continue
            if (MARKETING_AMOUNT.any { text.contains(it, ignoreCase = true) }) continue
            if (PAYMENT_METHOD.any { text.contains(it, ignoreCase = true) }) continue
            if (PAGE_CHROME.any { text.contains(it, ignoreCase = true) }) continue
            if (text.contains("订单号") || text.contains("交易单号") ||
                text.contains("商户单号") || text.contains("订单编号") ||
                text.contains("交易号")
            ) continue
            result += text.take(40)
            if (result.size >= 5) break
        }
        return result.toList()
    }

    private fun pageIdentity(
        packageName: String,
        snapshot: ScreenSnapshot,
        fullText: String,
    ): String {
        val hash = Integer.toHexString(fullText.hashCode())
        return "$packageName|$hash"
    }

    private fun reject(
        packageName: String,
        snapshot: ScreenSnapshot,
        reason: String,
    ): PaymentPageDetection = PaymentPageDetection(
        isSuccessPage = false,
        amountCents = null,
        orderId = null,
        merchantCandidates = emptyList(),
        confidence = 0f,
        reason = reason,
        pageIdentity = pageIdentity(
            packageName,
            snapshot,
            snapshot.flattenText(),
        ),
    )
}
