package com.bookkeeping.app.onboarding

/** Keeps the bubble within the safe content area even when target coordinates are off-screen. */
internal fun onboardingBubbleTop(
    targetTop: Int,
    bubbleHeight: Int,
    contentHeight: Int,
    headerHeight: Int,
    gap: Int,
): Int {
    val maxTop = (contentHeight - bubbleHeight).coerceAtLeast(0)
    val minTop = headerHeight.coerceIn(0, maxTop)
    return (targetTop - bubbleHeight - gap).coerceIn(minTop, maxTop)
}
