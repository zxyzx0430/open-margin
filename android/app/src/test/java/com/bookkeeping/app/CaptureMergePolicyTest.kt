package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.repository.CaptureMergePolicy
import com.bookkeeping.app.data.repository.DuplicateReviewPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集自动合并规则单测：一笔 9.20 元微信支付被「通知文本」和「支付成功页」
 * 各扫一次时，必须自动合成一条并保留信息最全的版本。
 */
class CaptureMergePolicyTest {

    private val t0 = 1_700_000_000_000L

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
        note: String = "[自动抓取] 微信支付凭证",
        orderId: String? = null,
        duplicateOfId: Long? = null,
        reviewStatus: String = "unverified",
        merchantIconKey: String? = null,
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
        merchantIconKey = merchantIconKey,
    )

    @Test
    fun emptyMerchantIsMergedWithRichMerchant() {
        // 两个入口带同一个订单号：强标识相等才允许并成一条，空商户由富记录补全。
        val fromNotification = entity(id = 5, orderId = "420000123420260101")
        val fromPaymentPage = entity(
            id = 0,
            timestamp = t0 + 9_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            category = TransactionCategory.FOOD.name,
            account = null,
            orderId = "420000123420260101",
        )

        assertTrue(CaptureMergePolicy.isSamePayment(fromNotification, fromPaymentPage))

        val merged = CaptureMergePolicy.mergeInto(fromNotification, fromPaymentPage)
        assertEquals(5L, merged.id)
        assertEquals("禧迎迎生鲜超市(岭南店)", merged.merchant)
        // 旧分类是「其他」，新记录给了具体分类 → 采用具体分类
        assertEquals(TransactionCategory.FOOD.name, merged.category)
        // 旧记录已有的账户不被空值覆盖
        assertEquals("微信", merged.account)
        assertEquals(t0, merged.timestamp)
        assertEquals(920L, merged.amountCents)
        assertEquals(TransactionSource.WECHAT.name, merged.source)
    }

    @Test
    fun orderIdOnOneSideOnlyIsNotEnoughToMerge() {
        val existing = entity(id = 7, merchant = "禧迎迎", account = null, note = "")
        val incoming = entity(
            id = 0,
            timestamp = t0 + 20_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            orderId = "420000123420260101",
            category = TransactionCategory.FOOD.name,
        )

        // 只有一边有订单号 → 不是「强标识相等」，不自动吞并，两笔各自成行。
        assertFalse(CaptureMergePolicy.isSamePayment(existing, incoming))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(existing), incoming))
        // 仍然按「可能重复」交人工确认，不丢记录
        assertTrue(DuplicateReviewPolicy.shouldFlag(existing, incoming))

        // mergeInto 的补全能力本身保留：真的合并（历史纠正）时订单号仍会写回
        val merged = CaptureMergePolicy.mergeInto(existing, incoming)
        assertEquals("420000123420260101", merged.sourceTransactionId)
        // 支付成功页的商户名更具体 → 升级，但不丢原有 id
        assertEquals("禧迎迎生鲜超市(岭南店)", merged.merchant)
        // 旧记录为空的备注/账户由新记录补齐
        assertEquals("[自动抓取] 微信支付凭证", merged.note)
        assertEquals("微信", merged.account)
        assertEquals(7L, merged.id)
    }

    @Test
    fun beyondMergeWindowIsNotMerged() {
        val existing = entity(timestamp = t0, orderId = "420000123420260101")
        val tooLate = entity(
            id = 0,
            timestamp = t0 + CaptureMergePolicy.AUTO_MERGE_WINDOW_MS + 1,
            merchant = "禧迎迎生鲜超市(岭南店)",
            orderId = "420000123420260101",
        )
        val atEdge = entity(
            id = 0,
            timestamp = t0 + CaptureMergePolicy.AUTO_MERGE_WINDOW_MS,
            merchant = "禧迎迎生鲜超市(岭南店)",
            orderId = "420000123420260101",
        )

        assertFalse(CaptureMergePolicy.isSamePayment(existing, tooLate))
        assertTrue(CaptureMergePolicy.isSamePayment(existing, atEdge))
        // 自动合并窗口必须窄于「可能重复」的人工确认窗口
        assertTrue(CaptureMergePolicy.AUTO_MERGE_WINDOW_MS < DuplicateReviewPolicy.WINDOW_MS)
    }

    @Test
    fun differentSourceIsNeverMerged() {
        val manual = entity(id = 3, source = TransactionSource.MANUAL.name, merchant = "")
        val wechat = entity(id = 0, merchant = "禧迎迎生鲜超市(岭南店)")
        val bankSms = entity(id = 0, source = TransactionSource.BANK_SMS.name, merchant = "")

        assertFalse(CaptureMergePolicy.isSamePayment(manual, wechat))
        assertFalse(CaptureMergePolicy.isSamePayment(bankSms, wechat))
    }

    @Test
    fun clearlyDifferentMerchantsFromSameSourceAreNotMerged() {
        val first = entity(id = 2, merchant = "麦当劳")
        val second = entity(id = 0, merchant = "星巴克", orderId = "4200009999")

        assertFalse(CaptureMergePolicy.isSamePayment(first, second))
        // 金额/时间接近但没有合并证据 → 仍然回到现有的人工确认逻辑
        assertTrue(DuplicateReviewPolicy.shouldFlag(first, second))
    }

    @Test
    fun richRecordIsNotDowngradedByPoorRecord() {
        val rich = entity(
            id = 9,
            merchant = "禧迎迎生鲜超市(岭南店)",
            category = TransactionCategory.FOOD.name,
            account = "微信",
            note = "[扫描] 微信 · 单号 420000123",
            orderId = "420000123",
            merchantIconKey = "catalog:xiyingying",
        )
        val poor = entity(id = 0, note = "[自动抓取] 微信支付凭证")

        val merged = CaptureMergePolicy.mergeInto(rich, poor)

        assertEquals(9L, merged.id)
        assertEquals("禧迎迎生鲜超市(岭南店)", merged.merchant)
        assertEquals(TransactionCategory.FOOD.name, merged.category)
        assertEquals("[扫描] 微信 · 单号 420000123", merged.note)
        assertEquals("420000123", merged.sourceTransactionId)
        assertEquals("catalog:xiyingying", merged.merchantIconKey)
        assertEquals("微信", merged.account)
    }

    @Test
    fun mergeClearsPossibleDuplicateMarker() {
        val flagged = entity(
            id = 11,
            duplicateOfId = 4,
            reviewStatus = CaptureMergePolicy.STATUS_POSSIBLE_DUPLICATE,
        )
        val incoming = entity(
            id = 0,
            timestamp = t0 + 5_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            category = TransactionCategory.FOOD.name,
        )

        val merged = CaptureMergePolicy.mergeInto(flagged, incoming)

        assertNull(merged.duplicateOfId)
        assertEquals("unverified", merged.remoteReviewStatus)
        assertFalse(CaptureMergePolicy.needsManualReview(merged))
        assertFalse(CaptureMergePolicy.isAutoMergedDuplicate(merged))
        assertEquals("禧迎迎生鲜超市(岭南店)", merged.merchant)
    }

    @Test
    fun pendingReviewFlagIsKeptWhenRecordIsNotAPossibleDuplicate() {
        val confirmed = entity(id = 12, reviewStatus = "confirmed", merchant = "")
        val incoming = entity(id = 0, merchant = "禧迎迎生鲜超市(岭南店)")

        val merged = CaptureMergePolicy.mergeInto(confirmed, incoming)

        assertEquals("confirmed", merged.remoteReviewStatus)
        assertNull(merged.duplicateOfId)
    }

    @Test
    fun historicalPairRepairKeepsTheRichRowAndHidesThePoorOne() {
        // 历史重复对能自动纠正的前提：两条都带同一个订单号（强标识相等）。
        val poor = entity(
            id = 5,
            duplicateOfId = 6,
            reviewStatus = CaptureMergePolicy.STATUS_POSSIBLE_DUPLICATE,
            orderId = "420000123420260101",
        )
        val rich = entity(
            id = 6,
            timestamp = t0 + 12_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            category = TransactionCategory.FOOD.name,
            orderId = "420000123420260101",
            note = "[扫描] 微信 · 单号 420000123420260101",
        )

        // 队列里先出现穷的那条（duplicateOfId 指向富的那条），也必须保留富的那条
        val plan = CaptureMergePolicy.planRepair(poor, rich)
        assertNotNull(plan)
        plan!!

        assertEquals(6L, plan.keep.id)
        assertEquals("禧迎迎生鲜超市(岭南店)", plan.keep.merchant)
        assertEquals("420000123420260101", plan.keep.sourceTransactionId)
        assertNull(plan.keep.duplicateOfId)
        assertEquals("unverified", plan.keep.remoteReviewStatus)

        // 穷的那条不删除：标成「已合并」、指向保留行，列表默认不显示
        assertEquals(5L, plan.absorbed.id)
        assertEquals(6L, plan.absorbed.duplicateOfId)
        assertEquals(CaptureMergePolicy.STATUS_MERGED_DUPLICATE, plan.absorbed.remoteReviewStatus)
        assertTrue(CaptureMergePolicy.isAutoMergedDuplicate(plan.absorbed))
        assertFalse(CaptureMergePolicy.needsManualReview(plan.absorbed))
        // 已被合并掉的行不能再被当成合并目标
        assertFalse(CaptureMergePolicy.canReceiveMerge(plan.absorbed))
    }

    @Test
    fun historicalPairRepairDoesNotTouchUnrelatedPairs() {
        val manual = entity(id = 5, source = TransactionSource.MANUAL.name, merchant = "现金")
        val wechat = entity(id = 6, merchant = "禧迎迎生鲜超市(岭南店)", orderId = "420000123")

        assertNull(CaptureMergePolicy.planRepair(manual, wechat))

        val transfer = entity(
            id = 8,
            merchant = "禧迎迎",
            reviewStatus = "transfer",
        )
        val payment = entity(id = 9, merchant = "禧迎迎生鲜超市(岭南店)")
        assertNull(CaptureMergePolicy.planRepair(transfer, payment))
    }

    @Test
    fun placeholderMerchantCountsAsMissingMerchant() {
        assertFalse(CaptureMergePolicy.hasMerchantInfo(""))
        assertFalse(CaptureMergePolicy.hasMerchantInfo("   "))
        assertFalse(CaptureMergePolicy.hasMerchantInfo("未知商户"))
        assertFalse(CaptureMergePolicy.hasMerchantInfo("微信支付"))
        assertTrue(CaptureMergePolicy.hasMerchantInfo("禧迎迎生鲜超市(岭南店)"))

        val placeholder = entity(id = 1, merchant = "微信支付", category = TransactionCategory.OTHER.name)
        val real = entity(id = 0, merchant = "禧迎迎生鲜超市(岭南店)", category = TransactionCategory.FOOD.name)
        val merged = CaptureMergePolicy.mergeInto(placeholder, real)
        assertEquals("禧迎迎生鲜超市(岭南店)", merged.merchant)
        assertEquals(TransactionCategory.FOOD.name, merged.category)
    }

    @Test
    fun oneSidedMerchantAndCloseAmountTimeRequireExactPlatformOrderIds() {
        val placeholder = entity(id = 21, merchant = "微信支付").copy(captureObservedAtMs = null)
        val richWithoutId = entity(
            id = 0,
            merchant = "Fixture shop",
            account = null,
            orderId = null,
            timestamp = t0 + 1_000L,
        ).copy(captureObservedAtMs = t0 + 1_000L)

        assertFalse(CaptureMergePolicy.isSamePaymentWithOneSidedMerchant(placeholder, richWithoutId))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(placeholder), richWithoutId))
        assertNull(CaptureMergePolicy.planConfirmedMerge(placeholder, richWithoutId))

        val onlyOneId = richWithoutId.copy(sourceTransactionId = "FIXTURE-ORDER-A")
        assertFalse(CaptureMergePolicy.isSamePaymentWithOneSidedMerchant(placeholder, onlyOneId))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(placeholder), onlyOneId))

        val exactIdOnBothSides = placeholder.copy(sourceTransactionId = "FIXTURE-ORDER-A")
        assertTrue(CaptureMergePolicy.isSamePaymentWithOneSidedMerchant(exactIdOnBothSides, onlyOneId))
        assertEquals(
            exactIdOnBothSides.id,
            CaptureMergePolicy.findMergeTarget(listOf(exactIdOnBothSides), onlyOneId)?.id,
        )

        val distinctId = onlyOneId.copy(sourceTransactionId = "FIXTURE-ORDER-B")
        assertFalse(CaptureMergePolicy.isSamePaymentWithOneSidedMerchant(exactIdOnBothSides, distinctId))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(exactIdOnBothSides), distinctId))
    }

    @Test
    fun notificationThenPaymentPageKeepsOneEnrichedRow() {
        // 两个入口抓到同一笔，且都带同一个订单号（强标识相等），才能自动合成一条。
        val orderId = "420000123420260101"
        // 1) 通知先入库：商户为空、分类「其他」，id = 5
        val ledger = mutableListOf(entity(id = 5, orderId = orderId))
        // 2) 支付成功页随后到：完整商户 + 订单号 + 具体分类
        val fromPaymentPage = entity(
            id = 0,
            timestamp = t0 + 4_000L,
            merchant = "禧迎迎生鲜超市(岭南店)",
            category = TransactionCategory.FOOD.name,
            orderId = orderId,
            account = null,
        )

        val target = CaptureMergePolicy.findMergeTarget(ledger, fromPaymentPage)
        assertNotNull(target)
        ledger[0] = CaptureMergePolicy.mergeInto(target!!, fromPaymentPage)

        // 没有插入第二行，旧行拿到全部有效信息
        assertEquals(1, ledger.size)
        assertEquals(5L, ledger.single().id)
        assertEquals("禧迎迎生鲜超市(岭南店)", ledger.single().merchant)
        assertEquals("420000123420260101", ledger.single().sourceTransactionId)
        assertEquals(TransactionCategory.FOOD.name, ledger.single().category)
        assertNull(ledger.single().duplicateOfId)
        assertEquals("unverified", ledger.single().remoteReviewStatus)

        // 3) 通知重发（同一个订单号）继续并进同一条，不会产生第三行
        val repost = entity(id = 0, timestamp = t0 + 30_000L, orderId = orderId)
        val again = CaptureMergePolicy.findMergeTarget(ledger, repost)
        assertNotNull(again)
        assertEquals(5L, again!!.id)
        assertEquals("禧迎迎生鲜超市(岭南店)", CaptureMergePolicy.mergeInto(again, repost).merchant)
        assertEquals(1, ledger.size)
    }

    @Test
    fun mergeTargetSkipsRowsAlreadyMergedAway() {
        val keptRich = entity(
            id = 6,
            merchant = "禧迎迎生鲜超市(岭南店)",
            category = TransactionCategory.FOOD.name,
            orderId = "420000123",
        )
        val mergedAway = entity(
            id = 5,
            duplicateOfId = 6,
            reviewStatus = CaptureMergePolicy.STATUS_MERGED_DUPLICATE,
        )

        val target = CaptureMergePolicy.findMergeTarget(
            candidates = listOf(mergedAway, keptRich),
            incoming = entity(id = 0, timestamp = t0 + 60_000L, orderId = "420000123"),
        )

        assertNotNull(target)
        assertEquals(6L, target!!.id)
    }

    @Test
    fun noMergeTargetWhenOnlyCrossSourceRowExists() {
        val manual = entity(id = 2, source = TransactionSource.MANUAL.name, merchant = "现金")

        assertNull(
            CaptureMergePolicy.findMergeTarget(
                candidates = listOf(manual),
                incoming = entity(id = 0, merchant = "麦当劳"),
            )
        )
    }
}
