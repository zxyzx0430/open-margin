package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.MoneyAmount

// 解析支付宝 / 微信通知文本，提取金额、收支方向、商户名。
//
// 真实通知样本（调研自 v2ex / AutoAccountingOrg issues / CFANZ + 用户实测）：
//   - 支付宝个人收款：  title="支付宝支付"   text="成功收款 1.00 元。享免费提现等更多专属服务..."
//   - 支付宝付款成功：  title="付款成功"    text="付款成功￥3.00 (AB)湖北武汉黄陂汉口北服装城美宜佳"
//   - 支付宝通用支出：  text="你有一笔 88.00 元的支出，点此查看详情"
//   - 支付宝退款：     title="支付宝"     text="收到退款 X.XX 元"
//   - 微信主动付款：   title="微信支付"   text="向 麦当劳 付款 12.30 元"
//   - 微信收到转账：   title="微信支付"   text="向你付款 100.00 元"  (别人付给你)
//   - 微信收转账：     text="收到 张三 的转账 100.00 元"
//
// ⚠️ 局限 1：微信扫码收款从 2020 后不再发系统通知（改 app 内提示）。
// ⚠️ 局限 2：支付宝转账入账（"某某给你转了一笔钱，点击查看详细信息"）通知文本不含金额，
//            extractAmount 会返回 null，整条通知被丢弃。
// 两个场景都需要 AccessibilityService 兜底（task #11）。
object NotificationParser {

    private const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
    private const val PKG_WECHAT = "com.tencent.mm"

