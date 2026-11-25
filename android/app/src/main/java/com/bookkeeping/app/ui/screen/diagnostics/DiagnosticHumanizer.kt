package com.bookkeeping.app.ui.screen.diagnostics

import com.bookkeeping.app.data.diagnostics.TransactionDiagnostics
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.i18n.EnglishUiText

enum class DiagnosticChannelFilter {
    ALL,
    NOTIFICATION,
    ACCESSIBILITY,
    SCREENSHOT,
    SMS,
    IMPORT,
    SYNC,
}

enum class DiagnosticOutcomeFilter {
    ALL,
    NON_AUTO_BOOKED,
    ISSUES_ONLY,
}

enum class DiagnosticSemanticOutcome {
    AUTO_BOOKED,
    HELD_FOR_REVIEW,
    DEDUP_IGNORED,
    SKIPPED,
    FAILED,
    RUNNING,
}

internal object DiagnosticHumanizer {
    private val AMOUNT_TOKEN_REGEX = Regex("""[+-]?(?:¥|￥|[A-Z]{3}\s)\d+\.\d{2}""")

    fun matchesChannel(
        entry: DiagnosticTimeline.Entry,
        filter: DiagnosticChannelFilter,
    ): Boolean = when (filter) {
        DiagnosticChannelFilter.ALL -> true
        DiagnosticChannelFilter.NOTIFICATION -> entry == DiagnosticTimeline.Entry.NOTIFICATION
        DiagnosticChannelFilter.ACCESSIBILITY -> entry == DiagnosticTimeline.Entry.ACCESSIBILITY
        DiagnosticChannelFilter.SCREENSHOT -> entry == DiagnosticTimeline.Entry.SCREENSHOT
        DiagnosticChannelFilter.SMS -> entry == DiagnosticTimeline.Entry.SMS
        DiagnosticChannelFilter.IMPORT -> entry == DiagnosticTimeline.Entry.IMPORT
        DiagnosticChannelFilter.SYNC -> entry == DiagnosticTimeline.Entry.SYNC
    }

    fun matchesOutcome(
        attempt: DiagnosticTimeline.Attempt,
        filter: DiagnosticOutcomeFilter,
    ): Boolean = when (filter) {
        DiagnosticOutcomeFilter.ALL -> true
        DiagnosticOutcomeFilter.NON_AUTO_BOOKED ->
            semanticOutcome(attempt) != DiagnosticSemanticOutcome.AUTO_BOOKED
        DiagnosticOutcomeFilter.ISSUES_ONLY ->
            attempt.outcome == DiagnosticTimeline.Outcome.FAILURE ||
                attempt.outcome == DiagnosticTimeline.Outcome.PARTIAL ||
                attempt.outcome == DiagnosticTimeline.Outcome.WARNING
    }

    fun filterAttempts(
        attempts: List<DiagnosticTimeline.Attempt>,
        channelFilter: DiagnosticChannelFilter,
        outcomeFilter: DiagnosticOutcomeFilter,
    ): List<DiagnosticTimeline.Attempt> = attempts.filter { attempt ->
        matchesChannel(attempt.entry, channelFilter) &&
            matchesOutcome(attempt, outcomeFilter)
    }

