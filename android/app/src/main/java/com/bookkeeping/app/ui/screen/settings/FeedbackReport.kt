package com.bookkeeping.app.ui.screen.settings

import java.net.URLEncoder

/**
 * Builds the feedback payload without touching ledger data.
 *
 * Only build/device facts are included; amounts, merchants, accounts, transaction
 * counts and log contents are deliberately excluded so a report can be pasted in
 * public places (GitHub issue) without leaking financial data.
 */
internal object FeedbackReport {
    const val EMAIL = "zxyzx0430@outlook.com"
    const val ISSUE_BASE = "https://github.com/zxyzx0430/open-margin/issues/new"

    fun diagnostics(
        versionName: String,
        versionCode: Int,
        device: String,
        androidRelease: String,
        sdkInt: Int,
        language: String,
    ): String = buildString {
        append("版本: ")
        append(versionName)
        append(" (Build ")
        append(versionCode)
        append(")\n")
        append("机型: ")
        append(device)
        append("\n")
        append("Android: ")
        append(androidRelease)
        append(" (API ")
        append(sdkInt)
        append(")\n")
        append("语言: ")
        append(language)
        append("\n")
        append("说明: 该报告只含版本与设备信息，不含账单金额、商户、账户或日志内容。")
    }

    fun mailtoUri(subject: String, body: String): String =
        "mailto:$EMAIL?subject=${encode(subject)}&body=${encode(body)}"

    fun issueUrl(subject: String, body: String): String =
        "$ISSUE_BASE?title=${encode(subject)}&body=${encode(body)}"

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
