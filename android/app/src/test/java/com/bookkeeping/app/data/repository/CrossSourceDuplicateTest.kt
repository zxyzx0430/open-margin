package com.bookkeeping.app.data.repository

import com.bookkeeping.app.capture.screenshot.IntakeSource
import com.bookkeeping.app.capture.screenshot.LedgerFingerprint
import com.bookkeeping.app.capture.screenshot.ScreenshotImportPipeline
import com.bookkeeping.app.capture.screenshot.ScreenshotIntakeRequest
import com.bookkeeping.app.capture.screenshot.TextBlock
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import org.junit.Assert.assertEquals
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

    // 层 1：账本里已有同额、同向、时间邻近、来源不同的一条 → 不自动写，交用户确认。
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
    }

    // 层 1 反例：同一来源（截图自己已入库）由现有 deduplicate 处理，不能误降级。
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
}