    // 金额提取：只匹配带 ￥/¥ 前缀或后缀"元"的数字，避免误匹配日期等
    private val AMOUNT_REGEX_SYMBOL = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")
    private val AMOUNT_REGEX_YUAN = Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元""")

    // 收入关键词
    private val INCOME_KEYWORDS = listOf(
        "成功收款", "收款成功", "已收款", "您已收款",
        "已到账", "到账成功", "已存入零钱",
        "收到转账", "转账到账",
        "向你付款", "向您付款",  // 别人付给你（微信）
        "收到退款", "退款到账", "退款成功",
    )

    // 支出关键词
    private val EXPENSE_KEYWORDS = listOf(
        "付款成功", "成功付款", "已付款", "您已付款",
        "支付成功", "已支付", "您已支付",
        "扣款", "扣费", "已扣款",
        "消费",
        "支出", "的支出",  // 支付宝："你有一笔 X 元的支出"
    )

    // 只有真正完成的支付/入账才可记账。支付宝卡包等推广通知
    // 常含 "14元红包今晚失效"、"未领取" 等金额，但不代表扣款。
    private val PROMOTION_HINTS = listOf(
        "红包", "卡包", "优惠券", "代金券", "优惠红包", "待领取",
        "未领取", "点击领取", "今晚失效", "即将失效", "即将过期",
        "领券", "优惠活动", "满减券", "返现券",
    )
    private val COMPLETED_EXPENSE_HINTS = listOf(
        "付款成功", "成功付款", "已付款", "您已付款",
        "支付成功", "已支付", "您已支付", "扣款成功", "已扣款", "扣费成功",
        "你有一笔", "您有一笔",
    )
    private val COMPLETED_INCOME_HINTS = listOf(
        "成功收款", "收款成功", "已收款", "您已收款",
        "已到账", "到账成功", "收到转账", "向你付款", "向您付款",
        "收到退款", "退款到账", "退款成功", "已存入零钱",
    )
    private val ENGLISH_PAID = Regex("""\bpaid\b""", RegexOption.IGNORE_CASE)

    /** Fail closed on generic amount-only notifications and redemption prompts. */
    internal fun isSettledPaymentNotification(full: String): Boolean {
        val explicitExpense = COMPLETED_EXPENSE_HINTS.any { full.contains(it) } &&
            !(full.contains("你有一笔") || full.contains("您有一笔")) ||
            (full.contains("你有一笔") || full.contains("您有一笔")) &&
                (full.contains("的支出") || full.contains("支出，") || full.contains("支出。"))
        val explicitIncome = COMPLETED_INCOME_HINTS.any { full.contains(it) }
        val payToMerchant = PAY_TO_MERCHANT_REGEX.containsMatchIn(full)
        val englishPaid = ENGLISH_PAID.containsMatchIn(full)
        val confirmed = explicitExpense || explicitIncome || payToMerchant || englishPaid
        if (!confirmed) return false
        if (PROMOTION_HINTS.any { full.contains(it) } &&
            !explicitExpense && !explicitIncome && !payToMerchant && !englishPaid) return false
        // "红包" must not be taken as a credit merely because a notification
        // says "收到红包"; opening/settlement is checked by the UI extractor.
        if (full.contains("红包") && !explicitExpense && !explicitIncome &&
            !payToMerchant && !englishPaid) return false
        return true
    }

    // "向 商户名 付款" 模式（区分于"向你/您 付款"）= 支出
    private val PAY_TO_MERCHANT_REGEX = Regex("""向\s*(?!你|您)(\S+?)\s*付款""")

    // 商户提取模式（按优先级试，命中即用）
    private val MERCHANT_PATTERNS = listOf(
        Regex("""向\s*(?!你|您)(\S+?)\s*付款"""),  // 微信：向 麦当劳 付款（排除"向你/您"）
        Regex("""付款给\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""付给\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""收到\s*(\S+?)\s*的转账"""),     // 微信：收到 张三 的转账
        Regex("""来自\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""转账给\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        // 支付宝付款成功后面跟商户："付款成功￥3.00 (AB)湖北武汉..."
        Regex("""[¥￥][0-9.]+\s*[-—]?\s*(\S{2,})"""),
    )

    fun parse(
        packageName: String,
        title: String?,
        text: String?,
        timestampMs: Long,
    ): TransactionEntity? {
        val source = when (packageName) {
            PKG_ALIPAY -> TransactionSource.ALIPAY
            PKG_WECHAT -> TransactionSource.WECHAT
            else -> return null
        }
        return parseCore(
            full = "${title.orEmpty()} ${text.orEmpty()}".trim(),
            source = source,
            timestampMs = timestampMs,
        )
    }

    private fun parseCore(
        full: String,
        source: TransactionSource,
        timestampMs: Long,
    ): TransactionEntity? {
        if (full.isBlank()) return null
        if (!isSettledPaymentNotification(full)) return null

        // 1. 提金额
        val amountCents = extractAmountCents(full) ?: return null

        // 2. 判收支方向。优先级：
        //    a. INCOME 关键词（包括"向你付款"这种明确信号）
        //    b. EXPENSE 关键词
        //    c. "向 商户 付款"模式（排除"向你/您"）
        //    d. 默认 EXPENSE
        val type = when {
            INCOME_KEYWORDS.any { full.contains(it) } -> TransactionType.INCOME
            EXPENSE_KEYWORDS.any { full.contains(it) } -> TransactionType.EXPENSE
            PAY_TO_MERCHANT_REGEX.containsMatchIn(full) -> TransactionType.EXPENSE
            else -> TransactionType.EXPENSE
        }

        // 3. 提商户名 + 自动分类
        val extracted = extractMerchant(full)
        // Payment status words are not actual merchants.
        val merchant = if (extracted.lowercase() in setOf("paid", "payment", "success", "付款成功")) {
            if (source == TransactionSource.WECHAT) "微信支付" else "支付宝"
        } else extracted
        val category = CategoryClassifier.classify(merchant, full)

        return TransactionEntity(
            amountCents = amountCents,
            type = type.name,
            category = category.name,
            merchant = merchant,
            note = "[自动抓取] " + full.take(80),
            source = source.name,
            account = if (source == TransactionSource.ALIPAY) "支付宝" else "微信",
            timestamp = timestampMs,
        )
    }

    // 按通知的真实文本顺序遍历 ￥ 金额和“元”金额，而非一律先找 ￥。
    // 营销活动可能在付款前展示优惠券；这类金额不能代替实际成交额。
    private val NON_TRANSACTION_AMOUNT_BEFORE = Regex(
        """(?:余额|可用余额|优惠券|红包|代金券|抵扣券|立减券|满减券|奖励|优惠金额)\s*[：:]?\s*$"""
    )
    private val NON_TRANSACTION_AMOUNT_AFTER = Regex(
        """^\s*(?:元)?\s*(?:优惠券|红包|代金券|抵扣券|立减券|满减券|优惠券红包)"""
    )
    private val TRANSACTION_AMOUNT_BEFORE = Regex(
        """(?:付款成功|支付成功|成功付款|成功支付|已付款|已支付|成功收款|收款成功|收到退款|退款到账|到账成功|已到账|的支出|付款|收款|扣款|扣费)\s*[：:]?\s*$"""
    )

    private fun extractAmountCents(text: String): Long? {
        // "¥5.00元" 两个正则都会命中同一笔金额，第二条不能绕过
        // 第一条的“优惠券 / 红包”排除判断。
        val withSymbol = AMOUNT_REGEX_SYMBOL.findAll(text).toList()
        val withoutSymbolOverlap = AMOUNT_REGEX_YUAN.findAll(text).filterNot { yuan ->
            withSymbol.any { symbol -> yuan.range.first in symbol.range }
        }
        val matches = (withSymbol.asSequence() + withoutSymbolOverlap)
            .sortedBy { it.range.first }
        for (match in matches) {
            val before = text.substring((match.range.first - 18).coerceAtLeast(0), match.range.first)
            val after = text.substring(match.range.last + 1, (match.range.last + 13).coerceAtMost(text.length))
            val isConfirmed = TRANSACTION_AMOUNT_BEFORE.containsMatchIn(before)
            val appearsPromotional = NON_TRANSACTION_AMOUNT_BEFORE.containsMatchIn(before) ||
                NON_TRANSACTION_AMOUNT_AFTER.containsMatchIn(after)
            if (appearsPromotional && !isConfirmed) continue
            val cents = MoneyAmount.cents(match.groupValues[1])
            if (cents != null) return cents
        }
        return null
    }

    private fun extractMerchant(text: String): String {
        for (pattern in MERCHANT_PATTERNS) {
            val m = pattern.find(text)
            if (m != null) {
                val merchant = m.groupValues[1].trim()
                if (merchant.length in 2..40 && !merchant.matches(Regex("""[0-9.]+"""))) {
                    return merchant
                }
            }
        }
        return ""
    }
}
