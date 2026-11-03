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

    // 深度上限与 ScreenSnapshot 的限额一致：更深的节点反正会被限额剪掉，
    // 在转换阶段就停手，防病态深树把递归栈打爆。
    fun convert(node: AccessibilityNodeInfo, depth: Int): SnapshotNode? {
        if (!visited.add(node)) return null
        val children = ArrayList<SnapshotNode>()
        if (depth < ScreenSnapshot.DEFAULT_MAX_DEPTH) {
            for (i in 0 until node.childCount) {
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
        )
    }

    val raw = convert(this, 0) ?: SnapshotNode()
    return ScreenSnapshot.of(packageName, capturedAtMs, raw)
}
