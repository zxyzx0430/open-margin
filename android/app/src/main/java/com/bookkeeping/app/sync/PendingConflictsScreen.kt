package com.bookkeeping.app.sync

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.i18n.tr
import kotlinx.coroutines.delay

/**
 * 待处理：只做冲突列表。
 *
 * 之前「待处理」和「同步状态」都跳到同一个同步设置页，两个入口一个去处。
 * 这里单独成页，空态只有「暂无冲突」，有冲突时逐笔展开，能直接保留本机 / 采用服务器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingConflictsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(TransactionSyncWorker.PREFS, Context.MODE_PRIVATE)
    }
    val pendingViewModel: PendingSyncViewModel = hiltViewModel()
    val pendingRows by pendingViewModel.pending.collectAsState()
    var snapshot by remember { mutableStateOf(readSyncSnapshot(prefs)) }
    var deferred by remember { mutableStateOf(setOf<String>()) }
    var resolving by remember { mutableStateOf(mapOf<String, Long>()) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(resolving.isNotEmpty()) {
        while (true) {
            val latest = readSyncSnapshot(prefs)
            snapshot = latest
            if (resolving.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val resolvedIds = resolving.keys.filter { it !in latest.conflictIds }
                val timedOutIds = resolving
                    .filter { (id, startedAt) -> id in latest.conflictIds && now - startedAt >= 15_000L }
                    .keys
                if (resolvedIds.isNotEmpty() || timedOutIds.isNotEmpty()) {
                    resolving = resolving - resolvedIds.toSet() - timedOutIds
                    if (resolvedIds.isNotEmpty()) {
                        snackbarHostState.showSnackbar(tr("冲突已处理并同步"))
                    } else if (timedOutIds.isNotEmpty()) {
                        snackbarHostState.showSnackbar(tr("同步处理超时，请检查网络后重试"))
                    }
                }
            }
            delay(if (resolving.isNotEmpty()) 500L else 3_000L)
        }
    }

    val installation = prefs.getString("installation_id", "").orEmpty()
    val conflicts = remember(snapshot.conflictIds, snapshot.missingIds, pendingRows, installation) {
        snapshot.conflictIds
            .map { eventId -> buildConflictEntry(eventId, prefs, pendingRows, installation, snapshot) }
            .sortedByDescending { it.phone?.timestampMs ?: it.server?.timestampMs ?: 0L }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        tr("待处理"),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
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
    ) { innerPadding ->
        if (conflicts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tr("暂无冲突"),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(conflicts, key = { _, entry -> entry.eventId }) { index, entry ->
                    ConflictCard(
                        entry = entry,
                        index = index + 1,
                        total = conflicts.size,
                        deferred = entry.eventId in deferred,
                        resolving = entry.eventId in resolving,
                        onDefer = { deferred = deferred + entry.eventId },
                        onRestore = { deferred = deferred - entry.eventId },
                        onKeepPhone = {
                            resolving = resolving + (entry.eventId to System.currentTimeMillis())
                            applyConflictChoice(prefs, entry, keepPhone = true)
                            TransactionSyncWorker.requestImmediate(context)
                            snapshot = readSyncSnapshot(prefs)
                        },
                        onKeepServer = {
                            resolving = resolving + (entry.eventId to System.currentTimeMillis())
                            applyConflictChoice(prefs, entry, keepPhone = false)
                            TransactionSyncWorker.requestImmediate(context)
                            snapshot = readSyncSnapshot(prefs)
                        },
                    )
                }
            }
        }
    }
}
