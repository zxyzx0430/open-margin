package com.bookkeeping.app.voice

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.notification.CategoryClassifier
import java.util.Calendar
import java.util.TimeZone

/**
 * 英文口述账单解析器：和 [VoiceTransactionParser] 完全同构的纯函数，
 * 只处理"没有汉字"的输入。
 *
 * 分工（见 [VoiceTransactionParser.parse] 开头的分流）：
 * - 有汉字 → 中文解析器，英文片段当商户名（`starbucks 花了 36`）。
 * - 无汉字、有拉丁字母或 `$` → 这里。
 *
 * 设计取舍与中文版一致：
 * - 全部规则是查表 + 正则，确定性，可离线单测；
 * - 金额一律整数分，不经过 Float/Double；
 * - 没说的不编：没说钟点就 [VoiceDraft.preciseTime] = false，没说日期就
 *   [VoiceDraft.dateKnown] = false；
 * - 解析不出来就返回 null + warning，不猜一个"看起来合理"的数。
 *
 * 已知不支持（都有对应 warning 或直接留空）：
 * - 外币（euros/pounds/yen/won/rupees）只标 [VoiceWarning.CURRENCY_UNSUPPORTED]，不换算。
 * - 一句话多笔（"coffee 5 and taxi 12"）只取第一个金额，并标 [VoiceWarning.MULTI_AMOUNT]。
 * - 日期区间（"from Monday to Friday"）、相对周（"last Monday"）不认。
 * - 超长拼写数字（million 及以上）不认。
 * - 钟点（"at 7pm"）不认：只认时段词，绝不编造 00:00。
 */
object VoiceEnglishParser {

    // ---------------------------------------------------------------- 入口

    private val HAN = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    private val LATIN = Regex("[A-Za-z]")

    /**
     * 语言分流：没有汉字、且出现拉丁字母或 `$`，就走英文解析。
     *
     * 刻意保守：
     * - 有汉字一律 false（中英混合按中文处理，英文片段留给商户名提取）；
     * - 纯数字 `36.5` 返回 false，仍走中文路径 —— 中文路径本来就认得这种写法，
     *   这样纯数字输入的既有行为一个字都不用改。
     * - 空串返回 false，交给中文路径产出统一的"空草稿"。
     */
    fun looksEnglish(raw: String): Boolean {
        if (raw.isBlank()) return false
        if (HAN.containsMatchIn(raw)) return false
        return LATIN.containsMatchIn(raw) || raw.contains('$')
    }

