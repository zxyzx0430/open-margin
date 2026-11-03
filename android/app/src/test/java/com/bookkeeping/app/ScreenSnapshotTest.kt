package com.bookkeeping.app

import com.bookkeeping.app.notification.accessibility.ScreenSnapshot
import com.bookkeeping.app.notification.accessibility.SnapshotNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSnapshotTest {

    private fun leaf(text: String) = SnapshotNode(text = text)

    @Test fun structureAndFlattenOrderPreserved() {
        val root = SnapshotNode(
            className = "Root",
            children = listOf(
                SnapshotNode(text = "标题", children = listOf(leaf("金额 12.30 元"))),
                leaf("页脚"),
            ),
        )
        val snap = ScreenSnapshot.of("pkg", 1L, root)
        assertFalse(snap.truncated)
        assertEquals(4, snap.nodeCount)
        assertEquals("标题 金额 12.30 元 页脚", snap.flattenText())
    }

    @Test fun siblingCardsKeepAmountsSeparate() {
        // 多卡页面：两张卡各自子树里的金额不能串到一起（专项 01 的老问题）。
        fun card(name: String, amount: String) = SnapshotNode(
            className = "Card",
            children = listOf(leaf(name), leaf(amount)),
        )
        val root = SnapshotNode(children = listOf(card("星巴克", "32.00 元"), card("麦当劳", "12.30 元")))
        val snap = ScreenSnapshot.of("pkg", 1L, root)
        val cards = snap.findAll { it.className == "Card" }
        assertEquals(2, cards.size)
        assertEquals(listOf("星巴克", "32.00 元"), cards[0].subtreeTexts())
        assertEquals(listOf("麦当劳", "12.30 元"), cards[1].subtreeTexts())
    }

    @Test fun nodeCapTruncatesAndFlags() {
        val wide = SnapshotNode(children = (1..600).map { leaf("n$it") })
        val snap = ScreenSnapshot.of("pkg", 1L, wide, maxNodes = 100)
        assertTrue(snap.truncated)
        assertEquals(100, snap.nodeCount)
        // 深度优先保留靠前的节点
        assertEquals("n1", snap.root.children.first().text)
    }

    @Test fun depthCapPrunesDeepSubtrees() {
        var deep: SnapshotNode = leaf("bottom")
        repeat(10) { deep = SnapshotNode(children = listOf(deep)) }
        val snap = ScreenSnapshot.of("pkg", 1L, deep, maxDepth = 3)
        assertTrue(snap.truncated)
        assertEquals("", snap.flattenText())
    }

    @Test fun combinedTextMergesDescLikeExistingUtils() {
        assertEquals("文字", SnapshotNode(text = "文字").combinedText())
        assertEquals("描述", SnapshotNode(contentDesc = "描述").combinedText())
        assertEquals("文字 / 描述", SnapshotNode(text = "文字", contentDesc = "描述").combinedText())
    }
}
