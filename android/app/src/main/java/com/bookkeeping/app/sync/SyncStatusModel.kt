package com.bookkeeping.app.sync

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.tr
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步状态与冲突对比的纯逻辑。
 *
 * 设置页原来把「状态」「冲突列表」「服务器地址/密钥/立即同步」塞进同一个弹窗，
 * 用户想看失败原因和想改密钥时进的是同一个地方，容易误改密钥。拆成状态页和设置页
 * 之后，「现在是什么状态」「这一笔哪几个字段不一致」必须先在这里算清楚，
 * 界面只负责排版，不靠字符串包含去猜。
 */

/** 状态页顶部唯一的状态词。只写状态，不写解释。 */
internal enum class SyncStateKind(val label: String) {
    NOT_ENABLED("同步未开启"),
    NOT_CONFIGURED("未配置"),
    NEVER_RUN("尚未同步"),
    OK("同步正常"),
    RETRY("同步异常"),
    BLOCKED("同步已阻断"),
    AUTH("授权失败");

    /**
     * 需要回同步设置页改地址/密钥的状态。网络类异常（RETRY）不在此列：
     * 那种情况点「立即同步」重试即可，去设置页没有可改的东西。
     */
    val needsReconnect: Boolean
        get() = this == NOT_ENABLED || this == NOT_CONFIGURED ||
            this == AUTH || this == BLOCKED

    /** 是否具备发起同步的前提（开关已开、地址和密钥有效），与成功与否无关。 */
    val syncAvailable: Boolean
        get() = this != NOT_ENABLED && this != NOT_CONFIGURED
}

internal object SyncState {
    /** 与 TransactionSyncWorker 的校验保持一致：HTTPS 地址 + 至少 16 位密钥。 */
    fun isConfigured(url: String?, token: String?): Boolean =
        !url.isNullOrBlank() &&
            url.trim().startsWith("https://") &&
            (token?.length ?: 0) >= MIN_TOKEN_LENGTH

    /**
     * 状态判定顺序：开关 → 配置 → 结构化结果码 → 历史记录。
     * 只有 CONFIG 需要结合开关才能区分「同步未开启」和「未配置」。
     */
    fun of(
        enabled: Boolean,
        configured: Boolean,
        code: SyncResultCode?,
        lastSuccessMs: Long,
    ): SyncStateKind = when {
        !enabled -> SyncStateKind.NOT_ENABLED
        !configured -> SyncStateKind.NOT_CONFIGURED
        code == SyncResultCode.OK -> SyncStateKind.OK
        code == SyncResultCode.RETRY -> SyncStateKind.RETRY
        code == SyncResultCode.BLOCKED -> SyncStateKind.BLOCKED
        code == SyncResultCode.AUTH -> SyncStateKind.AUTH
        code == SyncResultCode.CONFIG -> SyncStateKind.NOT_CONFIGURED
        lastSuccessMs > 0L -> SyncStateKind.OK
        else -> SyncStateKind.NEVER_RUN
    }

    /** 旧版本只写了 last_result，没有结构化结果码；关键词只用于回退。 */
    fun legacyFailed(message: String): Boolean =
        message.contains("失败") || message.contains("异常") ||
            message.contains("不可用") || message.contains("401") || message.contains("403")

    const val MIN_TOKEN_LENGTH = 16
}

/** 冲突一侧的展示字段；服务器侧从 conflict_server_preview 解析而来。 */
internal data class SyncConflictSide(
    val recordId: String = "",
    val timestampMs: Long? = null,
    val merchant: String? = null,
    val amountCents: Long? = null,
    val currency: String? = null,
    val category: String? = null,
)

internal enum class SyncConflictField(val label: String) {
    TIME("日期时间不同："),
    MERCHANT("商户不同："),
    AMOUNT("金额不同："),
    CATEGORY("分类不同："),
}

internal object SyncConflictDiff {
    /**
     * 只比较用户能看懂的四项，不比较 revision/mutation id：
     * 版本号不同但内容相同不需要用户做选择。
     */
    fun differingFields(
        phone: SyncConflictSide,
        server: SyncConflictSide,
    ): List<SyncConflictField> = buildList {
        if (phone.timestampMs != null && server.timestampMs != null &&
            phone.timestampMs != server.timestampMs
        ) {
            add(SyncConflictField.TIME)
        }
        if (phone.merchant.orEmpty().trim() != server.merchant.orEmpty().trim()) {
            add(SyncConflictField.MERCHANT)
        }
        if (phone.amountCents != server.amountCents ||
            phone.currency.orEmpty() != server.currency.orEmpty()
        ) {
            add(SyncConflictField.AMOUNT)
        }
        if (phone.category.orEmpty() != server.category.orEmpty()) {
            add(SyncConflictField.CATEGORY)
        }
    }
}

internal fun formatConflictTime(timestampMs: Long?): String =
    timestampMs?.takeIf { it > 0L }?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it))
    } ?: "—"

internal fun formatConflictAmount(amountCents: Long?, currency: String?): String {
    val cents = amountCents ?: return "—"
    val unit = currency?.takeIf { it.isNotBlank() } ?: "CNY"
    return "$unit " + String.format(Locale.CHINA, "%.2f", cents / 100.0)
}

internal fun formatConflictCategory(raw: String?): String {
    val value = raw?.takeIf { it.isNotBlank() } ?: return "—"
    val display = runCatching { TransactionCategory.valueOf(value).displayName }.getOrNull() ?: value
    return tr(display)
}

internal fun formatConflictMerchant(raw: String?): String =
    raw?.takeIf { it.isNotBlank() } ?: "—"

/** 一行写全一侧：来源 · 日期时间 · 商户 · 金额。 */
internal fun conflictSideLine(sourceLabel: String, side: SyncConflictSide): String = listOf(
    sourceLabel,
    formatConflictTime(side.timestampMs),
    formatConflictMerchant(side.merchant),
    formatConflictAmount(side.amountCents, side.currency),
).joinToString(" · ")

/** 相对时间：状态页只关心「多久以前」，不关心秒。 */
internal fun relativeSyncTime(
    timestampMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): String {
    if (timestampMs <= 0L) return tr("尚未同步")
    val diff = (nowMs - timestampMs).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> tr("刚刚")
        diff < 3_600_000L -> String.format(
            Locale.getDefault(),
            tr("%d 分钟前"),
            diff / 60_000L,
        )
        diff < 86_400_000L -> String.format(
            Locale.getDefault(),
            tr("%d 小时前"),
            diff / 3_600_000L,
        )
        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(timestampMs))
    }
}
