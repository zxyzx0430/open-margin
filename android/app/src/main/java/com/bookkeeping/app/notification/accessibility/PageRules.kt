package com.bookkeeping.app.notification.accessibility

import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.CandidateNature
import com.bookkeeping.app.notification.CompletionEvidence
import com.bookkeeping.app.notification.TransactionCandidate

// 页面规则层（蓝图 M3/D03）：在 ScreenSnapshot 上做页面分类与区域定位，
// 产出 TransactionCandidate，交给 SettlementGate 判定，不直接写账本。
// Page rules produce candidates only — the gate decides, the ledger adapter writes.
//
// ⚠️ 本文件的页面文案来自仓库现有提取器与公开已知的页面措辞，属于
// 合成样本基线；D01 真机采样回来后必须逐条核对（专项蓝图 D03→D07 顺序）。
// 待真机验证 / PENDING REAL-DEVICE VERIFICATION (blueprint D01).

/** 精确金额解析（字符串 → 分），避免 Double 乘 100 的舍入误差（如 0.29 元）。 */
object AmountText {
    private val SYMBOL = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")
    private val YUAN = Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元""")
    private val BARE = Regex("""^[¥￥]?\s*([0-9]+\.[0-9]{2})$""")

    fun parseCents(text: String): Long? {
        val raw = SYMBOL.find(text)?.groupValues?.get(1)
            ?: YUAN.find(text)?.groupValues?.get(1)
            ?: BARE.find(text.trim())?.groupValues?.get(1)
            ?: return null
        return toCents(raw)
    }

    /** "12.3" -> 1230, "12.30" -> 1230, "12" -> 1200。输入已由正则保证格式。 */
    fun toCents(raw: String): Long? {
        val parts = raw.split('.')
        val yuan = parts[0].toLongOrNull() ?: return null
        val frac = if (parts.size > 1) parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: return null else 0L
        return yuan * 100 + frac
    }
}

/**
 * 区域定位（M3 RegionLocator）：规则不在整页上分类，而是先定位"区域"——
 * 从状态文案锚点向上，找到同时含状态与金额的最小子树。详情页的区域会
 * 扩大到整页，聊天列表里每张卡片则是各自的区域，金额互不串扰。
 */
object RegionLocator {

    /** 锚点节点在树中的索引路径（root 到锚点，每级是 child 下标）。 */
    fun indexPathTo(root: SnapshotNode, predicate: (SnapshotNode) -> Boolean): List<Int>? {
        if (predicate(root)) return emptyList()
        for ((i, child) in root.children.withIndex()) {
            val sub = indexPathTo(child, predicate)
            if (sub != null) return listOf(i) + sub
        }
        return null
    }

    fun nodeAt(root: SnapshotNode, indexPath: List<Int>): SnapshotNode {
        var node = root
        for (i in indexPath) node = node.children[i]
        return node
    }

    /**
     * 从锚点（由索引路径指定）向上逐级扩大，直到子树内能找到金额为止，
     * 返回该区域的索引路径。整棵树都没有金额时返回锚点自身的路径。
     */
    fun regionPathFor(root: SnapshotNode, anchorIndexPath: List<Int>): List<Int> {
        var path = anchorIndexPath
        while (true) {
            val scope = nodeAt(root, path)
            if (scope.walk().any { AmountText.parseCents(it.combinedText()) != null }) return path
            if (path.isEmpty()) return path
            path = path.dropLast(1)
        }
    }

    /** 在区域内取离锚点最近的金额：先锚点子树，再区域内首个。 */
    fun amountInRegion(root: SnapshotNode, regionPath: List<Int>, anchorPath: List<Int>): Long? {
        val anchor = nodeAt(root, anchorPath)
        anchor.walk().firstNotNullOfOrNull { AmountText.parseCents(it.combinedText()) }?.let { return it }
        val region = nodeAt(root, regionPath)
        return region.walk().firstNotNullOfOrNull { AmountText.parseCents(it.combinedText()) }
    }
}

/**
 * 微信转账页面规则：覆盖转入（已有提取器的语义）与转出（专项 01 指出的缺口）。
 * 按区域分类：一个页面可能含多笔转账（如聊天列表），逐区域产出候选。
 * 只分类 + 定位金额；是否入账由 SettlementGate 决定。
 */
object WechatTransferPageRule {

    /** 页面级入口：返回全部区域的候选（详情页通常 1 个，聊天列表可多个）。 */
    fun classifyAll(snapshot: ScreenSnapshot): List<TransactionCandidate> {
        if (snapshot.packageName != "com.tencent.mm") return emptyList()
        val root = snapshot.root
        if (!root.subtreeTexts().any { it.contains("转账") }) return emptyList()

        // 状态锚点：所有命中状态短语的节点。没有锚点时整页作一个区域
        // （证据不足 → completion=NONE → 进 REVIEW，不猜）。
        val anchors = ArrayList<List<Int>>()
        fun collect(node: SnapshotNode, path: List<Int>) {
            if (STATUS_PHRASES.any { node.combinedText().contains(it.phrase) }) anchors.add(path)
            node.children.forEachIndexed { i, child -> collect(child, path + i) }
        }
        collect(root, emptyList())

        val regionPaths: List<List<Int>> = if (anchors.isEmpty()) {
            listOf(emptyList())
        } else {
            anchors.map { RegionLocator.regionPathFor(root, it) }.distinct()
        }

        return regionPaths.mapNotNull { regionPath ->
            classifyRegion(snapshot, RegionLocator.nodeAt(root, regionPath), root, regionPath, anchors)
        }
    }

    /** 单候选便捷入口（详情页/调用方只关心首个区域时用）。 */
    fun classify(snapshot: ScreenSnapshot): TransactionCandidate? = classifyAll(snapshot).firstOrNull()

    private fun classifyRegion(
        snapshot: ScreenSnapshot,
        region: SnapshotNode,
        root: SnapshotNode,
        regionPath: List<Int>,
        anchors: List<List<Int>>,
    ): TransactionCandidate? {
        val regionText = region.subtreeTexts().joinToString(" ")
        if (!regionText.contains("转账")) return null
        if (regionText.contains("红包")) return null // 红包走 WechatRedPacketExtractor

        // 状态判定顺序固定：先具体后一般。"对方已收钱"必须先于"已收钱"，
        // 否则付款方页面会被误判成收款（旧提取器靠整页排除法防这一点）。
        val status = STATUS_PHRASES.firstOrNull { regionText.contains(it.phrase) }
        val type = status?.type ?: TransactionType.INCOME
        val completion = status?.completion ?: CompletionEvidence.NONE

        // 金额：优先区域内离状态锚点最近的；没有锚点时用区域内首个金额。
        val anchorInRegion = anchors.firstOrNull {
            it.size >= regionPath.size && it.subList(0, regionPath.size) == regionPath
        }
        val amountCents = if (anchorInRegion != null) {
            RegionLocator.amountInRegion(root, regionPath, anchorInRegion)
        } else {
            region.walk().firstNotNullOfOrNull { AmountText.parseCents(it.combinedText()) }
        } ?: 0L

        val merchant = if (type == TransactionType.EXPENSE) {
            Regex("""转账给\s*([^\s，。]{1,20})""").find(regionText)?.groupValues?.get(1).orEmpty()
        } else {
            ""
        }

        return TransactionCandidate(
            amountCents = amountCents,
            type = type,
            nature = CandidateNature.TRANSFER,
            completion = completion,
            // 页面规则拿不到真实发生时间，只能用观察时间近似（待 D01 核对）。
            occurredAtMs = snapshot.capturedAtMs,
            observedAtMs = snapshot.capturedAtMs,
            source = TransactionSource.WECHAT,
            merchant = merchant,
            failReason = if (completion == CompletionEvidence.FAILED) "转账已退回" else "",
        )
    }

    private data class StatusPhrase(
        val phrase: String,
        val type: TransactionType,
        val completion: CompletionEvidence,
    )

    // 顺序即优先级：退回 > 转出已收 > 转出待确认 > 转入已收 > 转入待确认。
    private val STATUS_PHRASES = listOf(
        StatusPhrase("已退回", TransactionType.EXPENSE, CompletionEvidence.FAILED),
        StatusPhrase("对方已收钱", TransactionType.EXPENSE, CompletionEvidence.SETTLED),
        StatusPhrase("已被收钱", TransactionType.EXPENSE, CompletionEvidence.SETTLED),
        StatusPhrase("等待对方确认", TransactionType.EXPENSE, CompletionEvidence.PENDING),
        StatusPhrase("待对方确认", TransactionType.EXPENSE, CompletionEvidence.PENDING),
        StatusPhrase("已收钱", TransactionType.INCOME, CompletionEvidence.SETTLED),
        StatusPhrase("已收款", TransactionType.INCOME, CompletionEvidence.SETTLED),
        StatusPhrase("已存入零钱", TransactionType.INCOME, CompletionEvidence.SETTLED),
        StatusPhrase("已存入", TransactionType.INCOME, CompletionEvidence.SETTLED),
        StatusPhrase("待确认收款", TransactionType.INCOME, CompletionEvidence.PENDING),
        StatusPhrase("请收钱", TransactionType.INCOME, CompletionEvidence.PENDING),
        StatusPhrase("待领取", TransactionType.INCOME, CompletionEvidence.PENDING),
    )
}
