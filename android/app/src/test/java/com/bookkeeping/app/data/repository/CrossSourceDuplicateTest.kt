package com.bookkeeping.app.data.repository

import com.bookkeeping.app.capture.screenshot.IntakeSource
import com.bookkeeping.app.capture.screenshot.LedgerFingerprint
import com.bookkeeping.app.capture.screenshot.ScreenshotImportPipeline
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeRequest
import com.bookkeeping.app.capture.screenshot.TextBlock
import com.bookkeeping.app.capture.screenshot.TransactionDraft
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 防重复：同一笔钱不能被通知、截图、二次确认各记一次。
 *
 * 关键事实（读代码得出，与任务描述里的假设不同）：通知和截图写进账本的
 * `source` 都是 `WECHAT` / `ALIPAY`——包名只进通知指纹表，不进账本。所以「跨来源」
 * 不能靠 `source` 字符串区分。真正的通道标记是 `captureObservedAtMs`：截图/卡片
 * 采集会写它，通知、短信、无障碍转账提取器都不写。
 *
 * 三个层次：
 * - 层 1：截图候选入库前，账本里已有「同额、同向、同币种、同平台、时间邻近，
 *   但没有截图 provenance」的一条 → 降级为待确认，不自动写入。
 * - 层 2：[TransactionRepository.insertIfNotDuplicate] 的 `crossSourceWindowMs`；
 *   默认 0 = 旧行为，只有截图路径显式传窗口才启用。这一层只在**两侧都存在且完全相同
 *   的平台内订单号**时才判定「同一笔」；缺 ID、单侧 ID、ID 不同一律不拦，交给
 *   自动合并 / 疑似重复标记 / 正常插入。
 * - 层 3：同一张图里同额、同向、同商户出现两次 → 第二次交确认。
 *
 * 这里只覆盖纯 JVM 逻辑。Room 查询、真实入库、截图界面属于端到端范围，未在此验证。
 */
class CrossSourceDuplicateTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val capturedAt = 1_795_064_400_000L // 2026-11-19 13:00 Asia/Shanghai
    private val t0 = localTime(2026, 11, 19, 21, 49)

    private fun block(text: String, top: Int) = TextBlock(text = text, top = top, bottom = top + 20)

    private fun localTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
            isLenient = false
        }.timeInMillis

    private fun request() = ScreenshotIntakeRequest(
        source = IntakeSource.SHARE,
        uri = "content://media/external/images/media/42",
        mimeType = "image/jpeg",
        capturedAtMs = capturedAt,
        byteSize = 400_000L,
        imageFingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )

    /** 微信商户支付成功页：高置信、有真实交易时间，正常情况会自动入账。 */
    private fun wechatMerchantSuccess() = listOf(
        block("微信支付", 10),
        block("收款方 便利店", 40),
        block("-¥36.50", 80),
        block("当前状态", 120),
        block("交易成功", 140),
        block("2026-11-19 12:30", 180),
        block("订单号 1000123456789012", 220),
    )

    /** 另一条通道（通知/短信/无障碍/手工）写下的账本行：没有截图 provenance。 */
    private fun otherChannelRow(
        platform: String = CaptureCandidate.PLATFORM_WECHAT,
        amountCents: Long = 3650,
        direction: String = CaptureCandidate.DIRECTION_EXPENSE,
        merchant: String = "便利店",
        eventTimeMs: Long = localTime(2026, 11, 19, 12, 31),
        orderId: String? = null,
        currency: String = "CNY",
    ) = LedgerFingerprint(
        transactionId = 77,
        platform = platform,
        amountCents = amountCents,
        direction = direction,
        orderId = orderId,
        merchant = merchant,
        eventTimeMs = eventTimeMs,
        currency = currency,
    )

    /**
     * 账本行。默认是**通知通道**写的行：`source` 与截图相同（WECHAT/ALIPAY），
     * 没有截图 provenance。`fromScreenshot = true` 模拟截图自己已入库的行。
     */
    private fun ledgerRow(
        id: Long = 1,
        amountCents: Long = 500,
        type: String = "EXPENSE",
        currency: String = "CNY",
        merchant: String = "瑞幸咖啡",
        timestamp: Long = t0,
        source: String = "WECHAT",
        orderId: String? = null,
        eventTimeNeedsConfirmation: Boolean = false,
        fromScreenshot: Boolean = false,
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        type = type,
        category = TransactionCategory.OTHER.name,
        merchant = merchant,
        source = source,
        timestamp = timestamp,
        currency = currency,
        sourceTransactionId = orderId,
        eventTimeNeedsConfirmation = eventTimeNeedsConfirmation,
        captureObservedAtMs = if (fromScreenshot) timestamp else null,
    )

    private fun draft(
        amountCents: Long? = 500,
        direction: String = CaptureCandidate.DIRECTION_EXPENSE,
        merchant: String = "瑞幸咖啡",
        eventTimeMs: Long? = t0,
        pageIdentity: String = "wechat.payment.success",
        currency: String = "CNY",
    ) = TransactionDraft(
        index = 0,
        amountCents = amountCents,
        amountUnambiguous = true,
        direction = direction,
        directionExplicit = true,
        kind = CaptureCandidate.KIND_PAYMENT,
        completionStatus = CaptureCandidate.COMPLETION_COMPLETED,
        completionExplicit = true,
        merchantCandidates = listOf(merchant),
        orderId = null,
        eventTimeMs = eventTimeMs,
        currency = currency,
        pageIdentity = pageIdentity,
        supportedPaymentPage = true,
    )

    // ---------- 层 1：截图入库前的跨通道候选 ----------

    @Test
    fun `another channel row demotes auto booking to review`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = emptyList(),
            timeZone = zone,
            crossChannelRows = listOf(otherChannelRow()),
        )
        val decision = plan.decisions.single()
        assertEquals(Action.ASK_USER, decision.action)
        assertTrue(plan.bookings.isEmpty())
        assertEquals(77L, decision.matchedTransactionId!!)
    }

    // 同一通道（截图自己已入库）由现有 deduplicate 处理，不归层 1。
    @Test
    fun `same channel row keeps auto booking`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = listOf(otherChannelRow(merchant = "另一家店")),
            timeZone = zone,
        )
        assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
    }

    @Test
    fun `different merchant does not demote a cross channel row`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = emptyList(),
            timeZone = zone,
            crossChannelRows = listOf(otherChannelRow(merchant = "另一家店")),
        )
        assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
    }

    // 微信和支付宝同额同方是两笔钱，不能互相顶替。
    @Test
    fun `another payment platform is not a cross channel duplicate`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = emptyList(),
            timeZone = zone,
            crossChannelRows = listOf(otherChannelRow(platform = CaptureCandidate.PLATFORM_ALIPAY)),
        )
        assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
    }

    /** 支付成功页没有日期时用截图时间做锚点：100 分钟前的通知仍要提示。 */
    @Test
    fun `untimed page still matches an older cross channel row`() {
        val match = ScreenshotImportPipeline.crossSourceLedgerMatch(
            draft = draft(eventTimeMs = null, merchant = "瑞幸咖啡"),
            capturedAtMs = localTime(2026, 11, 19, 23, 29),
            crossChannelRows = listOf(otherChannelRow(amountCents = 500, merchant = "瑞幸咖啡")),
        )
        assertNotNull(match)
        assertEquals(77L, match!!.transactionId)
    }

    // ---------- 层 2：insertIfNotDuplicate 的跨通道窗口 ----------

    // 1. 默认 0：行为与改动前一致，另一条通道不拦。
    @Test
    fun `default window keeps existing behaviour across channels`() {
        val existing = ledgerRow(source = "WECHAT")
        val incoming = ledgerRow(id = 0, source = "WECHAT", fromScreenshot = true)
        assertNull(CrossSourceDuplicatePolicy.findMatch(listOf(existing), incoming, windowMs = 0L))
    }

    // 2. 窗口内、同额同方、另一条通道、两侧同一订单号 → 命中已有记录。
    //    订单号是唯一证据：没有它就只能交给后面的疑似重复/人工确认（见下方层 2b）。
    @Test
    fun `cross channel window returns the existing row`() {
        val existing = ledgerRow(source = "WECHAT", orderId = "4200001234")
        val incoming = ledgerRow(id = 0, source = "WECHAT", orderId = "4200001234", fromScreenshot = true)
        val match = CrossSourceDuplicatePolicy.findMatch(
            candidates = listOf(existing),
            incoming = incoming,
            windowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
        )
        assertNotNull(match)
        assertEquals(existing.id, match!!.id)
    }

    // 3. 金额不同 → 不拦。
    @Test
    fun `different amount is not a cross channel duplicate`() {
        val existing = ledgerRow(amountCents = 500)
        val incoming = ledgerRow(id = 0, amountCents = 800, fromScreenshot = true)
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 4. 方向不同 → 不拦。
    @Test
    fun `different direction is not a cross channel duplicate`() {
        val existing = ledgerRow(type = "EXPENSE")
        val incoming = ledgerRow(id = 0, type = "INCOME", fromScreenshot = true)
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 5. 时间超出窗口 → 不拦。
    @Test
    fun `outside the window is not a cross channel duplicate`() {
        val existing = ledgerRow(timestamp = t0)
        val incoming = ledgerRow(
            id = 0,
            fromScreenshot = true,
            timestamp = t0 + CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS + 1_000L,
        )
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    @Test
    fun `different merchant is not a cross channel duplicate`() {
        val existing = ledgerRow(merchant = "瑞幸咖啡")
        val incoming = ledgerRow(id = 0, merchant = "全家便利店", fromScreenshot = true)
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // ---------- 层 2b：跨来源自动匹配只认「两侧都有且完全相同」的订单号 ----------

    private fun crossSourceMatch(
        existing: TransactionEntity,
        incoming: TransactionEntity,
    ): TransactionEntity? = CrossSourceDuplicatePolicy.findMatch(
        candidates = listOf(existing),
        incoming = incoming,
        windowMs = CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
    )

    // 商户、金额、方向、币种、10 分钟内全都一致，但两边都没有订单号：
    // 只能说明「可能又是同一家店的一笔」，不能当作同一笔。
    @Test
    fun `no order id on either side is never an automatic match`() {
        val existing = ledgerRow(merchant = "瑞幸咖啡")
        val incoming = ledgerRow(id = 0, merchant = "瑞幸咖啡", fromScreenshot = true)
        assertNull(crossSourceMatch(existing, incoming))
    }

    // 只有一侧有订单号：单边 ID 不是证据，不能据此吞掉另一笔。
    @Test
    fun `order id on only one side is never an automatic match`() {
        val withId = ledgerRow(orderId = "4200001234")
        val withoutId = ledgerRow()
        assertNull(crossSourceMatch(withId, ledgerRow(id = 0, fromScreenshot = true)))
        assertNull(crossSourceMatch(withoutId, ledgerRow(id = 0, orderId = "4200001234", fromScreenshot = true)))
    }

    @Test
    fun `different order ids are never an automatic match`() {
        val existing = ledgerRow(orderId = "4200001234")
        val incoming = ledgerRow(id = 0, orderId = "4200009999", fromScreenshot = true)
        assertNull(crossSourceMatch(existing, incoming))
    }

    // 两侧订单号相同、商户也指向同一家：同一笔支付被两条通道各扫一次，按预期命中。
    @Test
    fun `same order id matches across channels`() {
        val existing = ledgerRow(orderId = "4200001234")
        val incoming = ledgerRow(id = 0, orderId = " 4200001234 ", fromScreenshot = true)
        val match = crossSourceMatch(existing, incoming)
        assertNotNull(match)
        assertEquals(existing.id, match!!.id)
    }

    // 10 分钟窗口仍然适用：同一订单号超出窗口不在本层判定，交给后续合并/人工复核。
    @Test
    fun `same order id outside the window is not matched here`() {
        val existing = ledgerRow(orderId = "4200001234")
        val incoming = ledgerRow(
            id = 0,
            orderId = "4200001234",
            timestamp = t0 + CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS + 1_000L,
            fromScreenshot = true,
        )
        assertNull(crossSourceMatch(existing, incoming))
    }

    // 微信和支付宝的订单号不是同一个命名空间：字符串相同也不能跨平台顶替。
    @Test
    fun `same order id text on another platform is not a match`() {
        val existing = ledgerRow(source = "ALIPAY", orderId = "4200001234")
        val incoming = ledgerRow(id = 0, source = "WECHAT", orderId = "4200001234", fromScreenshot = true)
        assertNull(crossSourceMatch(existing, incoming))
    }

    // 订单号相同但商户明确对不上：数据自相矛盾，与 CaptureMergePolicy 同一口径交人工确认，
    // 不在这一层静默吞掉，也不自动合并。
    @Test
    fun `same order id with a contradictory merchant needs manual review`() {
        val existing = ledgerRow(orderId = "4200001234", timestamp = t0)
        val incoming = ledgerRow(
            id = 0,
            orderId = "4200001234",
            merchant = "另一家店",
            timestamp = t0 + 60_000L,
            fromScreenshot = true,
        )
        assertNull(crossSourceMatch(existing, incoming))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(existing), incoming))
        assertTrue(DuplicateReviewPolicy.shouldFlag(existing, incoming))
    }

    // 订单号相同、但通知侧没有商户：订单号本身已是强证据，这一层照常命中，
    // 不会因为商户缺失退化成两行（与 CaptureMergePolicy 同一口径）。
    @Test
    fun `same order id with a missing merchant still matches`() {
        val existing = ledgerRow(orderId = "4200001234", merchant = "")
        val incoming = ledgerRow(id = 0, orderId = "4200001234", timestamp = t0 + 30_000L, fromScreenshot = true)
        val match = crossSourceMatch(existing, incoming)
        assertNotNull(match)
        assertEquals(existing.id, match!!.id)
        assertTrue(CaptureMergePolicy.isSamePayment(existing, incoming))
    }

    // 占位商户（「微信支付」「未知商户」…）和缺商户都不是「同方」的证据：
    // 没有订单号时一律不匹配。
    @Test
    fun `placeholder merchants never prove a cross channel duplicate`() {
        assertNull(
            crossSourceMatch(
                ledgerRow(merchant = "微信支付"),
                ledgerRow(id = 0, merchant = "微信支付", fromScreenshot = true),
            ),
        )
        assertNull(
            crossSourceMatch(
                ledgerRow(merchant = "瑞幸咖啡"),
                ledgerRow(id = 0, merchant = "未知商户", fromScreenshot = true),
            ),
        )
        assertNull(
            crossSourceMatch(
                ledgerRow(merchant = ""),
                ledgerRow(id = 0, merchant = "", fromScreenshot = true),
            ),
        )
    }

    // ---------- 调用链：不自动匹配之后必须继续到合并 / 疑似重复 / 正常插入 ----------

    /**
     * 复刻 [TransactionRepository.insertIfNotDuplicate] 在跨来源层返回 null 之后的决策：
     * [CaptureMergePolicy.findMergeTarget]（只认两侧相同订单号，120 秒内）→
     * [DuplicateReviewPolicy.shouldFlag]（3 分钟内同额即标疑似）→ 仍然插入。
     *
     * Room 查询由 DAO 的 SQL 保证：`findRecentByAmount` / `findRecentSimilar` 只按
     * 金额 + 方向 + 币种 + 时间粗筛，不会因为「同商户同金额」而提前返回 null。
     */
    @Test
    fun `no order id keeps both rows and flags the second for review`() {
        val existing = ledgerRow(merchant = "瑞幸咖啡", timestamp = t0)
        val incoming = ledgerRow(
            id = 0,
            merchant = "瑞幸咖啡",
            timestamp = t0 + 60_000L,
            fromScreenshot = true,
        )
        assertNull(crossSourceMatch(existing, incoming))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(existing), incoming))
        assertTrue(DuplicateReviewPolicy.shouldFlag(existing, incoming))

        val ledger = mutableListOf(existing)
        ledger += incoming.copy(
            duplicateOfId = existing.id,
            remoteReviewStatus = "possible_duplicate",
        )
        assertEquals(2, ledger.size)
        assertNull(ledger[0].duplicateOfId)
        assertEquals(existing.id, ledger[1].duplicateOfId)
        assertEquals("possible_duplicate", ledger[1].remoteReviewStatus)
    }

    // 单侧订单号同样走不到自动合并，但必须留在账本里等用户确认。
    @Test
    fun `one sided order id still reaches review instead of being dropped`() {
        val existing = ledgerRow(orderId = "4200001234", timestamp = t0)
        val incoming = ledgerRow(id = 0, timestamp = t0 + 60_000L, fromScreenshot = true)
        assertNull(crossSourceMatch(existing, incoming))
        assertNull(CaptureMergePolicy.findMergeTarget(listOf(existing), incoming))
        assertTrue(DuplicateReviewPolicy.shouldFlag(existing, incoming))
    }

    // 同一通道的行不归这里管：交给 8 秒窗口 / 订单号。
    @Test
    fun `same channel row is not blocked here`() {
        val existing = ledgerRow(fromScreenshot = true)
        val incoming = ledgerRow(id = 0, fromScreenshot = true)
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 通知/短信/无障碍的调用不传窗口，非截图行永远不会被这里拦下。
    @Test
    fun `non screenshot caller is never blocked`() {
        val existing = ledgerRow(fromScreenshot = true)
        val incoming = ledgerRow(id = 0, fromScreenshot = false)
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 时间只是截图时间兜底时不拦：时间本身不可信，不能靠它吞掉一笔钱。
    @Test
    fun `uncertain event time is never blocked`() {
        val existing = ledgerRow()
        val incoming = ledgerRow(
            id = 0,
            eventTimeNeedsConfirmation = true,
            fromScreenshot = true,
        )
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // ---------- 层 3：同一张图内的同额同方 ----------

    @Test
    fun `same amount direction and merchant twice in one image is flagged`() {
        assertTrue(
            ScreenshotImportPipeline.isSameMoneyAsEarlierDraft(
                draft = draft(),
                earlier = draft(),
            ),
        )
    }

    @Test
    fun `different amount twice in one image is not flagged`() {
        assertFalse(
            ScreenshotImportPipeline.isSameMoneyAsEarlierDraft(
                draft = draft(amountCents = 800),
                earlier = draft(amountCents = 500),
            ),
        )
    }

    @Test
    fun `different merchant twice in one image is not flagged`() {
        assertFalse(
            ScreenshotImportPipeline.isSameMoneyAsEarlierDraft(
                draft = draft(merchant = "全家便利店"),
                earlier = draft(merchant = "瑞幸咖啡"),
            ),
        )
    }
}
