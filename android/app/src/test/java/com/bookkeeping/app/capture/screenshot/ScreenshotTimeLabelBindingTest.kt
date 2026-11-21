package com.bookkeeping.app.capture.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Chats print the date separator *above* the message it belongs to. A screenshot
 * of a WeChat conversation therefore reads `周四 22:01`, then the payment bubble,
 * then the next separator `昨天 02:17`. These tests pin the two rules that make
 * such a page book the right time: a standalone date row belongs to the card
 * below it, and a weekday name is resolved backwards from the capture moment.
 *
 * 2026-11-21 is a Saturday, so the weekday cases are deterministic.
 */
class ScreenshotTimeLabelBindingTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val capturedAt = localTime(2026, 11, 21, 13, 0)

    private fun localTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
            isLenient = false
        }.timeInMillis

    private fun block(text: String, top: Int) = TextBlock(text = text, top = top, bottom = top + 20)

    /** One ordinary payment page carrying a single separator row. */
    private fun weekdayPage(stamp: String) = listOf(
        block("微信支付", 10),
        block("收款方 便利店", 40),
        block("-¥9.90", 70),
        block("支付成功", 100),
        block(stamp, 130),
    )

    private fun eventTime(stamp: String, captured: Long = capturedAt): Long? =
        ScreenshotPageInterpreter.interpret(weekdayPage(stamp), captured, zone).drafts.single().eventTimeMs

    private fun draftOf(drafts: List<TransactionDraft>, cents: Long): TransactionDraft =
        drafts.single { it.amountCents == cents }

    // ---- weekday resolution ----

    @Test
    fun `weekday separator resolves to the most recent Thursday`() {
        assertEquals(localTime(2026, 11, 19, 22, 1), eventTime("周四 22:01"))
    }

    @Test
    fun `weekday that names the capture day stays on that day`() {
        assertEquals(localTime(2026, 11, 21, 9, 30), eventTime("星期六 09:30"))
        assertEquals(localTime(2026, 11, 21, 9, 30), eventTime("周六 09:30"))
    }

    @Test
    fun `a weekday later in the week goes back seven days instead of forward`() {
        assertEquals(localTime(2026, 11, 15, 23, 0), eventTime("周日 23:00"))
        assertEquals(localTime(2026, 11, 15, 23, 0), eventTime("周天 23:00"))
        assertEquals(localTime(2026, 11, 15, 23, 0), eventTime("星期日 23:00"))
    }

    @Test
    fun `Wednesday resolves six days back`() {
        assertEquals(localTime(2026, 11, 18, 8, 0), eventTime("周三 08:00"))
        assertEquals(localTime(2026, 11, 18, 8, 0), eventTime("星期三 08:00"))
    }

    @Test
    fun `weekday without a unique clock is not a time`() {
        assertNull(eventTime("周四"))
    }

    @Test
    fun `weekday resolution crosses the year boundary`() {
        // 2027-01-01 is a Friday; the Thursday 22:00 it names is 2026-12-31.
        assertEquals(
            localTime(2026, 12, 31, 22, 0),
            eventTime("周四 22:00", captured = localTime(2027, 1, 1, 0, 30)),
        )
    }

    @Test
    fun `weekday resolution crosses the month boundary`() {
        // 2026-12-01 is a Tuesday; the Monday 09:00 it names is 2026-11-30.
        assertEquals(
            localTime(2026, 11, 30, 9, 0),
            eventTime("周一 09:00", captured = localTime(2026, 12, 1, 12, 0)),
        )
    }

    // ---- separator binding ----

    @Test
    fun `a chat date separator above a bubble binds to the bubble below`() {
        val blocks = listOf(
            block("拼多多", 10),
            block("百亿补贴", 40),
            block("周四 22:01", 100),
            block("新奥尔良烤鸡腿", 130),
            block("¥10.00", 160),
            block("使用零钱支付", 190),
            block("昨天 02:17", 220),
            block("瑞幸咖啡", 250),
            block("¥20.00", 280),
            block("支付成功", 310),
        )

        val drafts = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone).drafts
        val chicken = draftOf(drafts, 1000L)
        val coffee = draftOf(drafts, 2000L)

        assertEquals(listOf("新奥尔良烤鸡腿"), chicken.merchantCandidates)
        // The separator above the bubble is this card's clock; the one below it
        // describes the next message.
        assertEquals(localTime(2026, 11, 19, 22, 1), chicken.eventTimeMs)
        assertEquals(localTime(2026, 11, 20, 2, 17), coffee.eventTimeMs)
    }

    @Test
    fun `a labelled in-card time keeps its own card`() {
        val capture = localTime(2026, 11, 21, 18, 0)
        val blocks = listOf(
            block("微信支付", 10),
            block("星巴克咖啡", 40),
            block("¥18.00", 70),
            block("支付成功", 100),
            block("发生时间 2026-11-21 17:13", 130),
            block("瑞幸咖啡", 230),
            block("¥20.00", 260),
            block("支付成功", 290),
        )

        val drafts = ScreenshotPageInterpreter.interpret(blocks, capture, zone).drafts
        val star = draftOf(drafts, 1800L)
        val coffee = draftOf(drafts, 2000L)

        // 发生时间 carries a label, so it is a field of the card above, not a
        // date separator for the card below.
        assertEquals(localTime(2026, 11, 21, 17, 13), star.eventTimeMs)
        assertNull(coffee.eventTimeMs)
    }

    @Test
    fun `a separator after the last card belongs to no card`() {
        val blocks = listOf(
            block("微信支付", 10),
            block("星巴克咖啡", 40),
            block("¥18.00", 70),
            block("支付成功", 100),
            block("瑞幸咖啡", 130),
            block("¥20.00", 160),
            block("支付成功", 190),
            block("昨天 02:17", 240),
        )

        val drafts = ScreenshotPageInterpreter.interpret(blocks, capturedAt, zone).drafts

        assertNull(draftOf(drafts, 1800L).eventTimeMs)
        assertNull(draftOf(drafts, 2000L).eventTimeMs)
    }

    // ---- existing inference stays put ----

    @Test
    fun `relative days and a bare clock keep their old resolution`() {
        assertEquals(localTime(2026, 11, 20, 20, 10), eventTime("昨天 20:10"))
        assertEquals(localTime(2026, 11, 19, 8, 5), eventTime("前天 08:05"))
        assertEquals(localTime(2026, 11, 21, 9, 15), eventTime("今天 09:15"))
        assertEquals(localTime(2026, 11, 21, 9, 40), eventTime("09:40"))
        // A clock later than the screenshot belongs to the previous day.
        assertEquals(localTime(2026, 11, 20, 18, 40), eventTime("18:40"))
    }
}
