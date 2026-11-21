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
 * 「通知先记一行（占位商户）+ 用户点开详情页（真实商户）」的处置顺序。
 *
 * 现场：通知侧商户是「微信支付」且永远没有订单号，页面侧有真实商户。旧顺序是
 * 层 2 跨通道（含弱证据）先返回 [DuplicateReason.CROSS_SOURCE_WEAK]，根本走不到
 * 层 3 的自动合并，于是用户还要再确认一次。
 *
 * 这里钉死 [CaptureMergePolicy.crossChannelDisposition] —— 仓库层 2 唯一判定点：
 * - 弱证据能按「一侧缺商户」合并 → MERGE（不新增行、不问用户）；
 * - 超过 120 秒 / 商户矛盾 / 订单号矛盾 → HOLD（仍返回 CROSS_SOURCE_WEAK）；
 * - 强证据（两侧同订单号）在自动路径仍然 HOLD（CROSS_SOURCE），只有用户显式确认
 *   才允许 MERGE 补全；
 * - 两笔同额同商户、订单号不同的真实交易永远不合并。
 *
 * Room 查询与真实入库需要设备，这里只覆盖纯逻辑。
 */
class CrossChannelMergeOrderTest {

    private val t0 = 1_795_000_000_000L

    private fun row(
        id: Long = 0,
        amountCents: Long = 2000,
        type: String = "EXPENSE",
        merchant: String = "微信支付",
        timestamp: Long = t0,
        source: String = TransactionSource.WECHAT.name,
        orderId: String? = null,
        fromPage: Boolean = false,
        reviewStatus: String = "unverified",
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        type = type,
        category = TransactionCategory.OTHER.name,
        merchant = merchant,
        note = if (fromPage) "[扫描] 微信" else "[通知] 微信",
        source = source,
        timestamp = timestamp,
        currency = "CNY",
        sourceTransactionId = orderId,
        captureObservedAtMs = if (fromPage) timestamp else null,
        remoteReviewStatus = reviewStatus,
    )

    private fun weakMatch(notification: TransactionEntity, page: TransactionEntity) =
        CrossSourceDuplicatePolicy.findMatchDetailed(
            candidates = listOf(notification),
            incoming = page,
            windowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            allowWeakEvidence = true,
        )

    // ---------- B：弱证据先合并在前 ----------

    @Test
    fun `notification placeholder plus page merchant merges instead of asking`() {
        val notification = row(id = 10, merchant = "微信支付", timestamp = t0)
        val page = row(
            id = 0,
            merchant = "麦当劳",
            timestamp = t0 + 20_000L,
            orderId = "4200001234567890",
            fromPage = true,
        )

        val match = weakMatch(notification, page)
        assertNotNull(match)
        assertEquals(CrossSourceDuplicatePolicy.MatchStrength.WEAK, match!!.strength)

        assertEquals(
            CaptureMergePolicy.CrossChannelDisposition.MERGE_INTO_EXISTING,
            CaptureMergePolicy.crossChannelDisposition(
                matched = match.transaction,
                incoming = page,
                weak = true,
                confirmedByUser = false,
            ),
        )

        val merged = CaptureMergePolicy.mergeInto(match.transaction, page)
        // 账本不增行：保留先入库那条的 id / 时间 / 金额，只补全信息。
        assertEquals(10L, merged.id)
        assertEquals(t0, merged.timestamp)
        assertEquals(2000L, merged.amountCents)
        assertEquals("麦当劳", merged.merchant)
        assertEquals("4200001234567890", merged.sourceTransactionId)
        assertEquals(t0 + 20_000L, merged.captureObservedAtMs)
    }

    @Test
    fun `weak match outside the merge window stays held for review`() {
        val notification = row(id = 10, merchant = "微信支付", timestamp = t0)
        // 3 分钟的弱证据窗口内，但超过 120 秒的自动合并窗口。
        val page = row(
            id = 0,
            merchant = "麦当劳",
            timestamp = t0 + 180_000L,
            fromPage = true,
        )

        val match = weakMatch(notification, page)
        assertNotNull(match)
        assertEquals(CrossSourceDuplicatePolicy.MatchStrength.WEAK, match!!.strength)

        assertEquals(
            CaptureMergePolicy.CrossChannelDisposition.HOLD_FOR_REVIEW,
            CaptureMergePolicy.crossChannelDisposition(
                matched = match.transaction,
                incoming = page,
                weak = true,
                confirmedByUser = false,
            ),
        )
    }

    @Test
    fun `weak match with contradicting order ids stays held`() {
        val notification = row(id = 10, merchant = "微信支付", timestamp = t0, orderId = "AAA111")
        val page = row(
            id = 0,
            merchant = "麦当劳",
            timestamp = t0 + 10_000L,
            orderId = "BBB222",
            fromPage = true,
        )

        assertEquals(
            CaptureMergePolicy.CrossChannelDisposition.HOLD_FOR_REVIEW,
            CaptureMergePolicy.crossChannelDisposition(
                matched = notification,
                incoming = page,
                weak = true,
                confirmedByUser = false,
            ),
        )
        assertNull(CaptureMergePolicy.planConfirmedMerge(notification, page))
    }

