package com.bookkeeping.app.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.i18n.tr
import java.util.Locale

/**
 * 反馈入口：邮件 + GitHub Issue 两个通道，共用同一份诊断信息。
 * 诊断信息只含版本与设备，不含账本内容。
 */
@Composable
internal fun FeedbackDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    val diagnostics = remember {
        FeedbackReport.diagnostics(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            language = Locale.getDefault().toString(),
        )
    }
    val body = buildString {
        append(detail.trim().ifBlank { tr("（请描述遇到的问题）") })
        append("\n\n---\n")
        append(diagnostics)
    }
    val subject = "${tr("清衡反馈")} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    fun openLink(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("反馈问题")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = tr("两种方式都会带上版本与机型信息；不会包含金额、商户、账户或日志内容。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = detail,
                    onValueChange = {
                        detail = it
                        copied = false
                    },
                    label = { Text(tr("问题描述")) },
                    placeholder = { Text(tr("例如：语音记账后分类不对")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = diagnostics,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        openLink(
                            Intent(Intent.ACTION_SENDTO, Uri.parse(FeedbackReport.mailtoUri(subject, body))),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("发邮件到 zxyzx0430@outlook.com")) }
                OutlinedButton(
                    onClick = {
                        openLink(
                            Intent(Intent.ACTION_VIEW, Uri.parse(FeedbackReport.issueUrl(subject, body))),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("在 GitHub 提 Issue")) }
                TextButton(
                    onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText(tr("清衡诊断信息"), diagnostics))
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("只复制诊断信息")) }
                if (copied) {
                    Text(
                        text = tr("诊断信息已复制"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("关闭")) }
        },
    )
}
