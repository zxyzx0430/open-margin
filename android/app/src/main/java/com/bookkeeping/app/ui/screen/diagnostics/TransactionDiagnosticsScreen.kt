package com.bookkeeping.app.ui.screen.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.screen.settings.SettingsGroupCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TransactionDiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: TransactionDiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // The DAO returns newest-first; the timeline wants oldest-first so rows that
    // share a millisecond keep their real insertion order.
    val attempts = remember(state.events) { DiagnosticTimeline.build(state.events.asReversed()) }
    var expandedAttempts by remember { mutableStateOf(emptySet<String>()) }
    var openedNewest by remember { mutableStateOf(false) }
    var confirmClearRawText by remember { mutableStateOf(false) }
    LaunchedEffect(attempts.firstOrNull()?.attemptId) {
        val newest = attempts.firstOrNull()?.attemptId ?: return@LaunchedEffect
        if (!openedNewest) {
            expandedAttempts = expandedAttempts + newest
            openedNewest = true
        }
    }
    // The confirmation says the raw text is gone for good; drop the line by itself
    // so the page does not keep a stale status forever.
    LaunchedEffect(state.rawTextNotice) {
        if (state.rawTextNotice == null) return@LaunchedEffect
        delay(4_000L)
        viewModel.dismissRawTextNotice()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        tr("账单诊断日志"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr("记录每笔账从采集到入库经过的每一步，含原始正文。仅保存在本机，最长 7 天。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            state.rawTextNotice?.let { notice ->
                Text(
                    tr(notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notice.startsWith("清除失败")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = tr("清空搜索"))
                        }
                    }
                },
                label = { Text(tr("搜索账单 ID 或日志"), maxLines = 1) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = onlyIssues,
                    onClick = { onlyIssues = !onlyIssues },
                    label = { Text(tr("仅失败与警告"), maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = state.selectedTransactionId == null,
                    onClick = { viewModel.selectTransaction(null) },
                    label = { Text(tr("全部账单"), maxLines = 1, softWrap = false) },
                )
                state.transactionIds.forEach { id ->
                    FilterChip(
                        selected = state.selectedTransactionId == id,
                        onClick = { viewModel.selectTransaction(id) },
                        label = { Text("#${id}", maxLines = 1, softWrap = false) },
                    )
                }
            }

            when {
                state.loading && state.events.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                state.error != null && state.events.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(tr(state.error.orEmpty()), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::retry) { Text(tr("重试")) }
                }
                attempts.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (state.query.isBlank() && state.selectedTransactionId == null && !onlyIssues) {
                            tr("暂无诊断日志")
                        } else {
                            tr("没有匹配的日志")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(attempts, key = { it.attemptId }) { attempt ->
                        val expanded = attempt.attemptId in expandedAttempts
                        AttemptCard(
                            attempt = attempt,
                            expanded = expanded,
                            onOpenTransaction = onOpenTransaction,
                            onToggle = {
                                expandedAttempts = if (expanded) {
                                    expandedAttempts - attempt.attemptId
                                } else {
                                    expandedAttempts + attempt.attemptId
                                }
                            },
                        )
                    }
                    if (state.loading) {
                        item(key = "loading-more") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (state.hasMore) {
                        item(key = "load-more") {
                            TextButton(
                                onClick = viewModel::loadMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(tr("加载更多")) }
                        }
                    }
                    state.error?.let { message ->
                        item(key = "load-error") {
                            Text(tr(message), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            // One fixed footer so the clear action is reachable from any scroll
            // position; it cannot be mistaken for part of the log.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = { confirmClearRawText = true },
                enabled = !state.clearingRawText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    if (state.clearingRawText) tr("正在清除…") else tr("清除所有原始正文"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }

    if (confirmClearRawText) {
        AlertDialog(
            onDismissRequest = { confirmClearRawText = false },
            title = { Text(tr("清除所有原始正文？")) },
            text = {
                Text(tr("清除后只保留摘要，无法恢复。日志本身仍保留 7 天。"))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearRawText = false
                        viewModel.clearRawText()
                    },
                ) {
                    Text(tr("清除"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearRawText = false }) { Text(tr("取消")) }
            },
        )
    }
}

/**
 * One capture chain: what entered, what the chain produced, how long it took and
 * where it ended. Expanding lists every step in order with its own input/output.
 */
@Composable
private fun AttemptCard(
    attempt: DiagnosticTimeline.Attempt,
    expanded: Boolean,
    onOpenTransaction: (Long) -> Unit,
    onToggle: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(attempt.attemptId) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600L)
            copied = false
        }
    }
    SettingsGroupCard(modifier = Modifier.clickable(onClick = onToggle)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entryLabel(attempt.entry),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    outcomeLabel(attempt.outcome),
                    style = MaterialTheme.typography.labelMedium,
                    color = outcomeColor(attempt.outcome),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                "${formatFullTime(attempt.startedAtMs)} · ${tr("耗时")} ${DiagnosticTimeline.formatDuration(attempt.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            attempt.transactionId?.let { id ->
                Text(
                    text = tr("查看账单 #%d").format(id),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenTransaction(id) },
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${tr("链路 ID")}：${DiagnosticTimeline.shortAttemptId(attempt.attemptId)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(formatFullAttemptTrace(attempt)))
                            copied = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            if (copied) tr("已复制") else tr("复制整条链路"),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                attempt.steps.forEach { step ->
                    StepRow(step = step, showSummaries = attempt.hasReadableSummaries)
                }
            }
            Text(
                if (expanded) tr("收起") else tr("查看详情"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatFullAttemptTrace(attempt: DiagnosticTimeline.Attempt): String = buildString {
    appendLine("Attempt: ${attempt.attemptId}")
    appendLine("Time: ${formatFullTime(attempt.startedAtMs)} (${DiagnosticTimeline.formatDuration(attempt.durationMs)})")
    appendLine("Entry: ${attempt.entry.name} | Outcome: ${attempt.outcome.name}")
    attempt.transactionId?.let { appendLine("Transaction: #$it") }
    attempt.steps.forEach { step ->
        appendLine("- [${formatStepTime(step.occurredAtMs)}] ${step.stage}/${step.result}: ${step.details}")
        step.inputSummary?.let { appendLine("  in: $it") }
        step.outputSummary?.let { appendLine("  out: $it") }
        step.callSite?.let { appendLine("  at: $it") }
        step.rawText?.let { appendLine("  raw: $it") }
    }
}

/** One pipeline step: exact time, stage, result, then what went in and out. */
@Composable
private fun StepRow(
    step: TransactionDiagnosticEventEntity,
    showSummaries: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatStepTime(step.occurredAtMs),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                diagnosticStage(step.stage),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            Text(
                diagnosticResult(step.result),
                style = MaterialTheme.typography.labelMedium,
                color = resultColor(step.result),
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            tr(EnglishUiText.diagnosticDetailTranslationKey(step.details.ifBlank { "—" })),
            style = MaterialTheme.typography.bodySmall,
            color = if (step.result == "failure") MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (showSummaries) {
            Text(
                "${tr("输入")}：${step.inputSummary ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${tr("输出")}：${step.outputSummary ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        step.callSite?.let { callSite ->
            Text(
                callSite,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        step.rawText?.let { rawText -> RawTextBlock(rawText = rawText) }
    }
}

/**
 * The verbatim input of one step, collapsed by default.
 *
 * A user reporting "微信付了 5 块没记上" needs the original text; the person
 * scanning the log needs the summary. The block therefore shows only its size
 * until expanded, caps at 240dp with its own scroll so one long OCR page cannot
 * stretch the card past the screen, and lets the user copy the text out.
 * Raw text is NULL for rows written before v12 and after "清除所有原始正文",
 * in which case [StepRow] never calls this at all.
 */
@Composable
private fun RawTextBlock(rawText: String) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600L)
            copied = false
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    if (expanded) {
                        tr("收起原始内容（${rawText.length} 字）")
                    } else {
                        tr("原始内容（${rawText.length} 字）")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (expanded) {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(rawText))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (copied) tr("已复制") else tr("复制"),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (expanded) {
            Text(
                rawText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

private fun formatFullTime(occurredAtMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(occurredAtMs))

private fun formatStepTime(occurredAtMs: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(occurredAtMs))

@Composable
private fun resultColor(result: String) = when (result) {
    "failure" -> MaterialTheme.colorScheme.error
    "warning" -> MaterialTheme.colorScheme.tertiary
    "started" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun outcomeColor(outcome: DiagnosticTimeline.Outcome) = when (outcome) {
    DiagnosticTimeline.Outcome.FAILURE -> MaterialTheme.colorScheme.error
    DiagnosticTimeline.Outcome.PARTIAL -> MaterialTheme.colorScheme.error
    DiagnosticTimeline.Outcome.WARNING -> MaterialTheme.colorScheme.tertiary
    DiagnosticTimeline.Outcome.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
    DiagnosticTimeline.Outcome.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    DiagnosticTimeline.Outcome.SUCCESS -> MaterialTheme.colorScheme.primary
}

@Composable
private fun entryLabel(entry: DiagnosticTimeline.Entry): String = when (entry) {
    DiagnosticTimeline.Entry.SCREENSHOT -> tr("截图")
    DiagnosticTimeline.Entry.NOTIFICATION -> tr("通知")
    DiagnosticTimeline.Entry.SMS -> tr("短信")
    DiagnosticTimeline.Entry.ACCESSIBILITY -> tr("无障碍")
    DiagnosticTimeline.Entry.MANUAL -> tr("手工")
    DiagnosticTimeline.Entry.VOICE -> tr("语音")
    DiagnosticTimeline.Entry.IMPORT -> tr("导入")
    DiagnosticTimeline.Entry.SYNC -> tr("同步")
    DiagnosticTimeline.Entry.EDIT -> tr("编辑")
    DiagnosticTimeline.Entry.DELETE -> tr("删除")
    DiagnosticTimeline.Entry.CAPTURE -> tr("采集")
    DiagnosticTimeline.Entry.UNKNOWN -> tr("其他")
}

@Composable
private fun outcomeLabel(outcome: DiagnosticTimeline.Outcome): String = when (outcome) {
    DiagnosticTimeline.Outcome.SUCCESS -> tr("成功")
    DiagnosticTimeline.Outcome.WARNING -> tr("待确认")
    DiagnosticTimeline.Outcome.FAILURE -> tr("失败")
    DiagnosticTimeline.Outcome.PARTIAL -> tr("部分失败")
    DiagnosticTimeline.Outcome.RUNNING -> tr("进行中")
    DiagnosticTimeline.Outcome.UNKNOWN -> tr("结果未记录")
}

@Composable
private fun diagnosticStage(stage: String): String = when (stage) {
    "capture" -> tr("采集")
    "import" -> tr("导入")
    "entry" -> tr("录入")
    "parse" -> tr("解析")
    "dedup" -> tr("去重")
    "save" -> tr("保存")
    "sync" -> tr("同步")
    "edit" -> tr("编辑")
    "delete" -> tr("删除")
    else -> stage
}

@Composable
private fun diagnosticResult(result: String): String = when (result) {
    "started" -> tr("开始")
    "success" -> tr("成功")
    "warning" -> tr("警告")
    "failure" -> tr("失败")
    else -> result
}
