package com.bookkeeping.app.ui.screen.transactions

/** Formats the list subtitle, showing an account instead of duplicating its source label. */
internal fun transactionSubtitle(
    categoryLabel: String,
    sourceLabel: String,
    account: String?,
    timeText: String,
): String {
    val paymentLabel = account?.trim()?.takeIf { it.isNotEmpty() } ?: sourceLabel
    return "$categoryLabel · $paymentLabel · $timeText"
}
