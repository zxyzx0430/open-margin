package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ScreenshotV1475RegressionTest {
    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val nowMs = 1_795_578_900_000L

    private fun b(text: String, top: Int, left: Int, right: Int, height: Int = 34) =
        TextBlock(text = text, left = left, top = top, right = right, bottom = top + height)

    private fun stream(rows: List<TextBlock>) =
        listOf(b("微信支付", top = 105, left = 250, right = 450, height = 32)) +
            rows +
            listOf(
                b("我的账单", top = 1450, left = 90, right = 200, height = 28),
                b("支付服务", top = 1450, left = 300, right = 440, height = 28),
                b("优惠", top = 1450, left = 520, right = 620, height = 28),
            )

    private fun plan(blocks: List<TextBlock>) =
        ScreenshotImportPipeline.plan(
            ScreenshotIntakeRequest(
                IntakeSource.MEDIASTORE,
                "content://media/external/images/media/19",
                "image/jpeg",
                nowMs,
                byteSize = 300_000,
            ),
            stream(blocks),
            emptyList(),
            timeZone = zone,
        )

    @Test
    fun `narrow circle logo badge near title height does not override wide merchant title`() {
        // Anonymized 3rd-batch layout: circular badge OCR text ("徽标", 2 chars, narrow box)
        // sits left of a wide shop title ("喜迎生鲜超市(南区)") with height ratio < 1.5x.
        val blocks = listOf(
            b("11月20日21:49", top = 160, left = 270, right = 450, height = 28),
            b("喜迎生鲜超市(南区)", top = 250, left = 115, right = 460, height = 34),
            b("徽标", top = 254, left = 62, right = 110, height = 29),
            b("使用中国银行储蓄卡(4041)支付", top = 395, left = 210, right = 540, height = 30),
            b("¥5.00", top = 470, left = 255, right = 445, height = 56),
            b("账单详情>", top = 565, left = 280, right = 420, height = 30),
        )
        val result = plan(blocks)
        assertEquals(1, result.decisions.size)
        val decision = result.decisions.single()
        assertEquals(Action.AUTO_BOOK, decision.action)
        assertEquals(500L, decision.draft.amountCents)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, decision.draft.direction)
        assertEquals("喜迎生鲜超市(南区)", decision.draft.merchantCandidates.firstOrNull())
    }

    @Test
    fun `logo badge below title baseline after title in OCR order does not override title`() {
        // Anonymized 3rd-batch layout: 3-char title ("快充站") at top-right of logo,
        // OCR emits "快充站" first and then "图标印" (3-char small logo text) slightly lower.
        val blocks = listOf(
            b("11月18日16:28", top = 155, left = 270, right = 450, height = 28),
            b("快充站", top = 248, left = 122, right = 235, height = 34),
            b("图标印", top = 282, left = 58, right = 108, height = 22),
            b("使用零钱支付", top = 395, left = 260, right = 440, height = 30),
            b("￥4.00", top = 470, left = 255, right = 445, height = 56),
            b("账单详情>", top = 565, left = 280, right = 420, height = 30),
        )
        val result = plan(blocks)
        assertEquals(1, result.decisions.size)
        val decision = result.decisions.single()
        assertEquals(Action.AUTO_BOOK, decision.action)
        assertEquals(400L, decision.draft.amountCents)
        assertEquals("快充站", decision.draft.merchantCandidates.firstOrNull())
    }

    @Test
    fun `payment and refund in same screenshot keep independent merchants and directions`() {
        val blocks = listOf(
            b("11月18日16:28", top = 155, left = 270, right = 450, height = 28),
            b("快充站", top = 248, left = 122, right = 235, height = 34),
            b("图标印", top = 282, left = 58, right = 108, height = 22),
            b("使用零钱支付", top = 395, left = 260, right = 440, height = 30),
            b("￥4.00", top = 470, left = 255, right = 445, height = 56),
            b("账单详情>", top = 565, left = 280, right = 420, height = 30),
            b("11月18日20:07", top = 740, left = 270, right = 450, height = 28),
            b("退款到账通知", top = 810, left = 80, right = 260, height = 34),
            b("退款金额", top = 880, left = 280, right = 420, height = 28),
            b("￥1.85", top = 930, left = 260, right = 440, height = 56),
            b("商品详情", top = 1010, left = 80, right = 180, height = 28),
            b("充电订单-预付4元", top = 1010, left = 220, right = 440, height = 28),
            b("商户名称", top = 1055, left = 80, right = 180, height = 28),
            b("快充站", top = 1055, left = 220, right = 310, height = 28),
            b("退款方式", top = 1100, left = 80, right = 180, height = 28),
            b("退回零钱", top = 1100, left = 220, right = 340, height = 28),
            b("到账时间", top = 1145, left = 80, right = 180, height = 28),
            b("2026年11月18日20:07:39", top = 1145, left = 220, right = 500, height = 28),
        )
        val result = plan(blocks)
        assertEquals(2, result.decisions.size)
        val payment = result.decisions[0]
        val refund = result.decisions[1]
        assertEquals(400L, payment.draft.amountCents)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, payment.draft.direction)
        assertEquals("快充站", payment.draft.merchantCandidates.firstOrNull())
        assertEquals(185L, refund.draft.amountCents)
        assertEquals(CaptureCandidate.KIND_REFUND, refund.draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_INCOME, refund.draft.direction)
        assertEquals("快充站", refund.draft.merchantCandidates.firstOrNull())
    }

    @Test
    fun `payment plus withdrawal initiated keeps withdrawal booked as expense`() {
        val blocks = listOf(
            b("周一16:43", top = 155, left = 280, right = 420, height = 28),
            b("烘焙坊", top = 248, left = 122, right = 235, height = 34),
            b("使用中国银行储蓄卡(4041)支付", top = 395, left = 210, right = 540, height = 30),
            b("¥7.00", top = 470, left = 255, right = 445, height = 56),
            b("账单详情>", top = 565, left = 280, right = 420, height = 30),
            b("周一17:49", top = 740, left = 280, right = 420, height = 28),
            b("零钱提现发起", top = 810, left = 80, right = 260, height = 34),
            b("提现金额", top = 880, left = 280, right = 420, height = 28),
            b("￥499.00", top = 930, left = 245, right = 455, height = 56),
            b("提现银行", top = 1010, left = 80, right = 180, height = 28),
            b("中国银行(4041)", top = 1010, left = 220, right = 420, height = 28),
            b("提现时间", top = 1055, left = 80, right = 180, height = 28),
            b("2026-11-23 17:49:27", top = 1055, left = 220, right = 460, height = 28),
        )
        val result = plan(blocks)
        assertEquals(2, result.decisions.size)
        val withdrawal = result.decisions[1]
        assertEquals(CaptureCandidate.KIND_WITHDRAWAL, withdrawal.draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, withdrawal.draft.direction)
        assertEquals(49900L, withdrawal.draft.amountCents)
        assertEquals(Action.ASK_USER, withdrawal.action)
    }

    @Test
    fun `daily report summary plus payment card ignores summary and keeps real card`() {
        val blocks = listOf(
            b("微信记账本", top = 120, left = 120, right = 280, height = 32),
            b("记账日报", top = 175, left = 80, right = 200, height = 32),
            b("昨日支出", top = 230, left = 80, right = 180, height = 28),
            b("¥21.83，共6笔", top = 230, left = 210, right = 420, height = 28),
            b("昨日入账", top = 270, left = 80, right = 180, height = 28),
            b("¥500.03，共3笔", top = 270, left = 210, right = 420, height = 28),
            b("查看明细", top = 340, left = 80, right = 200, height = 28),
            b("昨天 21:25", top = 460, left = 270, right = 430, height = 28),
            b("快充站", top = 540, left = 122, right = 235, height = 34),
            b("图标印", top = 574, left = 58, right = 108, height = 22),
            b("使用中国银行储蓄卡(4041)支付", top = 660, left = 210, right = 540, height = 30),
            b("¥4.00", top = 735, left = 255, right = 445, height = 56),
            b("账单详情>", top = 820, left = 280, right = 420, height = 30),
        )
        val result = plan(blocks)
        assertEquals(1, result.decisions.size)
        val d = result.decisions.single()
        assertEquals(400L, d.draft.amountCents)
        assertEquals("快充站", d.draft.merchantCandidates.firstOrNull())
    }

    @Test
    fun `alipay payment message stream suppresses narrow left logo badge on second card`() {
        val blocks = listOf(
            b("服务消息", top = 85, left = 200, right = 320, height = 33),
            b("支付消息", top = 84, left = 367, right = 489, height = 37),
            b("商户一", top = 211, left = 105, right = 191, height = 35),
            b("11月9日00:16", top = 249, left = 104, right = 228, height = 23),
            b("付款成功", top = 297, left = 289, right = 401, height = 36),
            b("￥6.00", top = 336, left = 272, right = 417, height = 55),
            b("查看详情>", top = 393, left = 297, right = 407, height = 30),
            b("付款方式Visa(0888)", top = 450, left = 42, right = 276, height = 27),
            b("商户二长名", top = 693, left = 107, right = 215, height = 33),
            b("徽标印", top = 725, left = 45, right = 88, height = 18),
            b("11月3日23:01", top = 729, left = 106, right = 224, height = 23),
            b("付款成功", top = 780, left = 289, right = 400, height = 32),
            b("¥5.67", top = 818, left = 273, right = 414, height = 50),
            b("查看详情>", top = 873, left = 297, right = 406, height = 30),
            b("付款方式花呗", top = 930, left = 40, right = 206, height = 27),
        )
        val result = ScreenshotImportPipeline.plan(
            ScreenshotIntakeRequest(
                IntakeSource.MEDIASTORE,
                "content://media/external/images/media/20",
                "image/jpeg",
                nowMs,
                byteSize = 300_000,
            ),
            blocks,
            emptyList(),
            timeZone = zone,
        )
        assertEquals(2, result.decisions.size)
        assertEquals(600L, result.decisions[0].draft.amountCents)
        assertEquals("商户一", result.decisions[0].draft.merchantCandidates.firstOrNull())
        assertEquals(567L, result.decisions[1].draft.amountCents)
        assertEquals("商户二长名", result.decisions[1].draft.merchantCandidates.firstOrNull())
    }
}
