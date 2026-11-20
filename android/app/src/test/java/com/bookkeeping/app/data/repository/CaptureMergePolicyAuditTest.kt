package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6 下游审计：截图候选经用户确认（[com.bookkeeping.app.capture.screenshot.ScreenshotIntakeCoordinator.confirmCandidate]
 * → `TransactionRepository.insertIfNotDuplicate` → `findAutoMergeTarget`）后，不得再被
 * [CaptureMergePolicy] 静默并进已有账目。
 *
 * 设计文档第 7 节：不得仅凭「金额相同 + 几分钟内」自动吞并；连续同额交易必须能保留为
 * 不同账单。唯一允许自动合并的证据是两个强标识（订单号 / 交易号）相等。
 * 没有订单号就不合并：多出来的一行用户能自己删，被吞掉的那笔钱用户看不见。
 */
class CaptureMergePolicyAuditTest {

    private val t0 = 1_700_000_000_000L
    private val orderId = "420000123420260101"

    private fun entity(
        id: Long = 1,
        amountCents: Long = 920,
        type: String = "EXPENSE",
        category: String = TransactionCategory.OTHER.name,
        currency: String = "CNY",
        timestamp: Long = t0,
        source: String = TransactionSource.WECHAT.name,
        merchant: String = "",
        account: String? = "微信",
        note: String = "[扫描] 微信",
        orderId: String? = null,
        duplicateOfId: Long? = null,
        reviewStatus: String = "unverified",
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        type = type,
        category = category,
        currency = currency,
        timestamp = timestamp,
        source = source,
        merchant = merchant,
        account = account,
        note = note,
        sourceTransactionId = orderId,
        duplicateOfId = duplicateOfId,
        remoteReviewStatus = reviewStatus,
    )

    // 1. 强标识相等 → 同一笔，允许自动合并。
    @Test
    fun identicalOrderIdProvesSamePayment() {
        val existing = entity(id = 5, merchant = "禧迎迎生鲜超市(岭南店)", orderId = orderId)
        val candidate = entity(
            id = 0,
            timestamp = t0 + 30_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            orderId = orderId,
        )

        assertTrue(CaptureMergePolicy.isSamePayment(existing, candidate))
    }

