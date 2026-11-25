package com.bookkeeping.app.notification.accessibility

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** One visible text line with its screen-space bounds. Android-free for JVM tests. */
data class BillTextLine(
    val text: String,
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

enum class PaymentCardSource(val packageName: String) {
    WECHAT("com.tencent.mm"),
    ALIPAY("com.eg.android.AlipayGphone"),
    ;

    companion object {
        fun fromPackage(packageName: String): PaymentCardSource? =
            values().firstOrNull { it.packageName == packageName }
    }
}

enum class PaymentCardKind {
    /** Merchant / scan / wallet / bank-card payment. */
    PAYMENT,
    TRANSFER,
    RED_PACKET,
    WITHDRAWAL,
    REFUND,
    PAY_AFTER_USE,
    /** Ordinary bill card without a more specific kind. */
    BILL,
}

enum class PaymentCardStatus {
    /** Explicitly successful; only this status may be booked automatically. */
    COMPLETED,
    /** Waiting for confirmation or arrival. */
    PENDING,
    /** Pay-after-use order created but not charged. */
    CREATED,
    /** Cancelled, closed, expired, or returned. */
    CANCELLED,
    FAILED,
    UNKNOWN,
}

enum class PaymentDirection { EXPENSE, INCOME, UNKNOWN }

data class PaymentCardDetection(
    val source: PaymentCardSource,
    val kind: PaymentCardKind,
    val status: PaymentCardStatus,
    val direction: PaymentDirection,
    val amountCents: Long,
    val merchantCandidates: List<String>,
    val orderId: String?,
    val eventTimeMs: Long?,
    val confidence: Float,
    val reason: String,
    val nonFinalReason: String?,
    val pageIdentity: String,
    val cardKey: String,
    val completionExplicit: Boolean = false,
    val directionExplicit: Boolean = direction != PaymentDirection.UNKNOWN,
    /** The text contains a plausible amount, but missing geometry prevents safe grouping. */
    val reviewRequired: Boolean = false,
) {
    /** False for created/cancelled/pending/failed/unknown cards; never book those. */
    val isFinal: Boolean get() = status == PaymentCardStatus.COMPLETED
}

/**
 * Parses WeChat Pay and Alipay bill/payment cards from accessibility nodes or
 * OCR lines. It groups visible lines into cards before parsing so one card's
 * amount cannot be paired with another card's merchant or status. Nothing is
 * inserted here and no detection is approved automatically.
 */
object PaymentCardDetector {
    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"

    private data class Classification(
        val kind: PaymentCardKind,
        val status: PaymentCardStatus,
        val direction: PaymentDirection,
        val nonFinalReason: String? = null,
    )

    // --- card grouping -----------------------------------------------------

    private val STANDALONE_TIME = Regex(
        """^(?:(?:今天|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天])\s*)?(?:\d{1,2}[:：]\d{2}(?::\d{2})?|\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?(?:\s+\d{1,2}[:：]\d{2}(?::\d{2})?)?|\d{1,2}月\d{1,2}日(?:\s+\d{1,2}[:：]\d{2})?)$""",
    )
    private val TIME = Regex(
        """(?:今天|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|\d{1,2}[:：]\d{2}|\d{4}[-/年]\d{1,2}[-/月]\d{1,2}|\d{1,2}月\d{1,2}日)""",
    )
    private val TIME_LABELS = listOf(
        "下单时间", "交易时间", "支付时间", "付款时间", "到账时间", "退款时间",
        "提现时间", "转账时间", "领取时间", "发送时间", "创建时间", "订单时间",
        "扣款时间", "发生时间",
    )

    // --- classification markers -------------------------------------------

    private val SENSITIVE_MARKERS = listOf(
        "支付密码", "付款密码", "请输入密码", "验证码", "password", "verification code",
    )
    private val FAILURE_MARKERS = listOf(
        "支付失败", "付款失败", "交易失败", "交易关闭", "支付关闭", "退款失败", "提现失败",
    )
    private val SUMMARY_MARKERS = listOf(
        "本月统计", "本月支出", "本月收入", "本月已支出", "本月消费", "收支明细",
        "日报设置", "年度账单", "账单统计", "月账单", "点击查看昨日收支",
        "记账日报", "已入账", "已支出",
    )
    private val SUMMARY_PATTERN = Regex(
        """(?:本月|上月|今年|年度|累计|总)(?:已)?(?:支出|收入|消费|账单|提现|转账|红包|入账)|\d{1,2}月(?:已)?(?:支出|收入|消费|提现|转账|红包|入账)""",
    )
    private val PAY_AFTER_USE_MARKERS = listOf("先用后付", "已自动支付")
    private val REFUND_MARKERS = listOf(
        "退款到账通知", "退款金额", "退款成功", "退款已到账", "退款到账",
        "已退款", "原路退回", "退款已原路退回",
        "退款申请", "退款处理中", "退款中", "退款审核", "退款",
    )
    private val WITHDRAWAL_MARKERS = listOf("提现", "转出到银行卡", "转出至银行卡")
    private val TRANSFER_MARKERS = listOf("转账", "转给你", "转账给你")
    private val OUTGOING_TRANSFER = listOf(
        "对方已收钱", "已被收钱", "等待对方确认", "待对方确认", "你发起", "你向",
        "你已转账", "转账成功",
    )
    private val INCOMING_TRANSFER = listOf(
        "收到转账", "转账给你", "转给你", "来自", "已收钱", "已收款", "已存入零钱",
        "已存入", "已入账", "待确认收款", "请收钱", "收款成功", "你已收款", "已到账",
    )
    private val TRANSFER_PENDING = listOf(
        "等待对方确认", "待对方确认", "待确认收款", "请收钱", "待领取", "未领取", "未确认",
    )
    private val TRANSFER_SETTLED = listOf(
        "已收钱", "已收款", "已存入", "已入账", "对方已收钱", "已被收钱", "已被接收", "转账成功",
        "交易成功", "已到账", "收款成功",
    )
    private val PAYMENT_MARKERS = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "付款完成", "已支付", "已付款",
        "消费成功", "扣款成功", "使用零钱支付", "使用银行卡支付", "通过零钱扣款",
        "零钱支付", "银行卡支付", "余额支付", "余额宝支付", "花呗支付", "花呗付款",
        "支付处理中", "付款处理中", "支付中", "付款中",
        "支出", "消费",
    )
    private val NEGATIVE_PAYMENT_MARKERS = listOf(
        "你无需支付", "无需支付", "他人已支付", "对方已支付", "由对方支付",
        "好友代付", "他人代付", "找人代付", "请人代付",
    )
    private val GROUP_COLLECTION_MARKERS = listOf("群收款", "活动收款")
    private val STRONG_COMPLETED_PAYMENT = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "付款完成",
        "扣款成功", "已扣款", "消费成功",
    )
    private val PAYMENT_CONTEXT_LABELS = listOf(
        "商户名称", "商户全称", "收款方", "收款人", "付款方", "订单号",
        "交易单号", "商户单号", "支付方式", "付款方式", "交易详情", "账单详情",
        "使用零钱支付", "使用银行卡支付", "通过零钱扣款",
    )
    private val BILL_PAGE_MARKERS = listOf(
        "账单", "交易记录", "支付记录", "收支明细", "交易详情", "账单详情",
    )
    private val PAYMENT_CONTEXT_MARKERS = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "付款完成",
        "已支付", "已付款", "消费成功", "扣款成功",
        "支付处理中", "付款处理中", "支付中", "付款中",
    )
    private val CARD_EVIDENCE = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "付款完成", "已支付", "已付款",
        "消费成功", "扣款成功", "使用零钱支付", "使用银行卡支付", "通过零钱扣款",
        "支付处理中", "付款处理中", "支付中", "付款中",
        "先用后付", "已自动支付", "转账给", "转账给你", "收到转账", "来自",
        "已收钱", "已收款", "已存入", "已入账", "对方已收钱", "已被收钱",
        "待确认收款", "请收钱", "收款成功", "你已收款", "已到账",
        "微信红包", "恭喜发财", "你发出的红包", "发出红包", "红包已发出",
        "提现到银行卡", "零钱提现", "提现金额", "提现成功",
        "退款到账通知", "退款金额", "退款成功", "退款已到账",
    )
    private val PAYMENT_METHOD_LINES = listOf(
        "使用零钱支付", "使用银行卡支付", "通过零钱扣款", "零钱支付", "银行卡支付",
        "余额支付", "余额宝支付", "花呗支付", "花呗付款",
        "支付方式", "付款方式", "退款方式",
    )
    private val SPARSE_PAYMENT_ANCHORS = listOf(
        "管理扣费服务", "查看商家订单", "交易详情", "账单详情",
        "先用后付", "支付成功", "付款成功", "退款到账", "Transactions",
        "Payment Services", "钱包", "收付款", "账单",
    )

    // --- merchant filtering ------------------------------------------------

    private val CHROME = listOf(
        "微信支付", "支付宝", "我的账单", "账单详情", "交易详情", "支付服务", "账单",
        "查看退款详情", "查看商家订单", "管理扣费服务", "联系商家", "复制", "完成",
        "返回", "关闭", "查看更多", "详情", "本月统计", "查看明细", "日报设置",
        "微信支付分", "物流及商品详情", "使用零钱支付", "通过零钱扣款", "支付方式",
        "付款方式", "退款方式", "退款金额", "到账时间", "商品详情", "商户名称",
        "商户全称", "下单时间", "交易时间", "支付时间", "付款时间", "订单号",
        "交易单号", "商户单号", "订单编号", "交易号", "商户订单号", "退款到账通知",
        "退款成功", "退款已到账", "原路退回", "提现到银行卡", "零钱提现", "提现金额",
        "提现成功", "微信红包", "发出红包", "你发出的红包", "恭喜发财", "已存入零钱",
        "退回零钱", "零钱", "银行卡", "储蓄卡", "信用卡", "花呗", "余额宝", "余额",
        "支付成功", "付款成功", "交易成功", "支付完成", "已支付", "已付款",
        "消费成功", "扣款成功", "先用后付", "免密支付", "自动扣款", "规则", "说明",
        "帮助", "客服", "wechat pay", "alipay", "transaction details",
        "payment details", "更多信息", "查找交易", "收支统计", "全部账单",
        "搜索", "设置", "通讯录", "发现", "我", "微信", "登录",
        "记账日报", "Transactions", "Payment Services", "Coupon",
        "切换到发消息", "查看明细", "日报设置", "留言复购", "咨询", "客服",
    )
    private val KIND_MARKERS = listOf(
        "支付成功", "付款成功", "交易成功", "支付完成", "付款完成", "已支付", "已付款",
        "消费成功", "扣款成功", "使用零钱支付", "使用银行卡支付", "通过零钱扣款",
        "先用后付", "已自动支付", "自动支付", "退款", "转账", "红包", "提现",
        "支出", "消费", "收入", "入账", "收款", "付款", "领取",
    )
    private val STATUS_WORDS = listOf(
        "成功", "完成", "已取消", "已关闭", "已生成", "待支付", "待扣款", "待确认",
        "待领取", "已到账", "已领取", "未领取", "已存入", "已退回", "已过期",
        "已失效", "处理中", "查看", "管理", "详情", "记录", "说明", "规则", "帮助",
        "客服", "复制", "已收钱", "已收款", "对方已收钱", "已被收钱",
        "等待对方确认", "待对方确认", "待确认收款", "请收钱", "收款成功",
    )
    private val MERCHANT_LABELS = listOf(
        "商户名称", "商户全称", "收款方", "收款人", "付款方", "对方",
    )
    private val FIELD_LABELS = listOf(
        "商品详情", "商户名称", "商户全称", "收款方", "收款人", "付款方", "对方",
        "退款方式", "到账时间", "下单时间", "交易时间", "支付时间", "付款时间",
        "提现时间", "转账时间", "领取时间", "发送时间", "创建时间", "订单时间",
        "扣款时间", "支付方式", "付款方式", "订单号", "交易单号", "商户单号",
        "订单编号", "交易号", "商户订单号", "流水号", "凭证号", "退款金额",
        "提现金额", "交易金额", "订单金额", "支付金额", "付款金额", "扣款金额",
        "转账金额", "收款金额", "红包金额", "实付", "实付金额",
    )
    private val ONLY_PUNCT_OR_NUMBERS = Regex("""[\d\s:：./¥￥,，+\-]+""")
    private val ORDER_ID_LIKE = Regex("""[A-Za-z0-9_-]{10,}""")
    private val MONTH_HEADER = Regex("""\d{4}\s*年\s*\d{1,2}\s*月|\d{1,2}\s*月""")

    // --- amounts -----------------------------------------------------------

    private val SIGNED_AMOUNT = Regex(
        """^[¥￥]?\s*([+-])?\s*[¥￥]?\s*([0-9]{1,12}(?:\.[0-9]{1,2})?)\s*$""",
    )
    private val STANDALONE_AMOUNT = Regex(
        """^[¥￥]?\s*[+-]?\s*[¥￥]?\s*[0-9]{1,12}(?:\.[0-9]{1,2})?$""",
    )
    private val NO_LOOKAHEAD_LABELS = setOf(
        "支付成功", "付款成功", "已支付", "已付款", "交易成功", "消费成功", "扣款成功",
        "已自动支付", "先用后付",
    )

    private val ORDER = Regex(
        """(?:商户订单号|订单编号|订单号|交易单号|商户单号|交易号|流水号|凭证号|Order(?:\s*(?:ID|No\.?))?|Transaction(?:\s*(?:ID|No\.?))?)\s*[:：]?\s*([A-Za-z0-9_-]{6,})""",
        RegexOption.IGNORE_CASE,
    )

    // --- public API --------------------------------------------------------

    fun isSupported(packageName: String): Boolean =
        PaymentCardSource.fromPackage(packageName) != null

    fun pageIdentity(packageName: String, text: String): String =
        "$packageName|${Integer.toHexString(text.hashCode())}"

    /**
     * Whether a page is worth OCR when accessibility nodes expose no cards.
     * Requires both payment-page context and card evidence, plus an amount, so
     * ordinary chat or home pages do not trigger a screenshot.
     */
    fun looksLikePaymentPage(packageName: String, text: String): Boolean {
        if (!isSupported(packageName)) return false
        if (isBillListPage(text)) return false
        // Some WeChat Pay history/detail pages render the amount and status in a
        // WebView/canvas and expose only sparse chrome nodes. These anchors are
        // specific enough to justify one local screenshot + OCR attempt.
        if (SPARSE_PAYMENT_ANCHORS.any { text.contains(it) }) return true
        val hasPageContext = BILL_PAGE_MARKERS.any { text.contains(it) } ||
            PAYMENT_CONTEXT_MARKERS.any { text.contains(it) } ||
            text.contains("微信支付") || text.contains("支付宝") ||
            REFUND_MARKERS.any { text.contains(it) } ||
            WITHDRAWAL_MARKERS.any { text.contains(it) } ||
            TRANSFER_MARKERS.any { text.contains(it) } ||
            PAY_AFTER_USE_MARKERS.any { text.contains(it) } ||
            text.contains("红包")
        val hasCardEvidence = CARD_EVIDENCE.any { text.contains(it) }
        return hasPageContext && hasCardEvidence && parseAmount(text) != null
    }

    fun fromSnapshot(
        packageName: String,
        snapshot: ScreenSnapshot,
        capturedAtMs: Long,
    ): List<PaymentCardDetection> {
        if (!isSupported(packageName)) return emptyList()
        val lines = snapshot.root.walk().mapNotNull { node ->
            if (node.password || node.editable) return@mapNotNull null
            val text = node.text.trim().ifEmpty { node.contentDesc.trim() }
            if (text.isEmpty()) return@mapNotNull null
            // Keep text whose geometry is absent or invalid. fromLines will use
            // reading order for grouping and require review before this card can
            // enter the normal single-candidate capture path.
            val bounds = node.bounds
            BillTextLine(
                text = text,
                top = bounds?.top ?: 0,
                bottom = bounds?.bottom ?: 0,
                left = bounds?.left ?: 0,
                right = bounds?.right ?: 0,
            )
        }.distinctBy { "${it.text}|${it.top}|${it.left}" }
        if (lines.isEmpty()) return emptyList()
        val pageText = lines.joinToString(" ") { it.text }
        if (isBillListPage(pageText)) return emptyList()
        val screenHeight = lines.maxOf { it.bottom }.coerceAtLeast(1)
        return fromLines(
            packageName = packageName,
            lines = lines,
            capturedAtMs = capturedAtMs,
            screenHeightPx = screenHeight,
            pageIdentity = pageIdentity(packageName, snapshot.flattenText()),
        )
    }

    /** Best-effort OCR text input: each line becomes a synthetic bounds-less row. */
    fun fromText(
        packageName: String,
        text: String,
        capturedAtMs: Long,
        pageIdentityOverride: String? = null,
    ): List<PaymentCardDetection> {
        if (!isSupported(packageName)) return emptyList()
        val lines = text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, line ->
                BillTextLine(line, index * 100, index * 100 + 40)
            }
        if (lines.isEmpty()) return emptyList()
        return fromLines(
            packageName = packageName,
            lines = lines,
            capturedAtMs = capturedAtMs,
            screenHeightPx = lines.last().bottom.coerceAtLeast(1),
            pageIdentity = pageIdentityOverride ?: pageIdentity(packageName, text),
        )
    }

    fun fromLines(
        packageName: String,
        lines: List<BillTextLine>,
        capturedAtMs: Long,
        screenHeightPx: Int,
        pageIdentity: String,
    ): List<PaymentCardDetection> {
        val source = PaymentCardSource.fromPackage(packageName) ?: return emptyList()
        val rawVisible = lines.filter { it.text.isNotBlank() }
        if (rawVisible.isEmpty()) return emptyList()
        // Clamp partially off-screen nodes (e.g. top < 0 when scrolled) instead of
        // letting one clipped node invalidate the geometry of the entire screen.
        val visible = rawVisible.map { line ->
            if (line.right > line.left && line.bottom > line.top) {
                val clampedTop = line.top.coerceAtLeast(0)
                val clampedLeft = line.left.coerceAtLeast(0)
                val clampedBottom = if (screenHeightPx > 0) line.bottom.coerceAtMost(screenHeightPx) else line.bottom
                if (clampedBottom > clampedTop) {
                    line.copy(top = clampedTop, left = clampedLeft, bottom = clampedBottom)
                } else {
                    line
                }
            } else {
                line
            }
        }
        val pageText = visible.joinToString(" ") { it.text }
        val amountLines = visible.filter { parseAmount(it.text) != null }
        val usableCount = visible.count { it.hasUsableGeometry(screenHeightPx) }
        val geometriesUsable = visible.isNotEmpty() &&
            (amountLines.isEmpty() || amountLines.all { it.hasUsableGeometry(screenHeightPx) }) &&
            usableCount * 10 >= visible.size * 7
        val groups = if (geometriesUsable) {
            groupCards(visible.filter { it.hasUsableGeometry(screenHeightPx) }.ifEmpty { visible }, screenHeightPx)
        } else {
            groupCardsByText(visible)
        }
        val hasPaymentPageContext = KIND_MARKERS.any { pageText.contains(it) } ||
            BILL_PAGE_MARKERS.any { pageText.contains(it) } ||
            PAYMENT_CONTEXT_MARKERS.any { pageText.contains(it) }
        val raw = groups.flatMap { group ->
            val plausibleAmounts = group.filterNot { isNoisyAmountLine(it.text, PaymentCardKind.PAYMENT) }
                .mapNotNull { parseAmount(it.text)?.takeIf { amount -> amount > 0L } }
            if (!geometriesUsable && plausibleAmounts.size > 1) {
                if (hasPaymentPageContext) {
                    ambiguousAmountCandidates(
                        source = source,
                        amounts = plausibleAmounts,
                        capturedAtMs = capturedAtMs,
                        pageIdentity = pageIdentity,
                    )
                } else {
                    emptyList()
                }
            } else {
                val parsed = parseCard(source, group, pageText, capturedAtMs, pageIdentity)
                listOfNotNull(
                    if (!geometriesUsable) parsed?.copy(reviewRequired = true) else parsed,
                )
            }
        }
        return consolidateTransferBubblePairs(raw, pageText)
    }

    /**
     * In WeChat chat screens, a single accepted transfer renders two bubbles
     * with the same amount: the original transfer bubble ("已被接收"/"已收钱")
     * and the receipt bubble ("已收款"). Without consolidation, the first is
     * classified as EXPENSE and the second as INCOME, booking both -X and +X.
     */
    private fun consolidateTransferBubblePairs(
        detections: List<PaymentCardDetection>,
        pageText: String,
    ): List<PaymentCardDetection> {
        val incomeTransferAmounts = detections
            .filter {
                it.kind == PaymentCardKind.TRANSFER &&
                    it.direction == PaymentDirection.INCOME &&
                    it.amountCents > 0L
            }
            .map { it.amountCents }
            .toSet()
        if (incomeTransferAmounts.isEmpty()) return detections
        val hasExplicitOutgoing = containsAny(pageText, "你发起", "你向", "你已转账", "对方已收钱")
        if (hasExplicitOutgoing) return detections
        return detections.filterNot { card ->
            card.kind == PaymentCardKind.TRANSFER &&
                card.direction != PaymentDirection.INCOME &&
                card.amountCents in incomeTransferAmounts
        }
    }

    // --- grouping ----------------------------------------------------------

    private fun groupCards(
        lines: List<BillTextLine>,
        screenHeightPx: Int,
    ): List<List<BillTextLine>> {
        val sorted = lines.sortedWith(compareBy<BillTextLine> { it.top }.thenBy { it.left })
        val gapThreshold = (screenHeightPx / 12).coerceAtLeast(120)
        val groups = mutableListOf<MutableList<BillTextLine>>()
        var current = mutableListOf<BillTextLine>()
        for (line in sorted) {
            val previous = current.lastOrNull()
            val gap = if (previous != null) line.top - previous.bottom else 0
            val splitByGap = current.isNotEmpty() && gap > gapThreshold
            val splitByHeader = current.isNotEmpty() && startsNewCard(line, current)
            if (splitByGap || splitByHeader) {
                groups += current
                current = mutableListOf()
            }
            current += line
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    /** Text order is the only usable signal when OCR rectangles are absent or invalid. */
    private fun groupCardsByText(lines: List<BillTextLine>): List<List<BillTextLine>> {
        val groups = mutableListOf<MutableList<BillTextLine>>()
        var current = mutableListOf<BillTextLine>()
        for (line in lines) {
            if (current.isNotEmpty() && startsNewCard(line, current)) {
                groups += current
                current = mutableListOf()
            }
            current += line
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private fun BillTextLine.hasUsableGeometry(screenHeightPx: Int): Boolean =
        left >= 0 && top >= 0 && right > left && bottom > top &&
            screenHeightPx > 0 && bottom <= screenHeightPx

    private fun startsNewCard(line: BillTextLine, current: List<BillTextLine>): Boolean {
        val text = line.text.trim()
        val hasAmount = current.any { parseAmount(it.text) != null }
        if (!hasAmount) return false
        if (STANDALONE_TIME.matches(text)) {
            // A time line can sit between the merchant and amount of the same card.
            // Only split if the current card already has its own event time.
            val previous = current.lastOrNull()?.text.orEmpty()
            return TIME_LABELS.none { previous.contains(it) } &&
                current.any { STANDALONE_TIME.matches(it.text.trim()) }
        }

        // Adjacent rows can have no useful vertical gap (or no OCR geometry at
        // all). Once one card has both an amount and a terminal status, a
        // contradictory status marker starts a new card. Status is then
        // classified only from that card's own lines.
        val previousHasTerminalStatus = current.any {
            isCancelledStatus(it.text) || isSuccessfulStatus(it.text)
        }
        val nextHasTerminalStatus = isCancelledStatus(text) || isSuccessfulStatus(text)
        return previousHasTerminalStatus && nextHasTerminalStatus
    }

    private fun isCancelledStatus(text: String): Boolean = containsAny(
        text, "已取消", "订单已取消", "交易关闭", "支付关闭", "订单已关闭", "已关闭", "已失效", "已退回",
    )

    private fun isSuccessfulStatus(text: String): Boolean =
        STRONG_COMPLETED_PAYMENT.any(text::contains) || TRANSFER_SETTLED.any(text::contains) ||
            containsAny(text, "已到账", "到账成功", "提现成功", "退款成功", "已领取", "已存入零钱")

    private fun ambiguousAmountCandidates(
        source: PaymentCardSource,
        amounts: List<Long>,
        capturedAtMs: Long,
        pageIdentity: String,
    ): List<PaymentCardDetection> {
        return amounts.mapIndexed { index, amount ->
            val cardKey = listOf(pageIdentity, "AMBIGUOUS", index, amount).joinToString("|")
            PaymentCardDetection(
                source = source,
                kind = PaymentCardKind.PAYMENT,
                status = PaymentCardStatus.UNKNOWN,
                direction = PaymentDirection.UNKNOWN,
                amountCents = amount,
                merchantCandidates = emptyList(),
                orderId = null,
                eventTimeMs = null,
                confidence = 0.2f,
                reason = "invalid OCR bounds; transaction grouping needs review",
                nonFinalReason = "交易分组不明确，请核对候选金额",
                pageIdentity = pageIdentity,
                cardKey = cardKey,
                completionExplicit = false,
                directionExplicit = false,
                reviewRequired = true,
            )
        }
    }

    private fun hasCardContent(text: String): Boolean =
        parseAmount(text) != null || KIND_MARKERS.any { text.contains(it) }

    // --- card parsing ------------------------------------------------------

    private fun parseCard(
        source: PaymentCardSource,
        lines: List<BillTextLine>,
        pageText: String,
        capturedAtMs: Long,
        pageIdentity: String,
    ): PaymentCardDetection? {
        val joined = lines.joinToString(" ") { it.text }
        if (SENSITIVE_MARKERS.any { joined.contains(it, ignoreCase = true) }) return null
        if (NEGATIVE_PAYMENT_MARKERS.any { joined.contains(it, ignoreCase = true) }) return null
        // A group collection page lists what everyone paid. Only a strong
        // completed payment on the same page proves the user paid it.
        if (GROUP_COLLECTION_MARKERS.any { joined.contains(it) } &&
            !STRONG_COMPLETED_PAYMENT.any { joined.contains(it) }
        ) {
            return null
        }
        if (isSummaryGroup(joined)) return null

        val classification = classify(lines, joined, pageText) ?: return null
        val amount = primaryAmount(lines, classification.kind) ?: return null
        if (amount <= 0L) return null

        val merchants = merchantCandidates(lines, classification.kind)
        if (classification.kind == PaymentCardKind.BILL && merchants.isEmpty()) return null

        val orderId = orderId(joined)
        val eventTime = eventTime(lines, classification.kind, capturedAtMs)
        val confidence = confidence(classification, merchants, orderId, eventTime)
        val cardKey = listOf(
            pageIdentity,
            classification.kind.name,
            classification.status.name,
            amount.toString(),
            merchants.firstOrNull().orEmpty(),
            orderId.orEmpty(),
            (eventTime ?: capturedAtMs).toString(),
        ).joinToString("|")

        return PaymentCardDetection(
            source = source,
            kind = classification.kind,
            status = classification.status,
            direction = classification.direction,
            amountCents = amount,
            merchantCandidates = merchants,
            orderId = orderId,
            eventTimeMs = eventTime,
            confidence = confidence,
            reason = classification.nonFinalReason ?: "ok",
            nonFinalReason = classification.nonFinalReason,
            pageIdentity = pageIdentity,
            cardKey = cardKey,
            completionExplicit = completionExplicit(classification, joined),
            directionExplicit = classification.direction != PaymentDirection.UNKNOWN,
        )
    }

    private fun completionExplicit(classification: Classification, joined: String): Boolean {
        if (classification.status != PaymentCardStatus.COMPLETED) return false
        return when (classification.kind) {
            PaymentCardKind.PAYMENT -> STRONG_COMPLETED_PAYMENT.any(joined::contains)
            PaymentCardKind.TRANSFER -> TRANSFER_SETTLED.any(joined::contains)
            PaymentCardKind.RED_PACKET -> containsAny(
                joined, "对方已领取", "红包已领取", "已领取红包", "领取了红包", "已到账", "已存入零钱",
            )
            PaymentCardKind.WITHDRAWAL -> containsAny(
                joined, "提现成功", "已到账", "到账成功", "已提现",
            )
            PaymentCardKind.REFUND -> containsAny(
                joined, "退款成功", "退款已到账", "退款到账", "已退款", "原路退回", "退款已原路退回",
            )
            PaymentCardKind.PAY_AFTER_USE -> containsAny(
                joined, "已自动支付", "自动支付成功", "已扣款", "扣款成功", "支付成功", "付款成功", "已支付",
            )
            PaymentCardKind.BILL -> false
        }
    }

    fun isBillListPage(text: String): Boolean {
        val isDetailOrVoucherPage = containsAny(
            text,
            "账单详情", "交易详情", "订单详情", "交易单号", "商户单号", "商户订单号",
            "微信支付凭证", "扣费凭证", "支付成功", "付款成功", "退款到账通知", "切换到发消息",
        )
        if (isDetailOrVoucherPage) return false
        return containsAny(
            text,
            "全部账单", "收支明细", "账单统计", "查找交易", "本月统计", "记账日报", "月账单", "收支统计",
        ) || (text.contains("账单") && containsAny(text, "本月支出", "本月收入", "本月已支出", "点击查看昨日收支", "日报设置"))
    }

    private fun isSummaryGroup(joined: String): Boolean {
        val hasCardEvidence = CARD_EVIDENCE.any { joined.contains(it) }
        if (hasCardEvidence) return false
        if (SUMMARY_MARKERS.any { joined.contains(it) }) return true
        if (SUMMARY_PATTERN.containsMatchIn(joined)) return true
        return joined.contains("已支出")
    }

    private fun classify(
        lines: List<BillTextLine>,
        joined: String,
        pageText: String,
    ): Classification? {
        // A failed payment/refund never becomes a bookable card.
        if (FAILURE_MARKERS.any { joined.contains(it, ignoreCase = true) } &&
            !isCancelledStatus(joined)
        ) return null
        if (REFUND_MARKERS.any { joined.contains(it) }) return classifyRefund(joined)
        if (isPayAfterUse(joined)) return classifyPayAfterUse(joined)
        if (joined.contains("红包")) return classifyRedPacket(joined)
        if (WITHDRAWAL_MARKERS.any { joined.contains(it) }) return classifyWithdrawal(joined)
        if (TRANSFER_MARKERS.any { joined.contains(it) }) return classifyTransfer(joined)
        if (PAYMENT_MARKERS.any { joined.contains(it) } || isCancelledStatus(joined)) {
            return classifyPayment(lines, joined)
        }
        return classifyBill(lines, joined, pageText)
    }

    private fun classifyFailed(
        lines: List<BillTextLine>,
        joined: String,
        pageText: String,
    ): Classification? {
        val kind = when {
            REFUND_MARKERS.any(joined::contains) -> PaymentCardKind.REFUND
            joined.contains("红包") -> PaymentCardKind.RED_PACKET
            WITHDRAWAL_MARKERS.any(joined::contains) -> PaymentCardKind.WITHDRAWAL
            TRANSFER_MARKERS.any(joined::contains) -> PaymentCardKind.TRANSFER
            PAYMENT_MARKERS.any(joined::contains) || joined.contains("付款") || joined.contains("支付") ->
                PaymentCardKind.PAYMENT
            else -> return classifyBill(lines, joined, pageText)
        }
        val direction = when {
            kind == PaymentCardKind.REFUND -> PaymentDirection.INCOME
            kind == PaymentCardKind.WITHDRAWAL || kind == PaymentCardKind.PAYMENT ->
                PaymentDirection.EXPENSE
            kind == PaymentCardKind.TRANSFER -> classifyTransfer(joined).direction
            kind == PaymentCardKind.RED_PACKET && containsAny(joined, "发出红包", "发送红包", "你发出的红包") ->
                PaymentDirection.EXPENSE
            kind == PaymentCardKind.RED_PACKET && containsAny(joined, "收到红包", "领取红包") ->
                PaymentDirection.INCOME
            else -> PaymentDirection.UNKNOWN
        }
        return Classification(kind, PaymentCardStatus.FAILED, direction, "交易失败")
    }

    private fun isPayAfterUse(joined: String): Boolean =
        PAY_AFTER_USE_MARKERS.any { joined.contains(it) } ||
            (joined.contains("自动支付") && joined.contains("扣款"))

    private fun classifyRefund(joined: String): Classification {
        val pending = containsAny(
            joined,
            "退款处理中", "退款中", "预计", "退款申请", "申请已提交",
            "等待商家", "待商家", "商家处理", "审核中", "处理中",
        )
        val failed = containsAny(joined, "退款失败")
        val completed = containsAny(
            joined, "退款成功", "退款已到账", "退款到账", "已退款", "原路退回", "退款已原路退回",
        )
        val status = when {
            failed -> PaymentCardStatus.FAILED
            pending -> PaymentCardStatus.PENDING
            completed -> PaymentCardStatus.COMPLETED
            else -> PaymentCardStatus.UNKNOWN
        }
        return Classification(
            kind = PaymentCardKind.REFUND,
            status = status,
            direction = PaymentDirection.INCOME,
            nonFinalReason = when (status) {
                PaymentCardStatus.FAILED -> "退款失败"
                PaymentCardStatus.PENDING -> "退款处理中，尚未到账"
                PaymentCardStatus.UNKNOWN -> "退款状态未明确"
                else -> null
            },
        )
    }

    private fun classifyPayAfterUse(joined: String): Classification {
        val status = when {
            containsAny(joined, "已取消", "订单已取消", "已关闭", "已失效") ->
                PaymentCardStatus.CANCELLED
            containsAny(
                joined, "已完成", "已自动支付", "自动支付成功", "已扣款", "扣款成功",
                "支付成功", "付款成功", "已支付",
            ) -> PaymentCardStatus.COMPLETED
            containsAny(
                joined, "已生成", "订单已生成", "待支付", "待扣款", "尚未支付", "待付款", "待确认",
            ) -> PaymentCardStatus.CREATED
            else -> PaymentCardStatus.UNKNOWN
        }
        val nonFinalReason = when (status) {
            PaymentCardStatus.CANCELLED -> "先用后付订单已取消，未扣款"
            PaymentCardStatus.CREATED -> "先用后付订单已生成，尚未扣款"
            PaymentCardStatus.UNKNOWN -> "先用后付状态未识别，未确认扣款"
            else -> null
        }
        return Classification(
            kind = PaymentCardKind.PAY_AFTER_USE,
            status = status,
            direction = PaymentDirection.EXPENSE,
            nonFinalReason = nonFinalReason,
        )
    }

    private fun classifyRedPacket(joined: String): Classification {
        val sent = containsAny(
            joined, "发出红包", "发送红包", "你发出的红包", "红包已发出", "塞钱进红包",
            "已发出", "发红包", "发给",
        )
        val received = containsAny(
            joined, "收到红包", "恭喜发财", "拆到了", "拆开", "领取了红包", "领到红包",
            "已领取红包", "已存入零钱", "已存入", "已领取", "已到账",
        )
        val expired = containsAny(joined, "已过期", "已失效", "已退回", "已退还", "已退款")
        val pending = containsAny(joined, "待领取", "未领取", "待拆开", "请领取", "尚未领取")
        val claimed = containsAny(
            joined, "对方已领取", "红包已领取", "已领取红包", "领取了红包", "已领取", "已到账", "已存入零钱",
        ) && !pending
        if (sent) {
            val status = when {
                expired -> PaymentCardStatus.CANCELLED
                claimed -> PaymentCardStatus.COMPLETED
                else -> PaymentCardStatus.PENDING
            }
            return Classification(
                kind = PaymentCardKind.RED_PACKET,
                status = status,
                direction = PaymentDirection.EXPENSE,
                nonFinalReason = when (status) {
                    PaymentCardStatus.CANCELLED -> "红包已过期或退回，金额未支出"
                    PaymentCardStatus.PENDING -> "红包尚未领取，等待状态更新"
                    else -> "红包领取状态未明确"
                },
            )
        }
        if (received) {
            val status = when {
                expired -> PaymentCardStatus.CANCELLED
                claimed -> PaymentCardStatus.COMPLETED
                else -> PaymentCardStatus.PENDING
            }
            return Classification(
                kind = PaymentCardKind.RED_PACKET,
                status = status,
                direction = PaymentDirection.INCOME,
                nonFinalReason = when (status) {
                    PaymentCardStatus.CANCELLED -> "红包已过期或退回，金额未到账"
                    PaymentCardStatus.PENDING -> "红包待领取，尚未到账"
                    PaymentCardStatus.UNKNOWN -> "红包领取状态未明确"
                    else -> null
                },
            )
        }
        val fallbackDirection = when {
            joined.contains("收入") || joined.contains("入账") -> PaymentDirection.INCOME
            joined.contains("支出") -> PaymentDirection.EXPENSE
            else -> PaymentDirection.UNKNOWN
        }
        return Classification(
            kind = PaymentCardKind.RED_PACKET,
            status = PaymentCardStatus.UNKNOWN,
            direction = fallbackDirection,
            nonFinalReason = if (fallbackDirection == PaymentDirection.UNKNOWN) {
                "红包方向未识别，未入账"
            } else {
                "红包状态未识别，未入账"
            },
        )
    }

    private fun classifyWithdrawal(joined: String): Classification {
        val pending = containsAny(joined, "处理中", "受理中", "预计", "待到账")
        val failed = containsAny(joined, "提现失败")
        val completed = containsAny(joined, "提现成功", "已到账", "到账成功", "已提现")
        val status = when {
            failed -> PaymentCardStatus.FAILED
            pending -> PaymentCardStatus.PENDING
            completed -> PaymentCardStatus.COMPLETED
            else -> PaymentCardStatus.UNKNOWN
        }
        return Classification(
            kind = PaymentCardKind.WITHDRAWAL,
            status = status,
            direction = PaymentDirection.EXPENSE,
            nonFinalReason = when (status) {
                PaymentCardStatus.FAILED -> "提现失败"
                PaymentCardStatus.PENDING -> "提现处理中，尚未到账"
                PaymentCardStatus.UNKNOWN -> "提现状态未明确"
                else -> null
            },
        )
    }

    private fun classifyTransfer(joined: String): Classification {
        val strongIncoming = containsAny(
            joined,
            "收到转账", "转账给你", "转给你", "已收款", "你已收款", "已存入零钱", "收款成功",
        )
        val direction = when {
            strongIncoming -> PaymentDirection.INCOME
            OUTGOING_TRANSFER.any { joined.contains(it) } ||
                joined.contains("已被接收") ||
                Regex("""转账给(?!你)""").containsMatchIn(joined) -> PaymentDirection.EXPENSE
            INCOMING_TRANSFER.any { joined.contains(it) } -> PaymentDirection.INCOME
            joined.contains("收入") || joined.contains("入账") -> PaymentDirection.INCOME
            joined.contains("支出") -> PaymentDirection.EXPENSE
            else -> PaymentDirection.UNKNOWN
        }
        if (direction == PaymentDirection.UNKNOWN) {
            return Classification(
                kind = PaymentCardKind.TRANSFER,
                status = PaymentCardStatus.UNKNOWN,
                direction = direction,
                nonFinalReason = "转账方向未识别，未入账",
            )
        }
        val status = when {
            joined.contains("已退回") -> PaymentCardStatus.CANCELLED
            TRANSFER_PENDING.any { joined.contains(it) } -> PaymentCardStatus.PENDING
            TRANSFER_SETTLED.any { joined.contains(it) } -> PaymentCardStatus.COMPLETED
            else -> PaymentCardStatus.UNKNOWN
        }
        val nonFinalReason = when (status) {
            PaymentCardStatus.CANCELLED -> "转账已退回，未入账"
            PaymentCardStatus.PENDING -> "转账待对方确认，尚未入账"
            PaymentCardStatus.UNKNOWN -> "转账状态未识别，未入账"
            else -> null
        }
        return Classification(
            kind = PaymentCardKind.TRANSFER,
            status = status,
            direction = direction,
            nonFinalReason = nonFinalReason,
        )
    }

    private fun classifyPayment(
        lines: List<BillTextLine>,
        joined: String,
    ): Classification? {
        val weakPaid = containsAny(joined, "已支付", "已付款", "支出", "消费")
        val strongPaid = STRONG_COMPLETED_PAYMENT.any { joined.contains(it) }
        val hasContext = PAYMENT_CONTEXT_LABELS.any { joined.contains(it) } ||
            labeledMerchant(lines) != null
        // "已支付¥2.00" and "消费¥2.00" are common chat/service-message
        // previews. Without a merchant, order or payment-method field they are
        // not proof that the user paid this amount on this page.
        if (weakPaid && !strongPaid && !hasContext) return null
        val status = when {
            containsAny(
                joined, "已取消", "订单已取消", "交易关闭", "支付关闭", "订单已关闭", "已关闭", "已失效",
            ) ->
                PaymentCardStatus.CANCELLED
            containsAny(
                joined, "待支付", "等待付款", "未支付", "未付款",
                "支付中", "付款中", "支付处理中", "付款处理中", "处理中",
            ) -> PaymentCardStatus.PENDING
            strongPaid -> PaymentCardStatus.COMPLETED
            // A bill-detail card with a merchant/order/payment-method field is
            // the completed-payment layout: those pages do not repeat
            // "支付成功" on every row.
            hasContext -> PaymentCardStatus.COMPLETED
            else -> PaymentCardStatus.UNKNOWN
        }
        val direction = when {
            containsAny(joined, "收入", "入账", "到账", "成功收款", "收款成功", "已收款", "收到") &&
                !containsAny(joined, "支出", "付款", "支付", "消费", "扣款") ->
                PaymentDirection.INCOME
            else -> PaymentDirection.EXPENSE
        }
        val nonFinalReason = when (status) {
            PaymentCardStatus.CANCELLED -> "支付订单已取消，未扣款"
            PaymentCardStatus.PENDING -> "支付尚未完成"
            else -> null
        }
        return Classification(
            kind = PaymentCardKind.PAYMENT,
            status = status,
            direction = direction,
            nonFinalReason = nonFinalReason,
        )
    }

    private fun classifyBill(
        lines: List<BillTextLine>,
        joined: String,
        pageText: String,
    ): Classification? {
        if (BILL_PAGE_MARKERS.none { pageText.contains(it) }) return null
        val evidence = orderId(joined) != null ||
            labeledMerchant(lines) != null ||
            PAYMENT_METHOD_LINES.any { joined.contains(it) } ||
            lines.any { STANDALONE_TIME.matches(it.text.trim()) }
        if (!evidence) return null
        val direction = when {
            joined.contains("收入") || joined.contains("入账") ||
                joined.contains("收款") -> PaymentDirection.INCOME
            joined.contains("支出") || joined.contains("消费") ->
                PaymentDirection.EXPENSE
            lines.any { it.text.trim().startsWith("-") } -> PaymentDirection.EXPENSE
            lines.any { it.text.trim().startsWith("+") } -> PaymentDirection.INCOME
            else -> PaymentDirection.UNKNOWN
        }
        return Classification(
            kind = PaymentCardKind.BILL,
            status = if (direction == PaymentDirection.UNKNOWN) {
                PaymentCardStatus.UNKNOWN
            } else {
                PaymentCardStatus.COMPLETED
            },
            direction = direction,
            nonFinalReason = if (direction == PaymentDirection.UNKNOWN) {
                "账单方向未识别，未入账"
            } else {
                null
            },
        )
    }

    // --- amount selection --------------------------------------------------

    private fun primaryAmount(lines: List<BillTextLine>, kind: PaymentCardKind): Long? {
        labeledAmount(lines, amountLabels(kind), kind)?.let { return it }
        val candidates = lines.mapIndexedNotNull { index, line ->
            parseAmount(line.text)?.let { index to it }
        }.filterNot { (index, _) -> isNoisyAmountLine(lines[index].text, kind) }
        if (candidates.isEmpty()) return null
        val distinct = candidates.map { it.second }.distinct()
        if (distinct.size == 1) return distinct.single()
        val standalone = candidates
            .filter { (index, _) -> STANDALONE_AMOUNT.matches(lines[index].text.trim()) }
            .map { it.second }
            .distinct()
        return standalone.singleOrNull()
    }

    private fun amountLabels(kind: PaymentCardKind): List<String> = when (kind) {
        PaymentCardKind.REFUND -> listOf("退款金额", "退款")
        PaymentCardKind.PAY_AFTER_USE -> listOf(
            "订单金额", "扣款金额", "支付金额", "已自动支付", "先用后付", "扣款",
        )
        PaymentCardKind.TRANSFER -> listOf(
            "转账金额", "收款金额", "付款金额", "交易金额", "订单金额",
        )
        PaymentCardKind.RED_PACKET -> listOf("红包金额", "红包")
        PaymentCardKind.WITHDRAWAL -> listOf("提现金额", "提现")
        PaymentCardKind.PAYMENT -> listOf(
            "实付金额", "实付", "支付金额", "付款金额", "扣款金额", "交易金额",
            "订单金额", "已支付", "支付成功", "使用零钱支付", "使用银行卡支付",
            "通过零钱扣款", "支出",
        )
        PaymentCardKind.BILL -> listOf("交易金额", "订单金额", "账单金额")
    }

    private fun labeledAmount(
        lines: List<BillTextLine>,
        labels: List<String>,
        kind: PaymentCardKind,
    ): Long? {
        for (label in labels) {
            for ((index, line) in lines.withIndex()) {
                val text = line.text
                if (!text.contains(label)) continue
                if (isNoisyAmountLine(text, kind)) continue
                amountAfterLabel(text, label)?.let { return it }
                if (label in NO_LOOKAHEAD_LABELS) continue
                for (offset in 1..2) {
                    val next = lines.getOrNull(index + offset)?.text ?: break
                    if (isNoisyAmountLine(next, kind)) continue
                    parseAmount(next)?.let { return it }
                    if (labels.any { next.contains(it) }) break
                }
            }
        }
        return null
    }

    private fun amountAfterLabel(text: String, label: String): Long? {
        val index = text.indexOf(label)
        if (index < 0) return null
        val after = text.substring(index + label.length)
            .trim()
            .trimStart(':', '：', ' ')
            .trim()
        if (after.isEmpty()) return null
        return parseAmount(after)
    }

    private fun parseAmount(text: String): Long? {
        AmountText.parseCents(text)?.let { return it }
        val trimmed = text.trim()
        val match = SIGNED_AMOUNT.find(trimmed) ?: return null
        val raw = match.groupValues[2]
        // A bare integer is usually a badge/row index, not a price. Require a
        // currency marker or a decimal fraction before treating it as money.
        if (!trimmed.contains('¥') && !trimmed.contains('￥') && !raw.contains('.')) {
            return null
        }
        return AmountText.toCents(raw)
    }

    private fun isNoisyAmountLine(text: String, kind: PaymentCardKind): Boolean {
        val noisy = listOf(
            "原价", "优惠", "折扣", "立减", "节省", "积分", "券", "已支出", "本月",
            "统计", "合计", "可用", "预付", "商品详情", "服务费", "手续费", "运费",
            "总支出", "总收入", "总消费", "累计支出", "累计收入", "记账日报",
            "已入账", "本月统计",
        )
        if (noisy.any { text.contains(it) }) return true
        if (text.contains("余额") &&
            !text.contains("支付") && !text.contains("付款") &&
            !text.contains("扣款") && !text.contains("余额宝")
        ) {
            return true
        }
        if (kind != PaymentCardKind.RED_PACKET && text.contains("红包")) return true
        if (kind != PaymentCardKind.REFUND && text.contains("退款")) return true
        if (kind == PaymentCardKind.REFUND && text.contains("充电订单")) return true
        return false
    }

    // --- merchants ---------------------------------------------------------

    private fun merchantCandidates(
        lines: List<BillTextLine>,
        kind: PaymentCardKind,
    ): List<String> {
        if (kind == PaymentCardKind.RED_PACKET) {
            return listOfNotNull(labeledMerchant(lines)).take(1)
        }
        if (kind == PaymentCardKind.TRANSFER) {
            counterpart(lines)?.let { return listOf(it) }
        }
        if (kind == PaymentCardKind.REFUND) {
            refundMerchant(lines)?.let { return listOf(it) }
        }
        labeledMerchant(lines)?.let { return listOf(it) }
        if (kind == PaymentCardKind.WITHDRAWAL) return emptyList()

        val candidates = lines.mapNotNull { line ->
            val text = line.text.trim()
            if (!isMerchantCandidate(text)) null else line to text.take(40)
        }
        val filtered = candidates.filterNot { (c, cName) ->
            c.right > c.left && c.bottom > c.top && candidates.any { (other, otherName) ->
                other !== c && other.right > other.left && other.bottom > other.top &&
                    isLeftLogoBadge(c, cName, other, otherName)
            }
        }
        val result = LinkedHashSet<String>()
        for ((_, name) in (if (filtered.isNotEmpty()) filtered else candidates)) {
            result += name
            if (result.size >= 5) break
        }
        return result.toList()
    }

    private fun isLeftLogoBadge(
        candidate: BillTextLine,
        candidateName: String,
        other: BillTextLine,
        otherName: String,
    ): Boolean {
        val cH = (candidate.bottom - candidate.top).toFloat()
        val cW = (candidate.right - candidate.left).toFloat()
        val oH = (other.bottom - other.top).toFloat()
        val oW = (other.right - other.left).toFloat()
        if (cH <= 0f || cW <= 0f || oH <= 0f || oW <= 0f) return false
        val cCenterY = (candidate.top + candidate.bottom) * 0.5f
        val oCenterY = (other.top + other.bottom) * 0.5f
        val sameBand = kotlin.math.abs(oCenterY - cCenterY) <= maxOf(oH, cH) * 2.2f
        val toTheRight = other.left >= candidate.left + cW * 0.5f && other.right > candidate.right
        if (!sameBand || !toTheRight) return false
        val tallerTitle = oH >= cH * 1.15f
        val narrowBadge = cW < oW * 0.55f && candidateName.length <= 4 && otherName.length >= candidateName.length
        return tallerTitle || narrowBadge
    }

    private fun refundMerchant(lines: List<BillTextLine>): String? {
        val suffix = Regex("""[\s\-—－]*(?:已)?退款(?:成功|到账)?$""")
        for (line in lines) {
            val base = line.text.trim().replace(suffix, "").trim()
            if (base != line.text.trim() && isMerchantCandidate(base)) {
                return base.take(40)
            }
        }
        return null
    }

    private fun labeledMerchant(lines: List<BillTextLine>): String? {
        for (label in MERCHANT_LABELS) {
            for ((index, line) in lines.withIndex()) {
                if (!line.text.contains(label)) continue
                val after = line.text.substringAfter(label)
                    .trim()
                    .trimStart(':', '：', ' ')
                    .trim()
                if (after.isNotEmpty() && isMerchantCandidate(after)) return after.take(40)
                val next = lines.getOrNull(index + 1)?.text?.trim().orEmpty()
                if (next.isNotEmpty() && isMerchantCandidate(next)) return next.take(40)
            }
        }
        return null
    }

    private fun counterpart(lines: List<BillTextLine>): String? {
        for (line in lines) {
            for (pattern in COUNTERPART_PATTERNS) {
                val match = pattern.find(line.text) ?: continue
                val name = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (isMerchantCandidate(name)) return name.take(40)
            }
        }
        return null
    }

    private fun isMerchantCandidate(text: String): Boolean {
        if (text.length !in 2..40) return false
        if (parseAmount(text) != null) return false
        if (ONLY_PUNCT_OR_NUMBERS.matches(text)) return false
        if (MONTH_HEADER.matches(text)) return false
        if (TIME.containsMatchIn(text)) return false
        if (CHROME.any { text.contains(it, ignoreCase = true) }) return false
        if (KIND_MARKERS.any { text.contains(it, ignoreCase = true) }) return false
        if (FIELD_LABELS.any { text.contains(it, ignoreCase = true) }) return false
        if (STATUS_WORDS.any { text.contains(it, ignoreCase = true) }) return false
        if (ORDER_ID_LIKE.matches(text) && text.any { it.isDigit() }) return false
        return true
    }

    // --- time and order ----------------------------------------------------

    private val EXPLICIT_EVENT_DATE = Regex(
        """(?:20\d{2}[-/年]|\d{1,2}[-/月]\d{1,2}(?:日)?|今天|昨天|前天)""",
    )

    private fun eventTime(
        lines: List<BillTextLine>,
        kind: PaymentCardKind,
        capturedAtMs: Long,
    ): Long? {
        fun parseVerified(raw: String): Long? =
            raw.takeIf(EXPLICIT_EVENT_DATE::containsMatchIn)?.let { parseTime(it, capturedAtMs) }

        if (kind == PaymentCardKind.REFUND) {
            for (label in listOf("到账时间", "退款时间")) {
                labeledValue(lines, label)?.let { raw ->
                    parseVerified(raw)?.let { return it }
                }
            }
            return cardMessageTime(lines, capturedAtMs)
        }
        cardMessageTime(lines, capturedAtMs)?.let { return it }
        val labels = when (kind) {
            PaymentCardKind.TRANSFER -> listOf("转账时间", "交易时间", "创建时间")
            PaymentCardKind.WITHDRAWAL -> listOf("提现时间", "到账时间")
            PaymentCardKind.PAY_AFTER_USE -> listOf(
                "下单时间", "支付时间", "扣款时间", "订单时间",
            )
            PaymentCardKind.PAYMENT -> listOf("支付时间", "付款时间", "交易时间")
            PaymentCardKind.RED_PACKET -> listOf("领取时间", "发送时间", "交易时间")
            else -> listOf("交易时间", "支付时间", "下单时间")
        }
        for (label in labels) {
            labeledValue(lines, label)?.let { raw ->
                parseVerified(raw)?.let { return it }
            }
        }
        return null
    }

    private fun labeledValue(lines: List<BillTextLine>, label: String): String? {
        for ((index, line) in lines.withIndex()) {
            if (!line.text.contains(label)) continue
            val after = line.text.substringAfter(label)
                .trim()
                .trimStart(':', '：', ' ')
                .trim()
            if (after.isNotEmpty()) return after
            val next = lines.getOrNull(index + 1)?.text?.trim().orEmpty()
            if (next.isNotEmpty() && !next.contains(label)) return next
        }
        return null
    }

    private fun cardMessageTime(lines: List<BillTextLine>, fallbackMs: Long): Long? {
        for (line in lines.take(6)) {
            val text = line.text.trim()
            if (!text.any { it.isDigit() }) continue
            if (parseAmount(text) != null) continue
            val parsed = parseTime(text, fallbackMs)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun orderId(joined: String): String? =
        ORDER.findAll(joined)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .firstOrNull { value -> value.any { it.isDigit() } }

    private fun confidence(
        classification: Classification,
        merchants: List<String>,
        orderId: String?,
        eventTime: Long?,
    ): Float = when (classification.kind) {
        PaymentCardKind.REFUND -> when {
            merchants.isNotEmpty() && eventTime != null -> 0.95f
            merchants.isNotEmpty() -> 0.9f
            else -> 0.8f
        }
        PaymentCardKind.PAY_AFTER_USE -> when {
            classification.status != PaymentCardStatus.COMPLETED -> 0.8f
            merchants.isNotEmpty() -> 0.9f
            else -> 0.8f
        }
        PaymentCardKind.PAYMENT -> when {
            merchants.isNotEmpty() && orderId != null -> 0.9f
            merchants.isNotEmpty() -> 0.85f
            else -> 0.75f
        }
        PaymentCardKind.TRANSFER -> when {
            classification.direction == PaymentDirection.UNKNOWN -> 0.6f
            classification.status == PaymentCardStatus.COMPLETED && merchants.isNotEmpty() -> 0.9f
            classification.status == PaymentCardStatus.COMPLETED -> 0.85f
            else -> 0.8f
        }
        PaymentCardKind.RED_PACKET ->
            if (classification.direction == PaymentDirection.UNKNOWN) 0.6f else 0.9f
        PaymentCardKind.WITHDRAWAL -> 0.85f
        PaymentCardKind.BILL -> 0.6f
    }

    private fun containsAny(text: String, vararg markers: String): Boolean =
        markers.any { text.contains(it) }

    private val COUNTERPART_PATTERNS = listOf(
        Regex("""转账给\s*([^\s，。,;；:：]{1,20})"""),
        Regex("""(?:来自|收款方|付款方|对方)\s*[:：]?\s*([^\s，。,;；:：]{1,20})"""),
        Regex("""([^\s，。,;；:：]{1,20})\s*(?:转给你|转账给你|给你转)"""),
    )

    private fun parseTime(raw: String, fallbackMs: Long): Long? {
        val text = raw.trim()
        val patterns = listOf(
            "yyyy年M月d日 HH:mm:ss",
            "yyyy年M月d日 HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/M/d HH:mm:ss",
            "yyyy/M/d HH:mm",
            "M月d日 HH:mm:ss",
            "M月d日 HH:mm",
            "M-d HH:mm:ss",
            "M-d HH:mm",
            "HH:mm:ss",
            "HH:mm",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }.parse(text)
            }.getOrNull()
            if (parsed != null) {
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = parsed.time
                    if (!pattern.startsWith("yyyy")) {
                        val fallback = Calendar.getInstance().apply { timeInMillis = fallbackMs }
                        if (!pattern.startsWith("M")) {
                            set(Calendar.YEAR, fallback.get(Calendar.YEAR))
                            set(Calendar.MONTH, fallback.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, fallback.get(Calendar.DAY_OF_MONTH))
                        } else {
                            val month = get(Calendar.MONTH)
                            val day = get(Calendar.DAY_OF_MONTH)
                            val hour = get(Calendar.HOUR_OF_DAY)
                            val minute = get(Calendar.MINUTE)
                            val second = get(Calendar.SECOND)
                            val millisecond = get(Calendar.MILLISECOND)
                            return (-1..1).mapNotNull { yearOffset ->
                                runCatching {
                                    Calendar.getInstance().apply {
                                        isLenient = false
                                        clear()
                                        set(Calendar.YEAR, fallback.get(Calendar.YEAR) + yearOffset)
                                        set(Calendar.MONTH, month)
                                        set(Calendar.DAY_OF_MONTH, day)
                                        set(Calendar.HOUR_OF_DAY, hour)
                                        set(Calendar.MINUTE, minute)
                                        set(Calendar.SECOND, second)
                                        set(Calendar.MILLISECOND, millisecond)
                                    }.timeInMillis
                                }.getOrNull()
                            }.minByOrNull { abs(it - fallbackMs) }
                        }
                    }
                }
                return calendar.timeInMillis
            }
        }
        return when {
            text.startsWith("昨天") -> {
                val time = text.removePrefix("昨天").trim()
                parseTime(time, fallbackMs)?.let { it - 24 * 60 * 60 * 1000L }
            }
            text.startsWith("今天") -> parseTime(text.removePrefix("今天").trim(), fallbackMs)
            text.startsWith("前天") -> {
                val time = text.removePrefix("前天").trim()
                parseTime(time, fallbackMs)?.let { it - 2 * 24 * 60 * 60 * 1000L }
            }
            else -> null
        }
    }
}
