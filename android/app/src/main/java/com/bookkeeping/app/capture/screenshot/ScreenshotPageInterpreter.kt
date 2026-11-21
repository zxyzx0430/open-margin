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
 *  - a bill list shows several settled rows at once. Its rows carry no status
 *    word, because a list only prints money that already moved: a row with no
 *    unfinished word is finished. That completion is inferred from the list,
 *    which is enough for an ordinary payment but never for a transfer — a list
 *    never prints 已被接收 / 已收款, so the transfer keeps asking. A
 *    single-amount page is a detail page and keeps asking too, so the rule can
 *    never relax a real detail screenshot;
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

    /** A generic order total that may merely repeat the card's expected debit. */
    private const val ORDER_TOTAL_LABEL = "订单金额"

    /** The labelled refund row that carries the amount a refund page repeats. */
    private const val REFUND_AMOUNT_LABEL = "退款金额"

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

    /**
     * Rows that surround a bill-list card and can never be a shop name: the
     * payment-method sentence (使用…支付 / 零钱 / 储蓄卡 / 信用卡 / 余额), the
     * 账单详情 row and the 分分捐 / 领小红花 footer. On these cards the shop
     * header itself sits two to four rows above the amount.
     */
    private val MERCHANT_INTERFERENCE = listOf(
        "使用", "支付方式", "零钱", "储蓄卡", "信用卡", "余额",
        "账单详情", "交易详情", "分分捐", "领小红花",
    )

    /** How many rows above the amount a shop name may sit on a bill-list card. */
    private const val MERCHANT_LOOKUP_LINES = 4

    /**
     * The footer a bill-list card prints under its amount. A detail page is
     * already open, so it never prints one below the amount.
     */
    private val BILL_ROW_FOOTERS = listOf("账单详情", "交易详情")

    /** The payment-method sentence every bill-list card carries. */
    private fun isPaymentMethodRow(line: String): Boolean {
        val text = line.trim()
        if (text.contains("支付方式") || text.contains("付款方式")) return true
        return text.startsWith("使用") && text.contains("支付")
    }

    /** Any of these words means the row's money has not settled. */
    private val NON_FINAL_MARKERS =
        PENDING_MARKERS + CREATED_MARKERS + CANCELLED_MARKERS + FAILED_MARKERS

    /**
     * WeChat chat transfer bubbles: the amount, the counterparty's action and
     * the word 转账. 已被接收 is only drawn in the sender's bubble and 已收款
     * only in the receiver's, so those words identify the side. 待接收 is drawn
     * on both bubbles — the sender waiting for the money to be accepted and the
     * receiver waiting to accept it — so that word says nothing about direction.
     */
    private val TRANSFER_BUBBLE_STATES = listOf("已被接收", "已接收", "已收款", "待接收")

    /** A page span narrower than this cannot prove which side a bubble sits on. */
    private const val MIN_BUBBLE_SPAN = 240

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

    /** `11月19日 20:10`: a real date that carries no year; resolved to the nearest year. */
    private val MONTH_DAY_TIME_REGEX = Regex(
        "(?:^|[^0-9])(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?",
    )
    /** A date-shaped token: `11月19日` / `11月`, not `每日优鲜` or `月饼`; also `2026-11`, `11/19`. */
    private val DATE_TOKEN_REGEX = Regex("(\\d{1,2})\\s*[月日]|\\d{1,4}\\s*[-/]\\s*\\d{1,2}")
    private val TIME_ONLY_REGEX = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
    private val RELATIVE_DAY_REGEX = Regex("大前天|前天|昨天|昨日|今天|今日")

    /**
     * `周四` / `星期四` / `周天` at the start of a row: the weekday form of a
     * chat date separator. A shop such as `周六福` is not a weekday, so the
     * token must not be glued to more text.
     */
    private val WEEKDAY_REGEX = Regex(
        "^\\s*(?:周|星期)([一二三四五六日天])(?=\\s|$|\\d)",
        RegexOption.MULTILINE,
    )

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
        val platform = detectPlatform(allText).let { detected ->
            // A chat screenshot often carries no app name at all. The transfer
            // bubble itself (amount + 已被接收/已收款/待接收 + 转账) is then the
            // only proof that this is a WeChat payment page.
            if (detected == CaptureCandidate.PLATFORM_UNKNOWN && isTransferBubbleText(allText)) {
                CaptureCandidate.PLATFORM_WECHAT
            } else {
                detected
            }
        }
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
        // Horizontal page span, used only to tell which side a chat bubble is
        // drawn on. Without real boxes there is no side and no guess.
        val boxedContent = content.filter { hasGeometry(it) }
        val pageLeft = boxedContent.minOfOrNull { it.left } ?: 0
        val pageRight = boxedContent.maxOfOrNull { it.right } ?: 0
        // Status evidence belongs to the card that carries it. A refund marker on
        // one card must not retype, complete or suppress another payment card on
        // the same page, and a future-debit marker must not hide its neighbour.
        val cards = mergeRepeatedRefundCards(segments)
        // A bill list prints only money that already moved, so a row with no
        // unfinished word is settled. Card-level, because one list screenshot
        // can hold several rows and one unfinished row must not stop the rest.
        val settledRowCards = settledBillRowCards(cards)
        val refundCard = cards.associate { card -> card.index to carriesAny(card.blocks, REFUND_PAGE_MARKERS) }
        val futureDebitCard = cards.associate { card ->
            card.index to carriesAny(card.blocks, FUTURE_DEBIT_MARKERS)
        }
        val deductionCard = cards.associate { card -> card.index to carriesAny(card.blocks, DEDUCTION_LABELS) }
        // A generic 订单金额 that repeats the expected debit is the same card's
        // total, not a second transaction. Only those repeats are dropped.
        val repeatedOrderTotals = if (deductionCard.containsValue(true)) {
            duplicateOrderTotals(cards, deductionCard)
        } else {
            emptySet()
        }
        var drafts = cards.mapNotNull { card ->
            if (card.index in repeatedOrderTotals) return@mapNotNull null
            buildDraft(
                card.index,
                card.blocks,
                platform,
                pageType,
                capturedAtMs,
                timeZone,
                pageLeft,
                pageRight,
            )
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
        if (settledRowCards.isNotEmpty()) {
            drafts = drafts.map { draft ->
                if (draft.index !in settledRowCards) return@map draft
                val row = draft.copy(settledBillRow = true)
                // An explicit status word always wins; only a row that said
                // nothing at all is read as settled — and that completion is
                // then inferred from the list, not stated by the page.
                if (draft.completionStatus != CaptureCandidate.COMPLETION_UNKNOWN) return@map row
                row.copy(
                    completionStatus = CaptureCandidate.COMPLETION_COMPLETED,
                    completionExplicit = true,
                    completionInferredFromList = true,
                    reason = "settled bill list row",
                )
            }
        }
        if (futureDebitCard.containsValue(true) || deductionCard.containsValue(true)) {
            val anyFutureDebit = futureDebitCard.containsValue(true)
            drafts = drafts.map { draft ->
                val ownFutureDebit = futureDebitCard[draft.index] == true
                val expectedDebit = deductionCard[draft.index] == true && anyFutureDebit
                if (ownFutureDebit || expectedDebit) {
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
        if (refundCard.containsValue(true)) {
            drafts = drafts.map { draft ->
                if (refundCard[draft.index] != true) return@map draft
                val card = cards.first { it.index == draft.index }
                val amountBlock = card.blocks.firstOrNull { amountOf(it.text) != null }
                val refundCompleted = card.blocks.any { block ->
                    REFUND_COMPLETED_MARKERS.any(block.text::contains) &&
                        isCompletionMarker(block.text) && isBoundStatus(block, amountBlock)
                }
                val ambiguousCompletion = !refundCompleted &&
                    draft.completionStatus == CaptureCandidate.COMPLETION_COMPLETED
                draft.copy(
                    kind = CaptureCandidate.KIND_REFUND,
                    direction = CaptureCandidate.DIRECTION_INCOME,
                    directionExplicit = true,
                    completionStatus = if (refundCompleted) {
                        CaptureCandidate.COMPLETION_COMPLETED
                    } else if (ambiguousCompletion) {
                        CaptureCandidate.COMPLETION_UNKNOWN
                    } else {
                        draft.completionStatus
                    },
                    completionExplicit = !ambiguousCompletion && (draft.completionExplicit || refundCompleted),
                    reason = "incoming refund",
                )
            }
        }
        // A refund page repeats the amount once in the header and once in the
        // 退款金额 row. Which card the status line lands in depends on OCR
        // geometry, so the duplicate is dropped page-wide: any other draft with
        // the labelled refund amount in refund/income shape is the same money,
        // never a second incoming payment to confirm.
        val labelledRefundAmounts = cards
            .flatMap { card -> card.blocks }
            .filter { block -> block.text.contains(REFUND_AMOUNT_LABEL) }
            .mapNotNull { block -> amountOf(block.text) }
            .toSet()
        if (labelledRefundAmounts.isNotEmpty()) {
            drafts = drafts.filterNot { draft ->
                val card = cards.first { it.index == draft.index }
                draft.amountCents?.let { it in labelledRefundAmounts } == true &&
                    !carriesAny(card.blocks, listOf(REFUND_AMOUNT_LABEL)) &&
                    (
                        draft.kind == CaptureCandidate.KIND_REFUND ||
                            (draft.direction == CaptureCandidate.DIRECTION_INCOME &&
                                draft.merchantCandidates.isEmpty())
                        )
            }
        }
        val reconciled = futureDebitCard.containsValue(true) ||
            deductionCard.containsValue(true) || refundCard.containsValue(true)
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
     * the vertically nearest anchor, so the merchant header above the *next*
     * amount is not appended to the previous card.
     *
     * A whole row that is only a timestamp is the exception: chat apps print
     * `周四 22:01` above the message it dates, so the nearest amount is the card
     * *above* it and the row would carry the wrong clock. On a page with several
     * boxed amounts such a row joins the nearest amount below it instead, and
     * when nothing follows it the row describes none of the cards. A page with a
     * single amount is never split, so its own timestamp under the amount stays
     * where it is.
     *
     * OCR boxes are optional, and a missing box must never delete money. Only a
     * line with real geometry may be placed by distance, and only against an
     * anchor that also has one: an all-zero placeholder box would make every
     * line equidistant, and a zero-height box is not a row. An amount line
     * anchors its own card whether or not it has a box — dropping the unboxed
     * ones used to delete a whole transaction from a page that happened to have
     * a boxed amount too.
     *
     * Unboxed text keeps its OCR reading order, but it may only join a card that
     * is itself unboxed: geometry is the only proof that a word belongs to a
     * positioned card, so an unboxed 支付成功 must never rewrite the status of a
     * boxed card. Unboxed lines that follow no unboxed amount stay unplaced,
     * exactly as before.
     *
     * A page with no box at all stays one page-level card: nothing can be
     * attributed, so the amount is not unambiguous and the policy refuses to
     * auto book it. The same holds when the only amount on the page has no box:
     * one amount cannot be confused with another, and its status words belong to
     * it. Several unboxed amounts are split one card each, so none disappears,
     * and they carry no status evidence at all.
     */
    private fun splitSegments(blocks: List<TextBlock>): List<List<TextBlock>> {
        val anchors = blocks.filter { amountOf(it.text) != null }
        // Nothing to split, or no position anywhere: one page-level card.
        if (anchors.isEmpty() || blocks.none { hasGeometry(it) }) return listOf(blocks)
        val boxedAnchors = anchors.indices.filter { hasGeometry(anchors[it]) }
        // One amount and no boxed amount of its own: the page still describes
        // exactly that one transaction, so its status words belong to it.
        if (boxedAnchors.isEmpty() && anchors.size == 1) return listOf(blocks)
        val segments = anchors.map { mutableListOf<TextBlock>() }
        var anchorIndex = 0
        // Index of the last card opened by an amount line without a box, or -1.
        var unboxedCard = -1
        blocks.forEach { block ->
            when {
                // Every amount keeps its own card, boxed or not.
                amountOf(block.text) != null -> {
                    unboxedCard = if (hasGeometry(block)) -1 else anchorIndex
                    segments[anchorIndex] += block
                    anchorIndex += 1
                }
                // Missing geometry is not a position: the line keeps its OCR
                // reading order inside the unboxed card it follows.
                !hasGeometry(block) -> {
                    if (unboxedCard >= 0) segments[unboxedCard] += block
                }
                // A boxed line needs a boxed amount to be measured against;
                // without one there is no row it could be proven to belong to.
                boxedAnchors.isEmpty() -> Unit
                // A chat date separator is a row of its own printed *above* the
                // message it belongs to: `周四 22:01`, `昨天 02:17`. Measuring
                // by distance hands it to the card above, which then steals its
                // neighbour's clock (and leaves this card with the next
                // separator). A standalone timestamp therefore goes to the
                // nearest card below it, and to no card at all when it is the
                // last row on the page. Only a page with several boxed amounts
                // needs this: on a one-card page every row is that card's own
                // field, including the timestamp printed under its amount,
                // which must not be dropped. In-card fields also keep the
                // distance rule, because they carry a label
                // (`发生时间 2026-11-21 17:13`) and are not standalone rows.
                boxedAnchors.size > 1 && unboxedCard < 0 && isStandaloneTimestampLine(block.text) -> {
                    boxedAnchors
                        .filter { anchors[it].top > block.top }
                        .minByOrNull { anchors[it].top }
                        ?.let { segments[it] += block }
                }
                else -> {
                    var best = boxedAnchors.first()
                    var bestDistance = Long.MAX_VALUE
                    boxedAnchors.forEach { index ->
                        val distance = abs((block.top - anchors[index].top).toLong())
                        if (distance <= bestDistance) {
                            bestDistance = distance
                            best = index
                        }
                    }
                    segments[best] += block
                }
            }
        }
        return segments
    }

    /** One transaction card: its own amount anchor plus the text around it. */
    private data class Card(val index: Int, val blocks: List<TextBlock>)

    private fun carriesAny(blocks: List<TextBlock>, markers: List<String>): Boolean =
        blocks.any { block -> markers.any { block.text.contains(it) } }

    /**
     * Rows of a settled bill list, by card index.
     *
     * A list is several amount cards on one page, each printing a bare amount
     * plus its own `账单详情 >` footer or payment-method sentence. A detail page
     * prints labelled sub-totals (订单金额 / 商品金额 / 实付金额) instead, so a
     * labelled amount disqualifies the whole page: those cards are parts of one
     * transaction, not separate rows.
     *
     * A single-amount page is a detail page and is never upgraded here: without
     * a visible 支付成功 it still has to ask.
     */
    private fun settledBillRowCards(cards: List<Card>): Set<Int> {
        if (cards.size < 2) return emptySet()
        val amounts = cards.flatMap { card -> card.blocks.filter { amountOf(it.text) != null } }
        if (amounts.isEmpty()) return emptySet()
        if (amounts.any { block -> !isPlainCurrencyLine(block.text) }) return emptySet()
        return cards
            .filter { card -> isSettledBillRow(card.blocks) }
            .map { card -> card.index }
            .toSet()
    }

    private fun isSettledBillRow(blocks: List<TextBlock>): Boolean {
        val amount = blocks.singleOrNull { amountOf(it.text) != null } ?: return false
        // 待收款 / 已取消 / 支付失败 / 待付款 all mean the money has not settled.
        // The word is read anywhere in the card, bound to the amount or not.
        if (carriesAny(blocks, NON_FINAL_MARKERS)) return false
        val footer = blocks.any { block ->
            BILL_ROW_FOOTERS.any(block.text::contains) &&
                hasGeometry(block) && hasGeometry(amount) && block.top >= amount.top
        }
        if (footer) return true
        return blocks.any { block -> isPaymentMethodRow(block.text) }
    }

    /** A bare `¥8.00` / `-¥8.00` / `8.00元` line, the shape a list row prints. */
    private fun isPlainCurrencyLine(line: String): Boolean {
        val text = line.trim()
        return CURRENCY_LINE_REGEX.find(text) != null || YUAN_LINE_REGEX.matchEntire(text) != null
    }

    /**
     * A refund page repeats the same money twice: once as the header amount and
     * once as the labelled `退款金额` row. Those are one refund, so the header
     * card's status text is folded into the labelled card and its duplicate
     * amount line dropped. Refunds with different amounts stay separate cards.
     */
    private fun mergeRepeatedRefundCards(segments: List<List<TextBlock>>): List<Card> {
        val labelled = segments.indices.filter { index ->
            segments[index].any { block -> block.text.contains(REFUND_AMOUNT_LABEL) }
        }
        if (labelled.isEmpty()) return segments.mapIndexed { index, blocks -> Card(index, blocks) }

        val merged = segments.map { it.toMutableList() }
        val dropped = mutableSetOf<Int>()
        segments.forEachIndexed { index, segment ->
            if (index in labelled) return@forEachIndexed
            if (!carriesAny(segment, REFUND_PAGE_MARKERS)) return@forEachIndexed
            val amount = segment.firstNotNullOfOrNull { amountOf(it.text) } ?: return@forEachIndexed
            val target = labelled.firstOrNull { other ->
                segments[other].firstNotNullOfOrNull { amountOf(it.text) } == amount
            } ?: return@forEachIndexed
            merged[target] += segment.filterNot { amountOf(it.text) != null }
            dropped += index
        }
        if (dropped.isEmpty()) return segments.mapIndexed { index, blocks -> Card(index, blocks) }
        return segments.indices.filterNot { it in dropped }.map { Card(it, merged[it]) }
    }

    /**
     * Generic `订单金额` cards that only repeat the expected debit of a future-debit
     * card. A card that confirms its own payment, or sits next to no labelled
     * expected debit, is a real transaction and stays.
     */
    private fun duplicateOrderTotals(cards: List<Card>, deductionCard: Map<Int, Boolean>): Set<Int> {
        val deductionIndices = cards.map { it.index }.filter { deductionCard[it] == true }
        val deductionAmounts = deductionIndices.mapNotNull { index ->
            cards.first { it.index == index }.blocks.firstNotNullOfOrNull { amountOf(it.text) }
        }.toSet()
        val duplicates = mutableSetOf<Int>()
        cards.forEach { card ->
            if (card.blocks.none { it.text.contains(ORDER_TOTAL_LABEL) }) return@forEach
            if (carriesAny(card.blocks, COMPLETED_MARKERS)) return@forEach
            val ownFutureDebit = carriesAny(card.blocks, FUTURE_DEBIT_MARKERS)
            val amount = card.blocks.firstNotNullOfOrNull { amountOf(it.text) }
            val nextToExpectedDebit = deductionIndices.any { it == card.index - 1 || it == card.index + 1 }
            if (ownFutureDebit || nextToExpectedDebit || (amount != null && amount in deductionAmounts)) {
                duplicates += card.index
            }
        }
        return duplicates
    }

    private fun buildDraft(
        index: Int,
        segment: List<TextBlock>,
        platform: String,
        pageType: String,
        capturedAtMs: Long,
        timeZone: TimeZone,
        pageLeft: Int,
        pageRight: Int,
    ): TransactionDraft? {
        val amountBlock = segment.firstOrNull { amountOf(it.text) != null } ?: return null
        val segmentText = segment.joinToString("\n") { it.text }
        val amount = amountOf(amountBlock.text)
        val amountUnambiguous = segment.count { amountOf(it.text) != null } == 1
        val bubble = transferBubbleOf(segmentText)
        val side = if (bubble == null) null else bubbleSideOf(amountBlock, pageLeft, pageRight)
        val direction = if (bubble == null) {
            directionOf(segmentText, amountBlock.text, pageType)
        } else {
            // The state word and the bubble's own position are independent
            // evidence. When both are readable they must agree; a contradiction
            // stays UNKNOWN instead of being guessed.
            bubbleDirectionOf(bubble.direction, side)
        }
        val kind = kindOf(segmentText, direction)
        val completion = if (bubble != null) {
            bubble.completionStatus to bubble.completionExplicit
        } else {
            completionOf(segment)
        }
        val merchant = merchantOf(segment, amountBlock)
        val orderId = ORDER_ID_REGEX.find(segmentText)?.groupValues?.get(1)
        val eventTime = eventTimeOf(segmentText, capturedAtMs, timeZone)
        val supported = platform != CaptureCandidate.PLATFORM_UNKNOWN &&
            pageType in setOf(PAGE_SUCCESS, PAGE_DETAIL)
        // A waiting bubble whose own word proves no direction must not claim one
        // in its reason either: the card has to say the direction is still open.
        val directionUnproven = bubble != null && bubble.direction == null &&
            direction == CaptureCandidate.DIRECTION_UNKNOWN
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
            reason = when {
                !supported -> "page not recognised as a payment result"
                directionUnproven -> "waiting transfer; direction needs a user pick"
                else -> "supported payment page"
            },
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

    /**
     * A WeChat chat transfer bubble: amount + state + 转账.
     *
     * [direction] is the direction the state word itself proves, or null when
     * the word proves nothing. 待接收 is printed on the sender's bubble (the
     * money already left me, waiting for the other side to accept) and on the
     * receiver's bubble (waiting for me to accept), so only the bubble's own
     * position may decide the direction there; without that position the draft
     * stays DIRECTION_UNKNOWN and the user has to pick.
     *
     * Only 已收款 proves the money arrived. 已被接收 / 已接收 prove the money left
     * me and the other side took it: the transfer is settled, so those bubbles
     * are completed expenses. 待接收 describes a transfer that has not landed
     * yet, so it stays unfinished and direction-unknown.
     */
    private data class TransferBubble(
        val direction: String?,
        val completionStatus: String,
        val completionExplicit: Boolean,
    )

    private fun transferBubbleOf(text: String): TransferBubble? {
        if (!text.contains("转账")) return null
        return when {
            // The counterparty accepted: the money has left this account and the
            // transfer is final. This is the sender-side completion, not a wait.
            text.contains("已被接收") || text.contains("已接收") -> TransferBubble(
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                completionStatus = CaptureCandidate.COMPLETION_COMPLETED,
                completionExplicit = true,
            )
            // 待接收 appears on both sides of the chat, so it cannot prove a
            // direction. Keep the unfinished meaning only; the bubble's side or
            // the user decides who paid whom.
            text.contains("待接收") -> TransferBubble(
                direction = null,
                completionStatus = CaptureCandidate.COMPLETION_UNKNOWN,
                completionExplicit = false,
            )
            text.contains("已收款") -> TransferBubble(
                direction = CaptureCandidate.DIRECTION_INCOME,
                completionStatus = CaptureCandidate.COMPLETION_COMPLETED,
                completionExplicit = true,
            )
            else -> null
        }
    }

    /**
     * Direction for a chat transfer bubble.
     *
     * A state word that identifies its own side is used when the page carries no
     * readable side, and must agree with one when it does: a contradiction stays
     * UNKNOWN. A word that proves nothing (待接收) leaves the decision to the
     * bubble's side, and without a side stays UNKNOWN — never a guess.
     */
    private fun bubbleDirectionOf(textDirection: String?, side: String?): String {
        if (textDirection == null) return side ?: CaptureCandidate.DIRECTION_UNKNOWN
        if (side == null || side == textDirection) return textDirection
        return CaptureCandidate.DIRECTION_UNKNOWN
    }

    private fun isTransferBubbleText(text: String): Boolean =
        text.contains("转账") && TRANSFER_BUBBLE_STATES.any(text::contains)

    /**
     * Which side of the page a chat bubble is drawn on: the sender's bubble sits
     * on the left, the receiver's on the right. A side is only returned when
     * real boxes and a wide enough page make it unambiguous.
     */
    private fun bubbleSideOf(amount: TextBlock, pageLeft: Int, pageRight: Int): String? {
        if (!hasGeometry(amount)) return null
        val span = pageRight - pageLeft
        if (span < MIN_BUBBLE_SPAN) return null
        val center = (amount.left + amount.right) / 2
        val ratio = (center - pageLeft).toFloat() / span
        return when {
            ratio <= 0.35f -> CaptureCandidate.DIRECTION_EXPENSE
            ratio >= 0.65f -> CaptureCandidate.DIRECTION_INCOME
            else -> null
        }
    }

    /**
     * Status evidence is read for this card only, and a bare cancellation is the
     * weakest signal on a page: apps print 已取消 for unrelated cards, and OCR may
     * return no boxes at all. A cancellation therefore only wins when it is
     * attached to this card's amount and nothing confirmed the transaction —
     * a completed confirmation of the same transaction always beats a stray
     * 已取消.
     */
    private fun completionOf(segment: List<TextBlock>): Pair<String, Boolean> {
        val amount = segment.firstOrNull { amountOf(it.text) != null }
        val statusBlocks = segment
            .filter { block -> isCompletionMarker(block.text) }
            .filterNot { block -> isNonPaymentCallCancellation(block.text) }
            .filter { block -> isBoundStatus(block, amount) }
        val confirmedText = statusBlocks
            .filterNot { block -> isBareCancellation(block.text) }
            .joinToString("\n") { it.text }
        return when {
            FAILED_MARKERS.any(confirmedText::contains) -> CaptureCandidate.COMPLETION_FAILED to true
            PENDING_MARKERS.any(confirmedText::contains) -> CaptureCandidate.COMPLETION_PENDING to true
            CREATED_MARKERS.any(confirmedText::contains) -> CaptureCandidate.COMPLETION_CREATED to true
            // Checked before CANCELLED: "退款已原路退回" means the refund landed.
            REFUND_COMPLETED_MARKERS.any(confirmedText::contains) ->
                CaptureCandidate.COMPLETION_COMPLETED to true
            COMPLETED_MARKERS.any(confirmedText::contains) -> CaptureCandidate.COMPLETION_COMPLETED to true
            statusBlocks.any { block ->
                isBareCancellation(block.text) && isAttachedToAmount(block, amount)
            } -> CaptureCandidate.COMPLETION_CANCELLED to true
            else -> CaptureCandidate.COMPLETION_UNKNOWN to false
        }
    }

    private fun isBoundStatus(block: TextBlock, amount: TextBlock?): Boolean =
        !isDetachedFromAmount(block, amount) &&
            (amount == null || !hasGeometry(block) || !hasGeometry(amount) || isAttachedToAmount(block, amount))

    private fun isCompletionMarker(text: String): Boolean {
        val value = text.trim()
        val markers = COMPLETED_MARKERS + REFUND_COMPLETED_MARKERS + PENDING_MARKERS +
            CREATED_MARKERS + CANCELLED_MARKERS + FAILED_MARKERS
        return markers.any { marker ->
            value == marker || value == "对方$marker" ||
                value == "状态 $marker" || value == "状态：$marker" || value == "状态:$marker" ||
                value == "当前状态 $marker" || value == "当前状态：$marker" || value == "当前状态:$marker" ||
                (amountOf(value) != null && value.endsWith(" $marker"))
        }
    }

    private fun isNonPaymentCallCancellation(text: String): Boolean =
        text.contains("已取消") && listOf("通话", "语音", "视频", "电话", "呼叫").any(text::contains)

    /** A cancellation with no stronger status word in the same line. */
    private fun isBareCancellation(text: String): Boolean {
        if (CANCELLED_MARKERS.none(text::contains)) return false
        val stronger = COMPLETED_MARKERS + REFUND_COMPLETED_MARKERS + PENDING_MARKERS +
            CREATED_MARKERS + FAILED_MARKERS
        return stronger.none(text::contains)
    }

    /**
     * A box with a real vertical span. OCR may return no box at all, and the
     * zeroed placeholder must never be read as "same row" or "same card";
     * width alone is not required because the card split is vertical.
     */
    private fun hasGeometry(block: TextBlock): Boolean =
        block.hasBounds && block.bottom > block.top

    private fun horizontalGap(a: TextBlock, b: TextBlock): Int = when {
        a.right < b.left -> b.left - a.right
        b.right < a.left -> a.left - b.right
        else -> 0
    }

    /** A status line that real geometry places far away from the amount. */
    private fun isDetachedFromAmount(block: TextBlock, amount: TextBlock?): Boolean {
        if (amount == null) return false
        if (!hasGeometry(block) || !hasGeometry(amount)) return false
        return horizontalGap(block, amount) > statusHorizontalGapLimit(block, amount)
    }

    /**
     * Same card, or a real overlapping/nearby box. Without OCR boxes there is
     * no evidence at all: unknown geometry must never be read as "attached",
     * or a bare 已取消 would cancel whichever amount it happens to follow.
     */
    private fun isAttachedToAmount(block: TextBlock, amount: TextBlock?): Boolean {
        if (amount == null) return false
        if (!hasGeometry(block) || !hasGeometry(amount)) return false
        val verticalOverlap = block.bottom >= amount.top && amount.bottom >= block.top
        if (verticalOverlap) return true
        val verticalGap = when {
            block.bottom < amount.top -> amount.top - block.bottom
            amount.bottom < block.top -> block.top - amount.bottom
            else -> 0
        }
        return verticalGap <= statusHorizontalGapLimit(block, amount)
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
        // A chat transfer bubble has no merchant: its rows are the amount, the
        // counterparty's action and the word 转账, and chat text above it must
        // never be read as a shop name.
        if (transferBubbleOf(segment.joinToString("\n") { it.text }) != null) return emptyList()
        // Fall back to the rows above the amount. A bill-list card prints the
        // shop two to four rows above it (icon row, payment method, timestamp),
        // so giving up after one rejected row is exactly what left real shops
        // unread. Walk at most [MERCHANT_LOOKUP_LINES] rows, nearest first, and
        // skip the chrome that can never be a shop name.
        val amountIndex = segment.indexOfFirst { it === amountBlock }
        val above = if (amountIndex > 0) segment.subList(0, amountIndex) else emptyList()
        above.takeLast(MERCHANT_LOOKUP_LINES).asReversed().forEach { block ->
            if (isMerchantInterference(block.text)) return@forEach
            merchantName(block.text, allowDigit = true)?.let { return listOf(it) }
        }
        return emptyList()
    }

    /**
     * Rows around a bill-list card that are chrome, not a shop name: the
     * payment-method sentence, card / balance words, timestamps, dates, pure
     * numbers and the 账单详情 / 分分捐 footer.
     *
     * The shop itself may carry digits, brackets, @ and dots, so this is a list
     * of known non-shop shapes rather than a "must be Chinese" filter.
     */
    private fun isMerchantInterference(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return true
        if (amountOf(text) != null) return true
        if (isTimeOrDateLine(text)) return true
        if (MERCHANT_INTERFERENCE.any(text::contains)) return true
        if (text.all { it.isDigit() || it.isWhitespace() || it in ".,:：" }) return true
        return false
    }

    /** `21:46`, `昨天 14:01`, `周四 22:01`, `11月19日 20:10`, `2026-11-19 20:15`: never a shop. */
    private fun isTimeOrDateLine(line: String): Boolean {
        val stripped = WEEKDAY_REGEX.replace(RELATIVE_DAY_REGEX.replace(line, " "), " ").trim()
        if (TIME_ONLY_REGEX.matchEntire(stripped) != null) return true
        if (DATE_TIME_REGEX.containsMatchIn(line)) return true
        if (MONTH_DAY_TIME_REGEX.containsMatchIn(line)) return true
        return line.contains("月") && line.contains("日")
    }

    /**
     * A row that is nothing but a timestamp: `22:01`, `周四 22:01`,
     * `昨天 14:01`, `11月19日 20:10`, `2026-11-19 20:15`. Anchored to the whole
     * row on purpose: an in-card field such as `发生时间 2026-11-21 17:13`
     * carries a label and stays part of the card it is printed in.
     */
    private fun isStandaloneTimestampLine(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty() || amountOf(text) != null) return false
        val bare = WEEKDAY_REGEX.replace(RELATIVE_DAY_REGEX.replace(text, " "), " ").trim()
        return TIME_ONLY_REGEX.matchEntire(bare) != null ||
            DATE_TIME_REGEX.matchEntire(bare) != null ||
            MONTH_DAY_TIME_REGEX.matchEntire(bare) != null
    }

    /** Calendar weekday constant for a `周一` / `星期一` token, or null. */
    private fun weekdayOf(text: String): Int? =
        WEEKDAY_REGEX.find(text)?.groupValues?.get(1)?.let { day ->
            when (day) {
                "一" -> Calendar.MONDAY
                "二" -> Calendar.TUESDAY
                "三" -> Calendar.WEDNESDAY
                "四" -> Calendar.THURSDAY
                "五" -> Calendar.FRIDAY
                "六" -> Calendar.SATURDAY
                else -> Calendar.SUNDAY
            }
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

        // `11月19日 20:10` is a real date with the year omitted. Resolving it
        // against the capture year alone breaks at New Year: `12月31日` seen on
        // 2027-01-01 looked like a future date, and `1月1日` seen on 2026-12-31
        // landed eleven months early. Consider the neighbouring years and keep
        // the occurrence nearest the capture moment, allowing a day of clock
        // skew ahead. Beyond half a year back the year cannot be pinned, so the
        // date stays unresolved instead of being stamped as actual time.
        MONTH_DAY_TIME_REGEX.find(text)?.let { match ->
            val values = match.groupValues
            val captureYear = Calendar.getInstance(timeZone).apply { timeInMillis = capturedAtMs }
                .get(Calendar.YEAR)
            return ((captureYear - 1)..(captureYear + 1))
                .mapNotNull { year ->
                    runCatching {
                        Calendar.getInstance(timeZone).apply {
                            isLenient = false
                            clear()
                            set(
                                year,
                                values[1].toInt() - 1,
                                values[2].toInt(),
                                values[3].toInt(),
                                values[4].toInt(),
                                values[5].ifBlank { "0" }.toInt(),
                            )
                        }.timeInMillis
                    }.getOrNull()
                }
                .filter { it in (capturedAtMs - MONTH_DAY_MAX_AGE_MS)..(capturedAtMs + MONTH_DAY_FUTURE_TOLERANCE_MS) }
                .minByOrNull { abs(it - capturedAtMs) }
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
        // A chat separator may name the weekday instead of a date: `周四 22:01`
        // read on a Saturday is the Thursday three days back, while `周六`
        // read on a Saturday is today. Resolve the weekday against the capture
        // moment's own local date, so month and year boundaries fall out of the
        // calendar arithmetic instead of a hand-written table. Without exactly
        // one clock there is no evidence, and the capture day's HH:mm must not
        // be invented for it.
        weekdayOf(text)?.let { weekday ->
            val clock = times.singleOrNull() ?: return null
            return runCatching {
                Calendar.getInstance(timeZone).apply {
                    timeInMillis = capturedAtMs
                    isLenient = false
                    val daysBack = (get(Calendar.DAY_OF_WEEK) - weekday + 7) % 7
                    add(Calendar.DAY_OF_YEAR, -daysBack)
                    set(Calendar.HOUR_OF_DAY, clock.groupValues[1].toInt())
                    set(Calendar.MINUTE, clock.groupValues[2].toInt())
                    set(Calendar.SECOND, clock.groupValues[3].ifBlank { "0" }.toInt())
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis.takeIf {
                    it in MIN_EVENT_TIME..(capturedAtMs + FUTURE_TOLERANCE_MS)
                }
            }.getOrNull()
        }
        // Date-shaped tokens without a time-only match are not a clock: falling
        // through here would assert "capture day at HH:mm" for a date we failed
        // to read. Only a number glued to 月/日 (or a numeric 年-月-日 shape)
        // counts — a merchant named 每日优鲜, a note about 月饼 and a negative
        // amount like -¥9.90 must not kill a real 20:10.
        if (times.size != 1 || text.contains("年") || DATE_TOKEN_REGEX.containsMatchIn(text)) {
            return null
        }
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
    /** A yearless date may sit slightly in the future because of clock skew. */
    private const val MONTH_DAY_FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000
    /** Beyond half a year back a yearless month/day can no longer be pinned. */
    private const val MONTH_DAY_MAX_AGE_MS = 183L * 24 * 60 * 60 * 1000
    private const val MIN_EVENT_TIME = 1_262_304_000_000L // 2010-01-01
}
