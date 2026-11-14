package com.bookkeeping.app.notification.accessibility

// 结构化页面快照（蓝图 M2/D02）。
// 现状问题（专项 01 已确认）：无障碍采集把整页文本拍平，多张卡片/多笔
// 金额会串在一起。快照保留父子结构后，页面规则（D03）可以按卡片子树
// 分别取值，金额不再串卡。
// Pure Kotlin model — no Android imports, fully JVM-testable.
// Android 侧的转换在 AccessibilitySnapshot.kt。

/** Bounds in screen pixels; kept Android-free so the snapshot stays JVM-testable. */
data class SnapshotRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** 快照里的一个节点：只留识别需要的字段，不持有系统节点引用。 */
data class SnapshotNode(
    val text: String = "",
    val contentDesc: String = "",
    val viewId: String? = null,
    val className: String? = null,
    val children: List<SnapshotNode> = emptyList(),
    /** Screen-space bounds, used only for optional screenshot/icon cropping. */
    val bounds: SnapshotRect? = null,
    /** Window that produced this node; guards screenshots against a page switch. */
    val windowId: Int = 0,
    val editable: Boolean = false,
    val password: Boolean = false,
) {
    /** 与 AccessibilityNodeUtils.combinedText 相同的合并规则。 */
    fun combinedText(): String =
        if (text.isEmpty()) contentDesc
        else if (contentDesc.isEmpty()) text
        else "$text / $contentDesc"

    /** 本子树内所有节点（含自身），深度优先、保持出现顺序。迭代实现，防超深树爆栈。 */
    fun walk(): List<SnapshotNode> {
        val out = ArrayList<SnapshotNode>()
        val stack = ArrayDeque<SnapshotNode>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out.add(n)
            // 子节点逆序入栈，出栈顺序即原始顺序。
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
        return out
    }

    /** 本子树的可见文本（按节点顺序），空白节点跳过。 */
    fun subtreeTexts(): List<String> =
        walk().mapNotNull { it.combinedText().trim().takeIf { s -> s.isNotEmpty() } }
}

data class ScreenSnapshot(
    val packageName: String,
    val capturedAtMs: Long,
    val root: SnapshotNode,
    /** root 实际（限额后）节点数。 */
    val nodeCount: Int,
    /** 是否因节点数/深度上限被截断；true 时规则层只能得到部分页面。 */
    val truncated: Boolean,
) {
    /** 整页可见文本，深度优先顺序拼接（与旧 collectAllText 行为一致）。 */
    fun flattenText(): String = root.subtreeTexts().joinToString(" ")

    /** 按谓词找节点（返回的是子树根，可继续取其子树文本）。 */
    fun findAll(predicate: (SnapshotNode) -> Boolean): List<SnapshotNode> =
        root.walk().filter(predicate)

    companion object {
        const val DEFAULT_MAX_NODES = 512
        const val DEFAULT_MAX_DEPTH = 32

        /**
         * 给一棵原始节点树套用节点数/深度上限，返回 (限额后的树, 是否截断)。
         * 超出深度的子树整体剪掉；节点数超限时按深度优先顺序保留前 maxNodes 个，
         * 其余兄弟子树剪掉。纯函数，可单测。
         */
        fun limitTree(
            root: SnapshotNode,
            maxNodes: Int = DEFAULT_MAX_NODES,
            maxDepth: Int = DEFAULT_MAX_DEPTH,
        ): Pair<SnapshotNode, Boolean> {
            var budget = maxNodes
            var truncated = false

            fun build(node: SnapshotNode, depth: Int): SnapshotNode? {
                if (budget <= 0) {
                    truncated = true
                    return null
                }
                budget--
                if (depth >= maxDepth) {
                    if (node.children.isNotEmpty()) truncated = true
                    return node.copy(children = emptyList())
                }
                val kept = ArrayList<SnapshotNode>(node.children.size)
                for (child in node.children) {
                    val built = build(child, depth + 1)
                    if (built == null) {
                        truncated = true
                        break
                    }
                    kept.add(built)
                }
                if (kept.size < node.children.size) truncated = true
                return node.copy(children = kept)
            }

            val limited = build(root, 0) ?: root.copy(children = emptyList())
            return limited to truncated
        }

        /** 由原始树构造快照：套用限额并统计节点数。 */
        fun of(
            packageName: String,
            capturedAtMs: Long,
            rawRoot: SnapshotNode,
            maxNodes: Int = DEFAULT_MAX_NODES,
            maxDepth: Int = DEFAULT_MAX_DEPTH,
        ): ScreenSnapshot {
            val (limited, truncated) = limitTree(rawRoot, maxNodes, maxDepth)
            return ScreenSnapshot(
                packageName = packageName,
                capturedAtMs = capturedAtMs,
                root = limited,
                nodeCount = limited.walk().size,
                truncated = truncated,
            )
        }
    }
}
