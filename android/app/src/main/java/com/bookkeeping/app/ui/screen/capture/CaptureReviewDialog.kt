package com.bookkeeping.app.ui.screen.capture

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingIncome
import com.bookkeeping.app.ui.theme.QingTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CaptureReviewDialog(
    onDismiss: () -> Unit,
    viewModel: CaptureReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr("待确认扫描结果"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(tr("关闭")) }
                }
                Text(
                    text = tr("扫描只在本机保存结构化字段和截图，确认后才会写入账本。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = QingTextSecondary,
                )
                state.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = QingPrimary,
                    )
                }
                if (state.busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                if (state.pending.isEmpty()) {
                    Text(
                        text = tr("没有待确认扫描结果"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = QingTextSecondary,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.pending, key = { it.stableKey() }) { candidate ->
                            CaptureCandidateCard(
                                candidate = candidate,
                                busy = state.busy,
                                onApprove = { merchant, category ->
                                    viewModel.approve(candidate, merchant, category)
                                },
                                onIgnore = { viewModel.ignore(candidate) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureCandidateCard(
    candidate: CaptureCandidate,
    busy: Boolean,
    onApprove: (String, TransactionCategory) -> Unit,
    onIgnore: () -> Unit,
) {
    var merchant by remember(candidate.stableKey()) {
        mutableStateOf(candidate.merchantCandidates.firstOrNull().orEmpty())
    }
    var category by remember(candidate.stableKey()) {
        mutableStateOf(TransactionCategory.OTHER)
    }
    var categoryMenu by remember { mutableStateOf(false) }
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, candidate.screenshotPath) {
        value = candidate.screenshotPath?.let { path ->
            withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val image = thumbnail
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = tr("扫描截图"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            } else {
                CategoryIcon(category = category, size = 64.dp)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val refund = candidate.kind == CaptureCandidate.KIND_REFUND
                    Text(
                        text = (if (refund) "+¥" else "−¥") +
                            "%.2f".format(Locale.CHINA, candidate.amountCents / 100.0),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (refund) QingIncome else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = tr("置信度") + " " + (candidate.confidence * 100).toInt() + "%",
                        style = MaterialTheme.typography.labelSmall,
                        color = QingTextSecondary,
                    )
                }
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(tr("商户")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (candidate.merchantCandidates.size > 1) {
                    Text(
                        text = tr("候选：") + candidate.merchantCandidates.joinToString(" / "),
                        style = MaterialTheme.typography.labelSmall,
                        color = QingTextSecondary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                            .format(Date(candidate.eventTimeMs)) +
                            (candidate.orderId?.let { " · " + tr("单号") + " " + it } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = QingTextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        TextButton(onClick = { categoryMenu = true }) {
                            Text(tr(category.displayName))
                        }
                        DropdownMenu(
                            expanded = categoryMenu,
                            onDismissRequest = { categoryMenu = false },
                        ) {
                            TransactionCategory.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(tr(option.displayName)) },
                                    onClick = {
                                        category = option
                                        categoryMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onIgnore, enabled = !busy) {
                        Text(tr("忽略"))
                    }
                    Button(onClick = { onApprove(merchant, category) }, enabled = !busy) {
                        Text(tr("记入账本"))
                    }
                }
            }
        }
    }
}
