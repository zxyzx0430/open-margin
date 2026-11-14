package com.bookkeeping.app.notification.accessibility

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One visible text line with its screen-space bounds. Android-free for JVM tests. */
data class BillTextLine(
    val text: String,
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

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
 * Parses WeChat Pay official-account bill cards (merchant payment, refund
 * arrival, and pay-after-use/auto-payment). It groups visible lines into cards
 * before parsing so one card's amount cannot be paired with another card's
 * merchant. Nothing is inserted here.
 */
object WechatBillCardDetector {
    const val WECHAT = "com.tencent.mm"

    private val CHROME = listOf(
        "微信支付", "我的账单", "支付服务", "优惠", "账单详情", "交易详情",
        "查看退款详情", "查看商家订单", "管理扣费服务", "联系商家", "复制",
        "本月统计", "查看明细", "日报设置", "微信支付分", "物流及商品详情",
        "使用零钱支付", "通过零钱扣款", "支付方式", "付款方式", "退款金额",
        "退款方式", "到账时间", "商品详情", "商户名称", "下单时间",
        "wechat pay", "transaction details",
    )
    private val TIME = Regex(
        """(?:今天|昨天|星期.|周.|\d{1,2}[:：]\d{2}|\d{4}[-/年]\d{1,2}[-/月]\d{1,2}|\d{1,2}月\d{1,2}日)""",
    )
    private val KIND_MARKERS = listOf(
        "退款到账通知", "退款金额", "退款方式",
        "先用后付", "已自动支付", "自动支付",
        "支付成功", "付款成功", "已支付", "支付完成",
    )
    private val ORDER = Regex(
        """(?:订单号|交易单号|商户单号|订单编号|交易号|Order(?:\s*(?:ID|No\.?))?|Transaction(?:\s*(?:ID|No\.?))?)\s*[:：]?\s*([A-Za-z0-9_-]{6,})""",
        RegexOption.IGNORE_CASE,
    )
    private val LABELS = listOf(
        "商品详情", "商户名称", "退款方式", "到账时间", "下单时间",
        "交易时间", "支付时间", "付款方式", "支付方式", "订单号",
        "交易单号", "商户单号",
    )

    fun fromSnapshot(
        packageName: String,
        snapshot: ScreenSnapshot,
        capturedAtMs: Long,
    ): List<BillCardDetection> {
        if (packageName != WECHAT) return emptyList()
        val lines = snapshot.root.walk().mapNotNull { node ->
            if (node.password || node.editable) return@mapNotNull null
            val text = node.text.trim().ifEmpty { node.contentDesc.trim() }
            if (text.isEmpty()) return@mapNotNull null
            val b = node.bounds ?: return@mapNotNull null
            if (b.isEmpty) return@mapNotNull null
            BillTextLine(text, b.top, b.bottom, b.left, b.right)
        }.distinctBy { "${it.text}|${it.top}|${it.left}" }
        if (lines.isEmpty()) return emptyList()
        val screenHeight = lines.maxOf { it.bottom }.coerceAtLeast(1)
        return fromLines(
            packageName = packageName,
            lines = lines,
            capturedAtMs = capturedAtMs,
            screenHeightPx = screenHeight,
            pageIdentity = pageIdentity(packageName, snapshot.flattenText()),
        )
    }

    fun fromLines(
        packageName: String,
        lines: List<BillTextLine>,
        capturedAtMs: Long,
        screenHeightPx: Int,
        pageIdentity: String,
    ): List<BillCardDetection> {
        if (packageName != WECHAT) return emptyList()
        val visible = lines.filter { it.text.isNotBlank() }
        if (visible.isEmpty()) return emptyList()
        val groups = groupCards(visible, screenHeightPx)
        return groups.mapNotNull { group ->
            parseCard(group, capturedAtMs, pageIdentity)
        }.distinctBy { it.cardKey }
    }

    private fun groupCards(lines: List<BillTextLine>, screenHeightPx: Int): List<List<BillTextLine>> {
        val sorted = lines.sortedWith(compareBy<BillTextLine> { it.top }.thenBy { it.left })
        val gapThreshold = (screenHeightPx / 12).coerceAtLeast(120)
        val groups = mutableListOf<MutableList<BillTextLine>>()
        var current = mutableListOf<BillTextLine>()
        for (line in sorted) {
            val previous = current.lastOrNull()
            val gap = if (previous != null) line.top - previous.bottom else 0
            if (current.isNotEmpty() && gap > gapThreshold) {
                groups += current
                current = mutableListOf()
            }
            current += line
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private fun parseCard(
        lines: List<BillTextLine>,
        capturedAtMs: Long,
        pageIdentity: String,
    ): BillCardDetection? {
        val joined = lines.joinToString(" ") { it.text }
        if (joined.contains("退款失败")) return null
        val kind = when {
            joined.contains("退款到账通知") ||
                (joined.contains("退款金额") && joined.contains("退款方式")) -> BillCardKind.REFUND
            joined.contains("先用后付") ||
                joined.contains("已自动支付") ||
                (joined.contains("自动支付") && joined.contains("扣款")) -> BillCardKind.AUTO_PAY
            joined.contains("支付成功") || joined.contains("付款成功") ||
                joined.contains("已支付") || joined.contains("支付完成") ||
                joined.contains("使用零钱支付") || joined.contains("使用银行卡支付") ||
                joined.contains("通过零钱扣款") -> BillCardKind.PAYMENT
            else -> return null
        }
        val amount = primaryAmount(lines, kind) ?: return null
        if (amount <= 0L) return null
        val merchants = merchantCandidates(lines, kind)
        val eventTime = when (kind) {
            BillCardKind.REFUND -> labeledValue(lines, "到账时间")?.let {
                parseTime(it, capturedAtMs)
            } ?: capturedAtMs
            BillCardKind.AUTO_PAY, BillCardKind.PAYMENT ->
                cardMessageTime(lines, capturedAtMs)
        }
        val orderId = ORDER.findAll(joined)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .firstOrNull { value -> value.any { it.isDigit() } }
        val confidence = when (kind) {
            BillCardKind.REFUND -> when {
                merchants.isNotEmpty() && eventTime != null -> 0.95f
                merchants.isNotEmpty() -> 0.9f
                else -> 0.8f
            }
            BillCardKind.AUTO_PAY -> if (merchants.isNotEmpty()) 0.9f else 0.8f
            BillCardKind.PAYMENT -> if (merchants.isNotEmpty()) 0.85f else 0.75f
        }
        val cardKey = listOf(
            pageIdentity,
            kind.name,
            amount.toString(),
            merchants.firstOrNull().orEmpty(),
            (eventTime ?: capturedAtMs).toString(),
        ).joinToString("|")
        return BillCardDetection(
            kind = kind,
            amountCents = amount,
            merchantCandidates = merchants,
            orderId = orderId,
            eventTimeMs = eventTime,
            confidence = confidence,
            reason = "ok",
            pageIdentity = pageIdentity,
            cardKey = cardKey,
        )
    }

    private fun primaryAmount(lines: List<BillTextLine>, kind: BillCardKind): Long? {
        val candidates = lines.mapIndexedNotNull { index, line ->
            AmountText.parseCents(line.text)?.let { index to it }
        }.filterNot { (index, _) ->
            val text = lines[index].text
            text.contains("预付") || text.contains("商品详情") ||
                text.contains("本月") || text.contains("已支出") ||
                text.contains("已入账") || text.contains("优惠") ||
                text.contains("原价") ||
                (kind == BillCardKind.REFUND && text.contains("充电订单"))
        }
        if (candidates.isEmpty()) return null
        val labels = when (kind) {
            BillCardKind.REFUND -> listOf("退款金额")
            BillCardKind.AUTO_PAY -> listOf("通过零钱扣款", "扣款", "已自动支付")
            BillCardKind.PAYMENT -> listOf("已支付", "支付成功", "使用零钱支付", "通过零钱扣款")
        }
        val labelIndex = lines.indexOfFirst { line -> labels.any { line.text.contains(it) } }
        if (labelIndex >= 0) {
            candidates.firstOrNull { (index, _) -> index >= labelIndex }?.let { return it.second }
        }
        return candidates.first().second
    }

    private fun merchantCandidates(lines: List<BillTextLine>, kind: BillCardKind): List<String> {
        if (kind == BillCardKind.REFUND) {
            labeledValue(lines, "商户名称")?.let { return listOf(it) }
        }
        val result = LinkedHashSet<String>()
        for (line in lines) {
            val text = line.text.trim()
            if (text.length !in 2..40) continue
            if (AmountText.parseCents(text) != null) continue
            if (CHROME.any { text.contains(it, ignoreCase = true) }) continue
            if (KIND_MARKERS.any { text.contains(it, ignoreCase = true) }) continue
            if (LABELS.any { text.contains(it, ignoreCase = true) }) continue
            if (TIME.containsMatchIn(text)) continue
            if (text.matches(Regex("""[\d\s:：./¥￥,，-]+"""))) continue
            if (text.contains("已支付") || text.contains("成功") ||
                text.contains("完成") || text.contains("查看") ||
                text.contains("管理") || text.contains("详情")
            ) continue
            result += text.take(40)
            if (result.size >= 5) break
        }
        return result.toList()
    }

    private fun labeledValue(lines: List<BillTextLine>, label: String): String? {
        for (index in lines.indices) {
            val text = lines[index].text
            if (!text.contains(label)) continue
            val after = text.substringAfter(label).trim().trimStart(':', '：').trim()
            if (after.isNotEmpty()) return after
            val next = lines.getOrNull(index + 1)?.text?.trim().orEmpty()
            if (next.isNotEmpty() && !next.contains(label)) return next
        }
        return null
    }

    private fun kindMarker(text: String): String? =
        KIND_MARKERS.firstOrNull { text.contains(it, ignoreCase = true) }

    /** The message timestamp shown above a card ("昨天 21:21" / "15:31"). */
    private fun cardMessageTime(lines: List<BillTextLine>, fallbackMs: Long): Long? {
        for (line in lines.take(6)) {
            val text = line.text.trim()
            if (!text.any { it.isDigit() }) continue
            if (AmountText.parseCents(text) != null) continue
            val parsed = parseTime(text, fallbackMs)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun pageIdentity(packageName: String, text: String): String =
        "$packageName|${Integer.toHexString(text.hashCode())}"

    private fun parseTime(raw: String, fallbackMs: Long): Long? {
        val text = raw.trim()
        val patterns = listOf(
            "yyyy年M月d日 HH:mm:ss",
            "yyyy年M月d日 HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "M月d日 HH:mm:ss",
            "M月d日 HH:mm",
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
                            set(Calendar.YEAR, fallback.get(Calendar.YEAR))
                        }
                    }
                }
                return calendar.timeInMillis
            }
        }
        return if (text.startsWith("昨天")) {
            val time = text.removePrefix("昨天").trim()
            parseTime(time, fallbackMs)?.let { it - 24 * 60 * 60 * 1000L }
        } else if (text.startsWith("今天")) {
            parseTime(text.removePrefix("今天").trim(), fallbackMs)
        } else null
    }
}
