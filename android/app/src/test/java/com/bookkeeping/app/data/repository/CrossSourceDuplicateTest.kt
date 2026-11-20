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
 * 跨来源防重复。
 *
 * 同一笔钱可能被通知（source = 包名，如 com.tencent.mm）、短信、无障碍、截图
 * （source = WECHAT / ALIPAY）各记一次。同一来源内的重复由 8 秒窗口拦截；跨来源
 * 没有强标识时不能静默吞并，只能保守处理：
 *
 * - 层 1：截图候选在入库前发现账本里已有「同额、同向、时间邻近、来源不同」的一条
 *   → 降级为待确认，不自动写入。
 * - 层 2：`insertIfNotDuplicate` 新增 `crossSourceWindowMs`；默认 0 = 旧行为，
 *   只有截图路径显式传窗口才启用跨来源拦截。
 * - 层 3：同一张图里同额、同向、同商户出现两次 → 第二次交确认。
 *
 * 这里只覆盖纯 JVM 逻辑。Room 查询 / 真实入库 / 截图界面属于端到端范围，未在此验证。
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

    /** 通知链路已入库的一条：source 是包名，所以 platform 对截图侧是 UNKNOWN。 */
    private fun notificationRow(
        platform: String = CaptureCandidate.PLATFORM_UNKNOWN,
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

    /** 已入库的账本行（截图确认时 `insertIfNotDuplicate` 收到的同款实体）。 */
    private fun ledgerRow(
        id: Long = 1,
        amountCents: Long = 500,
        type: String = "EXPENSE",
        currency: String = "CNY",
        merchant: String = "瑞幸咖啡",
        timestamp: Long = t0,
        source: String = "com.tencent.mm",
        orderId: String? = null,
        eventTimeNeedsConfirmation: Boolean = false,
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

    // ---------- 层 1：截图入库前的跨来源候选 ----------

    @Test
    fun `cross source ledger row demotes auto booking to review`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = listOf(notificationRow()),
            timeZone = zone,
        )
        val decision = plan.decisions.single()
        assertEquals(Action.ASK_USER, decision.action)
        assertTrue(plan.bookings.isEmpty())
        assertEquals(77L, decision.matchedTransactionId!!)
    }

    // 同一来源（截图自己已入库）由现有 deduplicate 处理，不能误降级。
    // 商户不同，现有 deduplicate 判 CLEAR，所以这里只剩「不能按跨来源拦」这一条规则。
    @Test
    fun `same platform ledger row keeps auto booking`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = listOf(
                notificationRow(
                    platform = CaptureCandidate.PLATFORM_WECHAT,
                    merchant = "另一家店",
                ),
            ),
            timeZone = zone,
        )
        assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
    }

    @Test
    fun `different merchant does not demote a cross source row`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = listOf(notificationRow(merchant = "另一家店")),
            timeZone = zone,
        )
        assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
    }

    /** 支付成功页没有日期时用截图时间做锚点：100 分钟前的通知仍要提示。 */
    @Test
    fun `untimed page still matches an older cross source row`() {
        val match = ScreenshotImportPipeline.crossSourceLedgerMatch(
            draft = draft(eventTimeMs = null, merchant = "瑞幸咖啡"),
            capturedAtMs = localTime(2026, 11, 19, 23, 29),
            existing = listOf(notificationRow(amountCents = 500, merchant = "瑞幸咖啡")),
        )
        assertNotNull(match)
        assertEquals(77L, match!!.transactionId)
    }

    // ---------- 层 2：insertIfNotDuplicate 的跨来源窗口 ----------

    // 1. 默认 0：行为与改动前一致，不同来源不拦。
    @Test
    fun `default window keeps existing behaviour across sources`() {
        val existing = ledgerRow(source = "com.tencent.mm")
        val incoming = ledgerRow(id = 0, source = "WECHAT")
        assertNull(CrossSourceDuplicatePolicy.findMatch(listOf(existing), incoming, windowMs = 0L))
    }

    // 2. 窗口内、同额同方、来源不同 → 命中已有记录。
    @Test
    fun `cross source window returns the existing row`() {
        val existing = ledgerRow(source = "com.tencent.mm")
        val incoming = ledgerRow(id = 0, source = "WECHAT")
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
    fun `different amount is not a cross source duplicate`() {
        val existing = ledgerRow(amountCents = 500)
        val incoming = ledgerRow(id = 0, amountCents = 800, source = "WECHAT")
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 4. 方向不同 → 不拦。
    @Test
    fun `different direction is not a cross source duplicate`() {
        val existing = ledgerRow(type = "EXPENSE")
        val incoming = ledgerRow(id = 0, type = "INCOME", source = "WECHAT")
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 5. 时间超出窗口 → 不拦。
    @Test
    fun `outside the window is not a cross source duplicate`() {
        val existing = ledgerRow(timestamp = t0)
        val incoming = ledgerRow(
            id = 0,
            source = "WECHAT",
            timestamp = t0 + CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS + 1_000L,
        )
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // WECHAT 与 ALIPAY 是两条真实支付通道：同额同方也不跨平台拦。
    @Test
    fun `another known payment platform is not a cross source duplicate`() {
        val existing = ledgerRow(source = "ALIPAY")
        val incoming = ledgerRow(id = 0, source = "WECHAT")
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 同一来源不归这里管：同来源的重复由 8 秒窗口 / 订单号负责。
    @Test
    fun `same source is not handled by the cross source window`() {
        val existing = ledgerRow(source = "WECHAT")
        val incoming = ledgerRow(id = 0, source = "WECHAT")
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    @Test
    fun `different merchant is not a cross source duplicate`() {
        val existing = ledgerRow(merchant = "瑞幸咖啡")
        val incoming = ledgerRow(id = 0, merchant = "全家便利店", source = "WECHAT")
        assertNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 订单号相等是强标识：跨来源也成立。
    @Test
    fun `same order id matches across sources`() {
        val existing = ledgerRow(source = "com.tencent.mm", orderId = "4200001234")
        val incoming = ledgerRow(id = 0, source = "WECHAT", orderId = "4200001234", merchant = "另一家店")
        assertNotNull(
            CrossSourceDuplicatePolicy.findMatch(
                listOf(existing), incoming, CrossSourceDuplicatePolicy.SCREENSHOT_WINDOW_MS,
            ),
        )
    }

    // 时间只是截图时间兜底时不拦：时间本身不可信，不能靠它吞掉一笔钱。
    @Test
    fun `uncertain event time is never blocked`() {
        val existing = ledgerRow()
        val incoming = ledgerRow(id = 0, source = "WECHAT", eventTimeNeedsConfirmation = true)
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
}
