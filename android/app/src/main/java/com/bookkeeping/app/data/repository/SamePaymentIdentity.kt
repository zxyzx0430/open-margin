package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.notification.capture.CaptureCandidate
import kotlin.math.abs

/**
 * 「同一笔钱」的强身份判定：商户相同 + 金额/方向/币种相同 + 事件时间相同。
 *
 * 与 8 秒 / 10 分钟去重窗口的区别：窗口回答「刚刚是不是重发了一次」，身份回答
 * 「这两组数字描述的是不是同一笔支付」。同一笔支付隔 47 分钟再截一次图，页面上
 * 印的交易时间仍然是同一分钟，所以身份判定**不**受采集时间窗口限制。
 *
 * 时间必须真的相同：同店同价不同次消费很常见，只看商户 + 金额会把两笔真钱并成
 * 一笔。页面时间只印到分钟（秒位恒为 0），所以只有两侧都是分钟精度的时间才可以
 * 按分钟比较；带秒的实时时刻（通知、短信）必须逐毫秒相同，否则同一分钟内的两笔
 * 真实消费会被误并。任一侧缺时间、或时间只是截图兜底（needsConfirmation）时，
 * 一律不判定。
 */
object SamePaymentIdentity {

    const val MINUTE_MS = 60_000L

    /** 参与身份比较的一组字段；账本行与截图草稿都能无损映射到这里。 */
    data class PaymentIdentity(
        val amountCents: Long,
        val type: String,
        val currency: String,
        val merchant: String?,
        val eventTimeMs: Long?,
        val platform: String,
        val eventTimeNeedsConfirmation: Boolean = false,
    )

    /**
     * 两个事件时间是否指向同一分钟。
     *
     * 两侧都落在整分钟（页面只印到分钟）时按分钟比较；任一侧带秒时要求完全相同。
     * 这样「12:40 的页面」和「12:40:30 的实时时刻」不会被当成同一笔，而同一张
     * 支付页在 47 分钟后重截仍然是同一个 12:40。
     */
    fun eventTimesMatch(firstMs: Long?, secondMs: Long?): Boolean {
        if (firstMs == null || secondMs == null) return false
        if (firstMs <= 0L || secondMs <= 0L) return false
        if (firstMs == secondMs) return true
        if (firstMs / MINUTE_MS != secondMs / MINUTE_MS) return false
        val firstMinutePrecision = firstMs % MINUTE_MS == 0L
        val secondMinutePrecision = secondMs % MINUTE_MS == 0L
        return firstMinutePrecision && secondMinutePrecision
    }

    /** 占位商户（「微信支付」「未知商户」）不算证据，交给 [CrossSourceDuplicatePolicy]。 */
    fun merchantsMatch(first: String?, second: String?): Boolean =
        CrossSourceDuplicatePolicy.merchantsSame(first.orEmpty(), second.orEmpty())

    fun matches(first: PaymentIdentity, second: PaymentIdentity): Boolean {
        if (first.amountCents <= 0L || first.amountCents != second.amountCents) return false
        if (!first.type.equals(second.type, ignoreCase = true)) return false
        if (!first.currency.equals(second.currency, ignoreCase = true)) return false
        if (first.platform == CaptureCandidate.PLATFORM_UNKNOWN) return false
        if (first.platform != second.platform) return false
        if (first.eventTimeNeedsConfirmation || second.eventTimeNeedsConfirmation) return false
        if (!eventTimesMatch(first.eventTimeMs, second.eventTimeMs)) return false
        return merchantsMatch(first.merchant, second.merchant)
    }

    /**
     * 账本里与 [incoming] 同一身份的一行，时间上最接近的一条；没有返回 null。
     *
     * 只对带截图 provenance 的入账（[TransactionEntity.captureObservedAtMs] 非空）
     * 生效：通知 / 短信 / 手工记账的原有语义不变。
     */
    fun findMatch(candidates: List<TransactionEntity>, incoming: TransactionEntity): TransactionEntity? {
        if (incoming.captureObservedAtMs == null) return null
        val incomingIdentity = identityOf(incoming)
        if (incomingIdentity.eventTimeNeedsConfirmation) return null
        if (incomingIdentity.eventTimeMs == null || incomingIdentity.eventTimeMs <= 0L) return null
        return candidates
            .asSequence()
            .filter { it.id != incoming.id }
            .filter { !it.syncDeleted && it.duplicateOfId == null }
            .filter { it.remoteReviewStatus !in SKIPPED_STATUSES }
            .filter { matches(incomingIdentity, identityOf(it)) }
            .minByOrNull { abs(it.timestamp - incoming.timestamp) }
    }

    fun identityOf(entity: TransactionEntity): PaymentIdentity = PaymentIdentity(
        amountCents = entity.amountCents,
        type = entity.type,
        currency = entity.currency,
        merchant = entity.merchant,
        eventTimeMs = entity.timestamp,
        platform = platformOf(entity.source),
        eventTimeNeedsConfirmation = entity.eventTimeNeedsConfirmation,
    )

    /**
     * 账本来源 → 支付平台。与 [CrossSourceDuplicatePolicy] 的跨通道比较同一口径：
     * 微信和支付宝同额同方也是两笔钱，其它来源一律 OTHER。
     */
    fun platformOf(source: String): String = when (source) {
        "WECHAT" -> CaptureCandidate.PLATFORM_WECHAT
        "ALIPAY" -> CaptureCandidate.PLATFORM_ALIPAY
        else -> CaptureCandidate.PLATFORM_UNKNOWN
    }

    /** 已经不计入或已判定为重复的行不参与身份比较。 */
    private val SKIPPED_STATUSES = setOf("excluded", "possible_duplicate")
}
