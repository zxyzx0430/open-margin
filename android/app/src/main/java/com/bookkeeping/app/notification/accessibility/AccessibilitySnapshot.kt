package com.bookkeeping.app.notification.accessibility

import android.view.accessibility.AccessibilityNodeInfo

// Android 节点树 → ScreenSnapshot 的转换（蓝图 D02）。
// 与 AccessibilityNodeUtils.allNodes 相同的防环策略（visited 集合），
// 转换后立即套用 ScreenSnapshot 的节点/深度上限，规则层拿到的是
// 有界、可复现的结构，而不是活的系统节点。
fun AccessibilityNodeInfo.toSnapshot(
    packageName: String,
    capturedAtMs: Long,
): ScreenSnapshot {
    val visited = HashSet<AccessibilityNodeInfo>()

    // 节点预算在转换阶段就生效（交叉审查修正）：以前先整树拷贝再由
    // ScreenSnapshot 限额，病态大树（聊天列表动辄上万节点）的拷贝成本
    // 一点没省。现在拷贝到上限即停，limitTree 只再做兜底。
    var nodeBudget = ScreenSnapshot.DEFAULT_MAX_NODES

    // 深度上限与 ScreenSnapshot 的限额一致：更深的节点反正会被限额剪掉，
    // 在转换阶段就停手，防病态深树把递归栈打爆。
    // 注意：这里不 recycle 子节点——同一棵树随后还要喂给现有提取器，
    // 提前回收会让它们在 API 33 以下拿到失效节点。
    fun convert(node: AccessibilityNodeInfo, depth: Int): SnapshotNode? {
        if (!visited.add(node)) return null
        if (nodeBudget <= 0) return null
        nodeBudget--
        val children = ArrayList<SnapshotNode>()
        if (depth < ScreenSnapshot.DEFAULT_MAX_DEPTH) {
            for (i in 0 until node.childCount) {
                if (nodeBudget <= 0) break
                val child = node.getChild(i) ?: continue
                convert(child, depth + 1)?.let(children::add)
            }
        }
        return SnapshotNode(
            text = node.text?.toString().orEmpty(),
            contentDesc = node.contentDescription?.toString().orEmpty(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            children = children,
            bounds = boundsOf(node),
            windowId = node.windowId,
            editable = node.isEditable,
            password = node.isPassword,
        )
    }

    val raw = convert(this, 0) ?: SnapshotNode()
    return ScreenSnapshot.of(packageName, capturedAtMs, raw)
}

private fun boundsOf(node: AccessibilityNodeInfo): SnapshotRect? {
    val rect = android.graphics.Rect()
    node.getBoundsInScreen(rect)
    return if (rect.width() <= 0 || rect.height() <= 0) null else {
        SnapshotRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}
