package com.bookkeeping.app.ui.screen.settings

import com.bookkeeping.app.i18n.tr
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bookkeeping.app.notification.accessibility.AccessibilityCaptureService
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.i18n.AppLanguage
import com.bookkeeping.app.update.GitHubReleaseUpdateChecker
import com.bookkeeping.app.update.UpdateComponentScope
import com.bookkeeping.app.update.UpdateVersionPolicy
import com.bookkeeping.app.sync.SyncSettingsCard
import com.bookkeeping.app.sync.WindowsFirstConnectionWizard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val keepAlive by viewModel.keepAliveEnabled.collectAsState()
    val context = LocalContext.current

    // 无障碍服务的"是否已被系统勾选"状态。
    // 用户从无障碍设置返回时（ON_RESUME）重新读一次。
    val lifecycleOwner = LocalLifecycleOwner.current
    var accessibilityEnabled by remember {
        mutableStateOf(AccessibilityCaptureService.isEnabled(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = AccessibilityCaptureService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(tr("设置")) }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle(tr("后台保活")) }
            item {
                KeepAliveCard(
                    enabled = keepAlive,
                    onToggle = viewModel::setKeepAlive,
                )
            }

            item { SectionTitle(tr("语言 / Language")) }
            item { LanguageCard() }

            item { SectionTitle(tr("关于与更新")) }
            item { AboutUpdateCard() }

            item { SectionTitle(tr("权限授权")) }
            item {
                PermissionCard(
                    title = tr("通知使用权"),
                    desc = tr("用于抓取支付宝/微信支付通知。必须授权，否则自动记账无效。"),
                    buttonText = tr("去授权"),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }
                    },
                )
            }
            item {
                PermissionCard(
                    title = tr("短信权限"),
                    desc = tr("用于读取银行交易短信。如果你不使用银行卡或不需要银行通道，可以不开。"),
                    buttonText = tr("应用权限设置"),
                    onClick = {
                        runCatching {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }
                    },
                )
            }
            item {
                AccessibilityCard(
                    enabled = accessibilityEnabled,
                    onOpen = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                )
            }

            item { SectionTitle(tr("交易双向同步（Windows / VPS）")) }
            item { WindowsFirstConnectionWizard() }
            item { SyncSettingsCard() }

            item { SectionTitle(tr("国产 ROM 后台白名单（重要）")) }
            item { RomGuideCard() }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun KeepAliveCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("常驻通知保活"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = if (enabled) tr("已开启：通知栏会有一条常驻提示")
                               else tr("未开启：节省一条通知，但可能被国产 ROM 杀掉"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )
            Text(
                text = tr("原理：开启后 app 会启动一个前台 Service，挂在通知栏。") +
                       tr("Android 系统不会随便杀正在运行前台服务的进程，从而保住通知监听不掉线。") +
                       tr("代价是通知栏多一条常驻提示。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    desc: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            FilledTonalButton(onClick = onClick) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun AccessibilityCard(enabled: Boolean, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.tertiaryContainer
                             else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (enabled) tr("无障碍兜底：已开启") else tr("无障碍兜底：未开启"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (enabled)
                    tr("微信转账/红包、支付宝转账入账等没有通知的场景会自动抓取。")
                else
                    tr("微信转账、微信红包、支付宝转账入账等场景，通知里不含金额，必须靠无障碍服务兜底。") +
                    tr("未开启会漏抓这些交易。"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            // 强提示：隐私边界
            Text(
                text = tr("隐私说明"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tr("无障碍权限会让本应用看到微信、支付宝两个 app 内的所有窗口内容。") +
                       tr("我们已通过 packageNames 限定仅监听这两个包名（不会读取你打开的其他 app），") +
                       tr("金额与商户信息默认只存在本机；若您主动开启 VPS 同步，则会上传结构化交易字段。"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            FilledTonalButton(onClick = onOpen) {
                Text(if (enabled) tr("去无障碍设置（管理/关闭）") else tr("去无障碍设置（开启）"))
            }
        }
    }
}

@Composable
private fun RomGuideCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = tr("为什么需要？"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tr("小米、华为、OPPO、vivo 等国产 ROM 默认会激进省电，把不常用的 app 进程彻底杀掉，") +
                       tr("导致支付通知到达时本应用没法被系统拉起，漏抓账单。") +
                       tr("需要手动把本应用加进各家的「允许后台运行」/「自启动」白名单。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            RomEntry(
                brand = tr("小米 / Redmi"),
                path = tr("设置 → 应用设置 → 应用管理 → 找到本应用 → 「自启动」打开 +「省电策略」选「无限制」"),
            )
            RomEntry(
                brand = tr("华为 / 荣耀"),
                path = tr("设置 → 应用 → 应用启动管理 → 找到本应用 → 关闭「自动管理」→ 手动管理三项全开"),
            )
            RomEntry(
                brand = tr("OPPO / 一加 / realme"),
                path = tr("设置 → 电池 → 应用耗电管理 → 找到本应用 → 允许后台运行 + 允许关联启动"),
            )
            RomEntry(
                brand = "vivo / iQOO",
                path = tr("设置 → 电池 → 后台应用管理 → 找到本应用 → 允许后台运行"),
            )
            RomEntry(
                brand = tr("三星"),
                path = tr("设置 → 设备维护 → 电池 → 未受监视应用 → 添加本应用"),
            )
            RomEntry(
                brand = tr("原生 Android (Pixel)"),
                path = tr("一般不用动。如有问题：设置 → 电池 → 电池优化 → 本应用 → 不优化"),
            )
        }
    }
}

@Composable
private fun RomEntry(brand: String, path: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = brand,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Switch only presentation locale; no sync settings or transaction data change. */
@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppLanguage.read(context)) }
    var error by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("界面语言"), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AppLanguage.CHINESE to "简体中文",
                    AppLanguage.ENGLISH to "English"
                ).forEach { (code, label) ->
                    FilterChip(
                        selected = current == code,
                        onClick = {
                            if (code != current) {
                                if (AppLanguage.set(context, code)) {
                                    current = code
                                    (context as? Activity)?.recreate()
                                } else error = true
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
            if (error) Text(tr("语言设置保存失败"),
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Read-only public version check. APK upgrades always remain user-initiated. */
@Composable
private fun AboutUpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includePrereleases by remember {
        mutableStateOf(UpdateVersionPolicy.CURRENT_TAG.contains("-rc."))
    }
    var checking by remember { mutableStateOf(false) }
    var result by remember {
        mutableStateOf<GitHubReleaseUpdateChecker.CheckResult?>(null)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr("当前版本") + ": " + BuildConfig.VERSION_NAME +
                    " (Build " + BuildConfig.VERSION_CODE + ")",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                tr("发布标识") + ": " + UpdateVersionPolicy.CURRENT_TAG,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("包含测试版本（RC）"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = includePrereleases,
                    onCheckedChange = {
                        includePrereleases = it
                        result = null
                    },
                    enabled = !checking,
                )
            }
            FilledTonalButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    result = null
                    scope.launch {
                        result = withContext(Dispatchers.IO) {
                            GitHubReleaseUpdateChecker.check(includePrereleases)
                        }
                        checking = false
                    }
                },
            ) {
                Text(if (checking) tr("正在检查更新…") else tr("检查更新"))
            }
            when (val state = result) {
                null -> Unit
                GitHubReleaseUpdateChecker.CheckResult.UpToDate ->
                    Text(tr("没有找到更新的 GitHub 发布版本；未检测电脑/VPS 的实际部署版本。"))
                GitHubReleaseUpdateChecker.CheckResult.Unavailable ->
                    Text(
                        tr("检查更新失败，请检查网络或稍后重试"),
                        color = MaterialTheme.colorScheme.error,
                    )
                is GitHubReleaseUpdateChecker.CheckResult.UpdateAvailable -> {
                    Text(tr("发现新版本：") + state.tag)
                    val (scopeTitle, scopeAdvice) = when (state.componentScope) {
                        UpdateComponentScope.ANDROID_ONLY ->
                            tr("仅 Android APP 更新") to tr("此次只需更新手机 APP；电脑/VPS 不需要因这个版本重新部署。")
                        UpdateComponentScope.SERVER_ONLY ->
                            tr("仅 Windows / VPS 更新") to tr("此次只更新电脑或 VPS 服务端；手机 APP 无需因这个版本重装。")
                        UpdateComponentScope.BOTH ->
                            tr("Android APP 与 Windows / VPS 都有更新") to tr("此次两个组件都要更新，请按发布说明的步骤执行。")
                        UpdateComponentScope.UNSPECIFIED ->
                            tr("更新范围未注明") to tr("不能确定该版本需要更新哪些组件，请先阅读发布说明。")
                    }
                    Text(
                        tr("待更新组件：") + scopeTitle,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(scopeAdvice, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tr("此处显示发行版本的更新范围，并未检测你电脑/VPS 的实际安装版本。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        // The checker constructs only github.com/zxyzx0430/open-margin
                        // release URLs from strictly parsed version tags.
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(state.releaseUrl))
                            )
                        }
                    }) {
                        Text(tr("查看 GitHub 发布页"))
                    }
                }
            }
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/zxyzx0430/open-margin/releases")
                        )
                    )
                }
            }) {
                Text(tr("浏览全部发布版本"))
            }
            Text(
                tr("检查更新仅访问项目的公开 GitHub Releases，不上传财务数据；更新需要手动下载安装。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
