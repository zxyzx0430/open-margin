package com.bookkeeping.app.ui.navigation

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.bookkeeping.app.ui.theme.QingMotionTokens
import com.bookkeeping.app.ui.theme.rememberReduceMotion

@OptIn(ExperimentalSharedTransitionApi::class)
internal data class QingLedgerTransition(
    val scope: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val id: Long,
)

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalQingLedgerTransition = staticCompositionLocalOf<QingLedgerTransition?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.qingLedgerContainer(id: Long): Modifier {
    val transition = LocalQingLedgerTransition.current ?: return this
    if (transition.id != id || rememberReduceMotion()) return this
    val transform = BoundsTransform { _, _ -> QingMotionTokens.motionStandard.spec(false) }
    return with(transition.scope) {
        this@qingLedgerContainer.sharedBounds(
            sharedContentState = rememberSharedContentState("ledger-container:$id"),
            animatedVisibilityScope = transition.visibility,
            boundsTransform = transform,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(18.dp)),
        )
    }
}