    @Test
    fun `two sided different merchants stay held`() {
        val notification = row(id = 10, merchant = "肯德基", timestamp = t0)
        val page = row(id = 0, merchant = "麦当劳", timestamp = t0 + 10_000L, fromPage = true)

        // Weak evidence never matched two real merchant names; the held branch must
        // not merge them either.
        assertEquals(
            CaptureMergePolicy.CrossChannelDisposition.HOLD_FOR_REVIEW,
            CaptureMergePolicy.crossChannelDisposition(
                matched = notification,
                incoming = page,
                weak = false,
                confirmedByUser = false,
            ),
        )
    }

    // ---------- 强证据：自动路径不合并，确认后才补全 ----------

    @Test
    fun `strong order id match is not merged on the automatic path`() {
        val notification = row(id = 10, merchant = "微信支付", timestamp = t0, orderId = "4200001234567890")
        val page = row(
            id = 0,
            merchant = "麦当劳",
            timestamp = t0 + 10_000L,
            orderId = "4200001234567890",
            fromPage = true,
        )

        val strong = CrossSourceDuplicatePolicy.findMatch(
            candidates = listOf(notification),
            incoming = page,
            windowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
        )
        assertNotNull(strong)

        // 自动路径：仍然按 CROSS_SOURCE 处理，不静默合并。
        assertEquals(
            CaptureMergePolicy.CrossChannelDisposition.HOLD_FOR_REVIEW,
            CaptureMergePolicy.crossChannelDisposition(
                matched = notification,
                incoming = page,
                weak = false,
                confirmedByUser = false,
            ),
        )
    }

    @Test
    fun `strong order id match merges once the user confirmed`() {
        val notification = row(id = 10, merchant = "微信支付", timestamp = t0, orderId = "4200001234567890")
        val page = row(
            id = 0,
            merchant = "麦当劳",
            timestamp = t0 + 10_000L,
            orderId = "4200001234567890",
            fromPage = true,
        )

        assertEquals(
            CaptureMergePolicy.CrossChannelDisposition.MERGE_INTO_EXISTING,
            CaptureMergePolicy.crossChannelDisposition(
                matched = notification,
                incoming = page,
                weak = false,
                confirmedByUser = true,
            ),
        )

        val merged = CaptureMergePolicy.planConfirmedMerge(notification, page)
        assertNotNull(merged)
        assertEquals(10L, merged!!.id)
        assertEquals("麦当劳", merged.merchant)
        assertEquals("4200001234567890", merged.sourceTransactionId)
        assertEquals(t0 + 10_000L, merged.captureObservedAtMs)
    }

    // ---------- 安全性：两笔真实交易永远各自成行 ----------

    @Test
    fun `two real payments with different order ids are never merged`() {
        val first = row(id = 10, merchant = "麦当劳", timestamp = t0, orderId = "AAA111", fromPage = true)
        val second = row(id = 0, merchant = "麦当劳", timestamp = t0 + 30_000L, orderId = "BBB222", fromPage = true)

        assertFalse(CaptureMergePolicy.isSamePayment(first, second))
        assertFalse(CaptureMergePolicy.isSamePaymentWithOneSidedMerchant(first, second))
        assertNull(CaptureMergePolicy.planConfirmedMerge(first, second))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(first), second))
    }

    @Test
    fun `same amount same merchant without order ids is never merged`() {
        val first = row(id = 10, merchant = "麦当劳", timestamp = t0)
        val second = row(id = 0, merchant = "麦当劳", timestamp = t0 + 30_000L)

        assertFalse(CaptureMergePolicy.isSamePayment(first, second))
        assertFalse(CaptureMergePolicy.isSamePaymentWithOneSidedMerchant(first, second))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(first), second))
    }

    @Test
    fun `two real payments on the same platform are not a cross channel match`() {
        val first = row(id = 10, merchant = "麦当劳", timestamp = t0, fromPage = true)
        val second = row(id = 0, merchant = "麦当劳", timestamp = t0 + 30_000L, fromPage = true)

        assertNull(
            CrossSourceDuplicatePolicy.findMatchDetailed(
                candidates = listOf(first),
                incoming = second,
                windowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
                allowWeakEvidence = true,
            ),
        )
    }

    @Test
    fun `merged or excluded rows are never used as merge targets`() {
        val excluded = row(id = 11, merchant = "微信支付", timestamp = t0, reviewStatus = "excluded")
        val page = row(id = 0, merchant = "麦当劳", timestamp = t0 + 5_000L, fromPage = true)

        assertFalse(CaptureMergePolicy.canReceiveMerge(excluded))
        assertEquals(
            CaptureMergePolicy.CrossChannelDisposition.HOLD_FOR_REVIEW,
            CaptureMergePolicy.crossChannelDisposition(
                matched = excluded,
                incoming = page,
                weak = true,
                confirmedByUser = true,
            ),
        )
        assertTrue(CaptureMergePolicy.isAutoMergedDuplicate(excluded.copy(duplicateOfId = 9L)))
    }
}
