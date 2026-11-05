package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.CandidateNature
import com.bookkeeping.app.notification.CompletionEvidence
import com.bookkeeping.app.notification.DuplicateResolver
import com.bookkeeping.app.notification.NoticeFingerprint
import com.bookkeeping.app.notification.NotificationTextExtractor
import com.bookkeeping.app.notification.TransactionCandidate
import com.bookkeeping.app.notification.accessibility.AmountText
import com.bookkeeping.app.notification.accessibility.ScreenSnapshot
import com.bookkeeping.app.notification.accessibility.SnapshotNode
import com.bookkeeping.app.notification.accessibility.WechatTransferPageRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

// 鲁棒性/对抗性用例：金额溢出、空白交易号、窗口边界、超深/超宽树、
// Unicode 与超长文本、随机模糊输入下不崩溃且不变量成立。
// Robustness: money paths must never silently wrap, crash, or false-merge.
class CaptureRobustnessTest {

    // ---------- AmountText：金额解析是资金路径，先打这里 ----------

    @Test fun amountOverflowNeverWraps() {
        // 1e17 元 * 100 超出 Long 范围：必须返回 null，绝不能绕回成小额/负数。
        assertNull(AmountText.parseCents("99999999999999999.99 元"))
        assertNull(AmountText.parseCents("￥99999999999999999999"))
        assertNull(AmountText.toCents("9223372036854775807"))
        // The server caps amount_cents at 100,000,000,000; larger values must
        // not be captured locally because they could never be uploaded.
        assertEquals(AmountText.MAX_CENTS, AmountText.parseCents("1000000000.00 元"))
        assertNull(AmountText.parseCents("1000000000.01 元"))
    }

    @Test fun amountMalformedInputsReturnNull() {
        for (s in listOf("", " ", "元", "￥", "abc", "1.2.3 元", "-5 元", "1,000 元", "１２３ 元", "12.345 元")) {
            val v = AmountText.parseCents(s)
            assertTrue("input=$s got $v", v == null || v >= 0)
        }
        assertNull(AmountText.parseCents("1.2.3 元"))
    }

    @Test fun amountFuzzNeverThrowsAndNeverNegative() {
        val rng = Random(20261103)
        val alphabet = "0123456789.￥¥元 abc"
        repeat(20_000) {
            val s = buildString {
                repeat(rng.nextInt(0, 24)) { append(alphabet[rng.nextInt(alphabet.length)]) }
            }
            val v = AmountText.parseCents(s) // must not throw
            assertTrue("input=$s got $v", v == null || v >= 0)
        }
    }

    // ---------- DuplicateResolver：边界与空白证据 ----------

    private fun candidate(
        amount: Long = 1000, txId: String? = null, merchant: String = "星巴克",
        atMs: Long = 1_700_000_000_000L, source: TransactionSource = TransactionSource.WECHAT,
    ) = TransactionCandidate(
        amountCents = amount, type = TransactionType.EXPENSE, nature = CandidateNature.PAYMENT,
        completion = CompletionEvidence.SETTLED, occurredAtMs = atMs, observedAtMs = atMs,
        source = source, sourceTransactionId = txId, merchant = merchant,
    )

    private fun existing(
        id: Long = 1, amount: Long = 1000, txId: String? = null, merchant: String = "星巴克",
        atMs: Long = 1_700_000_000_000L, source: TransactionSource = TransactionSource.WECHAT,
    ) = DuplicateResolver.ExistingTx(id, amount, TransactionType.EXPENSE, atMs, source, merchant, txId)

    @Test fun blankTransactionIdsNeverMatch() {
        val c = candidate(txId = "")
        val e = existing(txId = "")
        // 交易号为空等于没证据；同源同商户同额且同时刻会走重放规则命中，
        // 但绝不能是"交易号命中"路径——用跨源+商户不同隔离掉其他规则：
        val cross = candidate(txId = "  ", source = TransactionSource.ALIPAY, merchant = "别人")
        val r = DuplicateResolver.resolve(cross, listOf(existing(txId = "  ", merchant = "别人家")))
        assertEquals(DuplicateResolver.Decision.POSSIBLE, r.decision) // 仅跨渠道疑似，不是 SAME
    }

    @Test fun windowBoundariesAreInclusive() {
        val base = 1_700_000_000_000L
        val atEdge = candidate(atMs = base + DuplicateResolver.SAME_SOURCE_REPLAY_WINDOW_MS)
        assertEquals(
            DuplicateResolver.Decision.SAME,
            DuplicateResolver.resolve(atEdge, listOf(existing(atMs = base))).decision,
        )
        val pastEdge = candidate(atMs = base + DuplicateResolver.SAME_SOURCE_REPLAY_WINDOW_MS + 1)
        assertEquals(
            DuplicateResolver.Decision.NEW,
            DuplicateResolver.resolve(pastEdge, listOf(existing(atMs = base))).decision,
        )
    }

