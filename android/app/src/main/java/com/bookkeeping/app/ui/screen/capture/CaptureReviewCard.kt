package com.bookkeeping.app.ui.screen.capture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.capture.screenshot.CandidateStorageIntakeKind
import com.bookkeeping.app.capture.screenshot.CandidateStorageWarningCopy
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeFailureNotice
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeFailureNoticeCopy
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingErrorContainer
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingSurfaceVariant
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import com.bookkeeping.app.ui.theme.QingWarning
import com.bookkeeping.app.ui.theme.QingWarningContainer

@Composable
fun CaptureReviewCard(
    count: Int,
    ignoredCount: Int = 0,
    storageWarningKinds: Set<CandidateStorageIntakeKind> = emptySet(),
    intakeFailureNotice: ScreenshotIntakeFailureNotice? = null,
    onOpen: () -> Unit,
    onDismissIgnored: () -> Unit = {},
    onAcknowledgeStorageWarning: () -> Unit = {},
    onAcknowledgeIntakeFailure: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hasAlert = storageWarningKinds.isNotEmpty() || intakeFailureNotice != null
    val isPending = count > 0 && !hasAlert
    val isIgnoredOnly = count == 0 && ignoredCount > 0 && !hasAlert

    val containerColor = when {
        hasAlert -> QingErrorContainer
        isPending -> QingSurface
        else -> QingSurfaceVariant.copy(alpha = 0.65f)
    }
    val borderColor = when {
        hasAlert -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
        isPending -> QingWarning.copy(alpha = 0.32f)
        else -> QingOutline
    }
    val titleColor = if (hasAlert) MaterialTheme.colorScheme.onErrorContainer else QingText

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPending) 1.dp else 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = when {
                        hasAlert -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                        isPending -> QingWarningContainer
                        else -> QingSurface
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                hasAlert -> Icons.Outlined.WarningAmber
                                isPending -> Icons.Outlined.ReceiptLong
                                else -> Icons.Outlined.History
                            },
                            contentDescription = null,
                            tint = when {
                                hasAlert -> MaterialTheme.colorScheme.error
                                isPending -> QingWarning
                                else -> QingTextSecondary
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = when {
                            count > 0 -> tr("待确认扫描结果") + " · $count"
                            ignoredCount > 0 -> tr("已忽略的截图") + " · $ignoredCount"
                            storageWarningKinds.isNotEmpty() -> tr("待确认存储已满")
                            intakeFailureNotice != null -> tr("截图导入未完成")
                            else -> tr("新识别结果未保存")
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isPending) {
                        Text(
                            text = tr("核对金额、方向与商户后记入账本"),
                            style = MaterialTheme.typography.bodySmall,
                            color = QingTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (isIgnoredOnly) {
                        Text(
                            text = tr("可随时查看找回，或点右侧关闭此提示"),
                            style = MaterialTheme.typography.bodySmall,
                            color = QingTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isPending) {
                    Button(
                        onClick = onOpen,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = QingPrimary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = tr("去核对"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    TextButton(onClick = onOpen) {
                        Text(tr("查看"))
                    }
                    if (isIgnoredOnly) {
                        IconButton(
                            onClick = onDismissIgnored,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = tr("收起提示"),
                                tint = QingTextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            if (storageWarningKinds.isNotEmpty()) {
                Text(
                    text = CandidateStorageWarningCopy.guidance(storageWarningKinds) { tr(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onAcknowledgeStorageWarning) {
                        Text(CandidateStorageWarningCopy.acknowledgement { tr(it) })
                    }
                }
            }
            if (intakeFailureNotice != null) {
                Text(
                    text = ScreenshotIntakeFailureNoticeCopy.message(intakeFailureNotice) { tr(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onAcknowledgeIntakeFailure) { Text(tr("知道了")) }
                }
            }
        }
    }
}
