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
    private val AGGREGATE_SUMMARY =
        Regex("""(?:微信支付|微信收款|支付宝)\s*\[\s*\d+\s*条\s*\]""")

    // 金额提取：只匹配带 ￥/¥ 前缀或后缀"元"的数字，避免误匹配日期等。
    // 尾部边界防止把 12.345 / 1.2.3 截成合法金额。
    private val AMOUNT_REGEX_SYMBOL = Regex("""[¥￥]\s*([0-9]{1,12}(?:\.[0-9]{1,2})?)(?![0-9.])""")
    private val AMOUNT_REGEX_YUAN = Regex("""(?<![0-9.])([0-9]{1,12}(?:\.[0-9]{1,2})?)(?![0-9.])\s*元""")

    // 收入关键词
    private val INCOME_KEYWORDS = listOf(
        "成功收款", "收款成功", "已收款", "您已收款", "收款到账",
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
        "付款给", "付给",
        "你有一笔", "您有一笔",
    )
    private val COMPLETED_INCOME_HINTS = listOf(
        "成功收款", "收款成功", "已收款", "您已收款", "收款到账",
        "已到账", "到账成功", "收到转账", "向你付款", "向您付款",
        "收到退款", "退款到账", "退款成功", "已存入零钱",
    )
    private val ENGLISH_PAID = Regex("""\bpaid\b""", RegexOption.IGNORE_CASE)
    private val SENSITIVE_HINTS = listOf("验证码", "动态密码", "支付密码", "付款密码", "口令")
    private val INCOMPLETE_HINTS = listOf(
        "处理中", "待支付", "待付款", "退款中", "退款申请", "等待商家",
        "待领取", "未领取", "等待对方确认", "待对方确认", "待确认收款",
    )
    // Transfers, withdrawals and red packets are not always phrased with a
    // payment verb. Keep these separate so the generic promotion filter does
    // not mistake a real red packet for a coupon.
    private val WITHDRAWAL_HINTS = listOf(
        "提现成功", "提现已到账", "提现到账", "提现到银行卡", "零钱提现",
    )
    private val OUTGOING_TRANSFER_REGEX = Regex("""(?:转账给|已转账给)(?!\s*[你您])""")
    private val OUTGOING_TRANSFER_HINTS = listOf(
        "你已转账", "您已转账",
    )
    private val INCOMING_RED_PACKET_REGEX =
        Regex("""(?:收到|领取了|已领取)\s*\S{0,20}\s*的?红包""")
    private val INCOMING_RED_PACKET_SETTLED = listOf("红包到账", "红包已存入零钱")
    private val OUTGOING_RED_PACKET_HINTS = listOf(
        "已发出红包", "红包已发出", "发红包成功", "已发红包",
    )

    private fun isWithdrawal(full: String): Boolean =
        WITHDRAWAL_HINTS.any { full.contains(it) }

    private fun isOutgoingTransfer(full: String): Boolean =
        OUTGOING_TRANSFER_REGEX.containsMatchIn(full) ||
            OUTGOING_TRANSFER_HINTS.any { full.contains(it) }

    private fun isIncomingRedPacket(full: String): Boolean =
        (INCOMING_RED_PACKET_REGEX.containsMatchIn(full) ||
            INCOMING_RED_PACKET_SETTLED.any { full.contains(it) }) &&
            !full.contains("封面") && !full.contains("皮肤") && !full.contains("表情")

    private fun isOutgoingRedPacket(full: String): Boolean =
        OUTGOING_RED_PACKET_HINTS.any { full.contains(it) }

    /** Fail closed on generic amount-only notifications and redemption prompts. */
    internal fun isSettledPaymentNotification(full: String): Boolean {
        if (SENSITIVE_HINTS.any { full.contains(it) }) return false
        if (INCOMPLETE_HINTS.any { full.contains(it) }) return false
        val explicitExpense = COMPLETED_EXPENSE_HINTS.any { full.contains(it) } &&
            !(full.contains("你有一笔") || full.contains("您有一笔")) ||
            GENERAL_EXPENSE_REGEX.containsMatchIn(full) ||
            (full.contains("你有一笔") || full.contains("您有一笔")) &&
                (full.contains("的支出") || full.contains("支出，") || full.contains("支出。"))
        val explicitIncome = COMPLETED_INCOME_HINTS.any { full.contains(it) } ||
            REFUND_RECEIVED_REGEX.containsMatchIn(full) ||
            GENERAL_INCOME_REGEX.containsMatchIn(full)
        val payToMerchant = PAY_TO_MERCHANT_REGEX.containsMatchIn(full)
        val payToYou = PAY_TO_YOU_REGEX.containsMatchIn(full)
        val receivedTransfer = RECEIVED_TRANSFER.containsMatchIn(full)
        val englishPaid = ENGLISH_PAID.containsMatchIn(full)
        val withdrawal = isWithdrawal(full)
        val outgoingTransfer = isOutgoingTransfer(full)
        val incomingRedPacket = isIncomingRedPacket(full)
        val outgoingRedPacket = isOutgoingRedPacket(full)
        val confirmed = explicitExpense || explicitIncome || payToMerchant ||
            payToYou || receivedTransfer || englishPaid || withdrawal ||
            outgoingTransfer || incomingRedPacket || outgoingRedPacket
        if (!confirmed) return false
        if (PROMOTION_HINTS.any { full.contains(it) } &&
            !explicitExpense && !explicitIncome && !payToMerchant && !payToYou &&
            !receivedTransfer && !englishPaid && !withdrawal && !outgoingTransfer &&
            !incomingRedPacket && !outgoingRedPacket) return false
        // "红包" must not be taken as a credit merely because a notification
        // says "收到红包"; a genuine red packet has an explicit settlement
        // phrase, while a coupon only mentions the word.
        if (full.contains("红包") && !explicitExpense && !explicitIncome &&
            !payToMerchant && !payToYou && !receivedTransfer && !englishPaid &&
            !incomingRedPacket && !outgoingRedPacket) return false
        return true
    }

    // "向 商户名 付款/支付" 模式（区分于"向你/您 付款"）= 支出
    private val PAY_TO_MERCHANT_REGEX = Regex("""向(?!\s*[你您])\s*(\S+?)\s*(?:付款|支付)""")
    private val PAY_TO_YOU_REGEX = Regex("""向\s*(?:你|您)\s*(?:付款|支付)""")
    // "收到 张三 的转账" 和 "张三转账给你" 都是微信个人转账的真实格式。
    private val RECEIVED_TRANSFER = Regex("""(?:收到\s*\S{1,20}\s*的转账|转账给(?:你|您))""")

    // 商户提取模式（按优先级试，命中即用）
    private val MERCHANT_PATTERNS = listOf(
        Regex("""向(?!\s*[你您])\s*(\S+?)\s*(?:付款|支付)"""),  // 微信：向 麦当劳 付款/向商户支付（排除"向你/您"）
        Regex("""付款给\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""付给\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""收到\s*(\S+?)\s*的转账"""),     // 微信：收到 张三 的转账
        Regex("""收到\s*(\S+?)\s*的红包"""),     // 微信：收到 张三 的红包
        Regex("""(?:领取了|已领取)\s*(\S+?)\s*的红包"""),
        Regex("""给\s*(\S+?)\s*(?:发|送)红包"""),
        Regex("""提现(?:到|至)\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""来自\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""转账给(?!\s*[你您])\s*(\S+?)(?:[\s，,。.！!]|$)"""),
        Regex("""收款方\s*(\S+?)(?:[\s，,。.！!]|$)"""),
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
        // Some ROMs deliver a group summary without FLAG_GROUP_SUMMARY. Its
        // "[3条]" count describes several child notifications, not one payment;
        // booking it duplicates the child notification and the visible page.
        val combined = "${title.orEmpty()} ${text.orEmpty()}"
        if (AGGREGATE_SUMMARY.containsMatchIn(combined)) return null
        // A chat message such as "张三: 支付成功 ¥12.30" is not a payment
        // notification. WeChat/Alipay payment notices have a service title.
        if (!title.isNullOrBlank() && !isPaymentTitle(source, title)) return null
        return parseCore(
            full = "${title.orEmpty()} ${text.orEmpty()}".trim(),
            source = source,
            timestampMs = timestampMs,
        )
    }

    private fun isPaymentTitle(source: TransactionSource, title: String): Boolean =
        when (source) {
            TransactionSource.WECHAT -> listOf(
                "微信支付", "微信收款", "服务通知", "支付通知", "收款通知", "退款到账",
                "微信红包", "账单通知", "动账提醒", "扣款通知", "转账通知",
                "Weixin Pay", "WeChat Pay",
            ).any { title.contains(it, ignoreCase = true) }
            TransactionSource.ALIPAY -> listOf(
                "支付宝", "付款成功", "收款成功", "支付助手", "交易提醒", "支付提醒",
                "到账通知", "退款", "账单通知", "动账提醒", "收支提醒", "扣款通知", "转账通知",
            ).any { title.contains(it, ignoreCase = true) }
            else -> false
        }

    private fun parseCore(
        full: String,
        source: TransactionSource,
        timestampMs: Long,
    ): TransactionEntity? {
        if (full.isBlank()) return null
        if (!isSettledPaymentNotification(full)) return null

        // 1. 提金额
        val amountCents = extractAmountCents(
            full,
            allowRedPacketAmount = isIncomingRedPacket(full) || isOutgoingRedPacket(full),
        ) ?: return null

        // 2. 判收支方向。优先级：
        //    a. INCOME 关键词（包括"向你付款"这种明确信号）
        //    b. EXPENSE 关键词
        //    c. "向 商户 付款"模式（排除"向你/您"）
        //    d. 默认 EXPENSE
        val type = when {
            // A payment notification can mention a reward red packet after
            // the actual amount; the payment verb must win over the word
            // "领取...红包".
            INCOME_KEYWORDS.any { full.contains(it) } -> TransactionType.INCOME
            REFUND_RECEIVED_REGEX.containsMatchIn(full) -> TransactionType.INCOME
            GENERAL_INCOME_REGEX.containsMatchIn(full) -> TransactionType.INCOME
            PAY_TO_YOU_REGEX.containsMatchIn(full) -> TransactionType.INCOME
            RECEIVED_TRANSFER.containsMatchIn(full) -> TransactionType.INCOME
            EXPENSE_KEYWORDS.any { full.contains(it) } -> TransactionType.EXPENSE
            GENERAL_EXPENSE_REGEX.containsMatchIn(full) -> TransactionType.EXPENSE
            PAY_TO_MERCHANT_REGEX.containsMatchIn(full) -> TransactionType.EXPENSE
            isIncomingRedPacket(full) -> TransactionType.INCOME
            isOutgoingRedPacket(full) -> TransactionType.EXPENSE
            isWithdrawal(full) -> TransactionType.EXPENSE
            isOutgoingTransfer(full) -> TransactionType.EXPENSE
            else -> TransactionType.EXPENSE
        }
        val transferLike = isWithdrawal(full) || isOutgoingTransfer(full) ||
            isOutgoingRedPacket(full)

        // 3. 提商户名 + 自动分类
        val extracted = extractMerchant(full)
        // Payment status words are not actual merchants.
        val merchant = if (extracted.lowercase() in setOf("paid", "payment", "success", "付款成功")) {
            if (source == TransactionSource.WECHAT) "微信支付" else "支付宝"
        } else extracted
        val category = if (isIncomingRedPacket(full)) {
            TransactionCategory.GIFT
        } else {
            val kind = when {
                full.contains("退款") &&
                    (full.contains("到账") || full.contains("收到") || full.contains("退回")) ->
                    ClassificationKind.REFUND
                isIncomingRedPacket(full) || isOutgoingRedPacket(full) ->
                    ClassificationKind.RED_PACKET
                isWithdrawal(full) -> ClassificationKind.WITHDRAWAL
                isOutgoingTransfer(full) || RECEIVED_TRANSFER.containsMatchIn(full) ->
                    ClassificationKind.TRANSFER
                else -> ClassificationKind.PAYMENT
            }
            CategoryClassifier.classify(
                ClassificationInput(
                    merchantRaw = merchant,
                    contextText = full,
                    direction = if (type == TransactionType.INCOME) {
                        ClassificationDirection.INCOME
                    } else {
                        ClassificationDirection.EXPENSE
                    },
                    source = if (source == TransactionSource.WECHAT) {
                        ClassificationSource.WECHAT
                    } else {
                        ClassificationSource.ALIPAY
                    },
                    kind = kind,
                    status = ClassificationStatus.COMPLETED,
                ),
            ).category
        }

        return TransactionEntity(
            amountCents = amountCents,
            type = type.name,
            category = category.name,
            merchant = merchant,
            note = "[自动抓取] " + full.take(80),
            source = source.name,
            account = if (source == TransactionSource.ALIPAY) "支付宝" else "微信",
            timestamp = timestampMs,
            remoteReviewStatus = if (transferLike) "transfer" else "unverified",
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

    private fun extractAmountCents(
        text: String,
        allowRedPacketAmount: Boolean = false,
    ): Long? {
        // Bank/payment texts may use thousands separators. Normalize before
        // matching, otherwise "￥1,234.56" is read as ￥1 or 234.56.
        val cleaned = text.replace(Regex("""(?<=\d),(?=\d)"""), "")
        // "¥5.00元" 两个正则都会命中同一笔金额，第二条不能绕过
        // 第一条的“优惠券 / 红包”排除判断。
        val withSymbol = AMOUNT_REGEX_SYMBOL.findAll(cleaned).toList()
        val withoutSymbolOverlap = AMOUNT_REGEX_YUAN.findAll(cleaned).filterNot { yuan ->
            withSymbol.any { symbol -> yuan.range.first in symbol.range }
        }
        val matches = (withSymbol.asSequence() + withoutSymbolOverlap)
            .sortedBy { it.range.first }
        for (match in matches) {
            val before = cleaned.substring((match.range.first - 18).coerceAtLeast(0), match.range.first)
            val after = cleaned.substring(match.range.last + 1, (match.range.last + 13).coerceAtMost(cleaned.length))
            val isConfirmed = TRANSACTION_AMOUNT_BEFORE.containsMatchIn(before)
            // An amount immediately preceded by "优惠券/红包/余额" is a
            // promotional value even if "付款成功" appears earlier. A real
            // red-packet notification is the one exception: its amount is
            // often written directly after "红包".
            if (NON_TRANSACTION_AMOUNT_BEFORE.containsMatchIn(before)) {
                val redPacketAmount = allowRedPacketAmount &&
                    Regex("""红包\s*[：:]?\s*$""").containsMatchIn(before)
                if (!redPacketAmount) continue
            }
            // A trailing promo label is allowed only for an amount already
            // confirmed by a transaction verb ("付款成功 ￥3.00 红包抵扣").
            if (NON_TRANSACTION_AMOUNT_AFTER.containsMatchIn(after) && !isConfirmed) continue
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
