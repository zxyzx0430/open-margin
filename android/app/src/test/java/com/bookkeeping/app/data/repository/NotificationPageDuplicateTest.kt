package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.notification.accessibility.PageCaptureOutcome
import com.bookkeeping.app.notification.accessibility.PageCaptureResultPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「支付通知先记一笔、点开页面又记一笔」的回归测试（纯逻辑层）。
 *
 * 现场链路：
 * 1. 微信/支付宝支付通知先入账一行，商户常常是「微信支付」占位名，且**永远没有订单号**；
 * 2. 用户点开交易详情页，无障碍识别又抓到同一笔，页面有真实商户、可能带订单号；
 * 3. 旧实现里 [CrossSourceDuplicatePolicy.findMatch] 只认两侧相同的订单号，
 *    [CaptureMergePolicy.isSamePayment] 同样只认订单号 → 两条都拦不住 → 第二行。
 *
 * 这里锁死三件事：
 * - 页面路径允许「一侧没有商户信息」的**弱证据**命中，但只作为「疑似」，候选必须留在
 *   待确认列表（[PageCaptureOutcome.HOLD_FOR_REVIEW]），不能当已入账丢掉；
 * - 自动合并允许「通知缺商户 + 页面有商户」，保留信息更全的一行并补全商户/订单号；
 * - 两笔真实交易（不同商户 / 不同订单号 / 两侧都缺商户和订单号）绝不被合并或拦下。
 *
 * Room 查询、真实入库属于端到端范围，未在此验证。
 */
class NotificationPageDuplicateTest {

    private val t0 = 1_795_000_000_000L

    private fun row(
        id: Long = 1,
        amountCents: Long = 1230,
        type: String = "EXPENSE",
        currency: String = "CNY",
        merchant: String = "微信支付",
        timestamp: Long = t0,
        source: String = TransactionSource.WECHAT.name,
        orderId: String? = null,
        fromPage: Boolean = false,
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        type = type,
        category = TransactionCategory.OTHER.name,
        merchant = merchant,
        note = if (fromPage) "[扫描] 微信" else "[通知] 微信",
        source = source,
        timestamp = timestamp,
        currency = currency,
        sourceTransactionId = orderId,
        captureObservedAtMs = if (fromPage) timestamp else null,
    )

    private fun weakMatch(
        notification: TransactionEntity,
        page: TransactionEntity,
    ): CrossSourceDuplicatePolicy.Match? = CrossSourceDuplicatePolicy.findMatchDetailed(
        candidates = listOf(notification),
        incoming = page,
        windowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
        allowWeakEvidence = true,
    )

    // ---------- 层 2：跨通道弱证据 ----------

