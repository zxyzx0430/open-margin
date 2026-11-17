package com.bookkeeping.app.sync

import com.bookkeeping.app.i18n.tr
import android.content.Context
import androidx.compose.foundation.BorderStroke
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
internal fun WindowsFirstConnectionWizard(
    initialPayload: PairingPayload? = null,
    openManualEntry: Boolean = false,
    onConnected: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE) }
    var expanded by remember(initialPayload, openManualEntry) {
        mutableStateOf(initialPayload != null || openManualEntry)
    }
    var step by remember(initialPayload, openManualEntry) {
        mutableIntStateOf(if (initialPayload != null || openManualEntry) 1 else 0)
    }
    var url by remember(initialPayload) {
        mutableStateOf(initialPayload?.url ?: (prefs.getString("url", "") ?: ""))
    }
    var token by remember(initialPayload) {
        mutableStateOf(initialPayload?.token ?: (prefs.getString("token", "") ?: ""))
    }
    var testResult by remember { mutableStateOf<WindowsConnectionPolicy.Result?>(null) }
    var verifiedServer by remember { mutableStateOf("") }
    // server_id read from the QR. Null means the fields are typed manually and
    // the probe trusts the identity already stored on the phone (if any).
    var scannedServerId by remember(initialPayload) { mutableStateOf(initialPayload?.serverId) }
    var testing by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun syncHistory() = PairingFlowPolicy.History(
        url = prefs.getString("url", "") ?: "",
        serverId = prefs.getString("sync_server_id", null),
        lastUploadedId = prefs.getLong("last_synced_id", 0L),
        lastPulledRevision = prefs.getLong("last_pull_revision", 0L),
        lastSuccessMs = prefs.getLong("last_success_ms", 0L),
        lastResultCode = prefs.getString(TransactionSyncWorker.LAST_RESULT_CODE, null),
    )

    /** Read-only identity probe shared by the typed and scanned paths. */
    fun startProbe(address: String, tokenValue: String, qrServerId: String?) {
        val preflight = WindowsConnectionPolicy.preflight(address, tokenValue)
        if (preflight != WindowsConnectionPolicy.Result.READY) {
            testResult = preflight
            message = connectionExplanation(preflight)
            return
        }
        if (PairingFlowPolicy.evaluatePreProbe(syncHistory(), address, qrServerId) ==
            PairingFlowPolicy.PreProbe.MIGRATION_REQUIRED) {
            testResult = WindowsConnectionPolicy.Result.WRONG_LEDGER
            verifiedServer = ""
            message = tr("手机已有成功同步记录，但这次配对的地址或账本身份与历史不同。为避免跨账本覆盖交易，已阻止配对；请先备份原账本并完成显式迁移。")
            return
        }
        val checkedToken = tokenValue.trim()
        val expectedIdentity = qrServerId ?: prefs.getString("sync_server_id", null)
        val checkedCursor = prefs.getLong("last_pull_revision", 0L)
        testing = true
        message = tr("正在验证 HTTPS、设备密钥和服务器账本身份…")
        scope.launch {
            val probe = withContext(Dispatchers.IO) {
                probeWindowsServer(address, checkedToken, expectedIdentity, checkedCursor)
            }
            testing = false
            // Never accept a result from a request for previous
            // credentials if inputs changed before it completed.
            if (!WindowsConnectionPolicy.isSameAttempt(address, checkedToken, url, token)) {
                testResult = null
                verifiedServer = ""
                message = tr("连接参数已改变，请重新测试")
            } else {
                val decision = PairingFlowPolicy.evaluateProbe(
                    qrServerId, probe.first, probe.second
                )
                testResult = decision
                verifiedServer = probe.second
                message = if (qrServerId != null &&
                    decision == WindowsConnectionPolicy.Result.WRONG_LEDGER) {
                    tr("二维码里的账本身份与服务器返回的不一致，已阻止连接。")
                } else {
                    connectionExplanation(decision)
                }
            }
        }
    }

    LaunchedEffect(initialPayload) {
        val payload = initialPayload ?: return@LaunchedEffect
        testResult = null
        verifiedServer = ""
        confirmed = false
        message = tr("已读取配对二维码，正在做只读验证…")
        startProbe(payload.url, payload.token, payload.serverId)
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Windows 首次连接向导"), style = MaterialTheme.typography.titleMedium)
            Text(tr("3 步连接自己的电脑。已使用 VPS 的用户无需运行此向导。"))
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) tr("收起向导") else tr("开始连接 Windows"))
            }
            if (!expanded && message.isNotBlank()) Text(message)
            if (expanded) {
                Text("${tr("步骤")} ${step + 1}/3", style = MaterialTheme.typography.titleSmall)
                when (step) {
                    0 -> {
                        Text(tr("① 电脑已安装 Docker Desktop，清衡服务已启动。"))
                        Text(tr("② 电脑已开启 Tailscale Funnel，手机不需要安装 Tailscale。"))
                        Text(tr("③ 手机用自己的网络或常用 VPN 即可访问 https://电脑名.网络名.ts.net/finance。"))
                        Text(tr("④ 在电脑完成页点「扫码连接」，或手动填写显示的地址和设备同步密钥。"))
                        Text(tr("电脑关机时仍可在手机离线记账，但暂时不能同步。"))
                        Button(onClick = { step = 1 }) { Text(tr("下一步：连接服务器")) }
                    }
                    1 -> {
                        Text(tr("最快的方式：扫描电脑完成页上的配对二维码，会自动填入地址和密钥，再经只读验证后才允许启用。"))
                        QrPairingControls(
                            enabled = !testing,
                            onPayload = { payload, _ ->
                                url = payload.url
                                token = payload.token
                                scannedServerId = payload.serverId
                                testResult = null
                                verifiedServer = ""
                                confirmed = false
                                message = tr("已读取配对二维码，正在做只读验证…")
                                startProbe(payload.url, payload.token, payload.serverId)
                            },
                            onRejected = { reason -> message = pairingRejectMessage(reason) },
                            onFailure = { failure ->
                                message = when (failure) {
                                    QrScanFailure.CAMERA_DENIED ->
                                        tr("未获得相机权限，无法扫码。可改用「从相册选择二维码」，或在系统设置中允许相机权限。")
                                    QrScanFailure.IMAGE_UNREADABLE ->
                                        tr("所选图片中没有识别到二维码，请换一张清晰的二维码截图。")
                                    QrScanFailure.IMAGE_ACCESS_DENIED ->
                                        tr("所选图片无法读取（权限或格式问题），请重新选择。")
                                }
                            },
                        )
                        OutlinedTextField(
                            value = url,
                            onValueChange = {
                                url = it; testResult = null; verifiedServer = ""
                                scannedServerId = null; message = ""
                            },
                            label = { Text(tr("Tailscale HTTPS 地址")) },
                            placeholder = { Text(tr("https://电脑名.网络名.ts.net/finance")) },
                            singleLine = true,
                            enabled = !testing,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = {
                                token = it; testResult = null; verifiedServer = ""
                                scannedServerId = null; message = ""
                            },
                            label = { Text(tr("设备同步密钥（不会显示）")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !testing,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(tr("连接测试只读取账本身份与版本，不会创建或修改交易。"))
                        if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotBlank()) Text(message)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { step = 0 }, enabled = !testing) { Text(tr("上一步")) }
                            Button(enabled = !testing, onClick = {
                                startProbe(url.trim().trimEnd('/'), token, scannedServerId)
                            }) { Text(tr("测试连接")) }
                        }
                        if (testResult == WindowsConnectionPolicy.Result.READY) {
                            Button(onClick = { step = 2; confirmed = false }) { Text(tr("下一步：确认启用")) }
                        }
                    }
                    else -> {
                        Text(tr("连接成功，已核对服务器账本身份。"))
                        Text("${tr("服务器标识：")}${verifiedServer.take(8)}…")
                        Text(tr("启用后手机将自动上传本地交易并下载服务器变更。"))
                        Text(tr("不会同步 Wallos 的订阅配置和预算设置。"))
                        Row {
                            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                            Text(tr("我确认这是自己的 Windows 账本，并同意开始双向同步。"))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { step = 1 }, enabled = !testing) { Text(tr("返回")) }
                            Button(enabled = confirmed && !testing, onClick = {
                                if (testResult != WindowsConnectionPolicy.Result.READY ||
                                    verifiedServer.isBlank()) {
                                    message = tr("请重新测试连接")
                                    step = 1
                                    return@Button
                                }
                                // A scanned QR pins the ledger identity even if
                                // prefs changed while this wizard was open.
                                if (scannedServerId != null && scannedServerId != verifiedServer) {
                                    message = tr("二维码里的账本身份与验证结果不一致，已阻止启用。")
                                    step = 1
                                    return@Button
                                }
                                if (PairingFlowPolicy.evaluatePreProbe(
                                        syncHistory(), url, scannedServerId
                                    ) != PairingFlowPolicy.PreProbe.ALLOWED) {
                                    message = tr("检测到历史同步记录与本次配对不一致，已阻止启用；请先备份并完成显式迁移。")
                                    step = 1
                                    return@Button
                                }
                                try {
                                    TransactionSyncWorker.configure(context, url.trim().trimEnd('/'), token.trim(), true)
                                    message = tr("同步已开启，正在请求首次同步。后续可以在双向同步卡片查看状态。")
                                    expanded = false
                                    onConnected()
                                } catch (e: IllegalArgumentException) {
                                    message = e.message ?: tr("保存配置失败，请检查服务器地址")
                                }
                            }) { Text(tr("启用双向同步")) }
                        }
                    }
                }
                if (message.isNotBlank() && step == 2) Text(message)
            }
        }
    }
}

