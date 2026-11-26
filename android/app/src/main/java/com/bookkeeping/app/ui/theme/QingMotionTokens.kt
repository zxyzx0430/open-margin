package com.bookkeeping.app.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring

/** P0 definitions only. P2 will wire pointer input and system animation-scale observation. */
data class QingSpringToken(val dampingRatio: Float, val stiffness: Float) {
    fun <T> spec(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else spring(dampingRatio = dampingRatio, stiffness = stiffness)
}

object QingMotionTokens {
    val motionPress = QingSpringToken(1f, 900f)
    val motionStandard = QingSpringToken(0.92f, 400f)
    val motionGesture = QingSpringToken(0.90f, 380f)
    val motionSnappy = QingSpringToken(0.94f, 560f)
    const val cardPressedScale = 0.97f
    const val rowPressedScale = 0.985f
}
