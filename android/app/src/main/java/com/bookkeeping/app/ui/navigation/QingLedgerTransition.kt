package com.bookkeeping.app.ui.navigation

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
internal fun Modifier.qingLedgerShared(id: Long, part: String): Modifier {
    val transition = LocalQingLedgerTransition.current ?: return this
    if (transition.id != id || rememberReduceMotion()) return this
    val transform = BoundsTransform { _, _ -> QingMotionTokens.motionStandard.spec(false) }
    return with(transition.scope) {
        this@qingLedgerShared.sharedElement(
            rememberSharedContentState("ledger:$id:$part"),
            transition.visibility,
            boundsTransform = transform,
        )
    }
}
