package com.bookkeeping.app.voice

import com.bookkeeping.app.data.local.entity.TransactionCategory
import java.util.Calendar
import java.util.TimeZone

/** 语音草稿的收支类型。退款和转账都单独成一类，避免被当成普通收支。 */
enum class VoiceType { EXPENSE, INCOME, TRANSFER, REFUND }

/**
 * 解析结果里的风险提示。UI 用它决定是直接可存、需要标黄，还是必须拦住。
 * 例如 [AMOUNT_MISSING] 时金额为 null，不能落库；[REFUND_NEEDS_CONFIRM] /
 * [TRANSFER_NEEDS_CONFIRM] 表示类型只是推测，必须让用户点头。
 */
enum class VoiceWarning {
    AMOUNT_MISSING,
    AMOUNT_APPROXIMATE,
    MULTI_AMOUNT,
    CURRENCY_UNSUPPORTED,
    DATE_APPROXIMATE,
    FUTURE_DATE,
    MERCHANT_UNKNOWN,
    TYPE_UNCERTAIN,
    REFUND_NEEDS_CONFIRM,
    TRANSFER_NEEDS_CONFIRM,
    QUANTITY_NOT_AMOUNT,
}

/**
 * 一句口述账单解析出的结构化草稿。
 *
 * 时间语义（UI 必须按这个理解，否则会显示错）：
 * - [dateKnown] = 用户是否真的说了日期。false 表示"默认今天"，UI 不要显示成用户选的。
 * - [preciseTime] = 用户是否说了具体钟点。false 时 [occurredAtMs] 只代表"哪一天"，
 *   钟点部分是 0 点占位，不要显示成 00:00 这种"精确时间"。
 * - [timeOfDayLabel] 是"早上/中午/晚上"这类时段词，没钟点时用它提示。
 */
data class VoiceDraft(
    val amountCents: Long?,
    val type: VoiceType,
    val merchant: String?,
    val category: TransactionCategory?,
    val occurredAtMs: Long?,
    val dateKnown: Boolean,
    val preciseTime: Boolean,
    val timeOfDayLabel: String?,
    val account: String?,
    val note: String?,
    val warnings: Set<VoiceWarning>,
    val raw: String,
)

/**
 * 中文口述账单解析器：纯 Kotlin，不依赖 Android Context、不联网、不调用大模型。
 *
 * 设计取舍：
 * - 全部规则是确定性的查表 + 正则，同样的输入永远得到同样的输出，方便写单测和回归。
 * - 金额一律用"分"做整数运算，不经过 Float/Double。
 * - 时间基准由 [parse] 的 nowMs 传入，时区可传；单测固定 nowMs 就不会抖。
 * - 解析不出来就返回 null + 对应 warning，绝不猜一个看起来合理的值骗用户。
 *
 * 已知不支持（确保 UI 侧不要假设）：
 * - 外币只标 [VoiceWarning.CURRENCY_UNSUPPORTED]，不做汇率换算。
 * - 一句话只取一个日期、一个钟点；"上周五到周日"这种区间只认第一个。
 * - 商户靠"在/去/到 + 品牌词 + 后缀词"提取，生僻小店名可能提不出来，会走 [VoiceWarning.MERCHANT_UNKNOWN]。
 * - 一句话里有多个金额时只取第一个（打折场景取实付），其余交给 UI 让用户改。
 */
object VoiceTransactionParser {

    // ---------------------------------------------------------------- 入口

