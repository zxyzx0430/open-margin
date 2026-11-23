package com.bookkeeping.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingPrimaryDeep
import com.bookkeeping.app.ui.theme.QingTextSecondary

/**
 * Two-row compact update card positioned at the top of the ledger list:
 *  - Row 1: "发现新版本 vX.XX" + concise 1-line summary note (if any).
 *  - Row 2: [ 忽略此版本 ]  [ 下次再提醒 ]  [ 立即更新 ]
 * Styled to stay uncrowded down to 360dp phone widths.
 */
@Composable
fun SiteUpdateBanner(
    versionName: String,
    summaryNote: String? = null,
    onUpdateNow: () -> Unit,
    onRemindLater: () -> Unit,
    onDismissForever: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = QingPrimaryContainer),
        border = BorderStroke(1.dp, QingPrimary.copy(alpha = 0.24f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = tr("发现新版本") + " v" + versionName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = QingPrimaryDeep,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val note = summaryNote?.trim().orEmpty()
                if (note.isNotEmpty()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = QingPrimaryDeep.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismissForever,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = tr("忽略此版本"),
                        style = MaterialTheme.typography.labelLarge,
                        color = QingTextSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                TextButton(
                    onClick = onRemindLater,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = tr("下次再提醒"),
                        style = MaterialTheme.typography.labelLarge,
                        color = QingTextSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Button(
                    onClick = onUpdateNow,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QingPrimary),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = tr("立即更新"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
