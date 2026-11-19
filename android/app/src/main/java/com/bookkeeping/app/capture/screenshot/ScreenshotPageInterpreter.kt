package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Deterministic page understanding for WeChat / Alipay transaction screenshots.
 *
 * OCR gives text plus positions; this object only applies explainable rules.
 * "No failure marker found" is never treated as proof of success: a completed
 * transaction needs an explicit completion marker on the page.
 */
object ScreenshotPageInterpreter {

    const val PAGE_SUCCESS = "SUCCESS_PAGE"
    const val PAGE_DETAIL = "DETAIL_PAGE"
    const val PAGE_CHECKOUT = "CHECKOUT_PAGE"
    const val PAGE_LIST = "BILL_LIST"
    const val PAGE_UNKNOWN = "UNKNOWN"

    private val WECHAT_MARKERS = listOf("微信支付", "微信", "零钱", "转账给", "收款方", "付款给", "当前状态")
    private val ALIPAY_MARKERS = listOf("支付宝", "账单详情", "交易成功", "付款方式", "订单号", "花呗", "余额宝")
    private val LIST_MARKERS = listOf("全部账单", "账单明细", "收支明细", "账单记录")
    private val CHECKOUT_MARKERS = listOf("立即支付", "确认付款", "确认转账", "请输入金额")

    private val COMPLETED_MARKERS = listOf(
        "交易成功", "支付成功", "付款成功", "转账成功", "交易完成", "已到账",
        "已存入零钱", "已收款", "收款成功", "支付完成",
    )
    private val PENDING_MARKERS = listOf(
        "等待对方确认", "待收款", "待领取", "待对方接收", "处理中", "转账处理中", "对方未领取",
    )
    private val CREATED_MARKERS = listOf("待付款", "等待付款", "已创建", "待支付")
    private val CANCELLED_MARKERS = listOf("已取消", "已关闭", "已过期", "已退还", "已退回")
    private val FAILED_MARKERS = listOf("支付失败", "转账失败", "交易失败")

    private val ORDER_ID_REGEX =
        Regex("(?:订单号|交易单号|交易号|商户订单号)\\s*[:：]?\\s*([A-Za-z0-9]{8,40})")
    private val AMOUNT_REGEX = Regex("([¥￥]\\s*)?(-|\\+)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(元)?")
    private val DATE_TIME_REGEX = Regex(
        "(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?",
    )
    private val TIME_ONLY_REGEX = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")

    fun interpret(
        blocks: List<TextBlock>,
        capturedAtMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): ScreenshotPageEvidence {
        val ordered = blocks.filter { it.text.isNotBlank() }.sortedWith(
            compareBy({ it.top }, { it.left }),
        )
        if (ordered.isEmpty()) {
            return ScreenshotPageEvidence(
                platform = CaptureCandidate.PLATFORM_UNKNOWN,
                pageType = PAGE_UNKNOWN,
                transactionLike = false,
                drafts = emptyList(),
                reason = "no text recognised",
            )
        }
        val allText = ordered.joinToString("\n") { it.text }
        val platform = detectPlatform(allText)
        val pageType = detectPageType(allText)
        if (pageType == PAGE_LIST) {
            return ScreenshotPageEvidence(
                platform = platform,
                pageType = pageType,
                transactionLike = false,
                drafts = emptyList(),
                reason = "bill list pages belong to the import flow",
            )
        }

        val segments = splitSegments(ordered)
        val drafts = segments.mapIndexedNotNull { index, segment ->
            buildDraft(index, segment, platform, pageType, capturedAtMs, timeZone)
        }
        if (drafts.isEmpty()) {
            return ScreenshotPageEvidence(
                platform = platform,
                pageType = pageType,
                transactionLike = false,
                drafts = emptyList(),
                reason = "no transaction fields",
            )
        }
        val conflicting = drafts.size == 1 && countDistinctAmounts(ordered) > 1
        return ScreenshotPageEvidence(
            platform = platform,
            pageType = pageType,
            transactionLike = pageType != PAGE_CHECKOUT,
            drafts = drafts.map { if (conflicting) it.copy(reason = "conflicting amounts") else it },
            conflictingFields = conflicting,
            reason = if (conflicting) "conflicting amounts" else "parsed",
        )
    }

