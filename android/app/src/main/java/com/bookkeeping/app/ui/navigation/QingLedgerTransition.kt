package com.bookkeeping.app.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.bookkeeping.app.ui.theme.QingMotionTokens
import com.bookkeeping.app.ui.theme.rememberReduceMotion

@OptIn(ExperimentalSharedTransitionApi::class)
internal data class QingLedgerTransition(val scope: SharedTransitionScope, val visibility: AnimatedVisibilityScope, val id: Long)

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalQingLedgerTransition = staticCompositionLocalOf<QingLedgerTransition?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.qingLedgerShared(id: Long, part: String, bounds: Boolean = false): Modifier {
    val transition = LocalQingLedgerTransition.current ?: return this
    if (transition.id != id || rememberReduceMotion()) return this
    val transform = BoundsTransform { _, _ -> QingMotionTokens.motionStandard.spec(false) }
    return with(transition.scope) {
        val state = rememberSharedContentState("ledger:$id:$part")
        if (bounds) this@qingLedgerShared.sharedBounds(
            sharedContentState = state, animatedVisibilityScope = transition.visibility,
            boundsTransform = transform, enter = EnterTransition.None, exit = ExitTransition.None,
        ) else this@qingLedgerShared.sharedElement(
            state, transition.visibility, boundsTransform = transform,
        )
    }
}
