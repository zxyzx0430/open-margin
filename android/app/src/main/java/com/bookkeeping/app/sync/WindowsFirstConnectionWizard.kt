package com.bookkeeping.app.sync

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Optional onboarding. Existing sync settings remain intact until explicit confirmation. */
@Composable
fun WindowsFirstConnectionWizard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE) }
    var expanded by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var testResult by remember { mutableStateOf<WindowsConnectionPolicy.Result?>(null) }
    var verifiedServer by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Windows 首次连接向导", style = MaterialTheme.typography.titleMedium)
            Text("3 步连接自己的电脑。已使用 VPS 的用户无需运行此向导。")
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起向导" else "开始连接 Windows")
            }
            if (!expanded && message.isNotBlank()) Text(message)
            if (expanded) {
                Text("步骤 ${step + 1}/3", style = MaterialTheme.typography.titleSmall)
                when (step) {
                    0 -> {
                        Text("① 电脑已安装 Docker Desktop，清衡服务已启动。")
                        Text("② 电脑和手机已安装并登录同一 Tailscale 私有网络。")
                        Text("③ 已在电脑本机注册 Wallos 首位账户，并开启 API-only Serve。")
                        Text("④ 复制安装器显示的 HTTPS 地址及设备同步密钥。")
                        Text("电脑关机时仍可在手机离线记账，但暂时不能同步。")
                        Button(onClick = { step = 1 }) { Text("下一步：连接服务器") }
                    }
                    1 -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it; testResult = null; verifiedServer = ""; message = "" },
                            label = { Text("Tailscale HTTPS 地址") },
                            placeholder = { Text("https://电脑名.网络名.ts.net/finance") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it; testResult = null; verifiedServer = ""; message = "" },
                            label = { Text("设备同步密钥（不会显示）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("连接测试只读取账本身份与版本，不会创建或修改交易。")
                        if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotBlank()) Text(message)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { step = 0 }, enabled = !testing) { Text("上一步") }
                            Button(enabled = !testing, onClick = {
                                val address = url.trim().trimEnd('/')
                                val preflight = WindowsConnectionPolicy.preflight(address, token)
                                if (preflight != WindowsConnectionPolicy.Result.READY) {
                                    testResult = preflight
                                    message = connectionExplanation(preflight)
                                    return@Button
                                }
                                if (!SyncEndpointPolicy.canChangeServer(
                                        prefs.getString("url", "") ?: "", address,
                                        prefs.getLong("last_synced_id", 0L),
                                        prefs.getLong("last_pull_revision", 0L),
                                        prefs.getLong("last_success_ms", 0L)
                                    )) {
                                    message = "手机已有另一台服务器的账本，不能直接切换；请先完成备份和迁移。"
                                    return@Button
                                }
                                testing = true
                                message = "正在验证 HTTPS、设备密钥和服务器账本身份…"
                                scope.launch {
                                    val probe = withContext(Dispatchers.IO) {
                                        probeWindowsServer(
                                            address, token.trim(),
                                            prefs.getString("sync_server_id", null),
                                            prefs.getLong("last_pull_revision", 0L)
                                        )
                                    }
                                    testing = false
                                    testResult = probe.first
                                    verifiedServer = probe.second
                                    message = connectionExplanation(probe.first)
                                }
                            }) { Text("测试连接") }
                        }
                        if (testResult == WindowsConnectionPolicy.Result.READY) {
                            Button(onClick = { step = 2; confirmed = false }) { Text("下一步：确认启用") }
                        }
                    }
                    else -> {
                        Text("连接成功，已核对服务器账本身份。")
                        Text("服务器标识：${verifiedServer.take(8)}…")
                        Text("启用后手机将自动上传本地交易并下载服务器变更。")
                        Text("不会同步 Wallos 的订阅配置和预算设置。")
                        Row {
                            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                            Text("我确认这是自己的 Windows 账本，并同意开始双向同步。")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { step = 1 }, enabled = !testing) { Text("返回") }
                            Button(enabled = confirmed && !testing, onClick = {
                                if (testResult != WindowsConnectionPolicy.Result.READY ||
                                    verifiedServer.isBlank()) {
                                    message = "请重新测试连接"
                                    step = 1
                                    return@Button
                                }
                                try {
                                    TransactionSyncWorker.configure(context, url.trim().trimEnd('/'), token.trim(), true)
                                    message = "同步已开启，正在请求首次同步。后续可以在双向同步卡片查看状态。"
                                    expanded = false
                                } catch (e: IllegalArgumentException) {
                                    message = e.message ?: "保存配置失败，请检查服务器地址"
                                }
                            }) { Text("启用双向同步") }
                        }
                    }
                }
                if (message.isNotBlank() && step == 2) Text(message)
            }
        }
    }
}

private fun connectionExplanation(result: WindowsConnectionPolicy.Result): String = when (result) {
    WindowsConnectionPolicy.Result.READY -> "连接成功：HTTPS、设备密钥和账本身份均已验证。"
    WindowsConnectionPolicy.Result.INVALID_ADDRESS -> "请输入完整的 Tailscale HTTPS 地址，格式为 https://设备名.网络名.ts.net/finance"
    WindowsConnectionPolicy.Result.INVALID_TOKEN -> "设备密钥至少需要 16 位，请从 Windows secrets/backend.env 读取。"
    WindowsConnectionPolicy.Result.UNAUTHORIZED -> "服务器拒绝访问。请检查设备密钥是否正确。"
    WindowsConnectionPolicy.Result.ROUTE_MISSING -> "找不到同步接口。请检查 Windows API-only 网关和 Tailscale Serve。"
    WindowsConnectionPolicy.Result.WRONG_LEDGER -> "服务器账本与手机已同步账本不符，已阻止连接；请核对迁移。"
    WindowsConnectionPolicy.Result.INVALID_RESPONSE -> "服务器返回的账本身份不完整，请确认清衡后端版本。"
    WindowsConnectionPolicy.Result.UNAVAILABLE -> "无法连接服务器。请检查电脑、Docker、Tailscale 和手机 VPN 状态。"
}

private fun probeWindowsServer(
    base: String,
    token: String,
    expectedIdentity: String?,
    cursor: Long
): Pair<WindowsConnectionPolicy.Result, String> {
    val conn = try {
        (URL("$base/api/v2/sync/changes?since=$cursor&limit=1").openConnection() as HttpURLConnection)
    } catch (_: Exception) {
        return WindowsConnectionPolicy.Result.UNAVAILABLE to ""
    }
    try {
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 12000
        conn.setRequestProperty("X-Device-Token", token)
        val status = conn.responseCode
        if (status != 200) return WindowsConnectionPolicy.response(status, null) to ""
        val body = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val identity = body.optString("server_id", "")
        val decision = SyncServerIdentityPolicy.evaluate(
            expectedIdentity, cursor, identity, body.optLong("head_revision", -1L)
        )
        return WindowsConnectionPolicy.response(status, decision) to identity
    } catch (_: Exception) {
        return WindowsConnectionPolicy.Result.UNAVAILABLE to ""
    } finally {
        conn.disconnect()
    }
}
