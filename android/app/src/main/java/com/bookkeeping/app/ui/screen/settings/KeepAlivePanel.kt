package com.bookkeeping.app.ui.screen.settings

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.CaptureConnectionState
import com.bookkeeping.app.notification.CaptureHealth
import com.bookkeeping.app.notification.CaptureHealthSnapshot
import com.bookkeeping.app.notification.CaptureUptime
import com.bookkeeping.app.notification.KeepAliveService
import com.bookkeeping.app.rom.RomSettingsKind
import com.bookkeeping.app.rom.RomSettingsLauncher
import com.bookkeeping.app.ui.components.StatusDot
import com.bookkeeping.app.ui.components.StatusTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 常驻运行状态弹窗内容。标题由弹窗外框给一次，这里不再重复。
 *
 * 旧版把秒级计时、「绿点/红点」解释、Android 13 权限和 ROM 路径全塞进两段长文，
 * 读起来像说明书；现在只有四块：开关、一行连接状态、一句影响说明、一个折叠排障。
 * 秒没有展示意义（常驻通知已经只保留一行品牌文案），最多显示到分钟。
 */
@Composable
internal fun KeepAlivePanel(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowElapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var nowWall by remember { mutableStateOf(System.currentTimeMillis()) }
    var health by remember { mutableStateOf<CaptureHealthSnapshot?>(null) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowElapsed = SystemClock.elapsedRealtime()
                nowWall = System.currentTimeMillis()
                health = withContext(Dispatchers.IO) {
                    CaptureHealth.snapshot(context, KeepAliveService.startedAtMs)
                }
                // 只显示到分钟，没必要按秒刷新。
                delay(5_000L)
            }
        }
    }
    val snapshot = health
    val stateText = when (snapshot?.state) {
        CaptureConnectionState.CONNECTED -> tr("已连接")
        CaptureConnectionState.DISCONNECTED, CaptureConnectionState.PERMISSION_OFF -> tr("未连接")
        else -> tr("正在确认")
    }
    val tone = when (snapshot?.state) {
        CaptureConnectionState.CONNECTED -> StatusTone.POSITIVE
        CaptureConnectionState.DISCONNECTED, CaptureConnectionState.PERMISSION_OFF -> StatusTone.ERROR
        else -> StatusTone.NEUTRAL
    }
    val durationText = snapshot?.let { current ->
        when (current.state) {
            CaptureConnectionState.CONNECTED -> shortDuration(
                CaptureUptime.elapsed(
                    connectedAtMs = current.connectedAtMs,
                    connectedElapsedMs = current.connectedElapsedMs,
                    nowMs = nowWall,
                    nowElapsedMs = nowElapsed,
                ),
            )
            CaptureConnectionState.DISCONNECTED -> shortDuration(
                CaptureUptime.elapsed(
                    connectedAtMs = current.disconnectedAtMs,
                    connectedElapsedMs = current.disconnectedElapsedMs,
                    nowMs = nowWall,
                    nowElapsedMs = nowElapsed,
                ),
            )
            else -> ""
        }
    }.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        KeepAliveSwitchRow(enabled = enabled, onToggle = onToggle)
        ConnectionStatusBlock(
            tone = tone,
            stateText = stateText,
            durationText = durationText,
            showPermissionHint = tone == StatusTone.ERROR,
            onOpenNotificationSettings = onOpenNotificationSettings,
        )
        Text(
            text = tr("关闭后通知栏不再显示采集状态，采集本身不受影响。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeepAliveTroubleshooting()
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            FilledTonalButton(onClick = onOpenNotificationSettings) {
                Text(tr("打开系统通知设置"))
            }
        }
    }
}

/** 开关行：左标题 + 一行副标题，右侧 Switch。 */
@Composable
private fun KeepAliveSwitchRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = tr("常驻通知"),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = tr("在通知栏显示采集状态"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** 状态行：圆点 + 状态词 + 右侧时长；未连接时给一句可点的排查入口。 */
@Composable
private fun ConnectionStatusBlock(
    tone: StatusTone,
    stateText: String,
    durationText: String,
    showPermissionHint: Boolean,
    onOpenNotificationSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = 14.dp,
                end = 14.dp,
                top = 12.dp,
                bottom = if (showPermissionHint) 4.dp else 12.dp,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(tone = tone)
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (tone == StatusTone.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                if (durationText.isNotBlank()) {
                    Text(
                        text = durationText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showPermissionHint) {
                TextButton(
                    onClick = onOpenNotificationSettings,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = tr("去系统设置检查通知权限"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 折叠排障：默认收起，点开后三条短句，不再写整段说明。 */
@Composable
private fun KeepAliveTroubleshooting() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("通知栏没有显示？"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HelpBullet(tr("允许「常驻 / 持续通知」"))
                HelpBullet(tr("小米 / Redmi / POCO：应用信息 → 通知 → 永久通知"))
                HelpBullet(tr("开启自启动，电池策略设为「无限制」"))
                FilledTonalButton(
                    onClick = { RomSettingsLauncher.open(context, RomSettingsKind.AUTOSTART) },
                ) { Text(tr("打开自启动管理")) }
                FilledTonalButton(
                    onClick = { RomSettingsLauncher.open(context, RomSettingsKind.BATTERY) },
                ) { Text(tr("打开后台耗电设置")) }
            }
        }
    }
}

@Composable
private fun HelpBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 相对时长：到分钟为止，不显示秒。 */
private fun shortDuration(elapsedMs: Long): String {
    if (elapsedMs < 60_000L) return tr("刚刚")
    val minutes = elapsedMs / 60_000L
    return when {
        minutes < 60L -> String.format(Locale.getDefault(), tr("%d 分钟"), minutes)
        minutes < 24 * 60L -> String.format(
            Locale.getDefault(),
            tr("%d 小时 %02d 分钟"),
            minutes / 60L,
            minutes % 60L,
        )
        else -> String.format(Locale.getDefault(), tr("%d 天"), minutes / (24 * 60L))
    }
}