    /**
     * @param raw 语音识别的原始文本（可以是带空格的、带标点的口语）
     * @param nowMs 时间基准，所有"今天/昨天/上周五"都相对它计算
     * @param zone 计算日期用的时区，默认取系统时区
     * @param merchantRule 调用方的"商户→分类"规则（例如用户手动改过分类的记录），优先于内置关键词
     */
    fun parse(
        raw: String,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
        merchantRule: (String) -> TransactionCategory? = { null },
    ): VoiceDraft {
        val original = raw.trim()
        // 语言分流：没有汉字、又有拉丁字母/美元符号的口述交给英文解析器；
        // 含汉字（包括 "starbucks 花了 36" 这种中英混合）继续走下面的中文路径，
        // 英文片段由商户名提取逻辑当作店名处理。纯数字输入也留在中文路径，
        // 保证既有行为一字不改。
        if (VoiceEnglishParser.looksEnglish(original)) {
            return VoiceEnglishParser.parse(original, nowMs, zone, merchantRule)
        }
        val text = normalize(original)
        if (text.isBlank()) {
            return VoiceDraft(
                amountCents = null,
                type = VoiceType.EXPENSE,
                merchant = null,
                category = TransactionCategory.OTHER,
                occurredAtMs = null,
                dateKnown = false,
                preciseTime = false,
                timeOfDayLabel = null,
                account = null,
                note = null,
                warnings = linkedSetOf(
                    VoiceWarning.AMOUNT_MISSING,
                    VoiceWarning.TYPE_UNCERTAIN,
                    VoiceWarning.MERCHANT_UNKNOWN,
                ),
                raw = original,
            )
        }

        val todayStart = startOfDay(nowMs, zone)
        val dateInfo = resolveDate(text, todayStart, zone)
        val clock = resolveClock(text, dateInfo?.timeOfDay)
        val timeOfDayLabel = clock?.label ?: dateInfo?.timeOfDay ?: timeOfDayOf(text)
        val dayStart = dateInfo?.startOfDayMs ?: todayStart

        val excluded = mutableListOf<Span>()
        dateInfo?.span?.let { excluded += it }
        clock?.span?.let { excluded += it }

        val amountScan = scanAmounts(text, excluded)
        val accountHit = findAccount(text)
        val amountSpans = amountScan.hits.map { it.span }
        val merchantHit = findMerchant(text, excluded, amountSpans, accountHit?.second)

        val masked = mask(
            text,
            excluded + amountSpans + listOfNotNull(accountHit?.second, merchantHit?.second),
        )
        val (type, typeUncertain) = detectType(masked, text)

        val category = resolveCategory(text, merchantHit?.first, merchantRule)
            ?: if (type == VoiceType.TRANSFER) TransactionCategory.TRANSFER else TransactionCategory.OTHER

        val choice = chooseAmount(amountScan.hits)

        val warnings = linkedSetOf<VoiceWarning>()
        if (choice.hit == null) warnings += VoiceWarning.AMOUNT_MISSING
        if (choice.hit?.approximate == true) warnings += VoiceWarning.AMOUNT_APPROXIMATE
        if (choice.multi) warnings += VoiceWarning.MULTI_AMOUNT
        if (amountScan.currency != null) warnings += VoiceWarning.CURRENCY_UNSUPPORTED
        if (dateInfo?.approximate == true || (timeOfDayLabel != null && clock == null)) {
            warnings += VoiceWarning.DATE_APPROXIMATE
        }
        if (dayStart > todayStart) warnings += VoiceWarning.FUTURE_DATE
        if (merchantHit == null || category == TransactionCategory.OTHER) {
            warnings += VoiceWarning.MERCHANT_UNKNOWN
        }
        if (typeUncertain) warnings += VoiceWarning.TYPE_UNCERTAIN
        if (type == VoiceType.REFUND) warnings += VoiceWarning.REFUND_NEEDS_CONFIRM
        if (type == VoiceType.TRANSFER) warnings += VoiceWarning.TRANSFER_NEEDS_CONFIRM
        if (choice.hit == null && amountScan.quantitySeen) warnings += VoiceWarning.QUANTITY_NOT_AMOUNT

        val occurredAtMs = when {
            clock != null -> atClock(dayStart, clock.hour, clock.minute, zone)
            dateInfo != null || timeOfDayLabel != null -> dayStart
            else -> nowMs
        }

        return VoiceDraft(
            amountCents = choice.hit?.cents,
            type = type,
            merchant = merchantHit?.first,
            category = category,
            occurredAtMs = occurredAtMs,
            dateKnown = dateInfo != null,
            preciseTime = clock != null,
            timeOfDayLabel = timeOfDayLabel,
            account = accountHit?.first,
            note = extractNote(text),
            warnings = warnings,
            raw = original,
        )
    }

    // ---------------------------------------------------------------- 内部结构

    private data class Span(val start: Int, val end: Int)

    private data class AmountHit(
        val span: Span,
        val cents: Long,
        val approximate: Boolean = false,
        val listPrice: Boolean = false,
    )

    private data class AmountScan(
        val hits: List<AmountHit>,
        val quantitySeen: Boolean,
        val currency: String?,
    )

    private data class AmountChoice(val hit: AmountHit?, val multi: Boolean)

    private data class DateInfo(
        val span: Span,
        val startOfDayMs: Long,
        val approximate: Boolean = false,
        val timeOfDay: String? = null,
    )

    private data class Clock(
        val span: Span,
        val hour: Int,
        val minute: Int,
        val label: String?,
    )

    // ---------------------------------------------------------------- 文本归一化

    // 只纠正"明显同音词"，不做模糊改写：整句改写会把本来正确的词弄坏。
    private val WORD_FIXES: List<Pair<String, String>> = listOf(
        "支付包" to "支付宝",
        "威信" to "微信",
        "花贝" to "花呗",
        "麦当老" to "麦当劳",
        "瑞星" to "瑞幸",
        "蜜雪冰成" to "蜜雪冰城",
        "肯德鸡" to "肯德基",
        "星巴客" to "星巴克",
        "饿了吗" to "饿了么",
    )

    // "36快" -> "36块"；"36员/36园/36圆" -> "36元"。只在前一个字是数字时改，
    // 所以"快点""员工"这类正常词不会被误伤。
    private val KUAI_FIX = Regex("([0-9零〇一二两三四五六七八九十百千万]+)\\s*快")
    private val YUAN_FIX = Regex("([0-9零〇一二两三四五六七八九十百千万]+)\\s*[园圆员]")

    /**
     * 识别文本归一化：修同音字，再把"36快/36园"改回"块/元"。
     * 单独暴露是为了调试和单测能直接看归一化结果。
     */
    fun normalize(raw: String): String {
        var s = KUAI_FIX.replace(raw) { it.groupValues[1] + "块" }
        s = YUAN_FIX.replace(s) { it.groupValues[1] + "元" }
        for ((from, to) in WORD_FIXES) {
            if (s.contains(from)) s = s.replace(from, to)
        }
        return s
    }

    // ---------------------------------------------------------------- 数字

    private const val CN_NUM = "零〇一二两三四五六七八九十百千万"
    private const val MAX_YUAN = 100_000_000L

    private val CN_DIGIT: Map<Char, Long> = mapOf(
        '零' to 0L, '〇' to 0L, '一' to 1L, '二' to 2L, '两' to 2L, '三' to 3L, '四' to 4L,
        '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L,
    )

    private fun isNumeral(c: Char): Boolean = c in CN_NUM || c in '0'..'9'

