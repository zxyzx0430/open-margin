package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.CompletionEvidence
import com.bookkeeping.app.notification.GateDecision
import com.bookkeeping.app.notification.SettlementGate
import com.bookkeeping.app.notification.accessibility.AmountText
import com.bookkeeping.app.notification.accessibility.ScreenSnapshot
import com.bookkeeping.app.notification.accessibility.SnapshotNode
import com.bookkeeping.app.notification.accessibility.WechatTransferPageRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// D03 合成样本回归：页面树是按现有提取器文案与已知页面措辞手工构造的，
// 不是真机采样。D01 真机样本回来后，这些样本要逐条与真实页面核对替换。
// SYNTHETIC FIXTURES — must be re-validated against real-device samples (D01).
class WechatTransferPageRuleTest {

    private fun page(vararg children: SnapshotNode): ScreenSnapshot =
        ScreenSnapshot.of("com.tencent.mm", 1_700_000_000_000L, SnapshotNode(children = children.toList()))

    private fun card(vararg texts: String): SnapshotNode =
        SnapshotNode(className = "Card", children = texts.map { SnapshotNode(text = it) })

    @Test fun incomeSettledAcceptedWithExactCents() {
        val snap = page(card("转账", "￥0.29", "已收钱", "已存入零钱"))
        val c = WechatTransferPageRule.classify(snap)!!
        assertEquals(TransactionType.INCOME, c.type)
        assertEquals(CompletionEvidence.SETTLED, c.completion)
        assertEquals(29L, c.amountCents) // 0.29 元：Double 路径会算成 28
        assertEquals(GateDecision.ACCEPTED, SettlementGate.evaluate(c))
    }

    @Test fun expenseSettledIsNotMisreadAsIncome() {
        val snap = page(card("转账给 张三", "￥88.00", "对方已收钱"))
        val c = WechatTransferPageRule.classify(snap)!!
        assertEquals(TransactionType.EXPENSE, c.type)
        assertEquals(CompletionEvidence.SETTLED, c.completion)
        assertEquals(8800L, c.amountCents)
        assertEquals("张三", c.merchant)
        assertEquals(GateDecision.ACCEPTED, SettlementGate.evaluate(c))
    }

    @Test fun expensePendingIgnoredByGate() {
        val snap = page(card("转账给 李四", "￥50.00", "等待对方确认收款"))
        val c = WechatTransferPageRule.classify(snap)!!
        assertEquals(TransactionType.EXPENSE, c.type)
        assertEquals(CompletionEvidence.PENDING, c.completion)
        assertEquals(GateDecision.IGNORED, SettlementGate.evaluate(c))
    }

    @Test fun refundedTransferIgnoredByGate() {
        val snap = page(card("转账给 王五", "￥20.00", "已退回"))
        val c = WechatTransferPageRule.classify(snap)!!
        assertEquals(CompletionEvidence.FAILED, c.completion)
        assertEquals(GateDecision.IGNORED, SettlementGate.evaluate(c))
    }

    @Test fun incomePendingIgnoredByGate() {
        val snap = page(card("转账", "￥66.00", "待确认收款", "请收钱"))
        val c = WechatTransferPageRule.classify(snap)!!
        assertEquals(TransactionType.INCOME, c.type)
        assertEquals(CompletionEvidence.PENDING, c.completion)
        assertEquals(GateDecision.IGNORED, SettlementGate.evaluate(c))
    }

    @Test fun redPacketPageNotClassified() {
        val snap = page(card("微信红包", "￥8.80", "已收钱"))
        assertNull(WechatTransferPageRule.classify(snap))
    }

    @Test fun multiCardPageYieldsOneCandidatePerCard() {
        // 聊天页里有两张卡：一张转出待确认，一张转入已收钱。
        // 整页分类必然顾此失彼，必须按区域逐个分类、逐个过门。
        val snap = page(
            card("转账给 赵六", "￥999.00", "等待对方确认收款"),
            card("转账", "￥12.30", "已收钱"),
        )
        val all = WechatTransferPageRule.classifyAll(snap)
        assertEquals(2, all.size)
        val expense = all.first { it.type == TransactionType.EXPENSE }
        assertEquals(99900L, expense.amountCents)
        assertEquals(GateDecision.IGNORED, SettlementGate.evaluate(expense))
        val income = all.first { it.type == TransactionType.INCOME }
        assertEquals(1230L, income.amountCents)
        assertEquals(GateDecision.ACCEPTED, SettlementGate.evaluate(income))
    }

    @Test fun noStatusEvidenceGoesToReview() {
        val snap = page(card("转账详情", "￥10.00"))
        val c = WechatTransferPageRule.classify(snap)!!
        assertEquals(CompletionEvidence.NONE, c.completion)
        assertEquals(GateDecision.REVIEW, SettlementGate.evaluate(c))
    }

    @Test fun amountTextParsesExactly() {
        assertEquals(1230L, AmountText.parseCents("付款 12.30 元"))
        assertEquals(1200L, AmountText.parseCents("￥12"))
        assertEquals(5L, AmountText.parseCents("0.05 元"))
        assertNull(AmountText.parseCents("没有金额"))
    }
}
