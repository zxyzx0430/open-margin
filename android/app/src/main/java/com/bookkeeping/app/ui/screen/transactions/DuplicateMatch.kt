package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.data.local.entity.TransactionEntity
import java.text.Normalizer
import kotlin.math.abs

/**
 * 保存前「疑似重复」检查用的最小信息。
 *
 * 只带规则真正用到的五个字段，不引 Room / Compose，纯 JVM 单测就能覆盖
 * （见 DuplicateRuleTest）。真正的候选来自 DAO 的只读查询
 * [com.bookkeeping.app.data.local.dao.TransactionDao.findAmountCandidatesAround]。
 */
data class DuplicateCandidate(
    val id: Long,
    val amountCents: Long,
    val merchant: String,
    val timestampMs: Long,
    val source: String,
)

/** DAO 行 → 检查用候选；映射放在这里，ViewModel 不用重复抄字段。 */
fun TransactionEntity.toDuplicateCandidate(): DuplicateCandidate =
    DuplicateCandidate(
        id = id,
        amountCents = amountCents,
        merchant = merchant,
        timestampMs = timestamp,
        source = source,
    )

/**
 * 手工记账保存前的疑似重复判定。
 *
 * 只提示、不拦截、不合并、不自动去重：命中以后由用户在弹框里选「取消」或
 * 「仍然记这一笔」，两笔都会原样保留。自动采集链路的 reviewStatus /
 * duplicateOfId 逻辑不经过这里，互不影响。
 *
 * 命中条件（金额必须完全相同）：
 * 1. 时间在前后 1 天内，且商户 normalize 后相等或一方包含另一方；
 * 2. 或时间在前后 10 分钟内（商户为空、看不清、写错都不影响）。
 *
 * 也就是说：商户写得不一样时只有 10 分钟内算疑似重复；商户对得上时窗口放宽到 1 天。
 */
object DuplicateRule {

    /** 同商户分支的时间窗口：前后各 1 天。 */
    const val SAME_MERCHANT_WINDOW_MS: Long = 24L * 60L * 60L * 1000L

    /** 不看商户分支的时间窗口：前后各 10 分钟。 */
    const val SAME_AMOUNT_WINDOW_MS: Long = 10L * 60L * 1000L

    /**
     * 包含匹配的最短长度：单个字符的商户名（"1"、"A"）跟谁都像，
     * 只允许完全相等，避免把「1号店」和「11号店」判成同一家。
     */
    private const val MIN_CONTAINMENT_LENGTH = 2

    /** normalize 时直接丢掉的符号：括号只丢符号本身，括号里的门店名保留。 */
    private val DROPPED_SYMBOLS: Set<Char> = setOf(
        '(', ')', '（', '）', '[', ']', '【', '】', '{', '}', '<', '>', '《', '》', '〈', '〉',
        '「', '」', '『', '』', '·', '・', '.', ',', '，', '。', '、', ':', '：', ';', '；',
        '!', '！', '?', '？', '\'', '"', '“', '”', '‘', '’', '-', '－', '—', '–', '_',
        '/', '\\', '|', '*', '#', '~', '+', '＋', '&', '@',
    )

    /**
     * 商户名归一：全角转半角（NFKC）→ 小写 → 去掉空白与标点/括号符号。
     *
     * 这样「星巴克(国贸店)」「星巴克（国贸店）」「 STARBUCKS 国贸店 」这类
     * 同店不同写法才能对上；括号里的门店名保留，不同门店不会被抹平成同一家。
     */
    fun normalizeMerchant(raw: String): String {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val builder = StringBuilder(folded.length)
        for (ch in folded) {
            when {
                ch.isWhitespace() -> Unit
                ch in DROPPED_SYMBOLS -> Unit
                else -> builder.append(ch.lowercaseChar())
            }
        }
        return builder.toString()
    }

    /**
     * 在候选里找一条最像的。
     *
     * 多条都命中时取时间差最小的一条；时间差相同取更早的一条，再相同取 id 小的，
     * 保证同样的输入永远给同一个结果（不用 Random、不依赖数据库返回顺序）。
     *
     * @param list 已按金额 + 时间窗口粗筛过的候选（DAO 查询结果）
     * @return 命中的候选；没有命中返回 null
     */
    fun find(
        list: List<DuplicateCandidate>,
        amountCents: Long,
        merchant: String,
        timestampMs: Long,
    ): DuplicateCandidate? {
        // 金额为 0 或负数的记录本身就该被表单拦住，不参与重复判定。
        if (amountCents <= 0L) return null
        val key = normalizeMerchant(merchant)
        return list
            .asSequence()
            .filter { it.amountCents == amountCents }
            .filter { candidate ->
                val distance = abs(candidate.timestampMs - timestampMs)
                if (distance <= SAME_AMOUNT_WINDOW_MS) {
                    true
                } else {
                    distance <= SAME_MERCHANT_WINDOW_MS &&
                        merchantsMatch(key, normalizeMerchant(candidate.merchant))
                }
            }
            .minWithOrNull(
                compareBy(
                    { abs(it.timestampMs - timestampMs) },
                    { it.timestampMs },
                    { it.id },
                ),
            )
    }

    /** 归一后相等，或较长的一方包含较短的一方；空商户永远不算同商户。 */
    private fun merchantsMatch(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return shorter.length >= MIN_CONTAINMENT_LENGTH && longer.contains(shorter)
    }
}
