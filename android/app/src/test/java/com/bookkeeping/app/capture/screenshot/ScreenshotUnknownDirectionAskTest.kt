package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureBookGate
import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.CaptureCandidateStore
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import com.bookkeeping.app.ui.screen.capture.CaptureReviewDirection
import java.nio.file.Files
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方向读不出来 ≠ 不是交易。
 *
 * 旧行为：金额、商户、平台都清楚，只是页面上没有 支出 / 收入 / 付款给 / 收款
 * 这类字样时，策略层直接 IGNORE，协调器把候选标成 IGNORED，**不建卡、不提示**，
 * 用户完全不知道少了一笔。
 *
 * 新行为：这种草稿进 ASK_USER / TIER_MEDIUM，待确认卡上显示金额、商户和
 * 「请先选择收支方向」两个按钮；用户选完之前 [CaptureBookGate.mayConfirm] 一直
 * 拒绝写入，所以「问一句」不会变成「替你记账」。
 *
 * 真正的非交易图（风景照、聊天截图、读不出金额的页面）仍然 IGNORE：连候选都不建。
 */
class ScreenshotUnknownDirectionAskTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val capturedAt = System.currentTimeMillis()

    private companion object {
        const val FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    private fun block(text: String, top: Int) =
        TextBlock(text = text, top = top, right = 300, bottom = top + 20)

    private fun request() = ScreenshotIntakeRequest(
        source = IntakeSource.SHARE,
        uri = "content://media/external/images/media/42",
        mimeType = "image/jpeg",
        capturedAtMs = capturedAt,
        byteSize = 400_000L,
        imageFingerprint = FINGERPRINT,
    )

    /** 支付宝页面上有一笔钱和一行说明，但没有任何方向词。 */
    private fun directionUnknownPage() = listOf(
        block("支付宝", 10),
        block("¥18.00", 40),
        block("商品说明", 80),
    )

    /** 风景照 / 聊天截图：没有平台、没有金额。 */
    private fun nonTransactionPhoto() = listOf(
        block("IMG_20261119_123456", 10),
        block("HDR", 40),
    )

    /** 高置信、方向显式、状态显式的普通支付页：回归用。 */
    private fun wechatMerchantSuccess() = listOf(
        block("微信支付", 10),
        block("收款方 便利店", 40),
        block("-¥36.50", 80),
        block("当前状态", 120),
        block("交易成功", 140),
        block("2026-11-19 12:30", 180),
        block("订单号 1000123456789012", 220),
    )

    private fun plan(
        blocks: List<TextBlock>,
        allowAuto: Boolean = true,
        previewOnly: Boolean = false,
    ) = ScreenshotImportPipeline.plan(
        request = request(),
        blocks = blocks,
        existing = emptyList(),
        allowAuto = allowAuto,
        previewOnly = previewOnly,
        timeZone = zone,
    )

    // ---------- 新语义：方向未知但其它证据充分 → 问一句 ----------

    @Test
    fun `a readable amount with an unreadable direction asks instead of vanishing`() {
        val plan = plan(directionUnknownPage())
        val decision = plan.decisions.single()

        assertEquals(CaptureCandidate.TIER_MEDIUM, decision.tier)
        assertEquals(Action.ASK_USER, decision.action)
        assertEquals(CaptureCandidate.DIRECTION_UNKNOWN, decision.draft.direction)
        assertEquals(1800L, decision.draft.amountCents)
        assertTrue(decision.draft.pageIdentity.startsWith("alipay"))
        // 「问一句」不是「先记一笔」：这张图不产生任何账本写入。
        assertTrue(plan.bookings.isEmpty())
        assertEquals(decision.index, plan.needsConfirmation.single().index)
    }

    @Test
    fun `auto booking disabled keeps the direction question and writes nothing`() {
        val plan = plan(directionUnknownPage(), allowAuto = false)
        val decision = plan.decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertFalse(decision.ledgerWriteAllowed)
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `preview only keeps the direction question and writes nothing`() {
        val plan = plan(directionUnknownPage(), previewOnly = true)
        val decision = plan.decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertFalse(decision.ledgerWriteAllowed)
        assertTrue(plan.bookings.isEmpty())
    }

    // ---------- 不得放宽：真正的非交易图 / 读不准的金额 ----------

    @Test
    fun `a non transaction photo is still ignored and never becomes a card`() = runBlocking {
        val photoPlan = plan(nonTransactionPhoto())
        assertEquals(FailureReason.UNKNOWN_PAGE, photoPlan.failure)
        assertTrue(photoPlan.decisions.isEmpty())
        assertTrue(photoPlan.bookings.isEmpty())

        // 协调器这一层：没有候选落到收件箱，所以既没有卡也没有提示。
        val dir = Files.createTempDirectory("unknown-direction-photo").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val outcome = coordinator(store).ingestBlocks(
                request = request(),
                blocks = nonTransactionPhoto(),
                bytes = null,
                fingerprint = FINGERPRINT,
                attemptId = "attempt-photo",
                allowAuto = true,
                previewOnly = false,
            )
            assertEquals(FailureReason.UNKNOWN_PAGE, outcome.failure)
            assertEquals(0, outcome.pending)
            assertEquals(0, outcome.ignored)
            assertTrue(store.latest().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an ambiguous amount is still ignored even when the direction is unknown`() {
        // 同一张卡片上两个不同金额：读不出哪一个是这笔钱，仍然不进收件箱。
        val twoAmounts = listOf(
            TextBlock(text = "支付宝"),
            TextBlock(text = "¥18.00"),
            TextBlock(text = "¥28.00"),
            TextBlock(text = "商品说明"),
        )
        val plan = plan(twoAmounts)
        val decision = plan.decisions.single()

        assertEquals(Action.IGNORE, decision.action)
        assertFalse(decision.draft.amountUnambiguous)
        assertTrue(plan.bookings.isEmpty())
        assertTrue(plan.needsConfirmation.isEmpty())
    }

    // ---------- 优先级：非终态仍然先 wait，方向问题不得盖过它 ----------

    @Test
    fun `an unfinished direction-unknown payment still waits for evidence`() {
        val pending = listOf(
            block("支付宝", 10),
            block("¥50.00", 40),
            block("商品说明", 80),
            block("处理中", 110),
        )
        val plan = plan(pending)
        val decision = plan.decisions.single()

        assertEquals(CaptureCandidate.DIRECTION_UNKNOWN, decision.draft.direction)
        assertEquals(Action.WAIT_FOR_EVIDENCE, decision.action)
        assertTrue(plan.needsConfirmation.isEmpty())
        assertTrue(plan.bookings.isEmpty())
    }

    // ---------- 回归：方向明确的页面没有被顺手改掉 ----------

    @Test
    fun `a completed payment with an explicit direction is still auto booked`() {
        val plan = plan(wechatMerchantSuccess())
        val decision = plan.decisions.single()

        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, decision.draft.direction)
        assertTrue(decision.draft.directionExplicit)
        assertEquals(Action.AUTO_BOOK, decision.action)
        assertEquals(1, plan.bookings.size)
    }

    @Test
    fun `completed refund red packet and withdrawal auto book when direction is explicit`() {
        val redPacket = listOf(
            block("微信红包", 10),
            block("¥8.88", 40),
            block("已存入零钱", 70),
            block("2026-11-19 12:30", 100),
        )
        val withdrawal = listOf(
            block("支付宝", 10),
            block("-¥100.00", 40),
            block("提现", 70),
            block("提现成功", 90),
            block("已到账", 110),
            block("2026-11-19 12:30", 140),
        )
        val refund = listOf(
            block("微信支付", 10),
            block("退款成功", 40),
            block("¥1.85", 70),
            block("退款金额 ¥1.85", 100),
            block("2026-11-19 12:30", 130),
        )

        listOf(redPacket, withdrawal, refund).forEach { page ->
            val plan = plan(page)
            assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
            assertEquals(1, plan.bookings.size)
        }
    }

    // ---------- 卡片上真的能选方向：requiresSelection → resolve → mayConfirm ----------

    @Test
    fun `the stored card blocks confirmation until the user picks a direction`() = runBlocking {
        val dir = Files.createTempDirectory("unknown-direction-card").toFile()
        try {
            val store = CaptureCandidateStore(dir)
            val outcome = coordinator(store).ingestBlocks(
                request = request(),
                blocks = directionUnknownPage(),
                bytes = null,
                fingerprint = FINGERPRINT,
                attemptId = "attempt-unknown-direction",
                allowAuto = true,
                previewOnly = false,
            )

            // 候选变成了待确认卡（不是 IGNORED），也没有任何账本写入。
            assertEquals(0, outcome.booked)
            assertEquals(1, outcome.pending)
            assertEquals(0, outcome.ignored)
            val stored = store.pending().single()
            assertEquals(CaptureCandidate.STATUS_PENDING, stored.status)
            assertEquals(CaptureCandidate.DIRECTION_UNKNOWN, stored.direction)
            assertEquals(1800L, stored.amountCents)
            assertTrue(stored.ledgerWriteAllowed)
            assertTrue(stored.isValid())

            // 未选方向：确认被拒。
            assertTrue(CaptureReviewDirection.requiresSelection(stored))
            assertFalse(CaptureBookGate.mayConfirm(stored))
            assertFalse(CaptureReviewDirection.canConfirm(stored, null))
            assertFalse(CaptureReviewDirection.canConfirm(stored, CaptureCandidate.DIRECTION_UNKNOWN))
            assertFalse(CaptureReviewDirection.canConfirm(stored, "SOMETHING_ELSE"))
            assertEquals(
                CaptureCandidate.DIRECTION_UNKNOWN,
                CaptureReviewDirection.resolve(stored, null).direction,
            )

            // 选了方向：同一张卡立刻可以确认，且方向以显式值进确认链。
            val pickedExpense = CaptureReviewDirection.resolve(stored, CaptureCandidate.DIRECTION_EXPENSE)
            assertEquals(CaptureCandidate.DIRECTION_EXPENSE, pickedExpense.direction)
            assertTrue(pickedExpense.directionExplicit)
            assertTrue(CaptureBookGate.mayConfirm(pickedExpense))
            assertTrue(CaptureReviewDirection.canConfirm(stored, CaptureCandidate.DIRECTION_INCOME))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun coordinator(store: CaptureCandidateStore) = ScreenshotIntakeCoordinator(store)
}