private fun connectionExplanation(result: WindowsConnectionPolicy.Result): String = when (result) {
    WindowsConnectionPolicy.Result.READY -> tr("连接成功：HTTPS、设备密钥和账本身份均已验证。")
    WindowsConnectionPolicy.Result.INVALID_ADDRESS -> tr("请输入完整的 Tailscale HTTPS 地址，格式为 https://设备名.网络名.ts.net/finance")
    WindowsConnectionPolicy.Result.INVALID_TOKEN -> tr("设备密钥至少需要 16 位，请从 Windows secrets/backend.env 读取。")
    WindowsConnectionPolicy.Result.UNAUTHORIZED -> tr("服务器拒绝访问。请检查设备密钥是否正确。")
    WindowsConnectionPolicy.Result.ROUTE_MISSING -> tr("找不到同步接口。请检查 Windows API-only 网关和 Tailscale Serve。")
    WindowsConnectionPolicy.Result.WRONG_LEDGER -> tr("服务器账本与手机已同步账本不符，已阻止连接；请核对迁移。")
    WindowsConnectionPolicy.Result.INVALID_RESPONSE -> tr("服务器返回的账本身份不完整，请确认清衡后端版本。")
    WindowsConnectionPolicy.Result.UNAVAILABLE -> tr("无法连接服务器。请检查电脑、Docker、Tailscale 和手机 VPN 状态。")
}