    private fun detectPlatform(text: String): String {
        val wechat = WECHAT_MARKERS.count { text.contains(it) }
        val alipay = ALIPAY_MARKERS.count { text.contains(it) }
        return when {
            wechat == 0 && alipay == 0 -> CaptureCandidate.PLATFORM_UNKNOWN
            wechat >= alipay -> CaptureCandidate.PLATFORM_WECHAT
            else -> CaptureCandidate.PLATFORM_ALIPAY
        }
    }

    private fun detectPageType(text: String): String {
        if (LIST_MARKERS.any(text::contains)) return PAGE_LIST
        if (CHECKOUT_MARKERS.any(text::contains) &&
            COMPLETED_MARKERS.none(text::contains)
        ) {
            return PAGE_CHECKOUT
        }
        if (COMPLETED_MARKERS.any(text::contains)) return PAGE_SUCCESS
        if (PENDING_MARKERS.any(text::contains) ||
            CREATED_MARKERS.any(text::contains) ||
            CANCELLED_MARKERS.any(text::contains) ||
            FAILED_MARKERS.any(text::contains)
        ) {
            return PAGE_DETAIL
        }
        if (text.contains("账单详情") || text.contains("交易详情")) return PAGE_DETAIL
        return if (amountOf(text) != null) PAGE_DETAIL else PAGE_UNKNOWN
    }

    /** A new segment starts at each amount-bearing line; other lines attach to it. */
    private fun splitSegments(blocks: List<TextBlock>): List<List<TextBlock>> {
        val segments = mutableListOf<MutableList<TextBlock>>()
        val prelude = mutableListOf<TextBlock>()
        blocks.forEach { block ->
            if (amountOf(block.text) != null) {
                val segment = mutableListOf<TextBlock>()
                if (segments.isEmpty()) segment += prelude
                segment += block
                segments += segment
            } else if (segments.isNotEmpty()) {
                segments.last() += block
            } else {
                // Header, status and "paid to" lines often sit above the amount.
                prelude += block
            }
        }
        return segments
    }

    private fun buildDraft(
        index: Int,
        segment: List<TextBlock>,
        platform: String,
        pageType: String,
        capturedAtMs: Long,
        timeZone: TimeZone,
    ): TransactionDraft? {
        val amountBlock = segment.firstOrNull { amountOf(it.text) != null } ?: return null
        val segmentText = segment.joinToString("\n") { it.text }
        val amount = amountOf(amountBlock.text)
        val amountUnambiguous = segment.count { amountOf(it.text) != null } == 1
        val direction = directionOf(segmentText, amountBlock.text, pageType)
        val kind = kindOf(segmentText, direction)
        val completion = completionOf(segmentText)
        val merchant = merchantOf(segment, amountBlock)
        val orderId = ORDER_ID_REGEX.find(segmentText)?.groupValues?.get(1)
        val eventTime = eventTimeOf(segmentText, capturedAtMs, timeZone)
        val supported = platform != CaptureCandidate.PLATFORM_UNKNOWN &&
            pageType in setOf(PAGE_SUCCESS, PAGE_DETAIL)
        return TransactionDraft(
            index = index,
            amountCents = amount,
            amountUnambiguous = amountUnambiguous,
            direction = direction,
            directionExplicit = direction != CaptureCandidate.DIRECTION_UNKNOWN,
            kind = kind,
            completionStatus = completion.first,
            completionExplicit = completion.second,
            merchantCandidates = merchant,
            orderId = orderId,
            eventTimeMs = eventTime,
            pageIdentity = "${platform.lowercase(Locale.ROOT)}:$pageType:${amount ?: 0}:${noiseKey(merchant.firstOrNull())}",
            supportedPaymentPage = supported,
            reason = if (supported) "supported payment page" else "page not recognised as a payment result",
        )
    }

