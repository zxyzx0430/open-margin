package com.bookkeeping.app.capture.screenshot

import android.content.Context

/** Intake categories persisted for a storage warning. The three-bit mask is bounded and contains no transaction data. */
enum class CandidateStorageIntakeKind(val bit: Int) {
    SCREENSHOT_PAGE(1),
    NOTIFICATION(2),
    SMS(4),
}

/** One bounded, persistent warning slot for candidate results rejected by full storage. */
object CandidateStorageWarningQueue {
    private const val PREFERENCES_NAME = "candidate_storage_warning"
    internal const val PENDING_MASK_KEY = "pending_intake_mask"
    internal const val LEGACY_PENDING_KEY = "pending"
    private val lock = Any()
    private val allMask = CandidateStorageIntakeKind.entries.fold(0) { mask, kind -> mask or kind.bit }

    fun markPending(context: Context, intakeKind: CandidateStorageIntakeKind): Boolean = synchronized(lock) {
        runCatching {
            val prefs = preferences(context)
            val nextMask = readMask(prefs) or intakeKind.bit
            prefs.edit()
                .putInt(PENDING_MASK_KEY, nextMask)
                .remove(LEGACY_PENDING_KEY)
                .commit()
        }.getOrDefault(false)
    }

    /** Read failures and malformed masks keep the warning visible for every intake kind. */
    fun pendingKinds(context: Context): Set<CandidateStorageIntakeKind> = synchronized(lock) {
        val mask = runCatching { readMask(preferences(context)) }.getOrDefault(allMask)
        CandidateStorageIntakeKind.entries.filterTo(linkedSetOf()) { (mask and it.bit) != 0 }
    }

    fun isPending(context: Context): Boolean = pendingKinds(context).isNotEmpty()

    fun acknowledge(context: Context): Boolean = synchronized(lock) {
        runCatching {
            preferences(context).edit()
                .remove(PENDING_MASK_KEY)
                .remove(LEGACY_PENDING_KEY)
                .commit()
        }.getOrDefault(false)
    }

    private fun readMask(preferences: android.content.SharedPreferences): Int {
        if (!preferences.contains(PENDING_MASK_KEY)) {
            return if (preferences.getBoolean(LEGACY_PENDING_KEY, false)) allMask else 0
        }
        val mask = preferences.getInt(PENDING_MASK_KEY, 0)
        return if ((mask and allMask.inv()) != 0) allMask else mask
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
}

/** One persistent, enum-only notice for the most recent background screenshot intake failure. */
data class ScreenshotIntakeFailureNotice(
    val source: IntakeSource,
    val reason: FailureReason,
)

object ScreenshotIntakeFailureNoticeQueue {
    private const val PREFERENCES_NAME = "screenshot_intake_failure_notice"
    internal const val SOURCE_KEY = "source"
    internal const val REASON_KEY = "reason"
    private val lock = Any()

    fun markPending(context: Context, source: IntakeSource, reason: FailureReason): Boolean = synchronized(lock) {
        runCatching {
            preferences(context).edit()
                .putString(SOURCE_KEY, source.name)
                .putString(REASON_KEY, reason.name)
                .commit()
        }.getOrDefault(false)
    }

    /** Corrupt notice data is converted to a generic actionable failure instead of being hidden. */
    fun pending(context: Context): ScreenshotIntakeFailureNotice? = synchronized(lock) {
        runCatching {
            val prefs = preferences(context)
            val source = IntakeSource.entries.firstOrNull { it.name == prefs.getString(SOURCE_KEY, null) }
            val reason = FailureReason.entries.firstOrNull { it.name == prefs.getString(REASON_KEY, null) }
            if (source == null && reason == null) null
            else ScreenshotIntakeFailureNotice(source ?: IntakeSource.MEDIASTORE, reason ?: FailureReason.UNREADABLE)
        }.getOrElse {
            ScreenshotIntakeFailureNotice(IntakeSource.MEDIASTORE, FailureReason.UNREADABLE)
        }
    }

    fun acknowledge(context: Context): Boolean = synchronized(lock) {
        runCatching {
            preferences(context).edit().remove(SOURCE_KEY).remove(REASON_KEY).commit()
        }.getOrDefault(false)
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
}

/** Localized recovery guidance shared by the banner and storage-full notification. */
object CandidateStorageWarningCopy {
    fun guidance(
        kinds: Set<CandidateStorageIntakeKind>,
        translate: (String) -> String,
    ): String = buildList {
        add(translate("这些识别结果未保存。"))
        if (CandidateStorageIntakeKind.SCREENSHOT_PAGE in kinds) {
            add(translate("截图或支付页面：现有候选最多保留 7 天，过期后释放名额；之后重新选择截图或重新打开支付页面核对。"))
        }
        if (CandidateStorageIntakeKind.NOTIFICATION in kinds) {
            add(translate("支付通知：现有候选最多保留 7 天，过期后释放名额；之后打开支付应用的通知或账单，核对后重新采集。"))
        }
        if (CandidateStorageIntakeKind.SMS in kinds) {
            add(translate("银行短信：现有候选最多保留 7 天，过期后释放名额；之后在短信应用中查找原消息，核对后重新采集。"))
        }
    }.joinToString(" ")

    fun acknowledgement(translate: (String) -> String): String =
        translate("知道了（仅关闭提示，未保存项目仍需重新采集）")
}

/** User copy for the Activity result and the durable background-failure banner. */
object ScreenshotIntakeFailureNoticeCopy {
    fun activityMessage(
        reason: FailureReason,
        translate: (String) -> String,
    ): String = translate(reasonLabel(reason)) + " " + translate("请检查图片后重新选择或分享原图。")

    fun message(
        notice: ScreenshotIntakeFailureNotice,
        translate: (String) -> String,
    ): String {
        val source = when (notice.source) {
            IntakeSource.MEDIASTORE -> translate("相册监听")
            IntakeSource.SHARE -> translate("分享图片")
            IntakeSource.PHOTO_PICKER -> translate("选图")
        }
        val reason = translate(reasonLabel(notice.reason))
        return translate("%s导入未完成：%s。请重新选择原图或从支付应用页面重新采集。")
            .format(source, reason)
    }

    private fun reasonLabel(reason: FailureReason): String = when (reason) {
        FailureReason.PERMISSION_DENIED -> "没有读取这张图片的权限"
        FailureReason.URI_INVALID -> "图片地址已失效"
        FailureReason.UNSUPPORTED_MIME -> "不是支持的图片格式"
        FailureReason.UNREADABLE -> "图片读不出来"
        FailureReason.OVERSIZED -> "图片太大，请先裁剪"
        FailureReason.DECODE_FAILED -> "图片解码失败"
        FailureReason.OCR_EMPTY -> "没有识别到文字"
        FailureReason.NOT_TRANSACTION -> "这张图片里没有交易"
        FailureReason.UNKNOWN_PAGE -> "没认出是微信或支付宝的交易页"
    }
}