    /**
     * 阿拉伯数字和中文数字共用一套解析："36"=36、"三十六"=36、"1万2"=12000。
     * 末尾简写按口语处理：一百二=120、两千三=2300、一万二=12000；带"零"就不是简写，
     * 一百零二=102、一千零五十=1050。
     */
    private fun parseInteger(raw: String): Long? {
        if (raw.isEmpty()) return null
        var total = 0L
        var section = 0L
        var number = 0L
        var lastUnit = 0L
        var zeroSeen = false
        var sawAny = false
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch in '0'..'9') {
                var j = i
                while (j < raw.length && raw[j] in '0'..'9') j++
                number = raw.substring(i, j).toLongOrNull() ?: return null
                zeroSeen = false
                sawAny = true
                i = j
                continue
            }
            val digit = CN_DIGIT[ch]
            if (digit != null) {
                sawAny = true
                if (ch == '零' || ch == '〇') {
                    zeroSeen = true
                    number = 0L
                } else {
                    number = digit
                }
                i++
                continue
            }
            val unit = when (ch) {
                '十' -> 10L
                '百' -> 100L
                '千' -> 1000L
                '万' -> 10_000L
                else -> return null
            }
            sawAny = true
            if (unit == 10_000L) {
                section += number
                if (section == 0L) section = 1L
                total += section * 10_000L
                section = 0L
                lastUnit = 10_000L
            } else {
                if (number == 0L) number = 1L
                section += number * unit
                lastUnit = unit
            }
            number = 0L
            zeroSeen = false
            i++
        }
        if (!sawAny) return null
        if (number > 0L && !zeroSeen && lastUnit >= 100L) {
            section += number * (lastUnit / 10L)
            number = 0L
        }
        total += section + number
        return total
    }

    /** "36.5" / "0.5" 这类带小数点的，直接折成分，不经过浮点。 */
    private fun decimalCents(raw: String): Long? {
        val dot = raw.indexOf('.')
        if (dot < 0) return parseInteger(raw)?.times(100L)
        val intPart = raw.substring(0, dot).ifEmpty { "0" }.toLongOrNull() ?: return null
        val fracRaw = raw.substring(dot + 1)
        val frac = when (fracRaw.length) {
            0 -> 0L
            1 -> (fracRaw.toLongOrNull() ?: return null) * 10L
            else -> fracRaw.substring(0, 2).toLongOrNull() ?: return null
        }
        return intPart * 100L + frac
    }

    private fun digitValue(text: String, index: Int): Int {
        if (index >= text.length) return -1
        val c = text[index]
        if (c in '0'..'9') return c - '0'
        return CN_DIGIT[c]?.toInt() ?: -1
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\u3000' || text[i] == '\t')) i++
        return i
    }

    // ---------------------------------------------------------------- 金额

    private const val QUANTITY_CLASSIFIERS = "个份杯斤瓶包盒双件箱袋张只位人次天年号日点折倍岁秒克吨升米碗串根颗支条把台部本节场集首篇页家名组套副对顿桌公里层楼排期"

    // 只用于判断"这个残余片段以量词开头，肯定不是商户名"。故意不含"张/家/名"，
    // 因为它们是常见姓氏和店名开头（张亮麻辣烫、家乐福）。
    private const val CLASSIFIER_START = "个份杯斤瓶包盒双件箱袋只位人次号点折倍顿碗串根颗支条把台部本节场集首篇页套副对"

    private val CURRENCY_WORDS = listOf("美元", "美金", "港币", "港元", "日元", "日币", "欧元", "英镑", "韩元", "外币")

    private val APPROX_PREFIX = listOf("差不多", "大概", "大约", "将近", "接近", "约为", "约", "快")

    private fun scanAmounts(text: String, excluded: List<Span>): AmountScan {
        val hits = mutableListOf<AmountHit>()
        var quantitySeen = false
        var currency: String? = null
        CURRENCY_WORDS.firstOrNull { text.contains(it) }?.let { currency = it }

        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (!isNumeral(c)) {
                i++
                continue
            }
            // 1000万 里的"万"是单位不是数字开头；"第3"是序数。
            // 注意"十"不能跳过：十八块五 就是 18.5，跳了就变成 8.5。
            if (c == '百' || c == '千' || c == '万') {
                i++
                continue
            }
            if (i > 0 && text[i - 1] == '第') {
                i++
                continue
            }
            if (inAnySpan(excluded, i)) {
                i++
                continue
            }

            var j = i
            var sawDot = false
            while (j < n) {
                val ch = text[j]
                if (ch in '0'..'9') {
                    j++
                    continue
                }
                if (ch == '.' && !sawDot && j > i && text[j - 1] in '0'..'9' &&
                    j + 1 < n && text[j + 1] in '0'..'9'
                ) {
                    sawDot = true
                    j++
                    continue
                }
                if (ch in CN_NUM) {
                    j++
                    continue
                }
                break
            }
            val runText = text.substring(i, j)
            if (runText.isEmpty()) {
                i++
                continue
            }

            var k = skipSpaces(text, j)
            if (k < n && (text[k] == '月' || text[k] == '号' || text[k] == '日' || text[k] == '点' ||
                    text[k] == '时' || text[k] == '時' || text[k] == ':' || text[k] == '：' ||
                    text[k] == '/' || text[k] == '年' || text[k] == '%' || text[k] == '％')
            ) {
                i = j
                continue
            }

            var approximate = isApproximatePrefix(text, i)
            if (k < n && (text[k] == '多' || text[k] == '来' || text[k] == '余')) {
                approximate = true
                k = skipSpaces(text, k + 1)
            }
            if (k + 1 < n && text[k] == '左' && text[k + 1] == '右') {
                approximate = true
                k = skipSpaces(text, k + 2)
            }

            val unitChar = if (k < n && (text[k] == '元' || text[k] == '块' || text[k] == '圆')) text[k] else null
            if (unitChar != null) {
                val yuan = if (sawDot) null else parseInteger(runText)
                val baseCents = if (sawDot) decimalCents(runText) else yuan?.times(100L)
                if (baseCents == null) {
                    i = j
                    continue
                }
                val (fracCents, end) = readFraction(text, k + 1)
                val cents = baseCents + fracCents
                if (cents in 0..(MAX_YUAN * 100L)) {
                    hits += AmountHit(
                        span = Span(i, end),
                        cents = cents,
                        approximate = approximate,
                        listPrice = isListPrice(text, i),
                    )
                }
                i = if (end > i) end else j
                continue
            }

            // "五毛"/"三角"：没有元块也要认
            if (k < n && (text[k] == '角' || text[k] == '毛')) {
                val value = if (sawDot) null else parseInteger(runText)
                if (value != null && value in 1..9) {
                    hits += AmountHit(
                        span = Span(i, k + 1),
                        cents = value * 10L,
                        approximate = approximate,
                        listPrice = false,
                    )
                    i = k + 1
                    continue
                }
            }

            val next = k
            val classifier = next < n && text[next] in QUANTITY_CLASSIFIERS
            if (classifier) {
                quantitySeen = true
                i = j
                continue
            }

            val prefixed = i > 0 && (text[i - 1] == '¥' || text[i - 1] == '￥')
            val chineseStyle = runText.any { it in CN_NUM }
            val value = if (sawDot) null else parseInteger(runText)
            val accept = when {
                prefixed -> true
                sawDot -> decimalCents(runText) != null
                chineseStyle -> value != null && value >= 10L
                else -> value != null && value > 0L
            }
            if (!accept) {
                i = j
                continue
            }
            val cents = if (sawDot) decimalCents(runText) else value?.times(100L)
            if (cents == null || cents < 0 || cents > MAX_YUAN * 100L) {
                i = j
                continue
            }
            hits += AmountHit(
                span = Span(i, j),
                cents = cents,
                approximate = approximate,
                listPrice = isListPrice(text, i),
            )
            i = j
        }
        return AmountScan(hits = hits, quantitySeen = quantitySeen, currency = currency)
    }

    /** 元/块后面那一截：半=5角，零五=5分，五毛=5角，5=5角，50=5角0分。 */
    private fun readFraction(text: String, from: Int): Pair<Long, Int> {
        val n = text.length
        var idx = skipSpaces(text, from)
        if (idx >= n) return 0L to idx
        if (text[idx] == '半') return 50L to idx + 1
        if (text[idx] == '零' || text[idx] == '〇') {
            var p = skipSpaces(text, idx + 1)
            val d = digitValue(text, p)
            if (d < 0) return 0L to idx
            p++
            if (p < n && text[p] == '分') p++
            return d.toLong() to p
        }
        val first = digitValue(text, idx)
        if (first < 0) return 0L to idx
        var p = skipSpaces(text, idx + 1)
        if (p < n && (text[p] == '角' || text[p] == '毛')) {
            var cents = first * 10L
            // 只有紧跟"分"才把后面的数字当分，避免把"五毛 三十"里的三十吃掉
            var q = p + 1
            if (q < n && text[q] == '零') q++
            if (q < n) {
                val f = digitValue(text, q)
                if (f in 0..9 && q + 1 < n && text[q + 1] == '分') {
                    cents += f.toLong()
                    p = q + 2
                } else {
                    p += 1
                }
            } else {
                p += 1
            }
            return cents to p
        }
        if (p < n && text[p] == '分') return first.toLong() to p + 1
        // 两个连续数字当角分："36块50" = 36.50
        if (p < n && text[p] in '0'..'9') {
            val second = text[p] - '0'
            return (first * 10L + second) to p + 1
        }
        return first * 10L to idx + 1
    }

    private fun isApproximatePrefix(text: String, index: Int): Boolean {
        val start = maxOf(0, index - 5)
        val head = text.substring(start, index).trimEnd()
        return APPROX_PREFIX.any { head.endsWith(it) }
    }

    private fun isListPrice(text: String, index: Int): Boolean {
        val start = maxOf(0, index - 4)
        val head = text.substring(start, index).trimEnd()
        return head.endsWith("原价") || head.endsWith("标价") || head.endsWith("吊牌价") ||
            head.endsWith("原") || head.endsWith("原售价")
    }

    /**
     * 选一个金额落库：
     * - "原价 100 打折 80" 这种，标价不算实付，取后面那个；
     * - 剩下多个就取第一个，并给出 MULTI_AMOUNT 让用户确认。
     */
    private fun chooseAmount(hits: List<AmountHit>): AmountChoice {
        if (hits.isEmpty()) return AmountChoice(null, false)
        val real = hits.filter { !it.listPrice }
        val pool = if (real.isEmpty()) hits else real
        val multi = pool.size > 1
        return AmountChoice(pool.first(), multi)
    }

    // ---------------------------------------------------------------- 日期

    private val WEEKDAY = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7,
        '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5, '6' to 6, '7' to 7,
    )

    private fun startOfDay(ms: Long, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun plusDays(ms: Long, days: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = ms
            add(Calendar.DAY_OF_MONTH, days)
        }.timeInMillis

    private fun isoDayOfWeek(ms: Long, zone: TimeZone): Int {
        val dow = Calendar.getInstance(zone).apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    private fun dayOf(year: Int, month: Int, day: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis

    private fun yearOf(ms: Long, zone: TimeZone): Int =
        Calendar.getInstance(zone).apply { timeInMillis = ms }.get(Calendar.YEAR)

    private fun monthOf(ms: Long, zone: TimeZone): Int =
        Calendar.getInstance(zone).apply { timeInMillis = ms }.get(Calendar.MONTH) + 1

    private fun atClock(dayStart: Long, hour: Int, minute: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = dayStart
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val DATES: List<Regex> = listOf(
        Regex("大前天"),
        Regex("前天"),
        Regex("昨天|昨日|昨儿|昨天儿"),
        Regex("今天|今日|今儿"),
        Regex("明天|明日|明儿"),
        Regex("大后天"),
        Regex("后天"),
        Regex("昨晚|昨夜"),
        Regex("今晚|今夜"),
        Regex("今早|今天早上|今天上午"),
        Regex("(上上|上|这|本|下)?(?:个)?(?:周|星期|礼拜)([一二三四五六日天1-7])"),
        Regex("(上|这|本)?(?:个)?周末"),
        Regex("上(?:个)?月\\s*(\\d{1,2})\\s*(?:号|日)?"),
        Regex("上(?:个)?月"),
        Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*(?:号|日)?"),
        Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?!\\d)"),
        Regex("(?<!\\d)(\\d{1,2})\\s*(?:号|日)"),
        Regex("([一二三四五六七八九十]{1,3})\\s*(?:号|日)"),
    )

    private fun resolveDate(text: String, todayStart: Long, zone: TimeZone): DateInfo? {
        val candidates = mutableListOf<DateInfo>()
        val year = yearOf(todayStart, zone)
        val month = monthOf(todayStart, zone)
        val iso = isoDayOfWeek(todayStart, zone)
        val monday = plusDays(todayStart, -(iso - 1), zone)

        for ((index, regex) in DATES.withIndex()) {
            for (m in regex.findAll(text)) {
                val span = Span(m.range.first, m.range.last + 1)
                val value = m.value
                val info: DateInfo? = when (index) {
                    0 -> DateInfo(span, plusDays(todayStart, -3, zone))
                    1 -> DateInfo(span, plusDays(todayStart, -2, zone))
                    2 -> DateInfo(span, plusDays(todayStart, -1, zone))
                    3 -> DateInfo(span, todayStart)
                    4 -> DateInfo(span, plusDays(todayStart, 1, zone))
                    5 -> DateInfo(span, plusDays(todayStart, 3, zone))
                    6 -> DateInfo(span, plusDays(todayStart, 2, zone))
                    7 -> DateInfo(span, plusDays(todayStart, -1, zone), timeOfDay = "晚上")
                    8 -> DateInfo(span, todayStart, timeOfDay = "晚上")
                    9 -> DateInfo(
                        span,
                        todayStart,
                        timeOfDay = if (value.contains("上午")) "上午" else "早上",
                    )
                    10 -> {
                        val prefix = m.groupValues[1]
                        val target = WEEKDAY[m.groupValues[2][0]] ?: continue
                        val thisWeek = plusDays(monday, target - 1, zone)
                        val day = when (prefix) {
                            "上上" -> plusDays(thisWeek, -14, zone)
                            "上" -> plusDays(thisWeek, -7, zone)
                            "下" -> plusDays(thisWeek, 7, zone)
                            "这", "本" -> thisWeek
                            // 不带前缀的"周三"按最近一个已经过去的周三算，因为记账记的是已经发生的事
                            else -> if (thisWeek <= todayStart) thisWeek else plusDays(thisWeek, -7, zone)
                        }
                        DateInfo(span, day)
                    }
                    11 -> {
                        val prefix = m.groupValues[1]
                        val saturday = plusDays(monday, 5, zone)
                        val day = when (prefix) {
                            "上" -> plusDays(saturday, -7, zone)
                            "这", "本" -> saturday
                            else -> if (saturday <= todayStart) saturday else plusDays(saturday, -7, zone)
                        }
                        DateInfo(span, day, approximate = true)
                    }
                    12 -> {
                        val day = m.groupValues[1].toIntOrNull() ?: continue
                        val (y, mo) = previousMonth(year, month, zone)
                        DateInfo(span, dayOf(y, mo, day, zone))
                    }
                    13 -> {
                        val (y, mo) = previousMonth(year, month, zone)
                        DateInfo(span, dayOf(y, mo, 1, zone), approximate = true)
                    }
                    14 -> {
                        val mo = m.groupValues[1].toIntOrNull() ?: continue
                        val day = m.groupValues[2].toIntOrNull() ?: continue
                        if (mo !in 1..12 || day !in 1..31) continue
                        DateInfo(span, dayOf(year, mo, day, zone))
                    }
                    15 -> {
                        val mo = m.groupValues[1].toIntOrNull() ?: continue
                        val day = m.groupValues[2].toIntOrNull() ?: continue
                        if (mo !in 1..12 || day !in 1..31) continue
                        DateInfo(span, dayOf(year, mo, day, zone))
                    }
                    16 -> {
                        val day = m.groupValues[1].toIntOrNull() ?: continue
                        if (day !in 1..31) continue
                        DateInfo(span, dayOf(year, month, day, zone))
                    }
                    17 -> {
                        val day = parseInteger(m.groupValues[1])?.toInt() ?: continue
                        if (day !in 1..31) continue
                        DateInfo(span, dayOf(year, month, day, zone))
                    }
                    else -> null
                }
                if (info != null) candidates += info
            }
        }
        return candidates.minWithOrNull(
            compareBy({ it.span.start }, { -(it.span.end - it.span.start) }),
        )
    }

    private fun previousMonth(year: Int, month: Int, zone: TimeZone): Pair<Int, Int> =
        if (month == 1) (year - 1) to 12 else year to (month - 1)

    // ---------------------------------------------------------------- 钟点 / 时段

    private val TIME_OF_DAY: List<Pair<String, String>> = listOf(
        "凌晨" to "凌晨",
        "早上" to "早上",
        "早晨" to "早上",
        "上午" to "上午",
        "中午" to "中午",
        "下午" to "下午",
        "傍晚" to "傍晚",
        "晚上" to "晚上",
        "夜里" to "晚上",
        "半夜" to "半夜",
    )

    private val PERIOD_WORDS = TIME_OF_DAY.map { it.first }.sortedByDescending { it.length }

    private fun timeOfDayOf(text: String): String? {
        val hit = TIME_OF_DAY.firstOrNull { text.contains(it.first) } ?: return null
        return hit.second
    }

    private val CLOCK_DIGITAL = Regex("(?<!\\d)(\\d{1,2})[:：](\\d{2})(?!\\d)")
    private val CLOCK_WORD = Regex(
        "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|半夜)?\\s*" +
            "(\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*[点時时]\\s*" +
            "(半|一刻|三刻|([零〇一二三四五六七八九十]{1,3})\\s*分?)?",
    )

    private fun resolveClock(text: String, fallbackLabel: String?): Clock? {
        CLOCK_DIGITAL.find(text)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues[2].toIntOrNull() ?: return@let
            if (hour !in 0..23 || minute !in 0..59) return@let
            return Clock(Span(m.range.first, m.range.last + 1), hour, minute, null)
        }
        CLOCK_WORD.find(text)?.let { m ->
            val rawHour = m.groupValues[2]
            val hour = parseInteger(rawHour)?.toInt() ?: return@let
            val label = m.groupValues[1].ifBlank { fallbackLabel }
            val suffix = m.groupValues[3]
            val minute = when {
                suffix == "半" -> 30
                suffix == "一刻" -> 15
                suffix == "三刻" -> 45
                suffix.isNotBlank() -> parseInteger(m.groupValues[4])?.toInt() ?: 0
                else -> 0
            }
            if (hour !in 0..24 || minute !in 0..59) return@let
            val adjusted = adjustHour(hour, label)
            return Clock(Span(m.range.first, m.range.last + 1), adjusted, minute, label)
        }
        return null
    }

    private fun adjustHour(hour: Int, label: String?): Int = when (label) {
        "下午", "傍晚", "晚上", "夜里" -> when {
            hour in 1..11 -> hour + 12
            hour == 12 -> 0
            else -> hour
        }
        "中午" -> if (hour in 1..3) hour + 12 else hour
        "半夜", "凌晨" -> if (hour == 12) 0 else hour
        else -> hour
    }

    // ---------------------------------------------------------------- 类型

    private val REFUND_WORDS = listOf("退款", "退了", "退钱", "退货", "返现", "退回", "退我", "退给", "退回钱")
    private val TRANSFER_WORDS = listOf(
        "转账", "转给", "转出", "转入", "汇款", "提现", "还信用卡", "还款", "借给", "充值", "转了", "转钱",
    )
    private val TRANSFER_PATTERN = Regex("给[^，,。]{1,6}转")
    private val INCOME_WORDS = listOf(
        "卖了", "卖出", "卖掉", "卖东西", "卖货", "收到", "收了", "收钱", "工资", "奖金", "报销",
        "利息", "分红", "进账", "入账", "到账", "中奖", "赚了", "补贴",
    )
    private val EXPENSE_WORDS = listOf(
        "花", "买", "付", "支付", "消费", "支出", "交了", "充了话费", "交话费", "交电费", "交水费",
        "交房租", "请客", "买单", "订", "打车", "吃", "喝", "加油", "停车", "用了", "花掉", "付款",
    )

    private fun detectType(text: String, raw: String): Pair<VoiceType, Boolean> {
        if (REFUND_WORDS.any { text.contains(it) }) return VoiceType.REFUND to false
        if (REPAY_PATTERN.containsMatchIn(raw)) return VoiceType.TRANSFER to false
        if (TRANSFER_WORDS.any { text.contains(it) }) return VoiceType.TRANSFER to false
        if (TRANSFER_PATTERN.containsMatchIn(text)) return VoiceType.TRANSFER to false
        if (INCOME_WORDS.any { text.contains(it) }) return VoiceType.INCOME to false
        if (EXPENSE_WORDS.any { text.contains(it) }) return VoiceType.EXPENSE to false
        return VoiceType.EXPENSE to true
    }

    // "还信用卡"里的"信用卡"会被当成账户抹掉，所以这条要在原文上单独判一次
    private val REPAY_PATTERN = Regex("还(?:了)?(?:信用卡|花呗|白条|房贷|车贷|借款|贷款)")

    // ---------------------------------------------------------------- 商户

    private val BRANDS: List<String> = listOf(
        "美团外卖", "饿了么", "滴滴出行", "滴滴", "淘宝", "天猫", "京东", "拼多多", "盒马", "山姆",
        "永辉", "瑞幸咖啡", "瑞幸", "蜜雪冰城", "麦当劳", "肯德基", "星巴克", "中石化", "中石油",
        "地铁", "公交", "海底捞", "喜茶", "奈雪", "华莱士", "必胜客", "汉堡王", "沃尔玛", "家乐福",
        "全家", "罗森", "7-11", "高德打车", "哈啰", "青桔", "瑞幸", "盒马鲜生",
    ).sortedByDescending { it.length }

    private val PREPOSITION_MERCHANT = Regex(
        "(?:在|从|去|到)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,10}?)\\s*" +
            "(?=[\\s\\d¥￥]|花|买|付|用|吃|喝|点|充|交|消费|结账|买单|打|坐|取|拿|，|,|。|！|!|$)",
    )

    private val MERCHANT_SUFFIX = Regex(
        "([\\u4e00-\\u9fa5A-Za-z]{1,6}(?:超市|便利店|加油站|药店|药房|餐厅|饭店|咖啡店|奶茶店|商场|市场|小馆|面馆))",
    )

    private const val MERCHANT_VERBS = "花买付收转退借还给送取拿点吃喝打坐骑开走跑看去来到在用充交卖换修寄洗剪订"

    private val MERCHANT_STOPWORDS = setOf(
        "家里", "家", "公司", "单位", "学校", "医院", "楼下", "楼上", "网上", "线上",
        "外面", "这边", "那边", "手机", "银行", "微信", "支付宝", "现金", "超市", "这里", "那里",
        "附近", "门口", "店里", "朋友", "同事", "老家",
    )

    private fun findMerchant(
        text: String,
        excluded: List<Span>,
        amountSpans: List<Span>,
        accountSpan: Span?,
    ): Pair<String, Span>? {
        for (m in PREPOSITION_MERCHANT.findAll(text)) {
            val start = m.range.first
            if (start > 0 && text[start - 1] in "现正还存好收到出去下") continue
            val cleaned = cleanMerchant(m.groupValues[1])
            if (cleaned != null) {
                return cleaned to Span(m.range.first, m.range.first + m.groupValues[1].length)
            }
        }
        for (m in MERCHANT_SUFFIX.findAll(text)) {
            if (inAnySpan(excluded, m.range.first)) continue
            val cleaned = cleanMerchant(m.groupValues[1], allowNumeral = true, checkVocab = false)
            if (cleaned != null) return cleaned to Span(m.range.first, m.range.last + 1)
        }
        for (brand in BRANDS) {
            var from = 0
            while (true) {
                val idx = text.indexOf(brand, from)
                if (idx < 0) break
                if (!inAnySpan(excluded, idx) && !inAnySpan(amountSpans, idx) &&
                    (accountSpan == null || !inSpan(accountSpan, idx))
                ) {
                    return brand to Span(idx, idx + brand.length)
                }
                from = idx + 1
            }
        }
        return residualMerchant(text, excluded + amountSpans + listOfNotNull(accountSpan))
    }

    private fun residualMerchant(text: String, spans: List<Span>): Pair<String, Span>? {
        val chars = text.toCharArray()
        for (span in spans) {
            for (i in maxOf(0, span.start) until minOf(chars.size, span.end)) chars[i] = '·'
        }
        val masked = String(chars)
        var offset = 0
        for (chunk in masked.split(Regex("[·\\s，,。.、！!？?；;：:]+"))) {
            if (!isResidualMerchantChunk(chunk)) continue
            val idx = masked.indexOf(chunk, offset)
            if (idx < 0) continue
            offset = idx + chunk.length
            val cleaned = cleanMerchant(chunk) ?: continue
            return cleaned to Span(idx, idx + chunk.length)
        }
        return null
    }

    /**
     * 残余片段能不能当店名：中文片段沿用原来的 2~8 字；
     * 纯拉丁片段放宽到 24 字，因为中英混合里 "starbucks" 这种店名本来就长。
     * 只放行纯拉丁，避免把一长串中文语气词误当店名。
     */
    private fun isResidualMerchantChunk(chunk: String): Boolean {
        if (chunk.length in 2..8) return true
        return chunk.length in 9..24 &&
            chunk.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == '-' || it == '&' }
    }

    private fun cleanMerchant(
        raw: String,
        allowNumeral: Boolean = false,
        checkVocab: Boolean = true,
    ): String? {
        var s = raw.trim().trim('，', ',', '。', '.', '！', '!', '的', '了')
        s = s.removePrefix("了").removePrefix("的")
        s = s.removeSuffix("上").removeSuffix("里").removeSuffix("中").removeSuffix("那边").removeSuffix("这边")
        if (s.length < 2) return null
        if (s in MERCHANT_STOPWORDS) return null
        if (s.any { it in MERCHANT_VERBS }) return null
        if (s.all { it in '0'..'9' }) return null
        if (s.first() in CLASSIFIER_START) return null
        if (s.last() in "的了吗呢吧啊呀嘛") return null
        if (!allowNumeral && s.any { it in "零〇一二两三四五六七八九十百千万" }) return null
        if (checkVocab && NON_MERCHANT_WORDS.any { s.contains(it) }) return null
        return s
    }

    // ---------------------------------------------------------------- 账户

    private val ACCOUNTS: List<String> = listOf(
        "微信零钱", "微信支付", "微信", "支付宝", "余额宝", "花呗", "白条", "云闪付",
        "招商银行", "招行", "建设银行", "建行", "工商银行", "工行", "农业银行", "农行",
        "中国银行", "中行", "交通银行", "交行", "邮储银行", "银行卡", "储蓄卡", "借记卡", "信用卡", "现金",
    ).sortedByDescending { it.length }

    private fun findAccount(text: String): Pair<String, Span>? {
        for (account in ACCOUNTS) {
            val idx = text.indexOf(account)
            if (idx >= 0) return account to Span(idx, idx + account.length)
        }
        return null
    }

    // ---------------------------------------------------------------- 分类

    private val CATEGORY_KEYWORDS: List<Pair<List<String>, TransactionCategory>> = listOf(
        listOf(
            "肯德基", "KFC", "麦当劳", "瑞幸", "星巴克", "咖啡", "奶茶", "外卖", "火锅", "早餐",
            "早饭", "午饭", "晚饭", "吃饭", "餐厅", "饭店", "食堂", "烧烤", "米线", "面包", "蛋糕",
            "蜜雪冰城", "喜茶", "奈雪", "海底捞", "汉堡", "披萨", "小吃", "水果", "买菜", "美团外卖",
            "饿了么", "夜宵", "下午茶", "聚餐",
        ) to TransactionCategory.FOOD,
        listOf(
            "地铁", "公交", "打车", "滴滴", "加油", "停车", "高铁", "火车", "机票", "飞机",
            "出租车", "网约车", "单车", "ETC", "过路费", "船票", "中石化", "中石油",
        ) to TransactionCategory.TRANSPORT,
        listOf(
            "超市", "淘宝", "天猫", "京东", "拼多多", "商场", "便利店", "唯品会", "山姆", "盒马",
            "永辉", "沃尔玛", "购物", "衣服", "鞋子", "日用品", "洗衣",
        ) to TransactionCategory.SHOPPING,
        listOf(
            "电影", "KTV", "游戏", "会员", "唱歌", "演出", "门票", "剧本杀", "桌游", "网吧",
            "演唱会", "视频会员", "音乐会员",
        ) to TransactionCategory.ENTERTAINMENT,
        listOf("房租", "物业", "水费", "电费", "燃气", "租金", "房贷", "中介费", "暖气费") to
            TransactionCategory.HOUSING,
        listOf("药店", "药房", "医院", "挂号", "看病", "体检", "牙科", "诊所", "门诊", "医保", "买药") to
            TransactionCategory.HEALTH,
        listOf("学费", "书", "课程", "培训", "教材", "考试", "网课", "补习", "书店") to
            TransactionCategory.EDUCATION,
        listOf("话费", "宽带", "流量", "网费", "手机费", "水电") to TransactionCategory.UTILITIES,
        listOf("红包", "礼金", "份子钱", "随礼", "礼物", "压岁钱") to TransactionCategory.GIFT,
    )

    /**
     * 这些词出现在残余片段里，说明那是"活动/品类/时间/账户"而不是店名。
     * 用 lazy 是为了不受属性初始化顺序影响。
     */
    private val NON_MERCHANT_WORDS: Set<String> by lazy {
        buildSet {
            CATEGORY_KEYWORDS.forEach { addAll(it.first) }
            addAll(REFUND_WORDS)
            addAll(TRANSFER_WORDS)
            addAll(INCOME_WORDS)
            addAll(EXPENSE_WORDS)
            addAll(ACCOUNTS)
            addAll(CURRENCY_WORDS)
            addAll(APPROX_PREFIX)
            addAll(PERIOD_WORDS)
            addAll(
                listOf(
                    "今天", "昨天", "前天", "明天", "后天", "周末", "上周", "下周", "这周", "本月",
                    "上月", "上个月", "原价", "打折", "折扣", "优惠", "备注", "说明", "记一下",
                    "那个", "这个", "然后", "就是", "麻烦", "帮我", "我们", "我的", "一下",
                    "元", "块", "毛", "角", "分", "钱", "付款", "收款", "消费", "一共", "总共",
                    "打包", "东西", "一点", "有点", "左右", "差不多",
                ),
            )
        }
    }

    private fun resolveCategory(
        text: String,
        merchant: String?,
        merchantRule: (String) -> TransactionCategory?,
    ): TransactionCategory? {
        if (merchant != null) {
            merchantRule(merchant)?.let { return it }
        }
        for ((keywords, category) in CATEGORY_KEYWORDS) {
            if (keywords.any { text.contains(it, ignoreCase = true) }) return category
        }
        // 中英混合：英文片段当商户名时，中文关键词表认不出，交给英文侧的品牌表。
        VoiceEnglishParser.categoryFor(merchant, text)?.let { return it }
        return null
    }

    // ---------------------------------------------------------------- 备注

    private val PURPOSE_PATTERNS = listOf(
        Regex("(?:吃|喝)(?:了)?(?:早|中|午|晚|夜)?(?:饭|餐|早饭|午饭|晚饭|夜宵|宵夜|下午茶|东西|早餐|午餐|晚餐)"),
        Regex("打车(?:回家|上班|下班|去公司|去学校|去机场|去车站)"),
    )

    private val EXPLICIT_NOTE = Regex("(?:备注|说明|记一下|记上)[:：]?\\s*([^，,。]{1,20})")

    private fun extractNote(text: String): String? {
        // 用户明确说了"备注"，就以它为准，不要再把"吃饭"这类目的短语拼进去
        EXPLICIT_NOTE.find(text)?.let { m ->
            val value = m.groupValues[1].trim()
            if (value.isNotBlank()) return value
        }
        val notes = mutableListOf<String>()
        for (pattern in PURPOSE_PATTERNS) {
            pattern.find(text)?.let { m -> if (m.value.isNotBlank()) notes += m.value }
        }
        if (notes.isEmpty()) return null
        return notes.distinct().joinToString("；")
    }

    // ---------------------------------------------------------------- 小工具

    private fun inSpan(span: Span, index: Int): Boolean = index in span.start until span.end

    private fun inAnySpan(spans: List<Span>, index: Int): Boolean = spans.any { inSpan(it, index) }

    private fun mask(text: String, spans: List<Span>): String {
        if (spans.isEmpty()) return text
        val chars = text.toCharArray()
        for (span in spans) {
            for (i in maxOf(0, span.start) until minOf(chars.size, span.end)) chars[i] = ' '
        }
        return String(chars)
    }
}
