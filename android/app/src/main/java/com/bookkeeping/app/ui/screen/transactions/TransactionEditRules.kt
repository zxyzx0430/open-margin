package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.data.MoneyAmount
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import java.util.Calendar
import java.util.TimeZone

/**
 * 详情页「编辑账单」用的全字段草稿。
 *
 * 字段名与 TransactionEntity 完全对应（id、同步状态、本地改动、重复标记这些
 * 只读字段不在其中）。[TransactionEditRules.applyTo] 只复制这九个字段；
 * syncDirty / syncModifiedMs / syncMutationId 始终由
 * TransactionRepository.update 写入，编辑页绝不自己伪造同步状态。
 */
data class TransactionEditFields(
    val amountCents: Long,
    val type: String,
    val category: String,
    val merchant: String,
    val note: String,
    val account: String,
    val source: String,
    val timestampMs: Long,
    val reviewStatus: String,
)

/** 每个字段最多一条错误提示；值是 tr() 的中文 key。 */
data class TransactionEditErrors(
    val amount: String? = null,
    val timestamp: String? = null,
    val category: String? = null,
    val source: String? = null,
    val reviewStatus: String? = null,
) {
    val isValid: Boolean
        get() = amount == null && timestamp == null && category == null &&
            source == null && reviewStatus == null
}

/**
 * 全字段编辑的纯逻辑：输入解析、校验、脏检查。无 Compose / Android 依赖，
 * 单元测试可直接覆盖（见 TransactionEditRulesTest）。
 */
object TransactionEditRules {

    /** 用户能手动选择的审核状态。取值集合与同步层一致，不新造状态。 */
    val SELECTABLE_REVIEW_STATUSES: List<String> =
        listOf("unverified", "confirmed", "excluded", "transfer", "refund")

    /**
     * 数据库里可能出现的全部审核状态：手动可选值 + 抓取流程自动写入的
     * possible_duplicate。后者不出现在下拉框里，用户没动审核状态时原样保留。
     *
     * 注意：refund 是服务端下发的状态（统计、详情页都按它排除退款），
     * 但 UploadPayload.REVIEW_STATUSES 没有收录它；这里不改同步协议，
     * 只保证本地能正确写入和展示。
     */
    val STORED_REVIEW_STATUSES: Set<String> =
        SELECTABLE_REVIEW_STATUSES.toSet() + "possible_duplicate"

    /** 发生时间允许的最大未来误差（分钟级时钟差异，不放过真正填错的日期）。 */
    const val FUTURE_TOLERANCE_MS: Long = 5 * 60 * 1000L

    /** 账户常用值；account 本身是自由文本，可直接改成银行名等。 */
    val ACCOUNT_PRESETS: List<String> = listOf("微信", "支付宝", "银行", "现金", "其他")

    /** 金额输入解析：非法（空、负数、超过两位小数、0）一律返回 null。 */
    fun amountCents(raw: String): Long? = MoneyAmount.cents(raw)

    /** 实时输入校验；返回 null 表示这一格当前合法。 */
    fun amountError(raw: String): String? {
        if (raw.contains('-') || raw.contains('−')) return "金额不能为负数，请用支出/收入切换方向"
        if (raw.isBlank()) return "请输入有效金额"
        if (raw.substringAfter('.', "").count { it.isDigit() } > 2) return "金额最多保留两位小数"
        return if (MoneyAmount.cents(raw) == null) "请输入有效金额" else null
    }

    fun timestampError(timestampMs: Long, nowMs: Long): String? =
        if (timestampMs > nowMs + FUTURE_TOLERANCE_MS) {
            "时间不能晚于当前时间 5 分钟以上"
        } else {
            null
        }

    fun categoryError(name: String): String? =
        if (runCatching { TransactionCategory.valueOf(name) }.isSuccess) null else "请选择有效分类"

    fun sourceError(name: String): String? =
        if (runCatching { TransactionSource.valueOf(name) }.isSuccess) null else "请选择有效来源"

    fun reviewStatusError(status: String): String? =
        if (status in STORED_REVIEW_STATUSES) null else "请选择有效审核状态"

    fun errorsOf(fields: TransactionEditFields, nowMs: Long): TransactionEditErrors =
        TransactionEditErrors(
            amount = if (fields.amountCents > 0L) null else "请输入有效金额",
            timestamp = timestampError(fields.timestampMs, nowMs),
            category = categoryError(fields.category),
            source = sourceError(fields.source),
            reviewStatus = reviewStatusError(fields.reviewStatus),
        )

    /** 审核状态下拉的初始选中值；自动状态（possible_duplicate）落到「未核实」。 */
    fun initialReviewStatus(stored: String): String =
        if (stored in SELECTABLE_REVIEW_STATUSES) stored else "unverified"

    /** 只比较可编辑字段；没有改动就不该写库，也不该产生同步任务。 */
    fun hasChanges(original: TransactionEntity, fields: TransactionEditFields): Boolean =
        original.amountCents != fields.amountCents ||
            original.type != fields.type ||
            original.category != fields.category ||
            original.merchant != fields.merchant ||
            original.note != fields.note ||
            (original.account ?: "") != fields.account.trim() ||
            original.source != fields.source ||
            original.timestamp != fields.timestampMs ||
            original.remoteReviewStatus != fields.reviewStatus

    /** 一次性写回九个可编辑字段；其余字段（含同步/重复标记）原样保留。 */
    fun applyTo(original: TransactionEntity, fields: TransactionEditFields): TransactionEntity =
        original.copy(
            amountCents = fields.amountCents,
            type = fields.type,
            category = fields.category,
            merchant = fields.merchant,
            note = fields.note,
            account = fields.account.trim().ifBlank { null },
            source = fields.source,
            timestamp = fields.timestampMs,
            remoteReviewStatus = fields.reviewStatus,
        )

    /** 金额（分）→ 编辑框里的「元」，固定两位小数。 */
    fun formatAmount(cents: Long): String =
        (cents / 100).toString() + "." + (cents % 100).toString().padStart(2, '0')

    /** Material3 DatePicker 用 UTC 零点表示日期，这里换算本地日期的 UTC 零点。 */
    fun localDateToUtcMillis(timestampMs: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = timestampMs }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    /** 保留原时间的时分秒，只替换日期。 */
    fun applyUtcDateToMillis(utcDateMs: Long, keepTimeFromMs: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDateMs }
        return Calendar.getInstance().apply {
            timeInMillis = keepTimeFromMs
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    fun hourOf(timestampMs: Long): Int =
        Calendar.getInstance().apply { timeInMillis = timestampMs }.get(Calendar.HOUR_OF_DAY)

    fun minuteOf(timestampMs: Long): Int =
        Calendar.getInstance().apply { timeInMillis = timestampMs }.get(Calendar.MINUTE)

    /** 保留原日期，只替换时分。 */
    fun withLocalTime(timestampMs: Long, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = timestampMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
