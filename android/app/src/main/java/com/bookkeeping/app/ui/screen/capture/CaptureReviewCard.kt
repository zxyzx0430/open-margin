package com.bookkeeping.app.ui.screen.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.capture.screenshot.CandidateStorageIntakeKind
import com.bookkeeping.app.capture.screenshot.CandidateStorageWarningCopy
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeFailureNotice
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeFailureNoticeCopy
import com.bookkeeping.app.i18n.tr

@Composable
fun CaptureReviewCard(
    count: Int,
    ignoredCount: Int = 0,
    storageWarningKinds: Set<CandidateStorageIntakeKind> = emptySet(),
    intakeFailureNotice: ScreenshotIntakeFailureNotice? = null,
    onOpen: () -> Unit,
    onAcknowledgeStorageWarning: () -> Unit = {},
    onAcknowledgeIntakeFailure: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (storageWarningKinds.isNotEmpty() || intakeFailureNotice != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when {
                        count > 0 -> tr("待确认扫描结果") + " · " + count
                        ignoredCount > 0 -> tr("已忽略的截图") + " · " + ignoredCount
                        storageWarningKinds.isNotEmpty() -> tr("待确认存储已满")
                        intakeFailureNotice != null -> tr("截图导入未完成")
                        else -> tr("新识别结果未保存")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (storageWarningKinds.isNotEmpty() || intakeFailureNotice != null) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen) { Text(tr("查看")) }
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
