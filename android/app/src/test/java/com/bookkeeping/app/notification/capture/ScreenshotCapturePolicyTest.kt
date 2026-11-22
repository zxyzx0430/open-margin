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
 * Automatic booking depends on settled status and reliable core fields, not on
 * the transaction kind or optional platform metadata.
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
        transactionLike: Boolean = true,
    ) = Evidence(
        supportedPaymentPage = supported,
        paymentPlatformKnown = platformKnown,
        transactionLike = transactionLike,
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
        // 方向读不出来不等于「不是交易」：金额、商户、平台都清楚时，静默丢掉会
        // 让用户完全不知道少了一笔。改成问一句，由用户在待确认卡上点 支出/收入；
        // 方向未显式前 CaptureBookGate 仍然拒绝写入，所以永远不会有账本写入。
        assertEquals(Action.ASK_USER, ScreenshotCapturePolicy.assess(unknownDirection, allowAuto = true).action)
    }

    @Test
    fun `completed transfer inferred from a settled list is auto bookable`() {
        val inferred = evidence(
            kind = KIND_TRANSFER,
            completionExplicit = false,
            completionInferredFromList = true,
        )

        assertTrue(ScreenshotCapturePolicy.transferAutoBookAllowed(inferred))
        assertEquals(Action.AUTO_BOOK, ScreenshotCapturePolicy.assess(inferred, allowAuto = true).action)
    }

    @Test
    fun `completed transfer without a known platform is still auto bookable`() {
        val unknownPlatform = evidence(kind = KIND_TRANSFER, platformKnown = false)

        assertTrue(ScreenshotCapturePolicy.transferAutoBookAllowed(unknownPlatform))
        val assessment = ScreenshotCapturePolicy.assess(unknownPlatform, allowAuto = true)
        assertEquals(TIER_MEDIUM, assessment.tier)
        assertEquals(Action.AUTO_BOOK, assessment.action)
    }

    @Test
    fun `transfer whose completion is inferred from a bill list books when direction is clear`() {
        // A settled bill-list row proves completion for every kind; direction is
        // the remaining core field and is explicit here.
        val inferred = evidence(kind = KIND_TRANSFER, completionInferredFromList = true)

        assertTrue(ScreenshotCapturePolicy.transferAutoBookAllowed(inferred))
        val assessment = ScreenshotCapturePolicy.assess(inferred, allowAuto = true)
        assertEquals(TIER_HIGH, assessment.tier)
        assertEquals(Action.AUTO_BOOK, assessment.action)
    }

    @Test
    fun `a payment whose completion is inferred from a bill list still books`() {
        // 回归：这轮列表自动入账的成果只对转账收紧，普通支付不得被连坐。
        val inferred = evidence(kind = KIND_PAYMENT, completionInferredFromList = true)

        assertEquals(Action.AUTO_BOOK, ScreenshotCapturePolicy.assess(inferred, allowAuto = true).action)
    }

    // ---------- 方向未知但其它证据充分：问一句，不是静默丢掉 ----------

    @Test
    fun `unknown direction with a usable amount asks the user instead of ignoring`() {
        // 金额、平台、状态都清楚，只是页面上没有方向词：这是一笔交易，
        // 旧语义直接 IGNORE（不建卡、不提示），用户永远不知道少了一笔。
        val unknown = evidence(direction = DIRECTION_UNKNOWN, directionExplicit = false)

        val assessment = ScreenshotCapturePolicy.assess(unknown, allowAuto = true)
        assertEquals(TIER_MEDIUM, assessment.tier)
        assertEquals(Action.ASK_USER, assessment.action)
    }

    @Test
    fun `unknown direction never auto books even when every other gate is high`() {
        // completed + 平台已知 + 完成态显式：普通支付这里会 AUTO_BOOK。
        // 方向没读到就必须先问，绝不允许借高置信直接记账。
        val unknown = evidence(direction = DIRECTION_UNKNOWN, directionExplicit = false)

        assertEquals(
            Action.ASK_USER,
            ScreenshotCapturePolicy.assess(unknown, allowAuto = true).action,
        )
        assertEquals(
            Action.ASK_USER,
            ScreenshotCapturePolicy.assess(unknown, allowAuto = false).action,
        )
        assertEquals(
            Action.ASK_USER,
            ScreenshotCapturePolicy.assess(unknown, allowAuto = true, previewOnly = true).action,
        )
    }

    @Test
    fun `a non transaction page is still ignored whatever the direction says`() {
        // 风景照 / 聊天截图：transactionLike=false，任何方向取值都不能把它变成卡。
        listOf(DIRECTION_UNKNOWN, DIRECTION_EXPENSE, DIRECTION_INCOME).forEach { direction ->
            val photo = evidence(
                direction = direction,
                directionExplicit = direction != DIRECTION_UNKNOWN,
                transactionLike = false,
            )
            assertEquals(
                "$direction must stay ignored on a non transaction page",
                Action.IGNORE,
                ScreenshotCapturePolicy.assess(photo, allowAuto = true).action,
            )
        }
    }

    @Test
    fun `an ambiguous amount requires manual review even with an unknown direction`() {
        val ambiguous = evidence(
            direction = DIRECTION_UNKNOWN,
            directionExplicit = false,
            amountUnambiguous = false,
        )

        assertEquals(Action.ASK_USER, ScreenshotCapturePolicy.assess(ambiguous, allowAuto = true).action)
    }

    @Test
    fun `conflicting fields require manual review even with an unknown direction`() {
        val conflicting = evidence(
            direction = DIRECTION_UNKNOWN,
            directionExplicit = false,
            conflictingFields = true,
        )

        assertEquals(Action.ASK_USER, ScreenshotCapturePolicy.assess(conflicting, allowAuto = true).action)
    }

    @Test
    fun `a missing amount requires manual review while out of range values are rejected`() {
        val missing = evidence(
            direction = DIRECTION_UNKNOWN,
            directionExplicit = false,
            amountCents = null,
        )
        assertEquals(Action.ASK_USER, ScreenshotCapturePolicy.assess(missing, allowAuto = true).action)

        listOf(0L, -1L, CaptureCandidate.MAX_CENTS + 1).forEach { amount ->
            val bad = evidence(
                direction = DIRECTION_UNKNOWN,
                directionExplicit = false,
                amountCents = amount,
            )
            assertEquals(
                "amount=$amount must not become a card",
                Action.IGNORE,
                ScreenshotCapturePolicy.assess(bad, allowAuto = true).action,
            )
        }
    }

    @Test
    fun `an unrecognised direction requires manual selection rather than auto booking`() {
        val broken = evidence(direction = "SOMETHING_ELSE", directionExplicit = true)

        assertEquals(Action.ASK_USER, ScreenshotCapturePolicy.assess(broken, allowAuto = true).action)
    }

    @Test
    fun `an unfinished direction-unknown payment still waits before asking`() {
        // 非终态优先：钱还没到，先等证据，而不是先问方向。
        val pending = evidence(
            direction = DIRECTION_UNKNOWN,
            directionExplicit = false,
            completion = CaptureCandidate.COMPLETION_PENDING,
        )

        val assessment = ScreenshotCapturePolicy.assess(pending, allowAuto = true)
        assertEquals(TIER_LOW, assessment.tier)
        assertEquals(Action.WAIT_FOR_EVIDENCE, assessment.action)
    }

    // ---------- kind alone is never a confirmation gate ----------

    @Test
    fun `refund red packet and withdrawal auto book when completed amount and direction are clear`() {
        listOf(
            KIND_REFUND to DIRECTION_INCOME,
            KIND_REFUND to DIRECTION_EXPENSE,
            KIND_RED_PACKET to DIRECTION_INCOME,
            KIND_WITHDRAWAL to DIRECTION_EXPENSE,
        ).forEach { (kind, direction) ->
            val completed = evidence(kind = kind, direction = direction)
            assertFalse("$kind must not be confirm-only", ScreenshotCapturePolicy.requiresConfirmation(kind))
            assertEquals(
                "$kind must preserve its explicit direction and book",
                Action.AUTO_BOOK,
                ScreenshotCapturePolicy.assess(completed, allowAuto = true).action,
            )
        }
    }

    @Test
    fun `transfer is no longer an unconditional confirm only kind`() {
        assertFalse(ScreenshotCapturePolicy.requiresConfirmation(KIND_TRANSFER))
        assertFalse(ScreenshotCapturePolicy.requiresConfirmation(KIND_REFUND))
        assertFalse(ScreenshotCapturePolicy.requiresConfirmation(KIND_RED_PACKET))
        assertFalse(ScreenshotCapturePolicy.requiresConfirmation(KIND_WITHDRAWAL))
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
