package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Pure-JVM coverage for the screenshot pipeline shared by ACTION_SEND, the
 * Photo Picker and the MediaStore watcher. Android-only pieces (ContentResolver,
 * Bitmap decoding, real OCR, notifications, ContentObserver registration) are
 * NOT exercised here and are marked as unverified for CI.
 */
class ScreenshotImportPipelineTest {

    private val capturedAt = 1_763_000_000_000L // 2025-11-05T13:33:20Z
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun block(text: String, top: Int) = TextBlock(text = text, top = top, bottom = top + 20)

    private fun request(
        source: IntakeSource = IntakeSource.SHARE,
        uri: String? = "content://media/external/images/media/42",
        mime: String? = "image/jpeg",
        permission: Boolean = true,
        readable: Boolean = true,
        size: Long? = 400_000L,
        fingerprint: String? = FINGERPRINT,
    ) = ScreenshotIntakeRequest(
        source = source,
        uri = uri,
        mimeType = mime,
        capturedAtMs = capturedAt,
        permissionGranted = permission,
        readable = readable,
        byteSize = size,
        imageFingerprint = fingerprint,
    )

    /** WeChat transfer success page: completed, merchant, order id, real time. */
    private fun wechatSuccess() = listOf(
        block("微信支付", 10),
        block("转账给 张三", 40),
        block("-¥36.50", 80),
        block("当前状态", 120),
        block("交易成功", 140),
        block("2026-11-19 12:30", 180),
        block("订单号 1000123456789012", 220),
    )

    /** Alipay merchant payment without an explicit completion marker. */
    private fun alipayMedium() = listOf(
        block("支付宝", 10),
        block("账单详情", 40),
        block("¥28.00", 80),
        block("收款方 星巴克", 120),
    )

    @Test
    fun `all three intakes go through the same pipeline and produce the same decision`() {
        val plans = IntakeSource.entries.map { source ->
            ScreenshotImportPipeline.plan(
                request = request(source = source),
                blocks = wechatSuccess(),
                existing = emptyList(),
                timeZone = zone,
            )
        }
        plans.forEach { plan ->
            assertEquals(1, plan.decisions.size)
            assertEquals(CaptureCandidate.TIER_HIGH, plan.decisions.single().tier)
            assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
            assertEquals(3650L, plan.decisions.single().draft.amountCents)
        }
        assertEquals(plans.first().decisions.map { it.action }, plans.last().decisions.map { it.action })
    }