    fun parse(
        raw: String,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
        merchantRule: (String) -> TransactionCategory? = { null },
    ): VoiceDraft {
        val original = raw.trim()
        val text = fold(original)
        val todayStart = startOfDay(nowMs, zone)

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

        val time = resolveTime(text, todayStart, zone)
        val timeSpans = listOfNotNull(time?.span)
        val amountScan = scanAmounts(text, timeSpans)
        val accountHit = findAccount(text)
        val amountSpans = amountScan.hits.map { it.span }
        val merchantHit = findMerchant(
            folded = text,
            original = original,
            excluded = timeSpans + amountSpans + listOfNotNull(accountHit?.second),
        )

        val masked = mask(
            text,
            timeSpans + amountSpans + listOfNotNull(accountHit?.second, merchantHit?.second),
        )
        val (type, typeUncertain) = detectType(masked)

        val merchant = merchantHit?.first
        val category = (
            merchant?.let { merchantRule(it) }
                ?: categoryFor(merchant, text)
            )
            ?: if (type == VoiceType.TRANSFER) TransactionCategory.TRANSFER else TransactionCategory.OTHER

        val choice = amountScan.hits.firstOrNull()

        val warnings = linkedSetOf<VoiceWarning>()
        if (choice == null) warnings += VoiceWarning.AMOUNT_MISSING
        if (choice?.approximate == true) warnings += VoiceWarning.AMOUNT_APPROXIMATE
        if (amountScan.hits.size > 1) warnings += VoiceWarning.MULTI_AMOUNT
        if (amountScan.foreignCurrency != null) warnings += VoiceWarning.CURRENCY_UNSUPPORTED
        if (time?.label != null) warnings += VoiceWarning.DATE_APPROXIMATE
        if (merchant == null || category == TransactionCategory.OTHER) {
            warnings += VoiceWarning.MERCHANT_UNKNOWN
        }
        if (typeUncertain) warnings += VoiceWarning.TYPE_UNCERTAIN
        if (type == VoiceType.REFUND) warnings += VoiceWarning.REFUND_NEEDS_CONFIRM
        if (type == VoiceType.TRANSFER) warnings += VoiceWarning.TRANSFER_NEEDS_CONFIRM

        val occurredAtMs = when {
            time != null -> time.dayStartMs
            else -> nowMs
        }

        return VoiceDraft(
            amountCents = choice?.cents,
            type = type,
            merchant = merchant,
            category = category,
            occurredAtMs = occurredAtMs,
            dateKnown = time?.dateKnown ?: false,
            preciseTime = false,
            timeOfDayLabel = time?.label,
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
    )

    private data class AmountScan(
        val hits: List<AmountHit>,
        val foreignCurrency: String? = null,
    )

    private data class Word(val text: String, val start: Int, val end: Int)

    private data class TimeHit(
        val span: Span,
        val dayStartMs: Long,
        val label: String?,
        val dateKnown: Boolean = true,
    )

    // ---------------------------------------------------------------- 文本归一化

    /**
     * 逐字符折叠：大写转小写、弯引号转直引号、全角空格转半角。
     * 故意 1:1 替换（不用 lowercase()），保证折叠后的下标和原文完全对齐，
     * 商户名才能从原文切出来保留大小写。
     */
    private fun fold(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when (ch) {
                    in 'A'..'Z' -> ch + 32
                    '\u2019', '\u2018', '\u02BC' -> '\''
                    '\u201C', '\u201D' -> '"'
                    '\u3000' -> ' '
                    '\u00A0' -> ' '
                    else -> ch
                },
            )
        }
        return sb.toString()
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '_'

    // ---------------------------------------------------------------- 金额：阿拉伯数字

    private const val MAX_CENTS = 100_000_000L * 100L

    private val NUMERIC_AMOUNT = Regex(
        "(?<![\\w.])(\\\$)?\\s?(\\d{1,3}(?:,\\d{3})+|\\d{1,9})(?:\\.(\\d{1,2}))?(?![\\d])(?![.,]\\d)",
    )

    private val CURRENCY_SUFFIX = Regex(
        "^[\\s-]*(dollars?|bucks?|usd|yuan|rmb|cny|kuai|quid|euros?|pounds?|yen|won|rupees?|cents?)\\b",
    )

    private const val FOREIGN = "euro,euros,pound,pounds,yen,won,rupee,rupees,gbp,eur,jpy"

    private fun isForeign(unit: String): Boolean =
        unit in FOREIGN.split(",")

    private fun isCny(unit: String): Boolean =
        unit in setOf("dollar", "dollars", "buck", "bucks", "usd", "yuan", "rmb", "cny", "kuai", "quid")

    /**
     * "12 dollars (and) 50 cents" / "12 dollars (and) fifty cents" 的尾巴。
     * 第 1 组是数字，第 2 组是拼写词（最多两词，如 "thirty five"）。
     */
    private val CENTS_TAIL = Regex(
        "^\\s*(?:and\\s+)?(?:(\\d{1,2})|([a-z]+(?:[\\s-]+[a-z]+)?))\\s*cents?\\b",
    )

    /** 解析 [CENTS_TAIL] 的匹配结果，返回分数值；不合法返回 null。 */
    private fun centsTailValue(match: MatchResult): Long? {
        val digits = match.groupValues[1]
        if (digits.isNotEmpty()) return digits.toLongOrNull()
        val words = match.groupValues[2].split(' ', '-').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val value = spelledInt(words) ?: return null
        return if (value in 0L..99L) value else null
    }

