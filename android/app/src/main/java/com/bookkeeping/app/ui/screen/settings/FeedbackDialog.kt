package com.bookkeeping.app.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.BuildConfig
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingTextSecondary
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

    SettingsDetailDialog(
        title = tr("反馈问题"),
        onDismiss = onDismiss,
    ) {
        Text(
            text = tr("两种方式都会带上版本与机型信息；不会包含金额、商户、账户或日志内容。"),
            style = MaterialTheme.typography.bodySmall,
            color = QingTextSecondary,
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
            maxLines = 5,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = QingSurface,
                unfocusedContainerColor = QingSurfaceVariant.copy(alpha = 0.45f),
                focusedBorderColor = QingPrimary,
                unfocusedBorderColor = QingOutline.copy(alpha = 0.65f),
                cursorColor = QingPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = QingSurfaceVariant.copy(alpha = 0.45f),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = diagnostics,
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText(tr("清衡诊断信息"), diagnostics))
                            copied = true
                        },
                    ) {
                        Text(
                            text = if (copied) tr("诊断信息已复制") else tr("只复制诊断信息"),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Button(
            onClick = {
                openLink(
                    Intent(Intent.ACTION_VIEW, Uri.parse(FeedbackReport.issueUrl(subject, body))),
                )
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = tr("在 GitHub 提 Issue"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        OutlinedButton(
            onClick = {
                openLink(
                    Intent(Intent.ACTION_SENDTO, Uri.parse(FeedbackReport.mailtoUri(subject, body))),
                )
            },
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, QingOutline),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = tr("发邮件到 zxyzx0430@outlook.com"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
