package com.bookkeeping.app.onboarding

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import com.bookkeeping.app.ui.screen.transactions.AddTransactionDialog

/** Checks the separate overlay window against a real Material bottom-sheet window. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class OnboardingOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sheetInputKeepsFocusUnderOverlay() {
        var target by mutableStateOf<OnboardingTarget?>(null)
        var savedCents by mutableStateOf<Long?>(null)
        compose.setContent {
            AddTransactionDialog(
                onDismiss = {},
                onConfirm = { cents, _, _, _, _, _, _ -> savedCents = cents; 1L },
                onPanelPositioned = { coordinates, host ->
                    target = OnboardingTarget(
                        Rect(coordinates.positionOnScreen(), Size(
                            coordinates.size.width.toFloat(), coordinates.size.height.toFloat(),
                        )),
                        host,
                    )
                },
            )
            target?.let {
                OnboardingOverlay(
                    step = 1,
                    target = it,
                    title = "Tour",
                    message = "Example",
                    skipLabel = "Skip",
                    nextLabel = "Next",
                    onSkip = {},
                    onNext = {},
                )
            }
        }
        compose.onNodeWithText("Tour").assertIsDisplayed()
        compose.onNodeWithContentDescription("一句话记账").performClick().assertIsFocused()
            .performTextInput("午饭 25 微信")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(5_000) { savedCents != null }
        assertEquals(2500L, savedCents)
    }
}
