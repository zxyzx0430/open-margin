package com.bookkeeping.app.notification.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// 无障碍兜底服务：监听微信、支付宝两个 app 的窗口事件，
// 在通知监听抓不到的场景（微信转账/红包、支付宝转账入账）里读 UI 抽金额。
//
// 限制只在 com.tencent.mm + com.eg.android.AlipayGphone 两个包名上触发（见 xml/accessibility_service_config）。
// 默认未启用，用户必须在系统「无障碍」里手动勾选我们才会跑。
class AccessibilityCaptureService : AccessibilityService() {

    private lateinit var repository: TransactionRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 包名 → 该包对应的 Extractor 列表（按优先级试）
    private lateinit var extractors: Map<String, List<AccessibilityExtractor>>

    // 短路：相同来源+金额+方向 30s 内只 emit 一次，避免 CONTENT_CHANGED 反复触发
    private var lastFingerprint: String? = null
    private var lastEmitAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val ep = EntryPointAccessors.fromApplication(
                applicationContext,
                AccessibilityServiceEntryPoint::class.java,
            )
            repository = ep.transactionRepository()
            extractors = mapOf(
                PKG_ALIPAY to listOf(AlipayTransferExtractor()),
                PKG_WECHAT to listOf(
                    WechatTransferExtractor(),
                    WechatRedPacketExtractor(),
                ),
            )
            AccessibilityCaptureHealth.onConnected(this)
            if (BuildConfig.DEBUG) Log.d(TAG, "AccessibilityCaptureService connected")
        } catch (failure: Throwable) {
            AccessibilityCaptureHealth.onFailure(this, "connect", failure)
            throw failure // Preserve prior crash semantics; report the type before failing.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleAccessibilityEvent(event)
        } catch (failure: Throwable) {
            AccessibilityCaptureHealth.onFailure(this, "event", failure)
            throw failure // Diagnostic-only; no change to event processing/recovery policy.
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // 入口处不过滤包名先打 log，方便确认事件分发是否被 vivo 拦截
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "EVT type=${eventTypeName(event.eventType)} pkg=$pkg class=${event.className}")
        }

        val list = extractors[pkg] ?: return
        AccessibilityCaptureHealth.onEvent(this)
        val root = rootInActiveWindow
        if (root == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "  -> rootInActiveWindow=null, skip")
            return
        }

        // 强制刷新一次根节点，避免事件到达时 UI 树尚未填充完
        runCatching { root.refresh() }
            .onFailure { AccessibilityCaptureHealth.onFailure(this, "refresh", it) }

        // D02：把活的节点树固化为结构化快照（有界、保留父子关系）。
        // 当前只在 DEBUG 下构建：快照的消费者（诊断日志 + D03 影子规则）
        // 都是调试用途，release 下构建了也用不上——每个无障碍事件都整树
        // 拷贝会白白吃 CPU/电量（交叉审查修正）。D03 转正时再移出门外。
        // Diagnostics must never print the accessibility tree (chat/payment content).
        if (BuildConfig.DEBUG) {
            runCatching { root.toSnapshot(pkg, System.currentTimeMillis()) }.getOrNull()?.let { snapshot ->
                Log.v(TAG, "  -> snapshot nodes=${snapshot.nodeCount} truncated=${snapshot.truncated}")
                // D03 影子模式：微信转账页面规则只产出候选并记录过门结果，
                // 不影响现有提取器的入库路径（等 D06 核验后才切换）。
                // 只记决策枚举，不记金额/对方（页面内容不进日志）。
                if (pkg == PKG_WECHAT) {
                    runCatching {
                        WechatTransferPageRule.classifyAll(snapshot).forEach { candidate ->
                            val decision = com.bookkeeping.app.notification.SettlementGate.evaluate(candidate)
                            Log.v(TAG, "  -> pageRule candidate type=${candidate.type} completion=${candidate.completion} gate=$decision")
                        }
                    }
                }
            }
        }

        for (ext in list) {
            val tx = runCatching { ext.extract(root, event) }
                .onFailure {
                    AccessibilityCaptureHealth.onFailure(this, "extract:" + ext.javaClass.simpleName, it)
                    if (BuildConfig.DEBUG) Log.w(TAG, "  -> extractor " + ext.javaClass.simpleName + " failed (" + it.javaClass.simpleName + ")")
                }
                .getOrNull()

            if (tx == null) {
                if (BuildConfig.DEBUG) Log.v(TAG, "  -> ${ext.javaClass.simpleName} no match")
                continue
            }

            if (!shouldEmit(tx)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "  -> ${ext.javaClass.simpleName} matched but fingerprint duplicate, skip")
                continue
            }
            persist(tx)
            break
        }
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WindowStateChanged"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WindowContentChanged"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "ViewClicked"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "ViewScrolled"
        else -> "Type$type"
    }

    private fun shouldEmit(tx: TransactionEntity): Boolean {
        val fp = "${tx.source}|${tx.amountCents}|${tx.type}"
        val now = System.currentTimeMillis()
        if (fp == lastFingerprint && now - lastEmitAtMs < FINGERPRINT_TTL_MS) return false
        lastFingerprint = fp
        lastEmitAtMs = now
        return true
    }

    private fun persist(tx: TransactionEntity) {
        scope.launch {
            try {
                val id = repository.insertIfNotDuplicate(tx)
                if (id == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Accessibility: skipped duplicate")
                } else {
                    Log.i(TAG, "Accessibility captured id=$id")
                }
            } catch (failure: Throwable) {
                // 只上报健康系统并吞掉：这里是脱离主线程的协程，异常若再抛
                // 出去会走未捕获异常处理器把整个进程打崩——一条记录入库
                // 失败（磁盘满/DB 锁）不值得让 App 闪退（交叉审查修正）。
                AccessibilityCaptureHealth.onFailure(this@AccessibilityCaptureService, "persist", failure)
                Log.e(TAG, "Accessibility persist failed", failure)
            }
        }
    }

    override fun onInterrupt() {
        AccessibilityCaptureHealth.onInterrupted(this)
        // An interruption does not mean the service was disconnected.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityCaptureHealth.onDisconnected(this, "unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityCaptureHealth.onDisconnected(this, "destroy")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BkAccessibility"
        private const val FINGERPRINT_TTL_MS = 30_000L

        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
        const val PKG_WECHAT = "com.tencent.mm"

        // 检查本服务是否被用户在系统「无障碍」里勾选了。
        // 通过读 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 字符串，按 ':' 分割比对组件名。
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AccessibilityCaptureService::class.java)
                .flattenToString()
            val list = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return list.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
