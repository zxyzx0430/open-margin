package com.bookkeeping.app.notification.capture

import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.COMPLETION_COMPLETED
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.COMPLETION_UNKNOWN
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.DIRECTION_EXPENSE
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.DIRECTION_INCOME
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.DIRECTION_UNKNOWN
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.KIND_PAYMENT
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.KIND_RED_PACKET
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.KIND_REFUND
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.KIND_TRANSFER
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.KIND_WITHDRAWAL
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.TIER_HIGH
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.TIER_LOW
import com.bookkeeping.app.notification.capture.CaptureCandidate.Companion.TIER_MEDIUM
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.DuplicateResult
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转账从「无条件确认」改成「按证据放行」的边界。
 *
 * 用户实测：截图里明明是「已被接收」（对方已收，钱已经转出），却还要再确认一次。
 * 这里钉死两点：
 * - 已完成 + 状态显式 + 方向显式 + 平台已知 → 允许 AUTO_BOOK；
 * - 其余（待接收、状态不明、方向不明、平台未知、allowAuto=false、previewOnly）
 *   一律不得自动入账；退款 / 红包 / 提现仍然永远确认。
 */
class ScreenshotCapturePolicyTest {

    private fun evidence(
        kind: String = KIND_PAYMENT,
        direction: String = DIRECTION_EXPENSE,
        directionExplicit: Boolean = true,
        completion: String = COMPLETION_COMPLETED,
        completionExplicit: Boolean = true,
        platformKnown: Boolean = true,
        supported: Boolean = true,
        amountCents: Long? = 2000L,
        amountUnambiguous: Boolean = true,
        conflictingFields: Boolean = false,
        duplicateResult: DuplicateResult = DuplicateResult.CLEAR,
        completionInferredFromList: Boolean = false,
    ) = Evidence(
        supportedPaymentPage = supported,
        paymentPlatformKnown = platformKnown,
        transactionLike = true,
        amountCents = amountCents,
        amountUnambiguous = amountUnambiguous,
        direction = direction,
        directionExplicit = directionExplicit,
        completionStatus = completion,
        completionExplicit = completionExplicit,
        completionInferredFromList = completionInferredFromList,
        kind = kind,
        conflictingFields = conflictingFields,
        duplicateResult = duplicateResult,
    )

    // ---------- A：已完成转账可以自动入账 ----------

    @Test
    fun `completed transfer from a known platform is auto bookable`() {
        val completed = evidence(kind = KIND_TRANSFER, direction = DIRECTION_EXPENSE)

        assertTrue(ScreenshotCapturePolicy.transferAutoBookAllowed(completed))
        val assessment = ScreenshotCapturePolicy.assess(completed, allowAuto = true)
        assertEquals(TIER_HIGH, assessment.tier)
        assertEquals(Action.AUTO_BOOK, assessment.action)
    }

    @Test
    fun `completed income transfer is auto bookable too`() {
        val received = evidence(kind = KIND_TRANSFER, direction = DIRECTION_INCOME)

        assertTrue(ScreenshotCapturePolicy.transferAutoBookAllowed(received))
        assertEquals(Action.AUTO_BOOK, ScreenshotCapturePolicy.assess(received, allowAuto = true).action)
    }

    @Test
    fun `waiting transfer is never auto booked`() {
        // 待接收：状态未完成。方向可能由气泡位置证明，但钱还没到。
        val waiting = evidence(kind = KIND_TRANSFER, completion = COMPLETION_UNKNOWN, completionExplicit = false)

        assertFalse(ScreenshotCapturePolicy.transferAutoBookAllowed(waiting))
        assertEquals(Action.ASK_USER, ScreenshotCapturePolicy.assess(waiting, allowAuto = true).action)
    }

    @Test
    fun `transfer with an unproven direction is never auto bookable`() {
        val unknownDirection = evidence(
            kind = KIND_TRANSFER,
            direction = DIRECTION_UNKNOWN,
            directionExplicit = false,
        )

        assertFalse(ScreenshotCapturePolicy.transferAutoBookAllowed(unknownDirection))
        // 方向都没有的草稿不是「问一句」，而是根本不能当交易：沿用原有 IGNORE，
        // 无论如何都不会产生一次账本写入。
        assertEquals(Action.IGNORE, ScreenshotCapturePolicy.assess(unknownDirection, allowAuto = true).action)
    }

    @Test
    fun `transfer with a non explicit completion marker is not auto bookable`() {
        val implicit = evidence(kind = KIND_TRANSFER, completionExplicit = false)

        assertFalse(ScreenshotCapturePolicy.transferAutoBookAllowed(implicit))
        assertEquals(Action.ASK_USER, ScreenshotCapturePolicy.assess(implicit, allowAuto = true).action)
    }

