package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0：退款到账通知页的商户读成了图标字母 Q。
 *
 * 真机页面明写 `商户名称  自来充`，旧解释器只认 收款方 / 商户全称 这类标签，
 * 于是退回到金额上方最近的文本，把图标上的 Q 当成了商户，tier 掉到 MEDIUM。
 * 修复后：带标签的值（商户名称 / 商户全称 / 收款方 / 收款人 / 对方）优先于
 * 无标签的邻近文本；一到两个字符的纯拉丁图标角标永远不是商户。
 */
class ScreenshotRefundMerchantLabelTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val capturedAt = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.NOVEMBER, 20, 9, 30, 0)
        isLenient = false
    }.timeInMillis

    private fun block(text: String, top: Int) =
        TextBlock(text = text, left = 40, top = top, right = 640, bottom = top + 24)

    private fun refundNoticePage() = listOf(
        block("微信支付", 10),
        block("退款到账通知", 40),
        // 商户图标上的单个字母：不是商户名。
        block("Q", 70),
        block("商户名称  自来充", 100),
        block("退款金额 ¥5.00", 130),
    )

    @Test
    fun `a labelled merchant outranks the icon letter above the amount`() {
        val draft = ScreenshotPageInterpreter.interpret(refundNoticePage(), capturedAt, zone)
            .drafts.single()

        assertEquals(listOf("自来充"), draft.merchantCandidates)
        assertEquals(500L, draft.amountCents)
        assertEquals(CaptureCandidate.KIND_REFUND, draft.kind)
    }

    @Test
    fun `a label on its own row still reads the value below it`() {
        val page = listOf(
            block("微信支付", 10),
            block("退款到账通知", 40),
            block("Q", 70),
            block("商户名称", 100),
            block("自来充", 130),
            block("退款金额 ¥5.00", 160),
        )

        val draft = ScreenshotPageInterpreter.interpret(page, capturedAt, zone).drafts.single()

        assertEquals(listOf("自来充"), draft.merchantCandidates)
    }

    @Test
    fun `a single latin icon letter is never a merchant candidate`() {
        val page = listOf(
            block("微信支付", 10),
            block("Q", 40),
            block("-¥5.00", 70),
            block("交易成功", 100),
        )

        val draft = ScreenshotPageInterpreter.interpret(page, capturedAt, zone).drafts.single()

        assertTrue(draft.merchantCandidates.isEmpty())
    }

    @Test
    fun `a two letter icon badge is never a merchant candidate`() {
        val page = listOf(
            block("微信支付", 10),
            block("JD", 40),
            block("-¥5.00", 70),
            block("交易成功", 100),
        )

        val draft = ScreenshotPageInterpreter.interpret(page, capturedAt, zone).drafts.single()

        assertTrue(draft.merchantCandidates.isEmpty())
    }

    @Test
    fun `payment chrome is still not a merchant`() {
        val page = listOf(
            block("微信支付", 10),
            block("使用中国银行储蓄卡(4041)支付", 40),
            block("¥8.00", 70),
            block("账单详情 >", 100),
        )

        val draft = ScreenshotPageInterpreter.interpret(page, capturedAt, zone).drafts.single()

        assertTrue(draft.merchantCandidates.isEmpty())
    }

    @Test
    fun `a chinese single character merchant is still kept`() {
        // 转账对方常常只写姓；图标过滤只能针对拉丁字母/符号。
        val page = listOf(
            block("转账给 何", 10),
            block("¥20.00", 40),
            block("已被接收", 70),
            block("转账", 100),
        )

        val draft = ScreenshotPageInterpreter.interpret(page, capturedAt, zone).drafts.single()

        assertEquals(listOf("何"), draft.merchantCandidates)
    }
}
