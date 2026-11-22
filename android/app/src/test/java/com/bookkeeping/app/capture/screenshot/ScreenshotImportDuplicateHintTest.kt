package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import com.bookkeeping.app.ui.screen.capture.likelyDuplicateNoticeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 截图导入路径上的「疑似重复」必须从结构化标记长出来，不能靠 reason 字符串比对。
 *
 * 背景：导入决策的 reason 原本是自由文本（`same amount already recorded from another
 * channel` / `same amount appears twice in this screenshot`），而复核卡片只在 reason
 * 等于 [CaptureCandidate.REASON_LIKELY_DUPLICATE] 时才显示提示。于是跨通道被拦下的
 * 候选在卡片上什么都不说，用户看不出为什么被拦、也不知道跟哪一笔重复了。
 *
 * 现在 [CandidateDecision.duplicateHint] 记录「为什么降级」，工厂据此写 reason：
 * - 跨通道（层 1）→ [DuplicateHint.CROSS_CHANNEL] → [CaptureCandidate.REASON_LIKELY_DUPLICATE]，
 *   账本里确有一行可指，提示带记录号；
 * - 同图重复（层 3）→ [DuplicateHint.SAME_IMAGE] → [CaptureCandidate.REASON_SAME_IMAGE_REPEAT]，
 *   没有账本行可指，不能写成「账本里已有一笔」；
 * - [Action.LINK_EXISTING] 是确定的重复（自动关联、不打扰用户），以及其它所有原因，一律 NONE。
 *
 * 动作语义不变：这里只断言既有判定（ASK_USER / LINK_EXISTING / AUTO_BOOK / IGNORE）
 * 与「不写第二笔」的结论，不新增、不放宽任何判定。
 */
class ScreenshotImportDuplicateHintTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val capturedAt = 1_795_064_400_000L // 2026-11-19 13:00 Asia/Shanghai

    private companion object {
        const val FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

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
        imageFingerprint = FINGERPRINT,
    )

    /** 微信商户支付成功页：高置信、有真实交易时间，正常情况下会自动入账。 */
    private fun wechatMerchantSuccess() = listOf(
        block("微信支付", 10),
        block("收款方 便利店", 40),
        block("-¥36.50", 80),
        block("当前状态", 120),
        block("交易成功", 140),
        block("2026-11-19 12:30", 180),
        block("订单号 1000123456789012", 220),
    )

    /**
     * 同一张截图里两条完全相同的支付：金额、方向、商户都一样，只是先后两行。
     * 第二行必须只降级为确认，不再写第二笔。
     */
    private fun samePaymentTwice() = listOf(
        block("微信支付", 10),
        block("-¥12.00", 40),
        block("收款方 便利店", 60),
        block("交易成功", 75),
        block("-¥12.00", 130),
        block("收款方 便利店", 150),
        block("交易成功", 165),
    )

    /** 另一条通道（通知/短信/无障碍/手工）写下的账本行：没有截图 provenance。 */
    private fun otherChannelRow(
        transactionId: Long = 77,
        amountCents: Long = 3650,
        merchant: String = "便利店",
    ) = LedgerFingerprint(
        transactionId = transactionId,
        platform = CaptureCandidate.PLATFORM_WECHAT,
        amountCents = amountCents,
        direction = CaptureCandidate.DIRECTION_EXPENSE,
        orderId = null,
        merchant = merchant,
        eventTimeMs = localTime(2026, 11, 19, 12, 31),
    )

    private fun candidateOf(decision: CandidateDecision) = ScreenshotCandidateFactory.create(
        decision = decision,
        source = IntakeSource.SHARE,
        capturedAtMs = capturedAt,
        imageFingerprint = FINGERPRINT,
    )

    // ---------- 跨通道：有账本记录可指 ----------

    @Test
    fun `cross channel suspected duplicate is marked and points at the ledger entry`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = emptyList(),
            timeZone = zone,
            crossChannelRows = listOf(otherChannelRow()),
        )
        val decision = plan.decisions.single()

        // 动作语义不变：仍然只是「问用户」，不写库。
        assertEquals(Action.ASK_USER, decision.action)
        assertTrue(plan.bookings.isEmpty())
        // 结构化标记 + 被怀疑的那条账本行都保留。
        assertEquals(DuplicateHint.CROSS_CHANNEL, decision.duplicateHint)
        assertEquals(77L, decision.matchedTransactionId)

        val candidate = candidateOf(decision)
        assertTrue("新的 reason 必须能通过候选校验", candidate.isValid())
        assertEquals(CaptureCandidate.REASON_LIKELY_DUPLICATE, candidate.reason)
        assertEquals(77L, candidate.matchedTransactionId)
        assertEquals(
            "疑似与账本里已有的一笔重复，请核对后再决定（已有记录 #77）",
            likelyDuplicateNoticeText(candidate),
        )
    }

    // ---------- 同图重复：没有账本记录可指，不能借用「账本里已有一笔」 ----------

    @Test
    fun `same image repeat is marked separately and still asks instead of booking twice`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = samePaymentTwice(),
            existing = emptyList(),
            timeZone = zone,
        )
        assertEquals(2, plan.decisions.size)
        val first = plan.decisions[0]
        val second = plan.decisions[1]

        assertEquals(Action.AUTO_BOOK, first.action)
        assertEquals(DuplicateHint.NONE, first.duplicateHint)
        assertEquals(Action.ASK_USER, second.action)
        assertEquals(DuplicateHint.SAME_IMAGE, second.duplicateHint)
        // 同图重复没有账本行：不许编一个记录号出来。
        assertNull(second.matchedTransactionId)
        assertEquals(listOf(0), plan.bookings.map { it.index })

        val candidate = candidateOf(second)
        assertTrue("新的 reason 必须能通过候选校验", candidate.isValid())
        assertEquals(CaptureCandidate.REASON_SAME_IMAGE_REPEAT, candidate.reason)
        val notice = likelyDuplicateNoticeText(candidate)
        assertEquals("同一张截图里有金额、商户相同的记录，请核对是否重复", notice)
        assertFalse("同图重复不得出现账本记录号", notice!!.contains("#"))
    }

    // ---------- 确定重复：自动关联，不打扰用户 ----------

    @Test
    fun `an exact ledger duplicate is linked without a likely duplicate notice`() {
        val pageTime = ScreenshotPageInterpreter.interpret(wechatMerchantSuccess(), capturedAt, zone)
            .drafts.single().eventTimeMs!!
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 77,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 3650,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                orderId = "1000123456789012",
                merchant = "便利店",
                eventTimeMs = pageTime,
            ),
        )
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = existing,
            timeZone = zone,
        )
        val decision = plan.decisions.single()

        assertEquals(Action.LINK_EXISTING, decision.action)
        assertEquals(77L, decision.matchedTransactionId)
        assertEquals(DuplicateHint.NONE, decision.duplicateHint)

        val candidate = candidateOf(decision)
        assertEquals("existing ledger transaction", candidate.reason)
        assertNull("确定的重复不该让用户再核对一遍", likelyDuplicateNoticeText(candidate))
    }

    // ---------- 其它原因一律不显示 ----------

    @Test
    fun `a plain auto booked payment carries no hint`() {
        val decision = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatMerchantSuccess(),
            existing = emptyList(),
            timeZone = zone,
        ).decisions.single()

        assertEquals(Action.AUTO_BOOK, decision.action)
        assertEquals(DuplicateHint.NONE, decision.duplicateHint)
        val candidate = candidateOf(decision)
        assertNull(likelyDuplicateNoticeText(candidate))
    }

    @Test
    fun `a medium confidence review is not labelled a duplicate`() {
        val medium = listOf(
            block("支付宝", 10),
            block("账单详情", 40),
            block("¥28.00", 80),
            block("收款方 星巴克", 120),
        )
        val decision = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = medium,
            existing = emptyList(),
            timeZone = zone,
        ).decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertEquals(DuplicateHint.NONE, decision.duplicateHint)
        val candidate = candidateOf(decision)
        assertFalse(candidate.reason == CaptureCandidate.REASON_LIKELY_DUPLICATE)
        assertFalse(candidate.reason == CaptureCandidate.REASON_SAME_IMAGE_REPEAT)
        assertNull(likelyDuplicateNoticeText(candidate))
    }

    @Test
    fun `a direction-unknown page asks without a duplicate hint`() {
        val amountOnly = listOf(
            block("支付宝", 10),
            block("¥18.00", 40),
            block("商品说明", 80),
        )
        val decision = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = amountOnly,
            existing = emptyList(),
            timeZone = zone,
        ).decisions.single()

        // 平台已知、金额唯一、方向读不出来：这笔进待确认让用户选方向（不再静默
        // IGNORE），但它不是重复，所以仍然不许带任何「疑似重复」提示。
        assertEquals(Action.ASK_USER, decision.action)
        assertEquals(DuplicateHint.NONE, decision.duplicateHint)
        assertNull(likelyDuplicateNoticeText(candidateOf(decision)))
    }

    @Test
    fun `a non transaction page is ignored while ambiguous money stays review only`() {
        // 风景照 / 聊天截图：没有平台、没有金额，连候选都不该生成。
        val photo = listOf(block("IMG_20261119_123456", 10), block("HDR", 40))
        val photoPlan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = photo,
            existing = emptyList(),
            timeZone = zone,
        )
        assertEquals(FailureReason.UNKNOWN_PAGE, photoPlan.failure)
        assertTrue(photoPlan.decisions.isEmpty())
        assertTrue(photoPlan.bookings.isEmpty())

        // 同一张卡片上有两个不同金额，读不准：必须保留给人工检查、禁止写账，
        // 但不该显示「疑似重复」——它跟重复无关。
        val twoAmounts = listOf(
            TextBlock(text = "支付宝"),
            TextBlock(text = "¥18.00"),
            TextBlock(text = "¥28.00"),
            TextBlock(text = "商品说明"),
        )
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = twoAmounts,
            existing = emptyList(),
            timeZone = zone,
        )
        val decision = plan.decisions.single()

        assertFalse("两个不同金额不能被当成一笔唯一的钱", decision.draft.amountUnambiguous)
        assertEquals(Action.ASK_USER, decision.action)
        assertFalse(decision.ledgerWriteAllowed)
        assertTrue(plan.bookings.isEmpty())
        assertEquals(DuplicateHint.NONE, decision.duplicateHint)
        assertNull(likelyDuplicateNoticeText(candidateOf(decision)))
    }

    // ---------- 排队中的候选也要保住标记 ----------

    @Test
    fun `a queued duplicate keeps its hint when the card is finally stored`() {
        val draft = ScreenshotPageInterpreter.interpret(wechatMerchantSuccess(), capturedAt, zone)
            .drafts.single()
        val decision = CandidateDecision(
            index = 1,
            tier = CaptureCandidate.TIER_HIGH,
            action = Action.ASK_USER,
            reason = "queued behind the previous confirmation",
            draft = draft,
            queued = true,
            matchedTransactionId = 77L,
            duplicateHint = DuplicateHint.CROSS_CHANNEL,
        )

        val candidate = candidateOf(decision)
        assertEquals(CaptureCandidate.REASON_LIKELY_DUPLICATE, candidate.reason)
        assertEquals(
            "疑似与账本里已有的一笔重复，请核对后再决定（已有记录 #77）",
            likelyDuplicateNoticeText(candidate),
        )
    }

    @Test
    fun `a queued candidate with no duplicate keeps the pipeline reason`() {
        val draft = ScreenshotPageInterpreter.interpret(wechatMerchantSuccess(), capturedAt, zone)
            .drafts.single()
        val decision = CandidateDecision(
            index = 1,
            tier = CaptureCandidate.TIER_MEDIUM,
            action = Action.ASK_USER,
            reason = "queued behind the previous confirmation",
            draft = draft,
            queued = true,
        )

        val candidate = candidateOf(decision)
        assertEquals(DuplicateHint.NONE, decision.duplicateHint)
        assertEquals("queued behind the previous confirmation", candidate.reason)
        assertNull(likelyDuplicateNoticeText(candidate))
    }

    // ---------- 常量本身要能存进候选（reason 上限 80） ----------

    @Test
    fun `duplicate reasons fit the stored candidate reason limit`() {
        assertTrue(CaptureCandidate.REASON_LIKELY_DUPLICATE.length <= 80)
        assertTrue(CaptureCandidate.REASON_SAME_IMAGE_REPEAT.length <= 80)
        assertTrue(CaptureCandidate.REASON_LIKELY_DUPLICATE != CaptureCandidate.REASON_SAME_IMAGE_REPEAT)
    }
}
