package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic page understanding for WeChat / Alipay transaction screenshots.
 *
 * OCR gives text plus positions; this object only applies explainable rules.
 * "No failure marker found" is never treated as proof of success: a completed
 * transaction needs an explicit completion marker on the page.
 *
 * Three page shapes need more than one-amount-one-draft handling:
 *  - future debit pages (支付分 / 先用后付) describe money that has not moved yet;
 *    the page keeps one expected-deduction amount and waits for evidence;
 *  - refund arrival pages describe one incoming refund, so an amount repeated in
 *    the header and in the "退款金额" row stays one refund draft;
 *  - app chrome (记账日报 / 本月统计) and narration ("充电订单-预付4元") are not
 *    transactions at all.
 */
object ScreenshotPageInterpreter {

    const val PAGE_SUCCESS = "SUCCESS_PAGE"
    const val PAGE_DETAIL = "DETAIL_PAGE"
    const val PAGE_CHECKOUT = "CHECKOUT_PAGE"
    const val PAGE_LIST = "BILL_LIST"
    const val PAGE_UNKNOWN = "UNKNOWN"

    private val WECHAT_MARKERS = listOf(
        "微信支付", "微信", "零钱", "转账给", "收款方", "付款给", "当前状态", "支付分",
    )
    private val ALIPAY_MARKERS = listOf("支付宝", "账单详情", "交易成功", "付款方式", "订单号", "花呗", "余额宝")
    private val LIST_MARKERS = listOf("全部账单", "账单明细", "收支明细", "账单记录")
    private val CHECKOUT_MARKERS = listOf("立即支付", "确认付款", "确认转账", "请输入金额")

    private val COMPLETED_MARKERS = listOf(
        "交易成功", "支付成功", "付款成功", "转账成功", "交易完成", "已到账",
        "已存入零钱", "已收款", "收款成功", "支付完成",
    )
    /** A refund that arrived is finished money, even though 已退回 reads like a cancellation. */
    private val REFUND_COMPLETED_MARKERS = listOf(
        "退款成功", "退款到账", "退款完成", "退款已到账", "退款已原路退回", "已全额退款",
    )
    private val PENDING_MARKERS = listOf(
        "等待对方确认", "待收款", "待领取", "待对方接收", "处理中", "转账处理中", "对方未领取",
    )
    private val CREATED_MARKERS = listOf(
        "待付款", "等待付款", "已创建", "待支付", "订单已生成", "预计扣费", "先用后付",
    )
    private val CANCELLED_MARKERS = listOf("已取消", "已关闭", "已过期", "已退还", "已退回")
    private val FAILED_MARKERS = listOf("支付失败", "转账失败", "交易失败")

    /** Login / security notices are not payments, even when they carry the app name. */
    private val NOTICE_MARKERS = listOf(
        "登录通知", "新设备登录", "异地登录", "登录保护", "验证码", "微信团队",
    )

    /** 微信支付分 / 先用后付: the order exists but nothing has been charged. */
    private val FUTURE_DEBIT_MARKERS = listOf(
        "订单已生成", "预计扣费", "先用后付", "自动确认收货", "确认收货后扣款",
    )
    private val DEDUCTION_LABELS = listOf("预计扣费金额", "扣费金额", "预计扣款", "扣款金额", "待扣金额")

    /** Refund arrival pages: one incoming refund, never the surrounding statistics. */
    private val REFUND_PAGE_MARKERS = listOf(
        "退款金额", "退款成功", "退款到账", "退款详情", "退款已原路退回", "已全额退款",
    )

    /** Footer / app-chrome statistics that sit under a payment page and are not transactions. */
    private val NOISE_LINE_MARKERS = listOf(
        "记账日报", "昨日支出", "昨日收入", "今日支出", "本周支出",
        "本月统计", "本月支出", "上月支出", "收支分析", "账单分析", "消费报表",
    )

    /** Field labels and chrome that must never be read as a merchant name. */
    private val MERCHANT_STOPWORDS = listOf(
        "金额", "时间", "单号", "状态", "方式", "备注", "说明", "详情", "统计", "日报",
        "支付", "收款", "转账", "退款", "订单", "零钱", "钱包", "账单",
    )