    // 1. 用户实测场景：通知只有「微信支付」占位名、没有订单号；页面有真实商户。
    //    强证据层（findMatch）仍然不命中，这是旧行为；弱证据层必须命中。
    @Test
    fun `notification placeholder plus page merchant is a weak match`() {
        val notification = row(id = 10, merchant = "微信支付", timestamp = t0)
        val page = row(id = 0, merchant = "麦当劳", timestamp = t0 + 20_000L, fromPage = true)

        val match = weakMatch(notification, page)
        assertNotNull(match)
        assertEquals(10L, match!!.transaction.id)
        assertEquals(CrossSourceDuplicatePolicy.MatchStrength.WEAK, match.strength)

        // findMatch 保持强证据语义：弱证据不算「已确认重复」。
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(notification), page, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 2. 通知侧根本没有商户（空字符串）同样算「没有商户信息」。
    @Test
    fun `empty notification merchant is a weak match too`() {
        val notification = row(id = 10, merchant = "")
        val page = row(id = 0, merchant = "麦当劳", timestamp = t0 + 15_000L, fromPage = true)
        assertEquals(
            CrossSourceDuplicatePolicy.MatchStrength.WEAK,
            weakMatch(notification, page)?.strength,
        )
    }

    // 3. 两笔真实交易、商户都明确且不同：不是弱证据，页面照常入库。
    @Test
    fun `two different real merchants are not a weak match`() {
        val notification = row(id = 10, merchant = "麦当劳")
        val page = row(id = 0, merchant = "星巴克", timestamp = t0 + 30_000L, fromPage = true)
        assertNull(weakMatch(notification, page))
    }

    // 4. 两侧都没有商户信息：只剩金额 + 时间，不足以证明是同一笔。
    @Test
    fun `two placeholder merchants are not a weak match`() {
        val notification = row(id = 10, merchant = "微信支付")
        val page = row(id = 0, merchant = "", timestamp = t0 + 30_000L, fromPage = true)
        assertNull(weakMatch(notification, page))
    }

    // 5. 跨平台同额同方是两笔钱（微信 ≠ 支付宝）。
    @Test
    fun `another payment platform is not a weak match`() {
        val notification = row(id = 10, merchant = "微信支付", source = TransactionSource.ALIPAY.name)
        val page = row(id = 0, merchant = "麦当劳", timestamp = t0 + 10_000L, fromPage = true)
        assertNull(weakMatch(notification, page))
    }

    // 6. 弱证据窗口 3 分钟；超出后不再拦，页面照常入库（多一行用户删得掉）。
    @Test
    fun `outside the weak evidence window is not a match`() {
        val notification = row(id = 10, merchant = "微信支付")
        val inWindow = row(
            id = 0,
            merchant = "麦当劳",
            timestamp = t0 + CrossSourceDuplicatePolicy.WEAK_EVIDENCE_WINDOW_MS,
            fromPage = true,
        )
        val tooLate = row(
            id = 0,
            merchant = "麦当劳",
            timestamp = t0 + CrossSourceDuplicatePolicy.WEAK_EVIDENCE_WINDOW_MS + 1L,
            fromPage = true,
        )
        assertNotNull(weakMatch(notification, inWindow))
        assertNull(weakMatch(notification, tooLate))
    }

    // 7. 两侧都有订单号但不同 → 明确是两笔，弱证据也不成立。
    @Test
    fun `different order ids block the weak match`() {
        val notification = row(id = 10, merchant = "微信支付", orderId = "420000000000000001")
        val page = row(
            id = 0, merchant = "麦当劳", timestamp = t0 + 10_000L,
            orderId = "420000000000000002", fromPage = true,
        )
        assertNull(weakMatch(notification, page))
    }

    // 8. 两侧同一订单号仍然是强证据，优先于弱证据。
    @Test
    fun `same order id stays a strong match`() {
        val notification = row(id = 10, merchant = "微信支付", orderId = "4200001234")
        val page = row(
            id = 0, merchant = "麦当劳", timestamp = t0 + 10_000L,
            orderId = "4200001234", fromPage = true,
        )
        val match = weakMatch(notification, page)
        assertEquals(CrossSourceDuplicatePolicy.MatchStrength.STRONG, match?.strength)
    }

    // 9. 同通道不归跨通道弱证据管：两条通知 / 两条页面都不能互相匹配。
    @Test
    fun `same channel rows are never a weak match`() {
        val notification = row(id = 10, merchant = "微信支付")
        val anotherNotification = row(id = 0, merchant = "麦当劳", timestamp = t0 + 10_000L)
        assertNull(weakMatch(notification, anotherNotification))

        val page = row(id = 20, merchant = "麦当劳", fromPage = true)
        val anotherPage = row(id = 0, merchant = "麦当劳", timestamp = t0 + 10_000L, fromPage = true)
        assertNull(weakMatch(page, anotherPage))
    }

    // 10. 弱证据命中后，页面识别必须把候选留在待确认列表，不能标成已入账。
    @Test
    fun `weak duplicate is held for review instead of being dropped`() {
        val duplicate = LedgerInsertResult.Duplicate(
            existingTransactionId = 10L,
            reason = DuplicateReason.CROSS_SOURCE_WEAK,
        )
        assertEquals(PageCaptureOutcome.HOLD_FOR_REVIEW, PageCaptureResultPolicy.of(duplicate))

        // 强证据/同通道重复才可以标记为已入账。
        assertEquals(
            PageCaptureOutcome.IGNORED,
            PageCaptureResultPolicy.of(
                LedgerInsertResult.Duplicate(10L, DuplicateReason.CROSS_SOURCE),
            ),
        )
        assertEquals(
            PageCaptureOutcome.IGNORED,
            PageCaptureResultPolicy.of(
                LedgerInsertResult.Duplicate(10L, DuplicateReason.SAME_SOURCE_WINDOW),
            ),
        )
        assertEquals(
            PageCaptureOutcome.APPROVED,
            PageCaptureResultPolicy.of(LedgerInsertResult.Inserted(11L)),
        )
        assertEquals(
            PageCaptureOutcome.APPROVED,
            PageCaptureResultPolicy.of(LedgerInsertResult.Merged(10L)),
        )
        assertEquals(
            PageCaptureOutcome.RETRY,
            PageCaptureResultPolicy.of(LedgerInsertResult.Failed(IllegalStateException("boom"))),
        )
    }

    // ---------- 层 3：自动合并（通知缺商户 + 页面有商户） ----------

    // 11. 核心修复：一侧缺商户、另一侧有商户、同额同向同平台且时间邻近 →
    //     合并为 1 行，保留先入库的 id，补全商户和订单号。
    @Test
    fun `one sided merchant merges into the existing row and fills it`() {
        val notification = row(id = 10, merchant = "微信支付", timestamp = t0)
        val page = row(
            id = 0, merchant = "麦当劳", timestamp = t0 + 20_000L,
            orderId = "4200001234", fromPage = true,
        )
        val target = CaptureMergePolicy.findMergeTarget(listOf(notification), page)
        assertNotNull("通知缺商户 + 页面有商户是同一笔，必须能合并", target)
        assertEquals(10L, target!!.id)

        val merged = CaptureMergePolicy.mergeInto(target, page)
        assertEquals(10L, merged.id)
        assertEquals(1_230L, merged.amountCents)
        assertEquals(t0, merged.timestamp)
        assertEquals("麦当劳", merged.merchant)
        assertEquals("4200001234", merged.sourceTransactionId)
        assertEquals(t0 + 20_000L, merged.captureObservedAtMs)
    }

    // 12. 两笔同额同平台但不同商户的真实交易：不合并。
    @Test
    fun `same amount same platform different merchants are never merged`() {
        val first = row(id = 10, merchant = "麦当劳", timestamp = t0)
        val second = row(id = 0, merchant = "星巴克", timestamp = t0 + 30_000L, fromPage = true)
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(first), second))
        assertFalse(CaptureMergePolicy.isSamePayment(first, second))
        assertFalse(CaptureMergePolicy.isSamePaymentWithOneSidedMerchant(first, second))
        assertFalse(CaptureMergePolicy.isSamePaymentWithOneSidedMerchant(second, first))
    }

    // 13. 两笔同额同平台同商户、时间相差数分钟、都带不同订单号：不合并。
    @Test
    fun `same merchant minutes apart with different order ids are never merged`() {
        val first = row(id = 10, merchant = "麦当劳", orderId = "420000000000000001")
        val second = row(
            id = 0, merchant = "麦当劳", timestamp = t0 + 5 * 60_000L,
            orderId = "420000000000000002", fromPage = true,
        )
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(first), second))

        // 即使缩到 120 秒窗口内，订单号不同也绝不合并。
        val closeSecond = second.copy(timestamp = t0 + 30_000L)
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(first), closeSecond))
    }

    // 14. 两侧都缺订单号、也都缺商户信息：只剩金额 + 时间，保持现有保守语义，不合并。
    @Test
    fun `both sides without order id and merchant stay separate`() {
        val first = row(id = 10, merchant = "微信支付", timestamp = t0)
        val second = row(
            id = 0, merchant = "", timestamp = t0 + 30_000L, fromPage = true,
        )
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(first), second))
    }

    // 15. 已验收语义原样保留：同商户同额、两侧都没有订单号 → 两笔各自成行。
    @Test
    fun `same merchant same amount without order id stays separate`() {
        val first = row(id = 10, merchant = "麦当劳", timestamp = t0)
        val second = row(
            id = 0, merchant = "麦当劳", timestamp = t0 + 90_000L, fromPage = true,
        )
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(first), second))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(first), first.copy(id = 0)))
    }

    // 16. 强证据仍然优先：两侧同一订单号照旧合并（不能因为新增弱规则而改变）。
    @Test
    fun `same order id still merges across channels`() {
        val notification = row(id = 10, merchant = "微信支付", orderId = "4200001234")
        val page = row(
            id = 0, merchant = "麦当劳", timestamp = t0 + 20_000L,
            orderId = "4200001234", fromPage = true,
        )
        val target = CaptureMergePolicy.findMergeTarget(listOf(notification), page)
        assertNotNull(target)
        assertEquals(10L, target!!.id)
        assertTrue(CaptureMergePolicy.isSamePayment(notification, page))
    }

    // 17. 同通道（两条页面记录）即使一侧缺商户也不能自动合并：跨通道才用弱规则。
    @Test
    fun `same channel one sided merchant is never auto merged`() {
        val pageWithoutMerchant = row(id = 10, merchant = "", fromPage = true)
        val pageWithMerchant = row(
            id = 0, merchant = "麦当劳", timestamp = t0 + 20_000L, fromPage = true,
        )
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(pageWithoutMerchant), pageWithMerchant))
    }
}