    fun semanticOutcome(attempt: DiagnosticTimeline.Attempt): DiagnosticSemanticOutcome {
        if (attempt.outcome == DiagnosticTimeline.Outcome.FAILURE ||
            attempt.outcome == DiagnosticTimeline.Outcome.PARTIAL
        ) {
            return DiagnosticSemanticOutcome.FAILED
        }
        if (attempt.outcome == DiagnosticTimeline.Outcome.RUNNING) {
            return DiagnosticSemanticOutcome.RUNNING
        }
        val detailsList = attempt.steps.map { it.details.trim() }
        val last = attempt.steps.lastOrNull()
        val lastOutput = last?.outputSummary.orEmpty()

        // Explicit dedup / duplicate ignore markers
        if (detailsList.any(::isDedupIgnoreDetail)) {
            return DiagnosticSemanticOutcome.DEDUP_IGNORED
        }

        // Screenshot intake summary can carry mixed counts even with RESULT_SUCCESS
        val intakeStep = attempt.steps.lastOrNull { it.details.trim() == "Screenshot intake applied" }
        if (intakeStep != null) {
            val out = intakeStep.outputSummary.orEmpty()
            val bookedCount = extractLabeledCount(out, "入账")
            val pendingCount = extractLabeledCount(out, "待确认")
            val skippedCount = extractLabeledCount(out, "跳过")
            return when {
                bookedCount > 0 && pendingCount == 0 -> DiagnosticSemanticOutcome.AUTO_BOOKED
                pendingCount > 0 -> DiagnosticSemanticOutcome.HELD_FOR_REVIEW
                skippedCount > 0 -> DiagnosticSemanticOutcome.DEDUP_IGNORED
                else -> DiagnosticSemanticOutcome.SKIPPED
            }
        }

        // Held for review / needs user confirmation
        if (detailsList.any(::isHeldForReviewDetail) ||
            (lastOutput.contains("待确认") && !detailsList.any(::isAutoBookedDetail))
        ) {
            return DiagnosticSemanticOutcome.HELD_FOR_REVIEW
        }

        // Skipped (non-transaction, no amount, sync disabled, stale change)
        if (detailsList.any(::isSkippedDetail)) {
            return DiagnosticSemanticOutcome.SKIPPED
        }

        // Clean auto-booked / merged / applied
        if (detailsList.any(::isAutoBookedDetail) ||
            attempt.outcome == DiagnosticTimeline.Outcome.SUCCESS
        ) {
            return DiagnosticSemanticOutcome.AUTO_BOOKED
        }

        return if (attempt.outcome == DiagnosticTimeline.Outcome.WARNING) {
            DiagnosticSemanticOutcome.HELD_FOR_REVIEW
        } else {
            DiagnosticSemanticOutcome.SKIPPED
        }
    }

    fun outcomePillKey(outcome: DiagnosticSemanticOutcome): String = when (outcome) {
        DiagnosticSemanticOutcome.AUTO_BOOKED -> "已自动记账"
        DiagnosticSemanticOutcome.HELD_FOR_REVIEW -> "转入待确认"
        DiagnosticSemanticOutcome.DEDUP_IGNORED -> "已去重忽略"
        DiagnosticSemanticOutcome.SKIPPED -> "已跳过"
        DiagnosticSemanticOutcome.FAILED -> "处理失败"
        DiagnosticSemanticOutcome.RUNNING -> "进行中"
    }

