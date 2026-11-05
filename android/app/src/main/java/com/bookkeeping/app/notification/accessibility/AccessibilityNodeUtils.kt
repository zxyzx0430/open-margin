package com.bookkeeping.app.notification.accessibility

import android.view.accessibility.AccessibilityNodeInfo

// 提取所有节点（深度优先），按出现顺序返回。
// 不递归到自身已访问的节点（防止环）。
fun AccessibilityNodeInfo.allNodes(): List<AccessibilityNodeInfo> {
    val result = mutableListOf<AccessibilityNodeInfo>()
    val visited = HashSet<AccessibilityNodeInfo>()

    fun walk(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (!visited.add(node)) return
        result.add(node)
        for (i in 0 until node.childCount) walk(node.getChild(i))
    }
    walk(this)
    return result
}

// 把节点的可见文本组合起来（text + contentDescription）
fun AccessibilityNodeInfo.combinedText(): String {
    val t = text?.toString().orEmpty()
    val d = contentDescription?.toString().orEmpty()
    return if (t.isEmpty()) d else if (d.isEmpty()) t else "$t / $d"
}

// 收集整个子树的所有可见文本，按节点顺序拼接（用空格分隔）。
// 用于关键词匹配，比逐节点遍历方便。
fun AccessibilityNodeInfo.collectAllText(): String =
    allNodes().mapNotNull {
        val s = it.combinedText().trim()
        s.takeIf { it.isNotEmpty() }
    }.joinToString(" ")

// 找第一个 text/contentDescription 命中 predicate 的节点。
fun AccessibilityNodeInfo.findFirst(predicate: (String) -> Boolean): AccessibilityNodeInfo? =
    allNodes().firstOrNull { predicate(it.combinedText()) }

// debug：把节点树 dump 成可读字符串。用于 tune Extractor 时把树打印出来。
fun AccessibilityNodeInfo.dumpTree(maxDepth: Int = 20): String {
    val sb = StringBuilder()
    val visited = HashSet<AccessibilityNodeInfo>()

    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > maxDepth) return
        if (!visited.add(node)) return
        repeat(depth) { sb.append("  ") }
        sb.append("[")
        sb.append(node.className?.toString()?.substringAfterLast('.') ?: "?")
        sb.append("]")
        node.viewIdResourceName?.let { sb.append(" id=$it") }
        node.text?.let { if (it.isNotBlank()) sb.append(" text='$it'") }
        node.contentDescription?.let { if (it.isNotBlank()) sb.append(" desc='$it'") }
        sb.append('\n')
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
    }
    walk(this, 0)
    return sb.toString()
}

// 金额解析统一走 AmountText（精确到分，不再经过 Double）。