    @Test
    fun `transfer from an unknown platform keeps asking`() {
        val unknownPlatform = evidence(kind = KIND_TRANSFER, platformKnown = false)

        assertFalse(ScreenshotCapturePolicy.transferAutoBookAllowed(unknownPlatform))
        val assessment = ScreenshotCapturePolicy.assess(unknownPlatform, allowAuto = true)
        assertEquals(TIER_MEDIUM, assessment.tier)
        assertEquals(Action.ASK_USER, assessment.action)
    }

    @Test
    fun `transfer whose completion is only inferred from a bill list still asks`() {
        // 账单列表行没有状态词：COMPLETED 是从「它在已成交列表里」推断出来的。
        // 普通支付可以据此入账，转账不行——列表没有写 已被接收 / 已收款。
        val inferred = evidence(kind = KIND_TRANSFER, completionInferredFromList = true)

        assertFalse(ScreenshotCapturePolicy.transferAutoBookAllowed(inferred))
        val assessment = ScreenshotCapturePolicy.assess(inferred, allowAuto = true)
        // 金额与方向都是硬的，缺的只是页面那句状态词：高置信，但仍要一次确认。
        assertEquals(TIER_HIGH, assessment.tier)
        assertEquals(Action.ASK_USER, assessment.action)
    }

    @Test
    fun `a payment whose completion is inferred from a bill list still books`() {
        // 回归：这轮列表自动入账的成果只对转账收紧，普通支付不得被连坐。
        val inferred = evidence(kind = KIND_PAYMENT, completionInferredFromList = true)

        assertEquals(Action.AUTO_BOOK, ScreenshotCapturePolicy.assess(inferred, allowAuto = true).action)
    }

    // ---------- 硬约束：其余 kind 一律不得放宽 ----------

    @Test
    fun `refund red packet and withdrawal stay confirm only even at high confidence`() {
        listOf(KIND_REFUND, KIND_RED_PACKET, KIND_WITHDRAWAL).forEach { kind ->
            val completed = evidence(kind = kind, direction = DIRECTION_INCOME)
            assertTrue("$kind must stay confirm-only", ScreenshotCapturePolicy.requiresConfirmation(kind))
            assertEquals(
                "$kind must keep asking",
                Action.ASK_USER,
                ScreenshotCapturePolicy.assess(completed, allowAuto = true).action,
            )
        }
    }

    @Test
    fun `transfer is no longer an unconditional confirm only kind`() {
        assertFalse(ScreenshotCapturePolicy.requiresConfirmation(KIND_TRANSFER))
        assertTrue(ScreenshotCapturePolicy.requiresConfirmation(KIND_REFUND))
        assertTrue(ScreenshotCapturePolicy.requiresConfirmation(KIND_RED_PACKET))
        assertTrue(ScreenshotCapturePolicy.requiresConfirmation(KIND_WITHDRAWAL))
    }

    // ---------- allowAuto / previewOnly 仍然优先 ----------

    @Test
    fun `auto booking disabled still asks for a completed transfer`() {
        val completed = evidence(kind = KIND_TRANSFER)

        assertEquals(Action.ASK_USER, ScreenshotCapturePolicy.assess(completed, allowAuto = false).action)
    }

    @Test
    fun `preview only still asks and never books a completed transfer`() {
        val completed = evidence(kind = KIND_TRANSFER)

        assertEquals(
            Action.ASK_USER,
            ScreenshotCapturePolicy.assess(completed, allowAuto = true, previewOnly = true).action,
        )
    }

    // ---------- 回归：普通支付页没有被顺手改掉 ----------

    @Test
    fun `ordinary completed payment still auto books`() {
        val payment = evidence(kind = KIND_PAYMENT)

        val assessment = ScreenshotCapturePolicy.assess(payment, allowAuto = true)
        assertEquals(TIER_HIGH, assessment.tier)
        assertEquals(Action.AUTO_BOOK, assessment.action)
    }

    @Test
    fun `incomplete payment still waits for evidence`() {
        val pending = evidence(
            completion = CaptureCandidate.COMPLETION_PENDING,
            completionExplicit = true,
        )

        val assessment = ScreenshotCapturePolicy.assess(pending, allowAuto = true)
        assertEquals(TIER_LOW, assessment.tier)
        assertEquals(Action.WAIT_FOR_EVIDENCE, assessment.action)
    }
}
