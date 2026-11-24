package com.bookkeeping.app.ui.screen.stats

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType

/** Aggregated stats for one normalized payment account in the selected period. */
data class AccountStat(
    val accountKey: String,
    val displayName: String,
    val totalCents: Long,
    val count: Int,
    val percentage: Float,
    val isUncategorized: Boolean = false,
)

/**
 * Normalizes synonymous account labels (e.g. `微信支付` / `微信零钱` -> `微信`,
 * `支付宝余额` -> `支付宝`, blank -> `未归类账户`) so stats grouping and
 * ledger drilldown filtering agree on the same account buckets.
 */
object AccountNormalization {
    const val UNASSIGNED_ACCOUNT_KEY = "未归类账户"

    fun normalize(rawAccount: String?): String {
        val trimmed = rawAccount?.trim().orEmpty()
        if (trimmed.isEmpty()) return UNASSIGNED_ACCOUNT_KEY
        return when (trimmed) {
            "微信支付", "微信零钱", "微信" -> "微信"
            "支付宝余额", "支付宝", "花呗", "蚂蚁花呗" -> "支付宝"
            UNASSIGNED_ACCOUNT_KEY -> UNASSIGNED_ACCOUNT_KEY
            else -> when {
                trimmed.equals("WeChat", ignoreCase = true) ||
                    trimmed.equals("WeChat Pay", ignoreCase = true) -> "微信"
                trimmed.equals("Alipay", ignoreCase = true) ||
                    trimmed.equals("Huabei", ignoreCase = true) ||
                    trimmed.equals("Ant Credit Pay", ignoreCase = true) ||
                    trimmed.equals("Ant Check Later", ignoreCase = true) ||
                    trimmed.contains("花呗", ignoreCase = true) -> "支付宝"
                else -> trimmed
            }
        }
    }

    fun isUncategorized(rawAccount: String?): Boolean =
        normalize(rawAccount) == UNASSIGNED_ACCOUNT_KEY

    /**
     * Resolves a transaction's display account: prefers explicit [TransactionEntity.account],
     * falling back to note contents mentioning payment channels (e.g. 花呗, 支付宝, 微信) or
     * [TransactionEntity.source] (`WECHAT` -> 微信, `ALIPAY` -> 支付宝,
     * `BANK_SMS` -> 银行卡) so auto-captured bills with null account are properly attributed.
     */
    fun effectiveAccount(transaction: TransactionEntity): String {
        val explicit = transaction.account?.trim().orEmpty()
        if (explicit.isNotEmpty()) return normalize(explicit)

        val note = transaction.note.trim()
        if (note.isNotEmpty()) {
            if (note.contains("花呗", ignoreCase = true) ||
                note.contains("Huabei", ignoreCase = true) ||
                note.contains("支付宝", ignoreCase = true) ||
                note.contains("Alipay", ignoreCase = true)
            ) {
                return "支付宝"
            }
            if (note.contains("微信", ignoreCase = true) ||
                note.contains("WeChat", ignoreCase = true)
            ) {
                return "微信"
            }
        }

        return when (transaction.source.trim().uppercase()) {
            "WECHAT" -> "微信"
            "ALIPAY" -> "支付宝"
            "BANK_SMS" -> "银行卡"
            else -> UNASSIGNED_ACCOUNT_KEY
        }
    }

    fun matchesFilter(rawAccount: String?, filterAccount: String?): Boolean {
        if (filterAccount == null) return true
        return normalize(rawAccount) == normalize(filterAccount)
    }

    fun matchesFilter(transaction: TransactionEntity, filterAccount: String?): Boolean {
        if (filterAccount == null) return true
        return effectiveAccount(transaction) == normalize(filterAccount)
    }
}

/** Pure account aggregation helpers aligned with [StatsTransactionPolicy]. */
internal object AccountAggregation {
    const val DEFAULT_VISIBLE_LIMIT = 5

    fun accountStats(
        transactions: List<TransactionEntity>,
        type: TransactionType,
    ): List<AccountStat> {
        val filtered = StatsTransactionPolicy.filter(transactions, type)
        if (filtered.isEmpty()) return emptyList()
        val total = filtered.sumOf { it.amountCents }
        return filtered
            .groupBy { AccountNormalization.effectiveAccount(it) }
            .map { (key, list) ->
                val accountTotal = list.sumOf { it.amountCents }
                val uncategorized = key == AccountNormalization.UNASSIGNED_ACCOUNT_KEY
                AccountStat(
                    accountKey = key,
                    displayName = key,
                    totalCents = accountTotal,
                    count = list.size,
                    percentage = if (total > 0L) accountTotal.toFloat() / total.toFloat() else 0f,
                    isUncategorized = uncategorized,
                )
            }
            .sortedWith(
                compareBy<AccountStat> { it.isUncategorized }
                    .thenByDescending { it.totalCents }
                    .thenByDescending { it.count }
                    .thenBy { it.accountKey },
            )
    }

    /** Fraction in [0, 1] of matched transactions that have a resolved account. */
    fun coverageRatio(
        transactions: List<TransactionEntity>,
        type: TransactionType,
    ): Float {
        val filtered = StatsTransactionPolicy.filter(transactions, type)
        if (filtered.isEmpty()) return 0f
        val categorized = filtered.count {
            AccountNormalization.effectiveAccount(it) != AccountNormalization.UNASSIGNED_ACCOUNT_KEY
        }
        return categorized.toFloat() / filtered.size.toFloat()
    }

    /** True when strictly more than 90% of matched transactions have no resolved account. */
    fun isMostlyUncategorized(
        transactions: List<TransactionEntity>,
        type: TransactionType,
    ): Boolean {
        val filtered = StatsTransactionPolicy.filter(transactions, type)
        if (filtered.isEmpty()) return false
        val uncategorizedCount = filtered.count {
            AccountNormalization.effectiveAccount(it) == AccountNormalization.UNASSIGNED_ACCOUNT_KEY
        }
        return uncategorizedCount * 10 > filtered.size * 9
    }
}