    private fun directionOf(text: String, amountLine: String, pageType: String): String {
        if (text.contains("退款") || text.contains("已退还") || text.contains("已退回")) {
            return CaptureCandidate.DIRECTION_INCOME
        }
        if (amountLine.contains("-") || text.contains("支出") || text.contains("付款给") ||
            text.contains("收款方") || text.contains("商户全称")
        ) {
            return CaptureCandidate.DIRECTION_EXPENSE
        }
        // "支付宝" / "微信支付" contain 支付 but say nothing about direction.
        val cleaned = text.replace("支付宝", "").replace("微信支付", "")
        if (amountLine.contains("+") || cleaned.contains("收款") ||
            cleaned.contains("已存入零钱") || cleaned.contains("收入")
        ) {
            return CaptureCandidate.DIRECTION_INCOME
        }
        return when {
            cleaned.contains("支付") || text.contains("转账给") || text.contains("付款") ||
                pageType == PAGE_CHECKOUT -> CaptureCandidate.DIRECTION_EXPENSE
            else -> CaptureCandidate.DIRECTION_UNKNOWN
        }
    }

    private fun kindOf(text: String, direction: String): String = when {
        text.contains("退款") || text.contains("已退还") || text.contains("已退回") ->
            CaptureCandidate.KIND_REFUND
        text.contains("红包") -> CaptureCandidate.KIND_RED_PACKET
        text.contains("提现") -> CaptureCandidate.KIND_WITHDRAWAL
        text.contains("转账") -> CaptureCandidate.KIND_TRANSFER
        text.contains("花呗") || text.contains("自动扣款") -> CaptureCandidate.KIND_AUTO_PAY
        direction == CaptureCandidate.DIRECTION_UNKNOWN -> CaptureCandidate.KIND_PAYMENT
        else -> CaptureCandidate.KIND_PAYMENT
    }

    private fun completionOf(text: String): Pair<String, Boolean> = when {
        FAILED_MARKERS.any(text::contains) -> CaptureCandidate.COMPLETION_FAILED to true
        CANCELLED_MARKERS.any(text::contains) -> CaptureCandidate.COMPLETION_CANCELLED to true
        PENDING_MARKERS.any(text::contains) -> CaptureCandidate.COMPLETION_PENDING to true
        CREATED_MARKERS.any(text::contains) -> CaptureCandidate.COMPLETION_CREATED to true
        COMPLETED_MARKERS.any(text::contains) -> CaptureCandidate.COMPLETION_COMPLETED to true
        else -> CaptureCandidate.COMPLETION_UNKNOWN to false
    }

    private fun merchantOf(segment: List<TextBlock>, amountBlock: TextBlock): List<String> {
        val labels = listOf("收款方", "付款给", "转账给", "商户全称", "对方", "收款人")
        segment.forEachIndexed { index, block ->
            labels.forEach { label ->
                if (block.text.startsWith(label) || block.text.contains("$label：") ||
                    block.text.contains("$label:")
                ) {
                    val inline = block.text.substringAfter(label).trimStart('：', ':', ' ')
                    if (inline.isNotBlank() && inline.length <= 40) return listOf(inline)
                    segment.getOrNull(index + 1)?.text?.takeIf { it.length <= 40 }?.let {
                        return listOf(it)
                    }
                }
                // "转账给 张三" on one line
                if (block.text.contains("$label ") && block != amountBlock) {
                    val inline = block.text.substringAfter("$label ").trim()
                    if (inline.isNotBlank() && inline.length <= 40) return listOf(inline)
                }
            }
        }
        // Fall back to the line directly above the amount, which is where both
        // apps put the merchant on a success page.
        val above = segment.lastOrNull { it.bottom <= amountBlock.top && it.text.length in 1..40 }
        return above?.text?.takeIf { it.none(Char::isDigit) }?.let { listOf(it) } ?: emptyList()
    }