    @Test
    fun `missing permission is rejected before any parsing`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(permission = false),
            blocks = wechatSuccess(),
            existing = emptyList(),
        )
        assertEquals(FailureReason.PERMISSION_DENIED, plan.failure)
        assertTrue(plan.decisions.isEmpty())
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `invalid uri and unsupported mime never reach the ledger`() {
        assertEquals(
            FailureReason.URI_INVALID,
            ScreenshotImportPipeline.plan(request(uri = "file:///sdcard/a.jpg"), wechatSuccess(), emptyList()).failure,
        )
        assertEquals(
            FailureReason.URI_INVALID,
            ScreenshotImportPipeline.plan(request(uri = null), wechatSuccess(), emptyList()).failure,
        )
        assertEquals(
            FailureReason.UNSUPPORTED_MIME,
            ScreenshotImportPipeline.plan(request(mime = "application/pdf"), wechatSuccess(), emptyList()).failure,
        )
        assertEquals(
            FailureReason.UNREADABLE,
            ScreenshotImportPipeline.plan(request(readable = false), wechatSuccess(), emptyList()).failure,
        )
        assertEquals(
            FailureReason.OVERSIZED,
            ScreenshotImportPipeline.plan(request(size = 40L * 1024 * 1024), wechatSuccess(), emptyList()).failure,
        )
    }

    @Test
    fun `decode failure and empty OCR do not create candidates`() {
        assertEquals(
            FailureReason.DECODE_FAILED,
            ScreenshotImportPipeline.plan(request(), blocks = null, existing = emptyList()).failure,
        )
        assertEquals(
            FailureReason.OCR_EMPTY,
            ScreenshotImportPipeline.plan(request(), blocks = listOf(block("   ", 0)), existing = emptyList()).failure,
        )
    }

    @Test
    fun `non transaction photo and unknown page are not booked`() {
        val photo = listOf(block("IMG_20261119_123456", 10), block("HDR", 40))
        val plan = ScreenshotImportPipeline.plan(request(), photo, emptyList())
        assertEquals(FailureReason.UNKNOWN_PAGE, plan.failure)
        assertTrue(plan.bookings.isEmpty())

        val amountOnly = listOf(block("支付宝", 10), block("¥18.00", 40), block("商品说明", 80))
        val unknownDirection = ScreenshotImportPipeline.plan(request(), amountOnly, emptyList())
        assertEquals(
            Action.IGNORE,
            unknownDirection.decisions.single().action,
        )
        assertTrue(unknownDirection.bookings.isEmpty())
    }

    @Test
    fun `high confidence is auto booked with real transaction time`() {
        val plan = ScreenshotImportPipeline.plan(request(), wechatSuccess(), emptyList(), timeZone = zone)
        val decision = plan.decisions.single()
        assertEquals(Action.AUTO_BOOK, decision.action)
        assertEquals(CaptureCandidate.KIND_TRANSFER, decision.draft.kind)
        assertEquals(listOf("张三"), decision.draft.merchantCandidates)
        assertEquals("1000123456789012", decision.draft.orderId)
        assertNotNull(decision.draft.eventTimeMs)
        assertEquals(1, plan.bookings.size)
    }

    @Test
    fun `preview only never yields a ledger booking even at high confidence`() {
        val plan = ScreenshotImportPipeline.plan(
            request(),
            wechatSuccess(),
            emptyList(),
            allowAuto = true,
            previewOnly = true,
            timeZone = zone,
        )
        assertEquals(Action.ASK_USER, plan.decisions.single().action)
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `auto disabled holds the candidate instead of booking`() {
        val plan = ScreenshotImportPipeline.plan(
            request(),
            wechatSuccess(),
            emptyList(),
            allowAuto = false,
            timeZone = zone,
        )
        assertEquals(Action.ASK_USER, plan.decisions.single().action)
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `pending transfer waits for evidence and is never auto booked`() {
        val pending = listOf(
            block("微信支付", 10),
            block("¥50.00", 40),
            block("转账给 李四", 80),
            block("当前状态", 120),
            block("转账处理中", 140),
        )
        val plan = ScreenshotImportPipeline.plan(request(), pending, emptyList(), timeZone = zone)
        assertEquals(Action.WAIT_FOR_EVIDENCE, plan.decisions.single().action)
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `medium confidence asks before writing`() {
        val plan = ScreenshotImportPipeline.plan(request(), alipayMedium(), emptyList(), timeZone = zone)
        val decision = plan.decisions.single()
        assertEquals(CaptureCandidate.TIER_MEDIUM, decision.tier)
        assertEquals(Action.ASK_USER, decision.action)
        assertTrue(plan.bookings.isEmpty())
        assertEquals(2800L, decision.draft.amountCents)
        assertEquals(CaptureCandidate.DIRECTION_EXPENSE, decision.draft.direction)
    }

    @Test
    fun `one image with several drafts decides each independently`() {
        val mixed = listOf(
            block("微信支付", 10),
            block("¥12.00", 40),
            block("收款方 便利店", 60),
            block("交易成功", 70),
            block("¥30.00", 120),
            block("收款方 李四", 140),
            block("转账处理中", 150),
        )
        val plan = ScreenshotImportPipeline.plan(request(), mixed, emptyList(), timeZone = zone)
        assertEquals(2, plan.decisions.size)
        val booked = plan.decisions.first { it.action == Action.AUTO_BOOK }
        val waited = plan.decisions.first { it.action == Action.WAIT_FOR_EVIDENCE }
        assertEquals(1200L, booked.draft.amountCents)
        assertEquals(3000L, waited.draft.amountCents)
        assertEquals(1, plan.bookings.size)
    }

    @Test
    fun `several medium drafts ask one at a time`() {
        val twoMedium = listOf(
            block("微信支付", 10),
            block("¥11.00", 40),
            block("收款方 A店", 60),
            block("¥22.00", 100),
            block("收款方 B店", 120),
        )
        val plan = ScreenshotImportPipeline.plan(request(), twoMedium, emptyList(), timeZone = zone)
        val asked = plan.needsConfirmation
        assertEquals(2, asked.size)
        assertFalse(asked.first().queued)
        assertTrue(asked.last().queued)
        assertEquals(asked.first().index, ScreenshotImportPipeline.nextConfirmation(plan.decisions)?.index)
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `re sharing the same image does not book again`() {
        val plan = ScreenshotImportPipeline.plan(
            request = request(),
            blocks = wechatSuccess(),
            existing = emptyList(),
            previousImageFingerprints = setOf(FINGERPRINT),
            timeZone = zone,
        )
        assertTrue(plan.repeatImage)
        assertTrue(plan.decisions.isEmpty())
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `exact order id links to the existing ledger row instead of adding one`() {
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 77,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 3650,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                orderId = "1000123456789012",
                merchant = "张三",
                eventTimeMs = capturedAt,
            ),
        )
        val plan = ScreenshotImportPipeline.plan(request(), wechatSuccess(), existing, timeZone = zone)
        val decision = plan.decisions.single()
        assertEquals(Action.LINK_EXISTING, decision.action)
        assertEquals(77L, decision.matchedTransactionId)
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `same amount a few minutes apart is not merged without matching merchant`() {
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 88,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 3650,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = "王五",
                eventTimeMs = capturedAt + 60_000,
            ),
        )
        val plan = ScreenshotImportPipeline.plan(request(), wechatSuccess(), existing, timeZone = zone)
        assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
        assertEquals(1, plan.bookings.size)
    }

    @Test
    fun `same amount same merchant within two minutes links to the notification row`() {
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 99,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 3650,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = "张三",
                eventTimeMs = capturedAt + 30_000,
            ),
        )
        val plan = ScreenshotImportPipeline.plan(request(), wechatSuccess(), existing, timeZone = zone)
        assertEquals(Action.LINK_EXISTING, plan.decisions.single().action)
        assertEquals(99L, plan.decisions.single().matchedTransactionId)
    }

    @Test
    fun `missing real transaction time falls back to capture time and stays marked`() {
        val noTime = listOf(
            block("微信支付", 10),
            block("-¥9.90", 40),
            block("收款方 便利店", 80),
            block("交易成功", 100),
        )
        val plan = ScreenshotImportPipeline.plan(request(), noTime, emptyList(), timeZone = zone)
        val decision = plan.decisions.single()
        assertNull(decision.draft.eventTimeMs)
        val candidate = ScreenshotCandidateFactory.create(
            decision = decision,
            source = IntakeSource.MEDIASTORE,
            capturedAtMs = capturedAt,
            imageFingerprint = FINGERPRINT,
        )
        assertEquals(capturedAt, candidate.eventTimeMs)
        assertEquals(CaptureCandidate.TIME_SOURCE_CAPTURE, candidate.eventTimeSource)
        assertTrue(candidate.eventTimeNeedsConfirmation)
        assertTrue(candidate.isValid())
    }

    @Test
    fun `real transaction time is not marked as needing confirmation`() {
        val plan = ScreenshotImportPipeline.plan(request(), wechatSuccess(), emptyList(), timeZone = zone)
        val candidate = ScreenshotCandidateFactory.create(
            decision = plan.decisions.single(),
            source = IntakeSource.SHARE,
            capturedAtMs = capturedAt,
            imageFingerprint = FINGERPRINT,
        )
        assertEquals(CaptureCandidate.TIME_SOURCE_ACTUAL, candidate.eventTimeSource)
        assertFalse(candidate.eventTimeNeedsConfirmation)
        assertEquals(CaptureCandidate.INTAKE_SHARE, candidate.intakeMethod)
    }
}