    /**
     * 数字后面紧跟这些词，说明是时长/数量/时间点而不是金额：
     * `3 pm`、`2 coffees`、`20 minutes`、`5%`、`1st`。
     */
    private val NON_AMOUNT_SUFFIX = Regex(
        "^\\s*(?:%|percent|per\\s*cent|o'?clock|a\\.?m\\.?|p\\.?m\\.?|" +
            "minutes?|mins?|hours?|hrs?|seconds?|secs?|days?|weeks?|months?|years?|" +
            "times?|x\\b|km|kms|kg|kgs|g\\b|mg|gb|mb|tb|ml|liters?|litres?|l\\b|" +
            "people|persons?|guests?|items?|pieces?|pcs|coffees?|teas?|beers?|drinks?|" +
            "cups?|bottles?|bags?|boxes?|tickets?|seats?|rooms?|nights?|" +
            "miles?|kilometers?|kilometres?|blocks?|stops?|" +
            "st\\b|nd\\b|rd\\b|th\\b)",
    )

    private val APPROX_WORDS = Regex("(?:\\babout|\\baround|\\broughly|\\bapproximately|\\balmost|\\bnearly|~)\\s*$")

    private fun scanAmounts(text: String, excluded: List<Span>): AmountScan {
        // 先跑拼写数字：它的 span 要挡住后面的数字扫描，避免
        // "twelve dollars fifty cents" 里那个 50 被重复当成一笔。
        val spelled = scanSpelledAmounts(text, excluded)
        val hits = spelled.first.toMutableList()
        var foreign: String? = spelled.second
        val excludedPlus = excluded + spelled.first.map { it.span }

        for (m in NUMERIC_AMOUNT.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (inAnySpan(excludedPlus, start)) continue
            if (looksLikeDateOrCode(text, start, end)) continue

            val suffixStart = minOf(text.length, end + 24)
            val suffix = text.substring(end, suffixStart)
            if (NON_AMOUNT_SUFFIX.containsMatchIn(suffix)) continue

            val unit = CURRENCY_SUFFIX.find(suffix)?.groupValues?.get(1)
            if (unit != null && isForeign(unit)) {
                // 外币不换算：整笔金额留空，只给 warning，绝不按 1:1 当人民币。
                foreign = unit
                continue
            }

            val intPart = m.groupValues[2].replace(",", "").toLongOrNull() ?: continue
            val fracRaw = m.groupValues[3]
            val fracCents = when (fracRaw.length) {
                0 -> 0L
                1 -> (fracRaw.toLongOrNull() ?: continue) * 10L
                else -> fracRaw.toLongOrNull() ?: continue
            }
            var cents = when {
                // "99 cents" 本身就是分，不能再乘 100
                unit == "cent" || unit == "cents" -> intPart
                else -> intPart * 100L + fracCents
            }
            var spanEnd = end

            // "12 dollars (and) 50 cents" 合成一笔，而不是拆成两笔报 MULTI_AMOUNT
            if (unit != null && isCny(unit)) {
                val tail = CENTS_TAIL.find(text.substring(spanEnd))
                if (tail != null && cents % 100L == 0L) {
                    centsTailValue(tail)?.let {
                        cents += it
                        spanEnd += tail.range.last + 1
                    }
                }
            }
            if (cents < 0L || cents > MAX_CENTS) continue

            hits += AmountHit(
                span = Span(start, spanEnd),
                cents = cents,
                approximate = APPROX_WORDS.containsMatchIn(prefix(text, start, 20)),
            )
        }

        if (hits.isEmpty()) return AmountScan(emptyList(), foreign)

        // 一句话多笔：中文版取第一个，这里保持一致（第一个通常是主句金额）。
        return AmountScan(hits.sortedBy { it.span.start }, foreign)
    }

    /** "7-11"、"11/17"、"2026-11-17"、"#36"、比分 "3:2" 都不是金额。 */
    private fun looksLikeDateOrCode(text: String, start: Int, end: Int): Boolean {
        if (start > 0) {
            val prev = text[start - 1]
            if (prev == '/' || prev == '-' || prev == '#' || prev == '_') return true
            if (prev == ':' && start > 1 && text[start - 2].isDigit()) return true
        }
        if (end < text.length) {
            val next = text[end]
            if (next == '/' || next == '%' || next == '#' || next == '_') return true
            if ((next == '-' || next == ':') && end + 1 < text.length && text[end + 1].isDigit()) return true
        }
        return false
    }

    private fun prefix(text: String, index: Int, size: Int): String =
        text.substring(maxOf(0, index - size), index)

