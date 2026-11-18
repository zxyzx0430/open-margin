package com.bookkeeping.app.onboarding

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.AppLanguage

/** The first-run card is drawn in the activity, over the actual home screen. */
@Composable
internal fun OnboardingWelcome(onStart: () -> Unit, onSkip: () -> Unit) {
    val english = AppLanguage.current() == AppLanguage.ENGLISH
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.64f))
            .pointerInput(onStart, accessibility) {
                detectTapGestures(onDoubleTap = {
                    if (accessibility?.isTouchExplorationEnabled != true) onStart()
                }, onTap = {})
            }
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(8) { index ->
                    Box(Modifier.size(7.dp).background(
                        if (index == 0) Color.White else Color.White.copy(alpha = 0.35f), CircleShape,
                    ))
                }
            }
            TextButton(onClick = onSkip) { Text(if (english) "Skip" else "跳过", color = Color.White) }
        }
        Surface(
            modifier = Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 400.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (english) "New to Qingheng?" else "第一次用清衡？",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (english) "Take a quick look around and start keeping track." else "花一点时间，看看清衡怎么帮你记账。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text(if (english) "Show me around" else "带我看看")
                }
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text(if (english) "I'll explore" else "自己来")
                }
            }
        }
    }
}