    private val ORDER_ID_REGEX =
        Regex("(?:订单号|交易单号|交易号|商户订单号)\\s*[:：]?\\s*([A-Za-z0-9]{8,40})")

    /**
     * A line that starts with a currency amount: `¥48.00`, `-¥36.50`, `¥ 1.85 元`.
     * Without an amount label this is the only accepted shape.
     */
    private val CURRENCY_LINE_REGEX = Regex(
        "^\\s*([-+])?\\s*[¥￥]\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元?",
    )

    /** A whole line that is only a number with the 元 suffix: `48.00元`. */
    private val YUAN_LINE_REGEX = Regex(
        "^\\s*([-+])?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元\\s*$",
    )

    /**
     * A labelled amount: `退款金额 ¥1.85`, `预计扣费金额 9.65元`, `合计 48.00元`.
     * The number still has to carry 元 or a currency sign, otherwise narration
     * such as "充电订单-预付4元" would count as money.
     */
    private val LABELLED_AMOUNT_REGEX = Regex(
        "(金额|实付|合计|支付|收款|退款|扣费|付款|转账|收入|支出)" +
            "[^0-9¥￥\\n]{0,6}([¥￥])?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(元)?",
    )

    private val DATE_TIME_REGEX = Regex(
        "(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?",
    )
    private val TIME_ONLY_REGEX = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
    private val RELATIVE_DAY_REGEX = Regex("大前天|前天|昨天|昨日|今天|今日")

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
        // Footer statistics belong to the app shell, not to the transaction.
        val content = ordered.filterNot { isNoiseLine(it.text) }
        val allText = content.joinToString("\n") { it.text }
        val platform = detectPlatform(allText)
        val pageType = detectPageType(allText, content.any { amountOf(it.text) != null })
        if (pageType == PAGE_LIST) {
            return ScreenshotPageEvidence(
                platform = platform,
                pageType = pageType,
                transactionLike = false,
                drafts = emptyList(),
                reason = "bill list pages belong to the import flow",
            )
        }
        if (isNoticePage(allText)) {
            return ScreenshotPageEvidence(
                platform = platform,
                pageType = pageType,
                transactionLike = false,
                drafts = emptyList(),
                reason = "login or security notice, not a payment",
            )
        }
        // A created-but-not-charged page still needs a supported transaction
        // source, but its marker is later attached to only the matching segment.
        val hasFutureDebitMarker = FUTURE_DEBIT_MARKERS.any(allText::contains)
        // Only WeChat / Alipay result pages are interpreted. An unknown app is
        // ignored instead of being turned into a guessed draft.
        if (platform == CaptureCandidate.PLATFORM_UNKNOWN && !hasFutureDebitMarker) {
            return ScreenshotPageEvidence(
                platform = platform,
                pageType = pageType,
                transactionLike = false,
                drafts = emptyList(),
                reason = "not a WeChat or Alipay payment page",
            )
        }