    private fun eventTimeOf(text: String, capturedAtMs: Long, timeZone: TimeZone): Long? {
        DATE_TIME_REGEX.find(text)?.let { match ->
            val values = match.groupValues
            return runCatching {
                Calendar.getInstance(timeZone).apply {
                    clear()
                    set(
                        values[1].toInt(),
                        values[2].toInt() - 1,
                        values[3].toInt(),
                        values[4].toInt(),
                        values[5].toInt(),
                        values[6].ifBlank { "0" }.toInt(),
                    )
                }.timeInMillis.takeIf { it in MIN_EVENT_TIME..(capturedAtMs + FUTURE_TOLERANCE_MS) }
            }.getOrNull()
        }
        TIME_ONLY_REGEX.find(text)?.let { match ->
            if (text.contains("年") || text.contains("-")) return null
            return runCatching {
                val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = capturedAtMs }
                val sameDay = Calendar.getInstance(timeZone).apply {
                    timeInMillis = capturedAtMs
                    set(Calendar.HOUR_OF_DAY, match.groupValues[1].toInt())
                    set(Calendar.MINUTE, match.groupValues[2].toInt())
                    set(Calendar.SECOND, match.groupValues[3].ifBlank { "0" }.toInt())
                }
                // A time later than the screenshot would belong to yesterday.
                if (sameDay.timeInMillis > capturedAtMs + FUTURE_TOLERANCE_MS) {
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                    calendar.set(Calendar.HOUR_OF_DAY, match.groupValues[1].toInt())
                    calendar.set(Calendar.MINUTE, match.groupValues[2].toInt())
                    calendar.set(Calendar.SECOND, match.groupValues[3].ifBlank { "0" }.toInt())
                    calendar.timeInMillis
                } else {
                    sameDay.timeInMillis
                }
            }.getOrNull()
        }
        return null
    }

    /** Amount in integer cents; only explicit currency or 元-suffixed numbers count. */
    fun amountOf(line: String): Long? {
        val match = AMOUNT_REGEX.findAll(line).firstOrNull { candidate ->
            candidate.groupValues[1].isNotBlank() || candidate.groupValues[4].isNotBlank()
        } ?: return null
        val raw = match.groupValues[3].replace(",", "")
        return runCatching {
            BigDecimal(raw).setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .longValueExact()
        }.getOrNull()?.takeIf { it in 1..CaptureCandidate.MAX_CENTS }
    }

    private fun countDistinctAmounts(blocks: List<TextBlock>): Int =
        blocks.mapNotNull { amountOf(it.text) }.distinct().size

    private fun noiseKey(value: String?): String =
        value.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "").take(24)

    /** Used by the coordinator for cross-source correlation. */
    fun sameTransaction(a: TransactionDraft, b: TransactionDraft): Boolean {
        if (a.orderId != null && b.orderId != null) return a.orderId == b.orderId
        if (a.amountCents != b.amountCents) return false
        if (a.direction != b.direction) return false
        val merchantA = noiseKey(a.merchantCandidates.firstOrNull())
        val merchantB = noiseKey(b.merchantCandidates.firstOrNull())
        if (merchantA.isNotEmpty() && merchantB.isNotEmpty() && merchantA != merchantB) return false
        val timeA = a.eventTimeMs ?: return false
        val timeB = b.eventTimeMs ?: return false
        return abs(timeA - timeB) <= CORRELATION_WINDOW_MS
    }

    private const val CORRELATION_WINDOW_MS = 2L * 60 * 1000
    private const val FUTURE_TOLERANCE_MS = 5L * 60 * 1000
    private const val MIN_EVENT_TIME = 1_262_304_000_000L // 2010-01-01
}
