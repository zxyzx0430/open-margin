package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0：判重不能只看时间窗。
 *
 * 真机：5.00 禧迎迎 17:11 已入账，17:58 再截同一张支付页（隔 47 分钟），
 * SCREENSHOT_WINDOW_MS = 10 分钟没认出重复，界面又问用户一次。
 *
 * 强身份 = 商户相同 + 金额/方向/币种相同 + 交易时间相同（页面只印到分钟）。
 * 时间必须真的相同：同店同价不同次消费很常见；任一侧时间缺失或只是截图兜底
 * 时间时都不判定。
 */
class ScreenshotSamePaymentIdentityTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun block(text: String, top: Int) = TextBlock(text = text, top = top, bottom = top + 20)

    private fun localTime(hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 19, hour, minute, 0)
            isLenient = false
        }.timeInMillis

    private val firstCapture = localTime(17, 58)

    /** 收款方 + 金额 + 交易成功的支付宝详情页，可带/不带交易时间。 */
    private fun paymentPage(time: String? = "2026-11-19 17:11") = listOfNotNull(
        block("支付宝", 10),
        block("禧迎迎", 40),
        block("-¥5.00", 70),
        block("交易成功", 100),
        time?.let { block(it, 130) },
    )

    private fun request(capturedAtMs: Long = firstCapture) = ScreenshotIntakeRequest(
        source = IntakeSource.SHARE,
        uri = "content://media/external/images/media/47",
        mimeType = "image/jpeg",
        capturedAtMs = capturedAtMs,
        byteSize = 300_000L,
    )

    private fun ledgerRow(
        transactionId: Long = 88,
        eventTimeMs: Long,
        merchant: String = "禧迎迎",
        amountCents: Long = 500L,
        platform: String = CaptureCandidate.PLATFORM_ALIPAY,
    ) = LedgerFingerprint(
        transactionId = transactionId,
        platform = platform,
        amountCents = amountCents,
        direction = CaptureCandidate.DIRECTION_EXPENSE,
        merchant = merchant,
        eventTimeMs = eventTimeMs,
        currency = "CNY",
    )

    @Test
    fun `the same payment screenshotted 47 minutes later links instead of asking`() {
        val page = paymentPage()
        val pageTime = ScreenshotPageInterpreter.interpret(page, firstCapture, zone)
            .drafts.single().eventTimeMs
        assertEquals(localTime(17, 11), pageTime)

        val plan = ScreenshotImportPipeline.plan(
            request = request(capturedAtMs = firstCapture),
            blocks = page,
            existing = listOf(ledgerRow(eventTimeMs = pageTime!!)),
            timeZone = zone,
        )
        val decision = plan.decisions.single()

        // 47 分钟早已超出 10 分钟窗口，但交易时间仍是同一分钟：不是新的一笔。
        assertEquals(Action.LINK_EXISTING, decision.action)
        assertEquals(88L, decision.matchedTransactionId)
        assertTrue("a known payment must not ask again", plan.needsConfirmation.isEmpty())
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `the same merchant and amount in another minute still asks`() {
        val page = paymentPage()
        val pageTime = ScreenshotPageInterpreter.interpret(page, firstCapture, zone)
            .drafts.single().eventTimeMs!!

        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = page,
            // 一分钟后的另一笔同店同价消费：金额与商户都对得上，时间不同。
            existing = listOf(ledgerRow(eventTimeMs = pageTime + 60_000L)),
            timeZone = zone,
        )
        val decision = plan.decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertNull(decision.matchedTransactionId)
        assertEquals(1, plan.needsConfirmation.size)
    }

    @Test
    fun `a page without a printed transaction time never links`() {
        val page = paymentPage(time = null)
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = page,
            existing = listOf(ledgerRow(eventTimeMs = localTime(17, 11))),
            timeZone = zone,
        )
        val decision = plan.decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertNull(decision.matchedTransactionId)
    }

    @Test
    fun `a ledger row without an event time never links`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = paymentPage(),
            existing = listOf(ledgerRow(eventTimeMs = 0L)),
            timeZone = zone,
        )
        val decision = plan.decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertNull(decision.matchedTransactionId)
    }

    @Test
    fun `a different merchant with the same amount and time is a different payment`() {
        val pageTime = ScreenshotPageInterpreter.interpret(paymentPage(), firstCapture, zone)
            .drafts.single().eventTimeMs!!

        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = paymentPage(),
            existing = listOf(ledgerRow(eventTimeMs = pageTime, merchant = "全家")),
            timeZone = zone,
        )

        val decision = plan.decisions.single()
        // 同额同时间但商户不同：是另一家店的一笔真钱，不能并进已有行。
        assertEquals(Action.AUTO_BOOK, decision.action)
        assertNull(decision.matchedTransactionId)
        assertEquals(1, plan.bookings.size)
    }
}