    /**
     * Extract a primary amount/merchant summary (e.g. "-¥8.00 · 祐迎迎生鲜超市")
     * from the chain's steps, ignoring noisy metadata summaries.
     */
    fun extractPrimarySummary(attempt: DiagnosticTimeline.Attempt): String? {
        val candidates = buildList {
            // Prefer save / parse / dedup / entry steps in reverse order (most resolved first).
            attempt.steps.asReversed().forEach { step ->
                step.outputSummary?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                step.inputSummary?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        for (text in candidates) {
            if (AMOUNT_TOKEN_REGEX.containsMatchIn(text)) {
                // Clean leading count prefix such as "1 笔候选 · -¥8.00 · 商户"
                val cleaned = text.replace(Regex("""^\d+\s*笔候选\s*·\s*"""), "").trim()
                if (cleaned.isNotEmpty()) return cleaned
            }
        }
        return null
    }

    /**
     * Returns a fixed Chinese explanation key suitable for both direct display
     * and [EnglishUiText] lookup via `tr(...)`.
     */
    fun explainAttemptKey(attempt: DiagnosticTimeline.Attempt): String {
        val semantic = semanticOutcome(attempt)
        val detailsReversed = attempt.steps.asReversed().map { it.details.trim() }.filter { it.isNotEmpty() }
        val intakeStep = attempt.steps.lastOrNull { it.details.trim() == "Screenshot intake applied" }
        if (intakeStep != null && semantic != DiagnosticSemanticOutcome.FAILED) {
            val out = intakeStep.outputSummary.orEmpty()
            val bookedCount = extractLabeledCount(out, "入账")
            val pendingCount = extractLabeledCount(out, "待确认")
            val skippedCount = extractLabeledCount(out, "跳过")
            return when {
                bookedCount > 0 && pendingCount == 0 -> "已从截图成功识别并自动记入账本"
                pendingCount > 0 -> "截图已识别，已转入待确认等待你核对"
                skippedCount > 0 -> "截图与已有记录重复或无需记账，已去重跳过"
                else -> "截图已处理，未产生新账单"
            }
        }

        for (detail in detailsReversed) {
            detailToPlainChineseKey(detail, semantic)?.let { return it }
        }

        return when (semantic) {
            DiagnosticSemanticOutcome.AUTO_BOOKED -> "已成功解析并自动记入账本"
            DiagnosticSemanticOutcome.HELD_FOR_REVIEW -> "已保留在待确认队列，等待你核对后处理"
            DiagnosticSemanticOutcome.DEDUP_IGNORED -> "与已有记录重复，已自动忽略"
            DiagnosticSemanticOutcome.SKIPPED -> "当前内容不符合入账条件，已跳过"
            DiagnosticSemanticOutcome.FAILED -> "处理过程中发生异常，未能完成入账"
            DiagnosticSemanticOutcome.RUNNING -> "正在处理该条采集链路"
        }
    }

    private fun detailToPlainChineseKey(
        detail: String,
        semantic: DiagnosticSemanticOutcome,
    ): String? = when {
        detail == "Recent platform order ID suppressed" ->
            "该订单号刚已抓取过，已自动跳过避免重复"
        detail == "Recent identical page capture suppressed" ||
            detail == "Same-source burst duplicate suppressed" ->
            "短时同页面重复触发，已自动跳过避免重复记账"
        detail == "Duplicate pending capture ignored" ->
            "待确认列表已有相同记录，本次重复抓取已忽略"
        detail == "Capture held for review; auto booking disabled" ->
            "自动记账未开启或仅预览，交易已转入待确认"
        detail == "Cross-channel weak duplicate held for review" ||
            detail == "Page capture held as possible cross-channel duplicate" ||
            detail.startsWith("Cross-channel match kept for confirmation") ||
            detail == "Cross-channel match could not be merged; kept for confirmation" ->
            "疑似与通知或其他来源已有记录重复，已转入待确认核对"
        detail == "Multi-card page held for individual confirmation" ||
            detail == "OCR payment candidates held for individual review" ||
            detail == "OCR page contained multiple bill candidates" ->
            "页面包含多笔或需人工确认的交易，已转入待确认列表"
        detail == "OCR candidates saved for review; screenshot evidence unavailable" ||
            detail == "Screenshot unavailable; candidate kept pending" ||
            detail == "Screenshot evidence unavailable; candidate kept for review" ->
            "交易已转入待确认，但截图原图保存失败或不可用"
        detail == "Exact order ID already recorded for the same payment platform; notification replay skipped" ||
            detail == "Existing source transaction ID; candidate not inserted" ->
            "该平台同订单号账单已存在，已自动忽略重复推送"
        detail == "Cross-channel duplicate candidate skipped" ||
            detail.startsWith("Duplicate confirmed by ledger") ->
            "跨通道核对确认是同一笔账单，已自动去重跳过"
        detail == "Exact duplicate candidate skipped" ->
            "与已有账单完全重复，已自动跳过未重复入账"
        detail == "Recent page-capture fingerprint suppressed" ||
            detail == "Accessibility fingerprint duplicate suppressed" ->
            "同页面或同截图近期已处理过，已自动去重跳过"
        detail == "Notification did not parse as a completed bill" ->
            "该通知不是已完成的交易（或未含有效金额），已跳过"
        detail == "Bank SMS did not parse as a transaction" ->
            "该短信未识别为银行动账通知，已跳过"
        detail == "OCR engine failed" ->
            "页面 OCR 引擎处理失败，等待下次采集"
        detail == "OCR returned no text" ->
            "页面截图未识别到文字，已跳过"
        detail == "OCR did not identify transaction fields" ->
            "页面截图未识别到交易字段，已跳过"
        detail == "OCR did not identify one completed bill" ->
            "未从页面截图中识别出完整已完成的账单，已跳过"
        detail == "Candidate merged into existing transaction" ||
            detail == "Cross-channel match merged into existing transaction" ->
            "已识别为同一笔交易，并自动合并补全到已有账单"
        detail == "Possible duplicate retained for review" ->
            "已暂存账本但疑似与已有记录重复，等待你核对确认"
        detail == "Possible duplicate requires confirmation" ->
            "检测到疑似重复账单，需要你确认后再入账"
        detail == "Transaction row inserted" ||
            detail == "Imported transaction row inserted" ||
            detail == "Confirmed import committed" ->
            "已成功解析并自动记入账本"
        detail == "Transaction row updated" ->
            "已更新账单记录"
        detail == "Transaction tombstone saved" ->
            "已删除账单并记录同步删除标记"
        detail == "Sync is disabled; transaction remains local" ||
            detail == "No network upload scheduled" ->
            "未开启云同步，账单仅保存在本机"
        detail == "Transaction sync conflict awaits resolution" ->
            "与服务端存在版本冲突，等待你确认保留哪一版"
        detail == "Server transaction change applied locally" ||
            detail == "Server acknowledged transaction upload" ||
            detail == "Server applied transaction change" ||
            detail == "Equivalent server revision acknowledged" ||
            detail == "User-approved local transaction change applied" ||
            detail == "User-approved server transaction change applied" ->
            "云同步已完成"
        detail == "Payment notification parsed" ||
            detail == "Payment page parsed" ||
            detail == "Bank SMS parsed" ||
            detail == "Accessibility page parsed" ->
            if (semantic == DiagnosticSemanticOutcome.AUTO_BOOKED) "已成功解析并自动记入账本" else null
        detail == "Screenshot could not be decoded" ->
            "截图无法解码，本次未能识别"
        detail == "Screenshot capture failed" ->
            "系统截屏失败，未能完成页面识别"
        detail == "Candidate index budget full; OCR candidate was not saved" ||
            detail == "Candidate index budget full; notification review candidate was not saved" ||
            detail == "Candidate index budget full; page candidate was not saved" ||
            detail == "Capture candidate could not be stored" ->
            "待确认队列存储已满或写入失败，候选未保存"
        detail == "Payment notification operation failed" ||
            detail == "Payment page processing failed" ||
            detail == "Bank SMS operation failed" ||
            detail == "Accessibility capture failed" ||
            detail == "Database operation failed" ->
            "处理过程中发生异常，未能完成入账"
        else -> null
    }

    private fun isDedupIgnoreDetail(detail: String): Boolean =
        detail == "Recent platform order ID suppressed" ||
            detail == "Recent identical page capture suppressed" ||
            detail == "Same-source burst duplicate suppressed" ||
            detail == "Duplicate pending capture ignored" ||
            detail == "Exact order ID already recorded for the same payment platform; notification replay skipped" ||
            detail == "Existing source transaction ID; candidate not inserted" ||
            detail == "Exact duplicate candidate skipped" ||
            detail == "Cross-channel duplicate candidate skipped" ||
            detail == "Recent page-capture fingerprint suppressed" ||
            detail == "Accessibility fingerprint duplicate suppressed" ||
            detail.startsWith("Duplicate confirmed by ledger")

    private fun isHeldForReviewDetail(detail: String): Boolean =
        detail == "Capture held for review; auto booking disabled" ||
            detail == "Cross-channel weak duplicate held for review" ||
            detail == "Page capture held as possible cross-channel duplicate" ||
            detail == "Multi-card page held for individual confirmation" ||
            detail == "OCR payment candidates held for individual review" ||
            detail == "OCR page contained multiple bill candidates" ||
            detail == "OCR candidates saved for review; screenshot evidence unavailable" ||
            detail == "Screenshot unavailable; candidate kept pending" ||
            detail == "Screenshot evidence unavailable; candidate kept for review" ||
            detail == "Possible duplicate retained for review" ||
            detail == "Possible duplicate requires confirmation" ||
            detail == "Parsed with review warnings" ||
            detail == "Transaction sync conflict awaits resolution" ||
            detail == "Cross-channel match could not be merged; kept for confirmation" ||
            detail.startsWith("Cross-channel match kept for confirmation")

    private fun isSkippedDetail(detail: String): Boolean =
        detail == "Notification did not parse as a completed bill" ||
            detail == "Bank SMS did not parse as a transaction" ||
            detail == "OCR did not identify one completed bill" ||
            detail == "OCR returned no text" ||
            detail == "OCR did not identify transaction fields" ||
            detail == "Amount not recognized" ||
            detail == "Import preview contains no rows" ||
            detail == "Sync is disabled; transaction remains local" ||
            detail == "No network upload scheduled" ||
            detail == "Stale server change ignored"

    private fun isAutoBookedDetail(detail: String): Boolean =
        detail == "Transaction row inserted" ||
            detail == "Candidate merged into existing transaction" ||
            detail == "Cross-channel match merged into existing transaction" ||
            detail == "Imported transaction row inserted" ||
            detail == "Imported fields added to existing transaction" ||
            detail == "Transaction row updated" ||
            detail == "Transaction tombstone saved" ||
            detail == "Confirmed import committed" ||
            detail == "Server transaction change applied locally" ||
            detail == "Server acknowledged transaction upload" ||
            detail == "Server applied transaction change" ||
            detail == "Equivalent server revision acknowledged" ||
            detail == "User-approved local transaction change applied" ||
            detail == "User-approved server transaction change applied" ||
            detail == "Weak cross-channel resemblance explicitly confirmed as a separate transaction"

    private fun extractLabeledCount(outputSummary: String, label: String): Int {
        val match = Regex("""$label\s+(\d+)""").find(outputSummary) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }
}
