package com.bookkeeping.app.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary

@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    SettingsDetailDialog(
        title = tr("清衡 Open Margin"),
        onDismiss = onDismiss,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = QingPrimaryContainer,
        ) {
            Text(
                text = "${tr("版本")} ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = QingPrimary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = QingSurfaceVariant.copy(alpha = 0.45f),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_SENDTO,
                                        Uri.parse("mailto:zxyzx0430@outlook.com"),
                                    ),
                                )
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = tr("工作邮箱 zxyzx0430@outlook.com"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = QingText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = QingTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = QingOutline.copy(alpha = 0.55f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/zxyzx0430/open-margin"),
                                    ),
                                )
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = tr("GitHub 仓库"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = QingText,
                        )
                        Text(
                            text = "github.com/zxyzx0430/open-margin",
                            style = MaterialTheme.typography.bodySmall,
                            color = QingPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = QingTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = QingOutline.copy(alpha = 0.55f),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "${tr("开源许可")} · GPL-3.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = QingText,
                    )
                    Text(
                        text = tr("源代码以 GNU GPL v3 发布"),
                        style = MaterialTheme.typography.bodySmall,
                        color = QingTextSecondary,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = QingSurfaceVariant.copy(alpha = 0.45f),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = tr("隐私说明"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = QingText,
                )
                Text(
                    text = tr("所有数据不上传到开发者服务器：账本存储在本机，开启同步后保存在你自己的服务器；语音转写在本机完成；反馈只带版本与机型，不含账本内容"),
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
            }
        }
    }
}
