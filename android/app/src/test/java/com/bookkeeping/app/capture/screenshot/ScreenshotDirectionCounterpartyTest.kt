package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * 真机错账：微信「支付成功，对方已收款」的结果页被判成收入并自动入了账。
 *
 * 「对方已收款」的主语是对方——钱是我付出去的。裸的「收款」仍然表示收入，
 * 所以这一组的判断必须先于裸「收款」；退款与负数金额的老规则一个字都不能动。
 * 这里只重建 OCR 读得到的那几行，不存任何截图字节。
 */
class ScreenshotDirectionCounterpartyTest {

    private val capturedAt = 1795064400000L // 2026-11-19 13:00 Asia/Shanghai
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun blockAt(text: String, top: Int, bottom: Int = top + 20, left: Int = 20, right: Int = 600) =
        TextBlock(text = text, left = left, top = top, right = right, bottom = bottom)

    private fun drafts(vararg blocks: TextBlock) =
        ScreenshotPageInterpreter.interpret(blocks.toList(), capturedAt, zone).drafts

    private fun directionOf(vararg blocks: TextBlock): String =
        drafts(*blocks).first().direction

    /** 真机页面：商户、付款方式、金额、状态分行，状态行只有「对方已收款」。 */
    @Test
    fun `payment page where the counterparty received the money is an expense`() {
        val draft = drafts(
            blockAt("微信支付", 10),
            blockAt("廖工@百顺装修.设计.建材", 40),
            blockAt("使用零钱支付", 70),
            blockAt("¥48.00", 100),
            blockAt("支付成功", 130, 150, 20, 300),
            blockAt("对方已收款", 170, 190, 20, 300),
        ).single()

        assertEquals(4800L, draft.amountCents)
        assertEquals(
            "「对方已收款」是付款方的结果页，方向必须是支出",
            CaptureCandidate.DIRECTION_EXPENSE,
            draft.direction,
        )
        assertEquals(
            "页面明写支付成功，这笔已经完成；错的是方向，不是完成度",
            CaptureCandidate.COMPLETION_COMPLETED,
            draft.completionStatus,
        )
    }

    /** OCR 把状态压成一行时也必须判支出。 */
    @Test
    fun `status line combining success with counterparty receipt is an expense`() {
        assertEquals(
            CaptureCandidate.DIRECTION_EXPENSE,
            directionOf(
                blockAt("微信支付", 10),
                blockAt("廖工@百顺装修.设计.建材", 40),
                blockAt("使用零钱支付", 70),
                blockAt("¥48.00", 100),
                blockAt("交易状态 支付成功，对方已收款", 130, 150, 20, 400),
            ),
        )
    }

    @Test
    fun `counterparty received phrasing is an expense`() {
        assertEquals(
            CaptureCandidate.DIRECTION_EXPENSE,
            directionOf(
                blockAt("微信支付", 10),
                blockAt("使用零钱支付", 40),
                blockAt("¥48.00", 70),
                blockAt("对方已收到", 100),
            ),
        )
        assertEquals(
            CaptureCandidate.DIRECTION_EXPENSE,
            directionOf(
                blockAt("微信支付", 10),
                blockAt("使用零钱支付", 40),
                blockAt("¥48.00", 70),
                blockAt("已收款方", 100),
            ),
        )
        assertEquals(
            CaptureCandidate.DIRECTION_EXPENSE,
            directionOf(
                blockAt("微信支付", 10),
                blockAt("使用零钱支付", 40),
                blockAt("¥48.00", 70),
                blockAt("对方已领取", 100),
            ),
        )
    }

    /** 回归：裸「收款」仍是我收到钱，不能被上面的规则误伤。 */
    @Test
    fun `bare receipt success is still income`() {
        val received = directionOf(
            blockAt("微信支付", 10),
            blockAt("收款成功", 40),
            blockAt("¥48.00", 70),
        )
        assertEquals(CaptureCandidate.DIRECTION_INCOME, received)

        val intoWallet = directionOf(
            blockAt("微信支付", 10),
            blockAt("已存入零钱", 40),
            blockAt("¥20.00", 70),
        )
        assertEquals(CaptureCandidate.DIRECTION_INCOME, intoWallet)
    }

    /** 回归：退款仍然是收入。 */
    @Test
    fun `refund success is still income`() {
        assertEquals(
            CaptureCandidate.DIRECTION_INCOME,
            directionOf(
                blockAt("微信支付", 10),
                blockAt("退款金额 ¥20.00", 40),
                blockAt("退款成功", 70),
            ),
        )
    }

    /** 回归：金额自带负号仍然是支出。 */
    @Test
    fun `negative amount line is still an expense`() {
        assertEquals(
            CaptureCandidate.DIRECTION_EXPENSE,
            directionOf(
                blockAt("微信支付", 10),
                blockAt("付款给 商店", 40),
                blockAt("-¥18.00", 70),
            ),
        )
    }
}
