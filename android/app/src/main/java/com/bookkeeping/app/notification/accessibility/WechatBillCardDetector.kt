package com.bookkeeping.app.notification.accessibility

/** Legacy WeChat-only card model kept for PageRecognitionService compatibility. */
enum class BillCardKind {
    PAYMENT,
    REFUND,
    AUTO_PAY,
}

data class BillCardDetection(
    val kind: BillCardKind,
    val amountCents: Long,
    val merchantCandidates: List<String>,
    val orderId: String?,
    val eventTimeMs: Long?,
    val confidence: Float,
    val reason: String,
    val pageIdentity: String,
    val cardKey: String,
)

/**
 * Legacy entry point for WeChat Pay bill cards. New code should use
 * [PaymentCardDetector], which also covers Alipay, transfers, red packets,
 * withdrawals, and non-final pay-after-use states. This adapter keeps the
 * existing service dispatch compiling and only surfaces final cards.
 */
object WechatBillCardDetector {
    const val WECHAT = "com.tencent.mm"

    fun fromSnapshot(
        packageName: String,
        snapshot: ScreenSnapshot,
        capturedAtMs: Long,
    ): List<BillCardDetection> =
        PaymentCardDetector.fromSnapshot(packageName, snapshot, capturedAtMs)
            .mapNotNull { it.toLegacyOrNull() }

    fun fromLines(
        packageName: String,
        lines: List<BillTextLine>,
        capturedAtMs: Long,
        screenHeightPx: Int,
        pageIdentity: String,
    ): List<BillCardDetection> =
        PaymentCardDetector.fromLines(
            packageName = packageName,
            lines = lines,
            capturedAtMs = capturedAtMs,
            screenHeightPx = screenHeightPx,
            pageIdentity = pageIdentity,
        ).mapNotNull { it.toLegacyOrNull() }

    private fun PaymentCardDetection.toLegacyOrNull(): BillCardDetection? {
        val legacyKind = when {
            kind == PaymentCardKind.REFUND && status == PaymentCardStatus.COMPLETED ->
                BillCardKind.REFUND
            kind == PaymentCardKind.PAY_AFTER_USE && status == PaymentCardStatus.COMPLETED ->
                BillCardKind.AUTO_PAY
            kind == PaymentCardKind.PAYMENT && status == PaymentCardStatus.COMPLETED ->
                BillCardKind.PAYMENT
            else -> return null
        }
        return BillCardDetection(
            kind = legacyKind,
            amountCents = amountCents,
            merchantCandidates = merchantCandidates,
            orderId = orderId,
            eventTimeMs = eventTimeMs,
            confidence = confidence,
            reason = "ok",
            pageIdentity = pageIdentity,
            cardKey = cardKey,
        )
    }
}
