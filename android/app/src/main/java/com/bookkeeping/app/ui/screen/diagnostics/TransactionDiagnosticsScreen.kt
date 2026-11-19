package com.bookkeeping.app.ui.screen.diagnostics

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.screen.settings.SettingsGroupCard
import java.text.DateFormat
import java.util.Date

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TransactionDiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: TransactionDiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var expandedEvents by remember { mutableStateOf(emptySet<String>()) }

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
                tr("仅保存在本机，7 天后自动清理"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text(tr("搜索账单 ID 或日志"), maxLines = 1) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                state.events.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (state.query.isBlank() && state.selectedTransactionId == null) {
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
                    items(state.events, key = { it.eventId }) { event ->
                        val expanded = event.eventId in expandedEvents
                        DiagnosticEventCard(
                            event = event,
                            expanded = expanded,
                            onToggle = {
                                expandedEvents = if (expanded) expandedEvents - event.eventId
                                else expandedEvents + event.eventId
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
        }
    }
}

@Composable
private fun DiagnosticEventCard(
    event: TransactionDiagnosticEventEntity,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SettingsGroupCard(
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    diagnosticStage(event.stage),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    diagnosticResult(event.result),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (event.result == "failure") MaterialTheme.colorScheme.error
                    else if (event.result == "warning") MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    .format(Date(event.occurredAtMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.transactionId?.let { id ->
                Text("${tr("账单")} #$id", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                tr(EnglishUiText.diagnosticDetailTranslationKey(event.details.ifBlank { "—" })),
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                color = if (event.result == "failure") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            if (expanded) {
                Text("${tr("尝试 ID")}：${event.attemptId}", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (expanded) tr("收起") else tr("查看详情"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
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
