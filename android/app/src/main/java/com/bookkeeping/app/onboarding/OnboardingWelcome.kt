package com.bookkeeping.app.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.AppLanguage

@Composable
internal fun OnboardingWelcome(
    onStart: () -> Unit,
    onSkip: () -> Unit,
    onEnableAutomaticEntry: () -> Unit,
    notificationAccessEnabled: Boolean,
) {
    val english = AppLanguage.current() == AppLanguage.ENGLISH
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
    ) {
        val shortScreen = maxHeight < 600.dp
        Column(
            Modifier.fillMaxSize()
                .then(if (shortScreen) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (english) "GETTING STARTED" else "新手必看",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                )
                TextButton(onClick = onSkip) { Text(if (english) "Skip" else "跳过") }
            }
            Column(
                Modifier.then(if (shortScreen) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState())),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (english) "Get started with Qingheng" else "开始使用清衡",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                WelcomeFeature(Icons.Outlined.Notifications,
                    if (english) "Automatic entries" else "自动记账",
                    if (english) "Allow notification access to recognize WeChat and Alipay payment notifications."
                    else "开启通知使用权后，识别微信、支付宝付款通知。")
                WelcomeFeature(Icons.Filled.Add,
                    if (english) "Add a transaction" else "随手记一笔",
                    if (english) "Tap + to enter a transaction, double-tap + to import screenshots, or touch and hold + to speak."
                    else "点击「＋」手动记账，双击「＋」导入截图，长按「＋」语音记账。")
                WelcomeFeature(Icons.Outlined.ReceiptLong,
                    if (english) "Review and edit" else "查看和修改账单",
                    if (english) "Tap a transaction to view details and its original screenshot, or edit time and category."
                    else "点击一笔账单，可以查看详情、扫描原图，或直接修改时间与分类。")
                WelcomeFeature(Icons.Outlined.CalendarMonth,
                    if (english) "Understand your spending" else "看清花费",
                    if (english) "Touch and hold a date, then drag to see spending; explore statistics and budgets."
                    else "在日历中长按日期并拖动，查看一段时间的花费；也可以查看统计和预算。")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (english) "We recommend importing your WeChat and Alipay orders to get started."
                         else "推荐通过导入微信与支付宝订单进行初始化",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (english) "Tap the import icon at the top right of the home screen."
                         else "点击首页右上角的导入图标",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (english) "Spotted a capture or duplicate issue?"
                         else "遇到漏记、重复或识别异常？",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (english) "Open Settings → Diagnostic Logs and tap Export to send logs directly to the developer."
                         else "在「设置 → 账单诊断日志」点击导出，可直接附带问题描述发给开发者排查。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (english) "Get started" else "开始使用", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onEnableAutomaticEntry, enabled = !notificationAccessEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (notificationAccessEnabled) {
                        if (english) "Automatic entry is enabled" else "自动记账已开启"
                    } else {
                        if (english) "Enable automatic entry →" else "开启自动记账 →"
                    })
                }
            }
        }
    }
}

@Composable
private fun WelcomeFeature(icon: ImageVector, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer,
            RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
