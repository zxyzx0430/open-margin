package com.bookkeeping.app.ui.screen.settings

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.MerchantRules

/**
 * 商户分类记忆页的纯逻辑：排序、搜索过滤、保存校验、最近商户候选。
 *
 * 规则表里的 key 是 DataStore 存下来的归一化商户名（trim + 小写 + 去空格 + 去括号内容），
 * 所以这里一律用 [MerchantRules.normalize] 比较，用户在搜索框里输入「星巴克 (外卖)」
 * 也能命中「星巴克」这条规则。
 */
data class MerchantRuleEntry(
    val merchant: String,
    val category: TransactionCategory,
    val learningPaused: Boolean,
)

/** 最近商户候选；[hasRule] 用于标「已有规则」并排到后面。 */
data class MerchantCandidate(
    val merchant: String,
    val hasRule: Boolean,
)

/** 保存按钮为什么不能点；null 表示可以保存。 */
enum class MerchantRuleIssue {
    EMPTY_NAME,
    NO_CATEGORY,
    DUPLICATE,
}

fun merchantRuleEntries(
    rules: Map<String, TransactionCategory>,
    learningPaused: Set<String>,
): List<MerchantRuleEntry> {
    val pausedKeys = learningPaused.mapTo(mutableSetOf(), MerchantRules::normalize)
    return rules.entries
        .map { (merchant, category) ->
            MerchantRuleEntry(
                merchant = merchant,
                category = category,
                learningPaused = MerchantRules.normalize(merchant) in pausedKeys,
            )
        }
        .sortedBy { MerchantRules.normalize(it.merchant) }
}

/** 归一化后做包含匹配，忽略大小写、空格和括号内容。 */
fun filterMerchantRules(
    entries: List<MerchantRuleEntry>,
    query: String,
): List<MerchantRuleEntry> {
    val needle = MerchantRules.normalize(query)
    if (needle.isEmpty()) return entries
    return entries.filter { MerchantRules.normalize(it.merchant).contains(needle) }
}

/**
 * 编辑时 [originalMerchant] 是这条规则原来的商户名：把名字改成自己不算重复，
 * 改成另一个已有规则的名字才算，避免悄悄覆盖别人的分类。
 */
fun merchantRuleIssue(
    merchant: String,
    category: TransactionCategory?,
    existingMerchants: Set<String>,
    originalMerchant: String? = null,
): MerchantRuleIssue? {
    val key = MerchantRules.normalize(merchant)
    if (key.isEmpty()) return MerchantRuleIssue.EMPTY_NAME
    if (category == null) return MerchantRuleIssue.NO_CATEGORY
    val existingKeys = existingMerchants.mapTo(mutableSetOf(), MerchantRules::normalize)
    val originalKey = originalMerchant?.let(MerchantRules::normalize)
    if (key in existingKeys && key != originalKey) return MerchantRuleIssue.DUPLICATE
    return null
}

/**
 * 输入框下方的最近商户候选：先按输入过滤，再把已有规则的排到后面，
 * 最多 [limit] 个（面板里放 30 个 chip 会把分类选择挤出屏幕）。
 */
fun merchantCandidates(
    recentMerchants: List<String>,
    existingMerchants: Set<String>,
    query: String,
    limit: Int = 10,
): List<MerchantCandidate> {
    val existingKeys = existingMerchants.mapTo(mutableSetOf(), MerchantRules::normalize)
    val needle = MerchantRules.normalize(query)
    return recentMerchants
        .asSequence()
        .filter { it.isNotBlank() }
        .distinctBy { MerchantRules.normalize(it) }
        .filter { needle.isEmpty() || MerchantRules.normalize(it).contains(needle) }
        .map { merchant ->
            val key = MerchantRules.normalize(merchant)
            MerchantCandidate(merchant = merchant, hasRule = key in existingKeys)
        }
        // sortedBy 是稳定排序，同组内保持「最近优先」的原始顺序。
        .sortedBy { if (it.hasRule) 1 else 0 }
        .take(limit)
        .toList()
}