    // ---------------------------------------------------------------- 金额：拼写数字

    private val ONES: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )

    private val TENS: Map<String, Int> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private val SCALES: Map<String, Long> = mapOf("hundred" to 100L, "thousand" to 1000L)

    private val SPELLED_CURRENCY = setOf(
        "dollar", "dollars", "buck", "bucks", "yuan", "rmb", "usd", "cny", "quid", "kuai",
        "cent", "cents",
    )

    private val SPELLED_FOREIGN = setOf(
        "euro", "euros", "pound", "pounds", "yen", "won", "rupee", "rupees",
    )

    private fun isNumberWord(w: String): Boolean = ONES.containsKey(w) || TENS.containsKey(w) || SCALES.containsKey(w)

    private fun wordTokens(text: String): List<Word> {
        val out = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            if (!isWordChar(text[i])) {
                i++
                continue
            }
            var j = i
            while (j < text.length && isWordChar(text[j])) j++
            out += Word(text.substring(i, j), i, j)
            i = j
        }
        return out
    }

    /** "thirty six" → 36；"one hundred and five"（and 已剔除）→ 105；不合法返回 null。 */
    private fun spelledInt(words: List<String>): Long? {
        if (words.isEmpty()) return null
        var total = 0L
        var current = 0L
        var sawAny = false
        for (w in words) {
            when {
                ONES.containsKey(w) -> {
                    current += ONES.getValue(w).toLong()
                    sawAny = true
                }
                TENS.containsKey(w) -> {
                    // "six thirty" 不是 36，是 6.30 —— 让上层按"整数 + 分"拆开
                    if (current % 10L != 0L) return null
                    current += TENS.getValue(w).toLong()
                    sawAny = true
                }
                w == "hundred" -> {
                    if (current == 0L) current = 1L
                    current *= 100L
                    sawAny = true
                }
                w == "thousand" -> {
                    if (current == 0L) current = 1L
                    total += current * 1000L
                    current = 0L
                    sawAny = true
                }
                else -> return null
            }
        }
        return if (sawAny) total + current else null
    }

    /**
     * 拼写金额 = "整数部分" + 可选的"分"部分：
     * - `twenty five` → 25.00
     * - `thirty six fifty` → 36.50（尾词 fifty 直接当分）
     * - `one hundred and five eighty` → 105.80
     * - `six thirty` → 6.30
     *
     * 返回 (分, 用掉几个词)。
     */
    private fun spelledCents(words: List<String>): Pair<Long, Int>? {
        for (headLen in words.size downTo 1) {
            val head = spelledInt(words.subList(0, headLen)) ?: continue
            if (headLen == words.size) return head * 100L to headLen
            val tailLen = minOf(2, words.size - headLen)
            val tail = spelledInt(words.subList(headLen, headLen + tailLen)) ?: continue
            if (tail > 99L) continue
            return head * 100L + tail to headLen + tailLen
        }
        return null
    }

    private fun scanSpelledAmounts(text: String, excluded: List<Span>): Pair<List<AmountHit>, String?> {
        val tokens = wordTokens(text)
        val hits = mutableListOf<AmountHit>()
        var foreign: String? = null
        var idx = 0
        while (idx < tokens.size) {
            if (!isNumberWord(tokens[idx].text)) {
                idx++
                continue
            }
            val run = mutableListOf<Pair<Int, String>>()
            var j = idx
            while (j < tokens.size && run.size < 6) {
                val bare = tokens[j].text
                if (isNumberWord(bare)) {
                    run += j to bare
                    j++
                    continue
                }
                if (bare == "and" && run.isNotEmpty()) {
                    val next = tokens.getOrNull(j + 1)?.text
                    if (next != null && isNumberWord(next)) {
                        j++
                        continue
                    }
                }
                if (bare == "a" && run.isEmpty()) {
                    val next = tokens.getOrNull(j + 1)?.text
                    if (next != null && SCALES.containsKey(next)) {
                        run += j to "one"
                        j++
                        continue
                    }
                }
                break
            }
            if (run.isEmpty()) {
                idx++
                continue
            }

            val start = tokens[run.first().first].start
            if (inAnySpan(excluded, start)) {
                idx = run.last().first + 1
                continue
            }

            val words = run.map { it.second }
            val split = spelledCents(words)
            if (split == null) {
                idx = run.last().first + 1
                continue
            }
            val (cents, used) = split
            val lastTokenIdx = run[used - 1].first
            val end = tokens[lastTokenIdx].end
            val nextBare = tokens.getOrNull(lastTokenIdx + 1)?.text
            val nextBare2 = tokens.getOrNull(lastTokenIdx + 2)?.text

            val unitIdx = when {
                nextBare != null && nextBare in SPELLED_CURRENCY -> lastTokenIdx + 1
                nextBare2 != null && nextBare2 in SPELLED_CURRENCY -> lastTokenIdx + 2
                else -> -1
            }
            val foreignUnit = when {
                nextBare != null && nextBare in SPELLED_FOREIGN -> nextBare
                nextBare2 != null && nextBare2 in SPELLED_FOREIGN -> nextBare2
                else -> null
            }
            if (foreignUnit != null) {
                foreign = foreignUnit
                idx = lastTokenIdx + 1
                continue
            }
            val hasUnit = unitIdx >= 0

            // 和中文版对齐：口语数字小于 10 时必须带货币词，否则更像数量（"two coffees"）
            if (!hasUnit && cents < 1000L) {
                idx = lastTokenIdx + 1
                continue
            }
            if (cents > MAX_CENTS) {
                idx = lastTokenIdx + 1
                continue
            }

            var spanEnd = end
            var total = cents
            var consumed = lastTokenIdx
            if (hasUnit) {
                // "twelve dollars (and) fifty cents" 合成一笔
                val unitEnd = tokens[unitIdx].end
                val unitWord = tokens[unitIdx].text
                if (unitWord == "cent" || unitWord == "cents") {
                    // "ninety nine cents" → 0.99，而不是 99.00
                    total /= 100L
                } else {
                    val tail = CENTS_TAIL.find(text.substring(unitEnd))
                    if (tail != null && total % 100L == 0L) {
                        val tailCents = centsTailValue(tail)
                        if (tailCents != null) {
                            total += tailCents
                            spanEnd = unitEnd + tail.range.last + 1
                            consumed = tokens.indexOfLast { it.end <= spanEnd }.coerceAtLeast(unitIdx)
                        }
                    }
                }
            }
            hits += AmountHit(
                span = Span(start, spanEnd),
                cents = total,
                approximate = APPROX_WORDS.containsMatchIn(prefix(text, start, 20)),
            )
            idx = consumed + 1
        }
        return hits to foreign
    }

    // ---------------------------------------------------------------- 方向

    private val REFUND_PATTERN = Regex(
        "\\b(refund(?:ed|s|ing)?|money\\s+back|cash\\s*back|got\\s+refunded)\\b",
    )
    private val TRANSFER_PATTERN = Regex(
        "\\b(transfer(?:red|s|ring)?|transferred|sent|send|sending|wire[sd]?|wired|venmo(?:d)?|zelle(?:d)?)\\b",
    )
    private val INCOME_PATTERN = Regex(
        "\\b(got\\s+paid|get\\s+paid|was\\s+paid|received?|receiving|salary|paycheck|payday|income|" +
            "earned?|earning|bonus|reimbursed?|reimbursement|deposited?|dividend|interest\\s+paid)\\b",
    )
    private val EXPENSE_PATTERN = Regex(
        "\\b(spent|spend|spending|paid|pay|paying|bought|buy|buying|cost|costs|costed|" +
            "charged?|charging|purchase[ds]?|ordering|ordered)\\b",
    )

    private fun detectType(masked: String): Pair<VoiceType, Boolean> {
        if (REFUND_PATTERN.containsMatchIn(masked)) return VoiceType.REFUND to false
        if (TRANSFER_PATTERN.containsMatchIn(masked)) return VoiceType.TRANSFER to false
        if (INCOME_PATTERN.containsMatchIn(masked)) return VoiceType.INCOME to false
        if (EXPENSE_PATTERN.containsMatchIn(masked)) return VoiceType.EXPENSE to false
        return VoiceType.EXPENSE to true
    }

    // ---------------------------------------------------------------- 商户

    private val PREPOSITION = Regex("\\b(at|from|on)\\s+|@\\s*")

    /**
     * 商户名里出现这些词就说明已经越界（介词短语结束）。
     * 全是折叠后的小写形式。
     */
    private val MERCHANT_STOP = setOf(
        "for", "using", "with", "via", "by", "and", "but", "or", "then", "so",
        "today", "yesterday", "tonight", "tomorrow", "morning", "afternoon", "evening",
        "night", "noon", "midnight", "last", "this", "next", "now", "please", "thanks", "thank",
        "my", "our", "your", "his", "her", "their", "its", "it", "me", "us", "them",
        "card", "cash", "credit", "debit", "wechat", "alipay", "account", "app", "online",
        "is", "was", "were", "are", "be", "been", "in", "to", "of", "a", "an",
    )

    /** `on` 后面跟这些词时不是商户，而是"在…上花的钱/时间"或账户。 */
    private val ON_REJECT_FIRST = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "weekend", "lunch", "dinner", "breakfast", "brunch", "supper", "coffee", "drinks",
        "groceries", "gas", "rent", "taxi", "food", "it", "that", "this", "the", "a", "an",
        "my", "our", "your", "credit", "debit", "card", "cash", "wechat", "alipay",
    )

    private val ARTICLES = setOf("the", "a", "an")

    private fun findMerchant(
        folded: String,
        original: String,
        excluded: List<Span>,
    ): Pair<String, Span>? {
        for (m in PREPOSITION.findAll(folded)) {
            val prep = m.groupValues[1].ifBlank { "@" }.lowercase()
            val start = m.range.last + 1
            if (start >= folded.length) continue
            if (inAnySpan(excluded, start)) continue
            // "look at me" 里的 at 前面是字母 → 不是介词短语？\b 已挡住。
            val captured = captureName(folded, start) ?: continue
            val firstBare = captured.tokens.firstOrNull()?.text ?: continue
            if (prep == "on" && firstBare in ON_REJECT_FIRST) continue
            val name = cleanName(original.substring(captured.start, captured.end)) ?: continue
            return name to Span(captured.start, captured.end)
        }
        // 没介词时兜底认品牌："starbucks 36" / "kfc 25"
        return findBrand(folded, original, excluded)
    }

    private data class Capture(val tokens: List<Word>, val start: Int, val end: Int)

    private fun captureName(text: String, from: Int): Capture? {
        var i = from
        while (i < text.length && text[i] == ' ') i++
        val tokens = mutableListOf<Word>()
        while (i < text.length && tokens.size < 4) {
            if (!isWordChar(text[i])) break
            var j = i
            while (j < text.length && isWordChar(text[j])) j++
            val word = text.substring(i, j)
            if (tokens.isNotEmpty() && word in MERCHANT_STOP) break
            if (word.all { it.isDigit() }) break
            tokens += Word(word, i, j)
            i = j
            while (i < text.length && text[i] == ' ') i++
            if (i < text.length && text[i] in ",.!?;:()[]\"") {
                break
            }
        }
        // 去掉开头冠词："at the Apple Store" → "Apple Store"
        while (tokens.size > 1 && tokens.first().text in ARTICLES) tokens.removeAt(0)
        if (tokens.isEmpty()) return null
        return Capture(tokens, tokens.first().start, tokens.last().end)
    }

    private fun cleanName(raw: String): String? {
        var s = raw.trim().trim(',', '.', '!', '?', ';', ':', '"', '\'', '(', ')', '[', ']', '-')
        s = s.trim()
        s = s.removePrefix("the ").removePrefix("The ")
        s = s.trim()
        if (s.length < 2) return null
        if (s.all { it.isDigit() }) return null
        val lower = s.lowercase()
        if (lower in MERCHANT_STOP || lower in ARTICLES) return null
        return s
    }

    private val EN_BRANDS: List<String> = listOf(
        "starbucks", "mcdonald's", "mcdonalds", "burger king", "pizza hut", "kfc", "subway",
        "chipotle", "domino's", "dominos", "dunkin", "tim hortons", "costa coffee", "costa",
        "luckin", "five guys", "amazon", "taobao", "tmall", "jd.com", "walmart", "costco",
        "target", "ikea", "uniqlo", "zara", "h&m", "nike", "adidas", "sephora", "netflix",
        "spotify", "steam", "uber", "lyft", "didi", "shell", "chevron", "7-11", "7-eleven",
        "apple store", "apple", "whole foods", "trader joe's", "aldi", "carrefour", "hema",
    ).sortedByDescending { it.length }

    private fun findBrand(folded: String, original: String, excluded: List<Span>): Pair<String, Span>? {
        for (brand in EN_BRANDS) {
            var from = 0
            while (true) {
                val idx = folded.indexOf(brand, from)
                if (idx < 0) break
                val end = idx + brand.length
                val boundedStart = idx == 0 || !isWordChar(folded[idx - 1])
                val boundedEnd = end >= folded.length || !isWordChar(folded[end])
                if (boundedStart && boundedEnd && !inAnySpan(excluded, idx)) {
                    val name = cleanName(original.substring(idx, end)) ?: break
                    return name to Span(idx, end)
                }
                from = idx + 1
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 账户

    private val ACCOUNTS: List<Pair<String, String>> = listOf(
        "wechat pay" to "微信",
        "weixin pay" to "微信",
        "wechat" to "微信",
        "weixin" to "微信",
        "alipay" to "支付宝",
        "ali pay" to "支付宝",
        "credit card" to "银行卡",
        "debit card" to "银行卡",
        "bank card" to "银行卡",
        "cash" to "现金",
        "card" to "银行卡",
    ).sortedByDescending { it.first.length }

    private fun findAccount(text: String): Pair<String, Span>? {
        for ((word, display) in ACCOUNTS) {
            val m = Regex("\\b" + Regex.escape(word) + "\\b").find(text) ?: continue
            return display to Span(m.range.first, m.range.last + 1)
        }
        return null
    }

    // ---------------------------------------------------------------- 时间

    private data class TimeRule(val phrase: String, val dayOffset: Int, val label: String?)

    private val TIME_RULES: List<TimeRule> = listOf(
        TimeRule("yesterday morning", -1, "早上"),
        TimeRule("yesterday afternoon", -1, "下午"),
        TimeRule("yesterday evening", -1, "晚上"),
        TimeRule("yesterday", -1, null),
        TimeRule("last night", -1, "晚上"),
        TimeRule("last evening", -1, "晚上"),
        TimeRule("this morning", 0, "早上"),
        TimeRule("this afternoon", 0, "下午"),
        TimeRule("this evening", 0, "晚上"),
        TimeRule("tonight", 0, "晚上"),
        TimeRule("today", 0, null),
        TimeRule("noon", 0, "中午"),
        TimeRule("midnight", 0, "半夜"),
    )

    private fun resolveTime(text: String, todayStart: Long, zone: TimeZone): TimeHit? {
        var best: TimeHit? = null
        for (rule in TIME_RULES) {
            val pattern = Regex("\\b" + Regex.escape(rule.phrase) + "\\b")
            for (m in pattern.findAll(text)) {
                val span = Span(m.range.first, m.range.last + 1)
                val current = best
                if (current == null || span.start < current.span.start ||
                    (span.start == current.span.start && span.end > current.span.end)
                ) {
                    best = TimeHit(
                        span = span,
                        dayStartMs = plusDays(todayStart, rule.dayOffset, zone),
                        label = rule.label,
                    )
                }
            }
        }
        return best
    }

    // ---------------------------------------------------------------- 分类

    /**
     * 英文关键词兜底表。只在内置 [CategoryClassifier] 没命中时使用：
     * 分类器认中文店名（星巴克/麦当劳），英文口述得先翻译成同义关键词。
     */
    private val EN_KEYWORDS: List<Pair<List<String>, TransactionCategory>> = listOf(
        listOf(
            "starbucks", "coffee", "latte", "cafe", "café", "mcdonald", "burger", "kfc",
            "pizza", "sushi", "noodle", "ramen", "subway", "chipotle", "domino", "taco",
            "bakery", "dessert", "cake", "bubble tea", "milk tea", "restaurant", "lunch",
            "dinner", "breakfast", "brunch", "doordash", "uber eats", "takeout", "food",
            "dunkin", "costa", "luckin", "kebab", "dim sum", "hotpot", "five guys",
        ) to TransactionCategory.FOOD,
        listOf(
            "uber", "lyft", "taxi", "cab", "metro", "train", "bus", "fuel", "gasoline",
            "parking", "toll", "flight", "airline", "didi", "shell", "chevron", "petrol",
            "oil change", "car wash", "ticket machine",
        ) to TransactionCategory.TRANSPORT,
        listOf(
            "amazon", "taobao", "ebay", "walmart", "costco", "target", "ikea", "uniqlo",
            "zara", "h&m", "nike", "adidas", "mall", "groceries", "grocery", "supermarket",
            "clothes", "shoes", "shopping", "sephora", "apple store", "electronics",
        ) to TransactionCategory.SHOPPING,
        listOf(
            "netflix", "spotify", "steam", "movie", "cinema", "theater", "theatre", "game",
            "concert", "ticket", "disney", "youtube premium", "playstation", "xbox",
            "nintendo", "ktv", "bowling",
        ) to TransactionCategory.ENTERTAINMENT,
        listOf("rent", "landlord", "mortgage", "property", "maintenance fee", "deposit for the flat") to
            TransactionCategory.HOUSING,
        listOf("electricity", "water bill", "internet", "wifi", "phone bill", "utility", "utilities", "gas bill") to
            TransactionCategory.UTILITIES,
        listOf("pharmacy", "hospital", "clinic", "doctor", "dentist", "medicine", "prescription", "cvs", "walgreens") to
            TransactionCategory.HEALTH,
        listOf("tuition", "course", "udemy", "coursera", "textbook", "book", "school", "class", "exam fee") to
            TransactionCategory.EDUCATION,
        listOf("salary", "paycheck", "payroll", "income") to TransactionCategory.SALARY,
        listOf("bonus", "reimbursement", "commission") to TransactionCategory.BONUS,
        listOf("gift", "present") to TransactionCategory.GIFT,
        listOf("stock", "invest", "crypto", "fund", "dividend") to TransactionCategory.INVESTMENT,
        listOf("insurance", "premium") to TransactionCategory.INSURANCE,
        listOf("transfer", "sent to", "wire") to TransactionCategory.TRANSFER,
    )

    /**
     * 商户 → 分类：先问 [CategoryClassifier]（用户规则 / 学习纠正 / 内置关键词），
     * 再用英文关键词表兜底。
     *
     * 中文路径也复用这里：中英混合时英文片段可能是商户名
     * （"在 Starbucks 花了 36"），中文关键词表认不出，但这里认得出。
     */
    fun categoryFor(merchant: String?, text: String): TransactionCategory? {
        merchant?.let { CategoryClassifier.rememberedCategory(it, text) }?.let { return it }
        return englishCategory(merchant, text)
    }

    private fun englishCategory(merchant: String?, text: String): TransactionCategory? {
        if (merchant != null) {
            for ((keywords, category) in EN_KEYWORDS) {
                if (keywords.any { merchant.contains(it, ignoreCase = true) }) return category
            }
        }
        for ((keywords, category) in EN_KEYWORDS) {
            if (keywords.any { text.contains(it, ignoreCase = true) }) return category
        }
        return null
    }

    // ---------------------------------------------------------------- 备注

    private val EXPLICIT_NOTE = Regex("\\bnotes?\\s*:?\\s*(.{1,40})")
    private val PURPOSE_NOTE = Regex(
        "\\bfor\\s+(lunch|dinner|breakfast|brunch|supper|coffee|drinks?|groceries|" +
            "gas|rent|parking|a\\s+taxi|the\\s+bus|medicine|the\\s+movies)\\b",
    )

    private fun extractNote(text: String): String? {
        EXPLICIT_NOTE.find(text)?.let { m ->
            val value = m.groupValues[1].trim().trimEnd('.', '!', '?', ',', ';')
            if (value.isNotBlank()) return value
        }
        PURPOSE_NOTE.find(text)?.let { m ->
            val value = m.groupValues[1].trim()
            if (value.isNotBlank()) return value
        }
        return null
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
}
