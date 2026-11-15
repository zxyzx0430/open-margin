package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory

enum class RuleStage { PRE, DEFAULT, POST }

/**
 * A declarative classification rule. Conditions are ANDed; keyword lists are
 * ORed inside one field. A rule only matches; the classifier decides the final
 * category by stage and specificity.
 */
data class CategoryRule(
    val id: String,
    val stage: RuleStage,
    /** Larger wins inside the same stage. */
    val specificity: Int,
    val category: TransactionCategory,
    val merchantKeywords: List<String> = emptyList(),
    val contextKeywords: List<String> = emptyList(),
    val directions: Set<ClassificationDirection> = emptySet(),
    val kinds: Set<ClassificationKind> = emptySet(),
    val sourceCategories: Set<String> = emptySet(),
    val stop: Boolean = false,
    val reason: String,
)