    // 2. R6 核心：同商户同额同方向、窗口内、没有订单号 → 不得合并。
    @Test
    fun sameMerchantSameAmountWithoutOrderIdStaysSeparate() {
        val first = entity(id = 5, merchant = "禧迎迎生鲜超市(岭南店)")
        val second = entity(
            id = 0,
            timestamp = t0 + 90_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
        )

        assertFalse(
            "金额 + 商户 + 120 秒窗口不足以证明是同一笔；两笔真实同额交易必须各自成行",
            CaptureMergePolicy.isSamePayment(first, second),
        )
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(first), second))
    }

    // 3. 只有一边带订单号 → 不是「强标识相等」，不合并（通知补全场景也不再自动吞并）。
    @Test
    fun oneSidedOrderIdStaysSeparate() {
        val existing = entity(id = 5, merchant = "禧迎迎生鲜超市(岭南店)")
        val candidate = entity(
            id = 0,
            timestamp = t0 + 20_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            orderId = orderId,
        )

        assertFalse(CaptureMergePolicy.isSamePayment(existing, candidate))
        assertFalse(CaptureMergePolicy.isSamePayment(candidate, existing))
    }

    // 4. 两边订单号不同 → 两笔不同的支付，即使金额商户时间全都一样。
    @Test
    fun differentOrderIdStaysSeparate() {
        val first = entity(id = 5, merchant = "禧迎迎生鲜超市(岭南店)", orderId = "420000000000000001")
        val second = entity(
            id = 0,
            timestamp = t0 + 30_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            orderId = "420000000000000002",
        )

        assertFalse(CaptureMergePolicy.isSamePayment(first, second))
    }

    // 5. 方向不同 → 一笔支出 + 一笔收入，即使订单号相同也不能合并。
    @Test
    fun oppositeDirectionStaysSeparate() {
        val expense = entity(id = 5, merchant = "禧迎迎生鲜超市(岭南店)", orderId = orderId)
        val income = entity(
            id = 0,
            timestamp = t0 + 10_000L,
            type = "INCOME",
            merchant = "禧迎迎生鲜超市(岭南店)",
            orderId = orderId,
        )

        assertFalse(CaptureMergePolicy.isSamePayment(expense, income))
    }

    // 6. 超过 120 秒自动合并窗口 → 不合并（订单号相同也一样，交订单号去重和人工确认）。
    @Test
    fun outsideMergeWindowStaysSeparate() {
        val existing = entity(id = 5, merchant = "禧迎迎生鲜超市(岭南店)", orderId = orderId)
        val tooLate = entity(
            id = 0,
            timestamp = t0 + CaptureMergePolicy.AUTO_MERGE_WINDOW_MS + 1,
            merchant = "禧迎迎生鲜超市(岭南店)",
            orderId = orderId,
        )

        assertFalse(CaptureMergePolicy.isSamePayment(existing, tooLate))
    }

    // 7. 商户不同 → 不合并（两家店可以一分钟内收同样金额）。
    @Test
    fun differentMerchantStaysSeparate() {
        val first = entity(id = 5, merchant = "麦当劳")
        val second = entity(id = 0, timestamp = t0 + 40_000L, merchant = "星巴克")

        assertFalse(CaptureMergePolicy.isSamePayment(first, second))
    }

    // 8. 订单号相同但商户明显矛盾 → 数据自相矛盾，仍然不自动合并，交人工。
    @Test
    fun conflictingMerchantBlocksEvenWhenOrderIdMatches() {
        val first = entity(id = 5, merchant = "麦当劳", orderId = orderId)
        val second = entity(id = 0, timestamp = t0 + 10_000L, merchant = "星巴克", orderId = orderId)

        assertFalse(CaptureMergePolicy.isSamePayment(first, second))
    }

    // 9. 端到端（策略层）：用户连续确认两个同额同商户候选 → 账本两行。
    //
    // 真实的 `confirmCandidate` → `insertIfNotDuplicate` 需要 Android/Room，JVM 单测跑不了；
    // 这里复刻仓库层在「自动合并目标为 null」之后的决策：交给 DuplicateReviewPolicy
    // 打「待确认」标记并插入，绝不返回 null 把候选标成 IGNORED。
    @Test
    fun twoConfirmedSameAmountCandidatesKeepTwoLedgerRows() {
        val first = entity(id = 5, merchant = "禧迎迎生鲜超市(岭南店)")
        val ledger = mutableListOf(first)
        val second = entity(
            id = 0,
            timestamp = t0 + 90_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
        )

        val mergeTarget = CaptureMergePolicy.findMergeTarget(ledger, second)
        assertNull("没有订单号，第二次确认不得并进第一行", mergeTarget)
        // 仓库层此时走 DuplicateReviewPolicy：插新行 + 标 possible_duplicate，不吞掉。
        assertTrue(DuplicateReviewPolicy.shouldFlag(first, second))
        ledger.add(second.copy(id = 6))

        assertEquals(2, ledger.size)
        assertEquals(listOf(5L, 6L), ledger.map { it.id })
        assertEquals(1_840L, ledger.sumOf { it.amountCents })
    }

    // 10. 对照：真正同一笔（两边同一个订单号）仍然合成一行并补全字段。
    @Test
    fun sameOrderIdStillMergesIntoOneRow() {
        val existing = entity(id = 5, merchant = "禧迎迎")
        val ledger = mutableListOf(existing)
        val capturedAgain = entity(
            id = 0,
            timestamp = t0 + 40_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            orderId = orderId,
            category = TransactionCategory.FOOD.name,
        )
        // 第一行也要有同一个订单号，才构成强标识相等。
        ledger[0] = existing.copy(sourceTransactionId = orderId)

        val target = CaptureMergePolicy.findMergeTarget(ledger, capturedAgain)
        assertNotNull(target)
        ledger[0] = CaptureMergePolicy.mergeInto(target!!, capturedAgain)

        assertEquals(1, ledger.size)
        assertEquals(5L, ledger.single().id)
        assertEquals("禧迎迎生鲜超市(岭南店)", ledger.single().merchant)
        assertEquals(TransactionCategory.FOOD.name, ledger.single().category)
        assertEquals(orderId, ledger.single().sourceTransactionId)
    }

    // 11. 历史纠正也不能凭商户 + 金额把两条并掉：没有共同订单号就留给人工确认。
    @Test
    fun legacyRepairLeavesPairsWithoutOrderIdToManualReview() {
        val poor = entity(
            id = 5,
            duplicateOfId = 6,
            reviewStatus = CaptureMergePolicy.STATUS_POSSIBLE_DUPLICATE,
        )
        val rich = entity(
            id = 6,
            timestamp = t0 + 12_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            category = TransactionCategory.FOOD.name,
            orderId = orderId,
        )

        assertNull(CaptureMergePolicy.planRepair(poor, rich))
        // 仍然留在人工确认卡片里，不会被静默藏掉。
        assertTrue(CaptureMergePolicy.needsManualReview(poor))
    }
}
