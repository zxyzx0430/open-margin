package com.bookkeeping.app.ui.screen.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.category.CategoryCatalog
import com.bookkeeping.app.data.category.CategoryDefinition
import com.bookkeeping.app.data.category.CategoryPresets
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.i18n.tr
import com.bookkeeping.app.ui.components.CategoryIcon
import com.bookkeeping.app.ui.components.CategoryIconCatalog
import com.bookkeeping.app.ui.components.SoftBadge
import com.bookkeeping.app.ui.components.StatusTone
import com.bookkeeping.app.ui.theme.QingOutline
import com.bookkeeping.app.ui.theme.QingPrimary
import com.bookkeeping.app.ui.theme.QingPrimaryContainer
import com.bookkeeping.app.ui.theme.QingSurface
import com.bookkeeping.app.ui.theme.QingText
import com.bookkeeping.app.ui.theme.QingTextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryManagerSheet(
    onDismiss: () -> Unit,
    countByCategory: suspend (String) -> Int,
    reassignCategory: suspend (String, String) -> Int,
    onCategoryCreated: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val catalog = remember(context) { CategoryCatalog.get(context) }
    val allCategories by catalog.categories.collectAsState()
    val activeCategories = remember(allCategories) { allCategories.filter { !it.isDeleted } }
    val deletedBuiltIns = remember(allCategories) { allCategories.filter { it.isBuiltIn && it.isDeleted } }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var editorTarget by remember { mutableStateOf<CategoryDefinition?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Pair<CategoryDefinition, Int>?>(null) }
    var targetReassignId by remember { mutableStateOf(TransactionCategory.OTHER.name) }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("分类管理"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tr("可新建自定义分类，或删除不需要的分类（「其他」和「转账」保底保留）"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = tr("关闭"))
                }
            }

            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QingPrimary),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("新建分类"), fontWeight = FontWeight.SemiBold)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(activeCategories, key = { it.id }) { cat ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = QingSurface,
                        border = BorderStroke(1.dp, QingOutline.copy(alpha = 0.65f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CategoryIcon(categoryKey = cat.id, size = 36.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr(cat.name),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        cat.isLocked -> tr("系统保底 · 不可删除")
                                        cat.isBuiltIn -> tr("系统分类")
                                        else -> tr("自定义分类")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = QingTextSecondary,
                                )
                            }
                            if (cat.isLocked) {
                                SoftBadge(text = tr("保底"), tone = StatusTone.NEUTRAL)
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = QingTextSecondary.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                if (!cat.isBuiltIn) {
                                    IconButton(
                                        onClick = { editorTarget = cat },
                                        enabled = !busy,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = tr("编辑"),
                                            tint = QingTextSecondary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (busy) return@IconButton
                                        busy = true
                                        scope.launch {
                                            val count = runCatching { countByCategory(cat.id) }.getOrDefault(0)
                                            targetReassignId = TransactionCategory.OTHER.name
                                            pendingDelete = cat to count
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = tr("删除"),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (deletedBuiltIns.isNotEmpty()) {
                    item(key = "deleted_builtins") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = tr("已删除的系统分类（点击可恢复）"),
                                style = MaterialTheme.typography.labelMedium,
                                color = QingTextSecondary,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                deletedBuiltIns.forEach { hidden ->
                                    Surface(
                                        onClick = { catalog.restoreBuiltIn(hidden.id) },
                                        shape = RoundedCornerShape(50),
                                        color = QingPrimaryContainer,
                                        border = BorderStroke(1.dp, QingPrimary.copy(alpha = 0.25f)),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Restore,
                                                contentDescription = null,
                                                tint = QingPrimary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                text = tr(hidden.name),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = QingPrimary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog || editorTarget != null) {
        val editing = editorTarget
        CategoryEditorDialog(
            initial = editing,
            onDismiss = {
                showCreateDialog = false
                editorTarget = null
            },
            onConfirm = { name, iconKey, colorArgb ->
                if (editing == null) {
                    catalog.addCustomCategory(name, iconKey, colorArgb).map { created ->
                        showCreateDialog = false
                        onCategoryCreated(created.id)
                    }
                } else {
                    val oldId = editing.id
                    val result = catalog.updateCustomCategory(oldId, name, iconKey, colorArgb)
                    result.onSuccess { updated ->
                        editorTarget = null
                        if (oldId != updated.id) {
                            scope.launch {
                                runCatching { reassignCategory(oldId, updated.id) }
                            }
                        }
                    }
                    result
                }
            },
        )
    }

    pendingDelete?.let { (cat, count) ->
        val candidates = activeCategories.filter { it.id != cat.id }
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text(tr("删除「%s」？").format(tr(cat.name))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (count > 0) {
                        Text(
                            text = tr("这个分类下还有 %d 笔旧账，删掉后要把它们归到哪个分类？").format(count),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            candidates.forEach { target ->
                                val selected = targetReassignId == target.id
                                Surface(
                                    onClick = { targetReassignId = target.id },
                                    shape = RoundedCornerShape(50),
                                    color = if (selected) QingPrimaryContainer else QingSurface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) QingPrimary else QingOutline,
                                    ),
                                ) {
                                    Text(
                                        text = tr(target.name),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) QingPrimary else QingText,
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = tr("这个分类下目前没有账单，删除后不会影响现有流水。"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            if (count > 0) {
                                runCatching { reassignCategory(cat.id, targetReassignId) }
                            }
                            catalog.deleteCategory(cat.id)
                            busy = false
                            pendingDelete = null
                        }
                    },
                ) {
                    Text(
                        text = if (count > 0) tr("转移并删除") else tr("确认删除"),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingDelete = null }) {
                    Text(tr("取消"))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorDialog(
    initial: CategoryDefinition?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Long) -> Result<CategoryDefinition>,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var iconKey by remember(initial?.id) { mutableStateOf(initial?.iconKey ?: CategoryPresets.ICONS.first().key) }
    var colorArgb by remember(initial?.id) { mutableLongStateOf(initial?.colorArgb ?: CategoryPresets.COLORS.first()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("新建分类") else tr("编辑分类")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(tr("分类名称（最多 20 字）")) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = tr("选择图标"),
                    style = MaterialTheme.typography.labelMedium,
                    color = QingTextSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CategoryPresets.ICONS.forEach { preset ->
                        val selected = iconKey == preset.key
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) Color(colorArgb).copy(alpha = 0.16f) else QingSurface,
                                )
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) Color(colorArgb) else QingOutline.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable { iconKey = preset.key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = CategoryIconCatalog.iconForKey(preset.key),
                                contentDescription = tr(preset.label),
                                tint = if (selected) Color(colorArgb) else QingTextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Text(
                    text = tr("选择颜色"),
                    style = MaterialTheme.typography.labelMedium,
                    color = QingTextSecondary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CategoryPresets.COLORS.forEach { hex ->
                        val selected = colorArgb == hex
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (selected) 2.5.dp else 0.dp,
                                    color = if (selected) QingText else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { colorArgb = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(name, iconKey, colorArgb).onFailure { failure ->
                        error = failure.message ?: tr("保存失败")
                    }
                },
            ) {
                Text(tr("保存"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消"))
            }
        },
    )
}