private fun pairingRejectMessage(reason: PairingRejectReason): String = when (reason) {
    PairingRejectReason.EMPTY -> tr("二维码内容为空，请重新扫描。")
    PairingRejectReason.OVERSIZED -> tr("二维码内容过长，已拒绝。请扫描电脑完成页上的配对二维码。")
    PairingRejectReason.MALFORMED -> tr("这不是清衡配对二维码（内容格式不正确）。")
    PairingRejectReason.UNSUPPORTED_VERSION -> tr("配对二维码版本不受支持，请把手机 App 更新到最新版本。")
    PairingRejectReason.WRONG_KIND -> tr("这不是清衡的配对二维码。")
    PairingRejectReason.INVALID_URL -> tr("二维码里的服务器地址无效：需要 https://设备名.网络名.ts.net/finance，且不能带端口、参数或用户名。")
    PairingRejectReason.INVALID_TOKEN -> tr("二维码里的设备密钥无效（至少 16 位，不能包含空白字符）。")
    PairingRejectReason.INVALID_SERVER_ID -> tr("二维码里的账本身份无效（至少 16 位）。")
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
        val remoteHead = (body.opt("head_revision") as? Number)?.toLong() ?: -1L
        val remoteCursor = (body.opt("cursor") as? Number)?.toLong() ?: -1L
        val items = body.optJSONArray("items")
        val hasMore = body.opt("has_more") as? Boolean
        if (!WindowsConnectionPolicy.isValidProbeEnvelope(
                cursor, remoteCursor, remoteHead, items?.length(), hasMore)) {
            return WindowsConnectionPolicy.Result.INVALID_RESPONSE to ""
        }
        val identity = body.optString("server_id", "")
        val decision = SyncServerIdentityPolicy.evaluate(
            expectedIdentity, cursor, identity, remoteHead
        )
        return WindowsConnectionPolicy.response(status, decision) to identity
    } catch (_: org.json.JSONException) {
        return WindowsConnectionPolicy.Result.INVALID_RESPONSE to ""
    } catch (_: Exception) {
        return WindowsConnectionPolicy.Result.UNAVAILABLE to ""
    } finally {
        conn.disconnect()
    }
}