    @Test fun resolverFuzzInvariants() {
        val rng = Random(42)
        val merchants = listOf("", " ", "星巴克", "麦当劳", "星巴克 ")
        repeat(5_000) {
            val c = candidate(
                amount = rng.nextLong(0, 3) * 500,
                merchant = merchants[rng.nextInt(merchants.size)],
                atMs = 1_700_000_000_000L + rng.nextLong(-300_000, 300_000),
                txId = if (rng.nextBoolean()) null else "tx${rng.nextInt(3)}",
            )
            val pool = (1..rng.nextInt(0, 6)).map { i ->
                existing(
                    id = i.toLong(),
                    amount = rng.nextLong(0, 3) * 500,
                    merchant = merchants[rng.nextInt(merchants.size)],
                    atMs = 1_700_000_000_000L + rng.nextLong(-300_000, 300_000),
                    txId = if (rng.nextBoolean()) null else "tx${rng.nextInt(3)}",
                    source = if (rng.nextBoolean()) TransactionSource.WECHAT else TransactionSource.BANK_SMS,
                )
            }
            val r = DuplicateResolver.resolve(c, pool) // must not throw
            if (r.decision == DuplicateResolver.Decision.SAME) {
                val m = pool.first { it.id == r.matchedId }
                // 交易号是最高优先级证据：金额被用户改过、或渠道回填金额不同，
                // 都不影响"同一笔"判定。
                val txIdHit = c.sourceTransactionId?.takeIf { it.isNotBlank() } != null &&
                    c.sourceTransactionId == m.sourceTransactionId
                if (txIdHit) return@repeat
                // 没有交易号证据时，SAME 必须金额/方向一致且商户与时间窗口成立。
                assertEquals(c.amountCents, m.amountCents)
                assertEquals(c.type, m.type)
                val merchantHit = c.merchant.trim().isNotEmpty() &&
                    c.merchant.trim() == m.merchant.trim()
                val window = if (c.source == m.source) {
                    DuplicateResolver.SAME_SOURCE_REPLAY_WINDOW_MS
                } else {
                    DuplicateResolver.CROSS_SOURCE_WINDOW_MS
                }
                assertTrue(
                    "false SAME without tx id: c=$c m=$m",
                    merchantHit && abs(c.occurredAtMs - m.occurredAtMs) <= window,
                )
            }
        }
    }

    // ---------- NoticeFingerprint：长文本/Unicode/确定性 ----------

    @Test fun fingerprintHandlesHugeAndUnicodeBodies() {
        val huge = "付款 12.30 元 ".repeat(100_000) // ~1.4MB
        val a = NoticeFingerprint.of("pkg", "标题", huge, 1L)
        val b = NoticeFingerprint.of("pkg", "标题", huge, 1L)
        assertEquals(a, b)
        assertEquals(64, a.length)
        val emoji = NoticeFingerprint.of("pkg", "🧧", "红包 ￥8.80 🎉", 7L)
        assertEquals(64, emoji.length)
        assertNotEquals(a, emoji)
    }

    // ---------- NotificationTextExtractor：随机候选不变量 ----------

    @Test fun extractorFuzzInvariants() {
        val rng = Random(7)
        val pieces = listOf(null, "", " ", "微信支付", "付款 12.30 元", "无数字", "9", "  多行  文本 ")
        repeat(5_000) {
            fun pick() = pieces[rng.nextInt(pieces.size)]
            fun pickList() = (0 until rng.nextInt(0, 5)).map { pick() ?: "" }
            val body = NotificationTextExtractor.bestBody(pick(), pick(), pickList(), pickList())
            // 结果要么为 null，要么是某个候选（或其拼接）的原文形态之一
            if (body != null) assertTrue(body.isNotBlank())
            val t = NotificationTextExtractor.resolveOriginalTimeMs(
                postTimeMs = 1_700_000_100_000L,
                notificationWhenMs = if (rng.nextBoolean()) 0L else 1_700_000_000_000L + rng.nextLong(-200_000_000, 200_000_000),
                messageTimesMs = (0 until rng.nextInt(0, 4)).map { 1_700_000_000_000L + rng.nextLong(-200_000_000, 200_000_000) },
            )
            assertTrue(t in (1_700_000_100_000L - 86_400_000L)..1_700_000_100_000L)
        }
    }

    // ---------- ScreenSnapshot：超宽与超深 ----------

    @Test fun snapshotHandlesVeryWideTree() {
        val wide = SnapshotNode(children = (1..50_000).map { SnapshotNode(text = "n$it") })
        val snap = ScreenSnapshot.of("pkg", 0L, wide)
        assertTrue(snap.truncated)
        assertEquals(ScreenSnapshot.DEFAULT_MAX_NODES, snap.nodeCount)
    }

    @Test fun snapshotHandlesVeryDeepTree() {
        var deep = SnapshotNode(text = "bottom")
        repeat(100_000) { deep = SnapshotNode(children = listOf(deep)) }
        val snap = ScreenSnapshot.of("pkg", 0L, deep) // must not stack-overflow
        assertTrue(snap.truncated)
        assertTrue(snap.nodeCount <= ScreenSnapshot.DEFAULT_MAX_NODES)
    }

    // ---------- PageRule：随机页面不崩溃、金额有界 ----------

    @Test fun pageRuleFuzzInvariants() {
        val rng = Random(99)
        val texts = listOf(
            "转账", "转账给 张三", "已收钱", "对方已收钱", "等待对方确认收款", "已退回",
            "￥12.30", "99999999999999999.99 元", "微信红包", "普通文本", "", " ",
        )
        repeat(2_000) {
            val cards = (0 until rng.nextInt(0, 6)).map {
                SnapshotNode(
                    className = "Card",
                    children = (0 until rng.nextInt(0, 5)).map { SnapshotNode(text = texts[rng.nextInt(texts.size)]) },
                )
            }
            val snap = ScreenSnapshot.of("com.tencent.mm", 1L, SnapshotNode(children = cards))
            val all = WechatTransferPageRule.classifyAll(snap) // must not throw
            for (c in all) {
                assertTrue("amount out of range: ${c.amountCents}", c.amountCents in 0..100_000_000_000L)
            }
        }
    }
}