        val segments = splitSegments(content)
        if (segments.isEmpty()) {
            return ScreenshotPageEvidence(
                platform = platform,
                pageType = pageType,
                transactionLike = false,
                drafts = emptyList(),
                reason = "no transaction fields",
            )
        }
        val refundPage = !hasFutureDebitMarker && REFUND_PAGE_MARKERS.any(allText::contains)
        val selected: List<Pair<Int, List<TextBlock>>> = when {
            hasFutureDebitMarker -> {
                val hasDeductionAmount = segments.any { segment ->
                    segment.any { block -> DEDUCTION_LABELS.any { block.text.contains(it) } }
                }
                segments.withIndex()
                    // The future-debit page often repeats the amount as a generic
                    // order total. Keep the labelled expected debit, not that total.
                    .filterNot { (_, segment) ->
                        hasDeductionAmount && segment.any { it.text.contains("订单金额") }
                    }
                    .map { it.index to it.value }
            }
            refundPage -> {
                val index = segments.indexOfFirst { segment ->
                    segment.any { it.text.contains("退款金额") }
                }.takeIf { it >= 0 } ?: 0
                listOf(index to segments[index])
            }
            else -> segments.mapIndexed { index, segment -> index to segment }
        }
        var drafts = selected.mapNotNull { (index, segment) ->
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
        if (hasFutureDebitMarker) {
            val localFutureIndices = segments.withIndex().filter { (_, segment) ->
                segment.any { block -> FUTURE_DEBIT_MARKERS.any { block.text.contains(it) } }
            }.map { it.index }.toSet()
            val labelledDebitIndex = segments.indexOfFirst { segment ->
                segment.any { block -> DEDUCTION_LABELS.any { block.text.contains(it) } }
            }
            val futureIndices = localFutureIndices + setOfNotNull(labelledDebitIndex.takeIf { it >= 0 })
            drafts = drafts.map { draft ->
                if (draft.index in futureIndices) {
                    draft.copy(
                        completionStatus = CaptureCandidate.COMPLETION_CREATED,
                        completionExplicit = true,
                        reason = "future debit; nothing charged yet",
                    )
                } else {
                    draft
                }
            }
        }
        if (refundPage) {
            val refundCompleted = REFUND_COMPLETED_MARKERS.any(allText::contains)
            drafts = drafts.map {
                it.copy(
                    kind = CaptureCandidate.KIND_REFUND,
                    direction = CaptureCandidate.DIRECTION_INCOME,
                    directionExplicit = true,
                    completionStatus = if (refundCompleted) {
                        CaptureCandidate.COMPLETION_COMPLETED
                    } else {
                        it.completionStatus
                    },
                    completionExplicit = it.completionExplicit || refundCompleted,
                    reason = "incoming refund",
                )
            }
        }
        val reconciled = hasFutureDebitMarker || refundPage
        val conflicting = !reconciled && drafts.size == 1 && countDistinctAmounts(content) > 1
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

    private fun detectPageType(text: String, hasAmountLine: Boolean): String {
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
        return if (hasAmountLine) PAGE_DETAIL else PAGE_UNKNOWN
    }

    private fun isNoticePage(text: String): Boolean =
        COMPLETED_MARKERS.none(text::contains) && NOTICE_MARKERS.any(text::contains)

    private fun isNoiseLine(line: String): Boolean {
        val text = line.trim()
        return NOISE_LINE_MARKERS.any(text::contains)
    }

    /**
     * Each amount-bearing line anchors one transaction. Every other line joins
     * the vertically nearest anchor (ties go to the lower one), so the merchant
     * header above the *next* amount is not appended to the previous card.
     */
    private fun splitSegments(blocks: List<TextBlock>): List<List<TextBlock>> {
        val anchors = blocks.filter { amountOf(it.text) != null }
        if (anchors.isEmpty()) return emptyList()
        val segments = anchors.map { mutableListOf<TextBlock>() }
        var anchorIndex = 0
        blocks.forEach { block ->
            if (amountOf(block.text) != null) {
                segments[anchorIndex] += block
                anchorIndex += 1
            } else {
                var best = 0
                var bestDistance = Long.MAX_VALUE
                anchors.forEachIndexed { index, anchor ->
                    val distance = abs((block.top - anchor.top).toLong())
                    if (distance <= bestDistance) {
                        bestDistance = distance
                        best = index
                    }
                }
                segments[best] += block
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
        val completion = completionOf(segment)
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
        text.contains("花呗") || text.contains("自动扣款") || text.contains("先用后付") ->
            CaptureCandidate.KIND_AUTO_PAY
        direction == CaptureCandidate.DIRECTION_UNKNOWN -> CaptureCandidate.KIND_PAYMENT
        else -> CaptureCandidate.KIND_PAYMENT
    }

    private fun completionOf(segment: List<TextBlock>): Pair<String, Boolean> {
        val amount = segment.firstOrNull { amountOf(it.text) != null }
        val statusBlocks = segment.filter { block -> isCompletionMarker(block.text) }
        val anchoredStatuses = statusBlocks.filterNot { block ->
            isNonPaymentCallCancellation(block.text) ||
                (amount != null && hasGeometry(block) && hasGeometry(amount) &&
                    horizontalGap(block, amount) > statusHorizontalGapLimit(block, amount))
        }
        val paymentStatusText = anchoredStatuses.joinToString("\n") { it.text }
        return when {
            FAILED_MARKERS.any(paymentStatusText::contains) -> CaptureCandidate.COMPLETION_FAILED to true
            PENDING_MARKERS.any(paymentStatusText::contains) -> CaptureCandidate.COMPLETION_PENDING to true
            CREATED_MARKERS.any(paymentStatusText::contains) -> CaptureCandidate.COMPLETION_CREATED to true
            // Checked before CANCELLED: "退款已原路退回" means the refund landed.
            REFUND_COMPLETED_MARKERS.any(paymentStatusText::contains) ->
                CaptureCandidate.COMPLETION_COMPLETED to true
            CANCELLED_MARKERS.any(paymentStatusText::contains) -> CaptureCandidate.COMPLETION_CANCELLED to true
            COMPLETED_MARKERS.any(paymentStatusText::contains) -> CaptureCandidate.COMPLETION_COMPLETED to true
            else -> CaptureCandidate.COMPLETION_UNKNOWN to false
        }
    }

    private fun isCompletionMarker(text: String): Boolean =
        COMPLETED_MARKERS.any(text::contains) || REFUND_COMPLETED_MARKERS.any(text::contains) ||
            PENDING_MARKERS.any(text::contains) || CREATED_MARKERS.any(text::contains) ||
            CANCELLED_MARKERS.any(text::contains) || FAILED_MARKERS.any(text::contains)

    private fun isNonPaymentCallCancellation(text: String): Boolean =
        text.contains("已取消") && listOf("通话", "语音", "视频", "电话", "呼叫").any(text::contains)

    private fun hasGeometry(block: TextBlock): Boolean =
        block.right > block.left && block.bottom > block.top

    private fun horizontalGap(a: TextBlock, b: TextBlock): Int = when {
        a.right < b.left -> b.left - a.right
        b.right < a.left -> a.left - b.right
        else -> 0
    }

    private fun statusHorizontalGapLimit(a: TextBlock, b: TextBlock): Int =
        max(MIN_STATUS_HORIZONTAL_GAP, max(a.bottom - a.top, b.bottom - b.top) * 4)

    private fun merchantOf(segment: List<TextBlock>, amountBlock: TextBlock): List<String> {
        val labels = listOf("收款方", "付款给", "转账给", "商户全称", "对方", "收款人")
        segment.forEachIndexed { index, block ->
            labels.forEach { label ->
                if (block.text.startsWith(label) || block.text.contains("$label：") ||
                    block.text.contains("$label:")
                ) {
                    val inline = block.text.substringAfter(label).trimStart('：', ':', ' ')
                    merchantName(inline, allowDigit = true)?.let { return listOf(it) }
                    merchantName(segment.getOrNull(index + 1)?.text, allowDigit = true)?.let {
                        return listOf(it)
                    }
                }
                // "转账给 张三" on one line
                if (block.text.contains("$label ") && block != amountBlock) {
                    val inline = block.text.substringAfter("$label ").trim()
                    merchantName(inline, allowDigit = true)?.let { return listOf(it) }
                }
            }
        }
        // Fall back to the line directly above the amount, which is where both
        // apps put the merchant on a success page.
        val above = segment.lastOrNull { it.bottom <= amountBlock.top }
        return merchantName(above?.text, allowDigit = false)?.let { listOf(it) } ?: emptyList()
    }

    /**
     * Field labels ("退款金额"), status words ("交易成功") and sentences with
     * numbers are not merchant names. Explicitly labelled values may contain
     * digits, because real shops do ("7-ELEVEN").
     */
    private fun merchantName(value: String?, allowDigit: Boolean): String? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || text.length > 40) return null
        if (!allowDigit && text.any(Char::isDigit)) return null
        if (text.contains('¥') || text.contains('￥') || text.endsWith("元")) return null
        if (MERCHANT_STOPWORDS.any { text == it || text.endsWith(it) || text.startsWith(it) }) return null
        val status = COMPLETED_MARKERS + REFUND_COMPLETED_MARKERS + CANCELLED_MARKERS +
            PENDING_MARKERS + FAILED_MARKERS + CREATED_MARKERS + NOTICE_MARKERS +
            listOf("当前状态", "支付方式", "商品详情", "交易详情", "账单详情", "订单号", "交易单号")
        if (status.any(text::contains)) return null
        return text
    }

    private fun eventTimeOf(text: String, capturedAtMs: Long, timeZone: TimeZone): Long? {
        DATE_TIME_REGEX.find(text)?.let { match ->
            val values = match.groupValues
            return runCatching {
                Calendar.getInstance(timeZone).apply {
                    isLenient = false
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

        val relativeDay = RELATIVE_DAY_REGEX.find(text)?.value
        val times = TIME_ONLY_REGEX.findAll(text).toList()
        if (relativeDay != null) {
            // A relative day without exactly one valid clock is not evidence for
            // the transaction time. Do not fall through to capture-day inference.
            val match = times.singleOrNull() ?: return null
            val dayOffset = when (relativeDay) {
                "大前天" -> -3
                "前天" -> -2
                "昨天", "昨日" -> -1
                else -> 0
            }
            return runCatching {
                Calendar.getInstance(timeZone).apply {
                    timeInMillis = capturedAtMs
                    isLenient = false
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, match.groupValues[1].toInt())
                    set(Calendar.MINUTE, match.groupValues[2].toInt())
                    set(Calendar.SECOND, match.groupValues[3].ifBlank { "0" }.toInt())
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis.takeIf {
                    it in MIN_EVENT_TIME..(capturedAtMs + FUTURE_TOLERANCE_MS)
                }
            }.getOrNull()
        }
        if (times.size != 1 || text.contains("年") || text.contains("-")) return null
        val match = times.single()
        return runCatching {
            val sameDay = Calendar.getInstance(timeZone).apply {
                timeInMillis = capturedAtMs
                isLenient = false
                set(Calendar.HOUR_OF_DAY, match.groupValues[1].toInt())
                set(Calendar.MINUTE, match.groupValues[2].toInt())
                set(Calendar.SECOND, match.groupValues[3].ifBlank { "0" }.toInt())
                set(Calendar.MILLISECOND, 0)
            }
            val sameDayTime = sameDay.timeInMillis
            // A time later than the screenshot would belong to yesterday.
            val eventTime = if (sameDayTime > capturedAtMs + FUTURE_TOLERANCE_MS) {
                sameDay.add(Calendar.DAY_OF_YEAR, -1)
                sameDay.timeInMillis
            } else {
                sameDayTime
            }
            eventTime.takeIf { it in MIN_EVENT_TIME..(capturedAtMs + FUTURE_TOLERANCE_MS) }
        }.getOrNull()
    }

    /**
     * Amount in integer cents.
     *
     * A line counts only when it is a whole-line currency amount ("¥48.00",
     * "-¥36.50", "48.00元") or when it carries an explicit amount label next to
     * a currency-suffixed number ("退款金额 ¥1.85"). Narration such as
     * "商品详情 充电订单-预付4元" therefore never becomes a transaction.
     */
    fun amountOf(line: String): Long? {
        val text = line.trim()
        if (text.isEmpty()) return null
        CURRENCY_LINE_REGEX.find(text)?.let { match ->
            return centsOf(match.groupValues[2])
        }
        YUAN_LINE_REGEX.matchEntire(text)?.let { match ->
            return centsOf(match.groupValues[2])
        }
        val labelled = LABELLED_AMOUNT_REGEX.findAll(text).firstOrNull { candidate ->
            candidate.groupValues[2].isNotBlank() || candidate.groupValues[4].isNotBlank()
        } ?: return null
        return centsOf(labelled.groupValues[3])
    }

    private fun centsOf(raw: String): Long? {
        val plain = raw.replace(",", "")
        if (plain.isEmpty()) return null
        return runCatching {
            BigDecimal(plain).setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .longValueExact()
        }.getOrNull()?.takeIf { it in 1..CaptureCandidate.MAX_CENTS }
    }

    private fun countDistinctAmounts(blocks: List<TextBlock>): Int =
        blocks.mapNotNull { amountOf(it.text) }.distinct().size

    private fun noiseKey(value: String?): String =
        value.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "").take(24)

    private const val MIN_STATUS_HORIZONTAL_GAP = 72
    private const val FUTURE_TOLERANCE_MS = 5L * 60 * 1000
    private const val MIN_EVENT_TIME = 1_262_304_000_000L // 2010-01-01
}
