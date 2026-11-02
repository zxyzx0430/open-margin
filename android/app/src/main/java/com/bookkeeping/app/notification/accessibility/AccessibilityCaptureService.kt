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

        // Diagnostics must never print the accessibility tree (chat/payment content).

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
                AccessibilityCaptureHealth.onFailure(this@AccessibilityCaptureService, "persist", failure)
                throw failure
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
