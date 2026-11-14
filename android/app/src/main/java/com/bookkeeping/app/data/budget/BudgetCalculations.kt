package com.bookkeeping.app.data.budget

import kotlin.math.roundToLong

enum class BudgetStatus { UNSET, NORMAL, NEAR_LIMIT, OVER }

data class BudgetSummary(
    val month: String,
    val budgetCents: Long,
    val spentCents: Long,
    val remainingCents: Long,
    val usedRatio: Float,
    val daysRemaining: Int,
    val dailyRemainingCents: Long,
    val forecastCents: Long,
    val projectedRemainingCents: Long,
    val status: BudgetStatus,
)

data class MonthComparison(
    val currentCents: Long,
    val previousCents: Long,
    /** null when the previous month has no comparable total. */
    val percentChange: Float?,
)

/** Pure monthly finance math; no Android or database dependencies. */
object BudgetCalculations {
    const val NEAR_LIMIT_RATIO: Float = 0.8f

    fun status(budgetCents: Long, spentCents: Long): BudgetStatus {
        if (budgetCents <= 0L) return BudgetStatus.UNSET
        if (spentCents > budgetCents) return BudgetStatus.OVER
        if (spentCents.toDouble() >= budgetCents.toDouble() * NEAR_LIMIT_RATIO) {
            return BudgetStatus.NEAR_LIMIT
        }
        return BudgetStatus.NORMAL
    }

    fun summary(
        month: String,
        budgetCents: Long,
        spentCents: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): BudgetSummary {
        val daysRemaining = BudgetMonth.daysRemaining(month, nowMs)
        val daysElapsed = BudgetMonth.daysElapsed(month, nowMs)
        val daysInMonth = BudgetMonth.daysInMonth(month)
        val remaining = (budgetCents - spentCents).coerceAtLeast(0L)
        val usedRatio = if (budgetCents > 0L) spentCents.toFloat() / budgetCents.toFloat() else 0f
        val dailyRemaining = if (daysRemaining > 0) remaining / daysRemaining else 0L
        val forecast = when {
            daysElapsed <= 0 -> 0L
            BudgetMonth.isCurrent(month, nowMs) && daysInMonth > 0 ->
                (spentCents.toDouble() * daysInMonth / daysElapsed).roundToLong()
            else -> spentCents
        }
        return BudgetSummary(
            month = month,
            budgetCents = budgetCents,
            spentCents = spentCents,
            remainingCents = remaining,
            usedRatio = usedRatio,
            daysRemaining = daysRemaining,
            dailyRemainingCents = dailyRemaining,
            forecastCents = forecast,
            projectedRemainingCents = budgetCents - forecast,
            status = status(budgetCents, spentCents),
        )
    }

    /** Matches the target stats screen: month total divided by calendar days. */
    fun averagePerDay(totalCents: Long, month: String): Long {
        val days = BudgetMonth.daysInMonth(month)
        return if (days <= 0) 0L else (totalCents.toDouble() / days).roundToLong()
    }

    fun comparison(currentCents: Long, previousCents: Long): MonthComparison {
        val percent = if (previousCents > 0L) {
            ((currentCents - previousCents).toDouble() * 100.0 / previousCents.toDouble()).toFloat()
        } else {
            null
        }
        return MonthComparison(currentCents, previousCents, percent)
    }
}
