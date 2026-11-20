package com.bookkeeping.app.capture.screenshot

import com.bookkeeping.app.notification.capture.CaptureCandidate
import com.bookkeeping.app.notification.capture.ScreenshotCapturePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private val capturedAt = 1795064400000L // 2026-11-19 13:00 Asia/Shanghai, after the page time

    private companion object {
        const val FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun block(text: String, top: Int) = TextBlock(text = text, top = top, bottom = top + 20)

    private fun localTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
            isLenient = false
        }.timeInMillis

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

    private fun parsedEventTime(): Long =
        ScreenshotPageInterpreter.interpret(wechatMerchantSuccess(), capturedAt, zone).drafts.first().eventTimeMs!!

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

    /** WeChat merchant payment success page: the ordinary auto-book shape. */
    private fun wechatMerchantSuccess() = listOf(
        block("微信支付", 10),
        block("收款方 便利店", 40),
        block("-¥36.50", 80),
        block("当前状态", 120),
        block("交易成功", 140),
        block("2026-11-19 12:30", 180),
        block("订单号 1000123456789012", 220),
    )

    /** Refund arrival page: incoming money, but not ordinary income. */
    private fun wechatRefundArrival() = listOf(
        block("微信支付", 10),
        block("退款成功", 40),
        block("¥1.85", 70),
        block("退款金额 ¥1.85", 100),
        block("2026-11-19 12:30", 130),
    )

    /** Nothing may be written before the user confirms. */
    private fun assertNothingBooked(plan: ImportPlan) {
        assertTrue(plan.bookings.isEmpty())
        assertTrue(plan.decisions.none { it.action == Action.AUTO_BOOK })
    }

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
                blocks = wechatMerchantSuccess(),
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
    fun `completed merchant payment is still auto booked with real transaction time`() {
        val plan = ScreenshotImportPipeline.plan(
            request(),
            wechatMerchantSuccess(),
            emptyList(),
            timeZone = zone,
        )
        val decision = plan.decisions.single()
        assertEquals(Action.AUTO_BOOK, decision.action)
        assertEquals(CaptureCandidate.KIND_PAYMENT, decision.draft.kind)
        assertEquals(listOf("便利店"), decision.draft.merchantCandidates)
        assertEquals("1000123456789012", decision.draft.orderId)
        assertNotNull(decision.draft.eventTimeMs)
        assertEquals(1, plan.bookings.size)
    }

    @Test
    fun `completed transfer is held for confirmation and writes nothing`() {
        val plan = ScreenshotImportPipeline.plan(request(), wechatSuccess(), emptyList(), timeZone = zone)
        val decision = plan.decisions.single()
        assertEquals(CaptureCandidate.KIND_TRANSFER, decision.draft.kind)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, decision.draft.completionStatus)
        // Confidence is still high; only the booking action is withheld.
        assertEquals(CaptureCandidate.TIER_HIGH, decision.tier)
        assertEquals(Action.ASK_USER, decision.action)
        assertNothingBooked(plan)
        assertEquals(1, plan.needsConfirmation.size)
        assertEquals(decision.index, ScreenshotImportPipeline.nextConfirmation(plan.decisions)?.index)
    }

    @Test
    fun `refund arrival is held for confirmation and writes nothing`() {
        val plan = ScreenshotImportPipeline.plan(request(), wechatRefundArrival(), emptyList(), timeZone = zone)
        val decision = plan.decisions.single()
        assertEquals(CaptureCandidate.KIND_REFUND, decision.draft.kind)
        assertEquals(CaptureCandidate.DIRECTION_INCOME, decision.draft.direction)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, decision.draft.completionStatus)
        assertEquals(Action.ASK_USER, decision.action)
        assertNothingBooked(plan)
    }

    @Test
    fun `red packet and withdrawal are held for confirmation and write nothing`() {
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
        val redPacketPlan = ScreenshotImportPipeline.plan(request(), redPacket, emptyList(), timeZone = zone)
        val redPacketDecision = redPacketPlan.decisions.single()
        assertEquals(CaptureCandidate.KIND_RED_PACKET, redPacketDecision.draft.kind)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, redPacketDecision.draft.completionStatus)
        assertEquals(Action.ASK_USER, redPacketDecision.action)
        assertNothingBooked(redPacketPlan)

        val withdrawalPlan = ScreenshotImportPipeline.plan(request(), withdrawal, emptyList(), timeZone = zone)
        val withdrawalDecision = withdrawalPlan.decisions.single()
        assertEquals(CaptureCandidate.KIND_WITHDRAWAL, withdrawalDecision.draft.kind)
        assertEquals(CaptureCandidate.COMPLETION_COMPLETED, withdrawalDecision.draft.completionStatus)
        assertEquals(Action.ASK_USER, withdrawalDecision.action)
        assertNothingBooked(withdrawalPlan)
    }

    @Test
    fun `a forged auto booking for a transfer never reaches the booking list`() {
        val draft = ScreenshotPageInterpreter.interpret(wechatSuccess(), capturedAt, zone).drafts.single()
        val plan = ImportPlan(
            listOf(
                CandidateDecision(
                    index = 0,
                    tier = CaptureCandidate.TIER_HIGH,
                    action = Action.AUTO_BOOK,
                    reason = "forged",
                    draft = draft,
                ),
            ),
        )
        // The model layer is the last lock: only payment kinds can ever appear
        // in ImportPlan.bookings, whatever a decision claims.
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `preview only never yields a ledger booking even for a merchant payment`() {
        val plan = ScreenshotImportPipeline.plan(
            request(),
            wechatMerchantSuccess(),
            emptyList(),
            allowAuto = true,
            previewOnly = true,
            timeZone = zone,
        )
        assertEquals(Action.ASK_USER, plan.decisions.single().action)
        assertFalse(plan.decisions.single().ledgerWriteAllowed)
        assertFalse(
            ScreenshotCandidateFactory.create(
                decision = plan.decisions.single(),
                source = IntakeSource.SHARE,
                capturedAtMs = capturedAt,
                imageFingerprint = FINGERPRINT,
            ).ledgerWriteAllowed,
        )
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `auto disabled holds the merchant payment instead of booking`() {
        val plan = ScreenshotImportPipeline.plan(
            request(),
            wechatMerchantSuccess(),
            emptyList(),
            allowAuto = false,
            timeZone = zone,
        )
        assertEquals(Action.ASK_USER, plan.decisions.single().action)
        assertFalse(plan.decisions.single().ledgerWriteAllowed)
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
    fun `cancelled transfer still waits for evidence instead of asking`() {
        val cancelled = listOf(
            block("微信支付", 10),
            block("转账给 张三", 40),
            block("¥200.00", 70),
            block("对方已取消", 100),
        )
        val plan = ScreenshotImportPipeline.plan(request(), cancelled, emptyList(), timeZone = zone)
        val decision = plan.decisions.single()
        assertEquals(CaptureCandidate.KIND_TRANSFER, decision.draft.kind)
        assertEquals(Action.WAIT_FOR_EVIDENCE, decision.action)
        assertTrue(plan.needsConfirmation.isEmpty())
        assertNothingBooked(plan)
    }

    @Test
    fun `medium confidence asks before writing`() {
        val plan = ScreenshotImportPipeline.plan(request(), alipayMedium(), emptyList(), timeZone = zone)
        val decision = plan.decisions.single()
        assertEquals(CaptureCandidate.TIER_MEDIUM, decision.tier)
        assertEquals(Action.ASK_USER, decision.action)
        assertTrue(decision.ledgerWriteAllowed)
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
    fun `queued preview candidates remain non-writable`() {
        val twoMedium = listOf(
            block("微信支付", 10),
            block("¥11.00", 40),
            block("收款方 A店", 60),
            block("¥22.00", 100),
            block("收款方 B店", 120),
        )
        val plan = ScreenshotImportPipeline.plan(
            request(), twoMedium, emptyList(), previewOnly = true, timeZone = zone,
        )

        assertEquals(2, plan.needsConfirmation.size)
        assertTrue(plan.needsConfirmation.all { !it.ledgerWriteAllowed })
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
                merchant = "便利店",
                eventTimeMs = parsedEventTime(),
            ),
        )
        val plan = ScreenshotImportPipeline.plan(request(), wechatMerchantSuccess(), existing, timeZone = zone)
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
                eventTimeMs = parsedEventTime() + 60_000,
            ),
        )
        val plan = ScreenshotImportPipeline.plan(request(), wechatMerchantSuccess(), existing, timeZone = zone)
        assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
        assertEquals(1, plan.bookings.size)
    }

    @Test
    fun `same amount same merchant within two minutes links to the notification row`() {
        // No order id on this page, so the merchant + amount + 2 minute window
        // rule is what decides.
        val page = alipayMedium() + block("2026-11-19 12:40", 160)
        val pageTime = ScreenshotPageInterpreter.interpret(page, capturedAt, zone)
            .drafts.first().eventTimeMs!!
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 99,
                platform = CaptureCandidate.PLATFORM_ALIPAY,
                amountCents = 2800,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = "星巴克",
                eventTimeMs = pageTime + 30_000,
            ),
        )
        val plan = ScreenshotImportPipeline.plan(request(), page, existing, timeZone = zone)
        assertEquals(Action.LINK_EXISTING, plan.decisions.single().action)
        assertEquals(99L, plan.decisions.single().matchedTransactionId)
    }

    @Test
    fun `same order id on another payment platform is not linked`() {
        val otherPlatform = listOf(
            LedgerFingerprint(
                transactionId = 78,
                platform = CaptureCandidate.PLATFORM_ALIPAY,
                amountCents = 3650,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                orderId = "1000123456789012",
                merchant = "便利店",
                eventTimeMs = parsedEventTime(),
            ),
        )
        val plan = ScreenshotImportPipeline.plan(
            request(), wechatMerchantSuccess(), otherPlatform, timeZone = zone,
        )

        assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
        assertNull(plan.decisions.single().matchedTransactionId)
        assertEquals(1, plan.bookings.size)
    }

    @Test
    fun `same fuzzy fields on another payment platform are not linked`() {
        val alipay = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("2026-11-19 12:30", 130),
        )
        val time = ScreenshotPageInterpreter.interpret(alipay, capturedAt, zone).drafts.single().eventTimeMs!!
        val otherPlatform = listOf(
            LedgerFingerprint(
                transactionId = 79,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 990,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = "便利店",
                eventTimeMs = time,
            ),
        )
        val plan = ScreenshotImportPipeline.plan(request(), alipay, otherPlatform, timeZone = zone)

        assertEquals(Action.AUTO_BOOK, plan.decisions.single().action)
        assertNull(plan.decisions.single().matchedTransactionId)
    }

    @Test
    fun `missing actual event time makes a plausible fuzzy match review-only`() {
        val noTime = listOf(
            block("微信支付", 10),
            block("-¥9.90", 40),
            block("收款方 便利店", 80),
            block("交易成功", 100),
        )
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 80,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 990,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = "便利店",
                eventTimeMs = capturedAt,
            ),
        )
        val decision = ScreenshotImportPipeline.plan(request(), noTime, existing, timeZone = zone)
            .decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertNull(decision.matchedTransactionId)
    }

    @Test
    fun `same amount with an ocr missing merchant is review only, never a link`() {
        // The page shows no merchant at all, so nothing proves this is the row
        // the notification capture stored a minute earlier. It must not be
        // swallowed as a duplicate.
        val noMerchant = listOf(
            block("微信支付", 10),
            block("-¥9.90", 40),
            block("交易成功", 70),
            block("2026-11-19 12:30", 100),
        )
        val pageTime = ScreenshotPageInterpreter.interpret(noMerchant, capturedAt, zone)
            .drafts.single().eventTimeMs!!
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 91,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 990,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = "便利店",
                eventTimeMs = pageTime + 60_000,
            ),
        )

        val plan = ScreenshotImportPipeline.plan(request(), noMerchant, existing, timeZone = zone)
        val decision = plan.decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertNull(decision.matchedTransactionId)
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `ledger row without a merchant name is never an exact match`() {
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 92,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 3650,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = null,
                eventTimeMs = parsedEventTime() + 30_000,
            ),
        )

        val plan = ScreenshotImportPipeline.plan(request(), wechatMerchantSuccess(), existing, timeZone = zone)
        val decision = plan.decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertNull(decision.matchedTransactionId)
        assertTrue(plan.bookings.isEmpty())
    }

    @Test
    fun `a second unidentifiable row keeps the duplicate decision ambiguous`() {
        val time = parsedEventTime()
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 93,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 3650,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = "便利店",
                eventTimeMs = time + 30_000,
            ),
            LedgerFingerprint(
                transactionId = 94,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 3650,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = null,
                eventTimeMs = time + 45_000,
            ),
        )

        val decision = ScreenshotImportPipeline.plan(request(), wechatMerchantSuccess(), existing, timeZone = zone)
            .decisions.single()

        assertEquals(Action.ASK_USER, decision.action)
        assertNull(decision.matchedTransactionId)
    }

    @Test
    fun `same amount in another currency is never an exact match`() {
        val existing = listOf(
            LedgerFingerprint(
                transactionId = 95,
                platform = CaptureCandidate.PLATFORM_WECHAT,
                amountCents = 3650,
                direction = CaptureCandidate.DIRECTION_EXPENSE,
                merchant = "便利店",
                eventTimeMs = parsedEventTime() + 30_000,
                currency = "USD",
            ),
        )

        val plan = ScreenshotImportPipeline.plan(request(), wechatMerchantSuccess(), existing, timeZone = zone)
        val decision = plan.decisions.single()

        assertNotEquals(Action.LINK_EXISTING, decision.action)
        assertNull(decision.matchedTransactionId)
        assertEquals(1, plan.bookings.size)
    }

    @Test
    fun `a plain month day beats capture day inference`() {
        // Captured 2026-11-20 12:00, page says 11月19日 20:10: yesterday evening,
        // not today's clock. Guards the ordinary case the new-year logic sits on.
        val capture = localTime(2026, 11, 20, 12, 0)
        val blocks = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("11月19日 20:10", 130),
        )

        val decision = ScreenshotImportPipeline.plan(
            request().copy(capturedAtMs = capture), blocks, emptyList(), timeZone = zone,
        ).decisions.single()

        assertEquals(localTime(2026, 11, 19, 20, 10), decision.draft.eventTimeMs)
        // Capture-day inference would have produced the 20th at 20:10 instead.
        assertNotEquals(localTime(2026, 11, 20, 20, 10), decision.draft.eventTimeMs)
    }

    @Test
    fun `chinese month day resolves to the nearest year`() {
        // Captured 2027-01-01 00:30, page says 12月31日 22:00: the capture year
        // would be eleven months ahead, so the year that just ended wins.
        val capture = localTime(2027, 1, 1, 0, 30)
        val blocks = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("12月31日 22:00", 130),
        )

        val decision = ScreenshotImportPipeline.plan(
            request().copy(capturedAtMs = capture), blocks, emptyList(), timeZone = zone,
        ).decisions.single()
        val candidate = ScreenshotCandidateFactory.create(
            decision = decision,
            source = IntakeSource.SHARE,
            capturedAtMs = capture,
            imageFingerprint = FINGERPRINT,
        )

        assertEquals(localTime(2026, 12, 31, 22, 0), decision.draft.eventTimeMs)
        assertEquals(CaptureCandidate.TIME_SOURCE_ACTUAL, candidate.eventTimeSource)
        assertFalse(candidate.eventTimeNeedsConfirmation)
        // Not the capture year's December, and not the capture day's clock.
        assertNotEquals(localTime(2027, 12, 31, 22, 0), decision.draft.eventTimeMs)
        assertNotEquals(localTime(2027, 1, 1, 22, 0), decision.draft.eventTimeMs)
    }

    @Test
    fun `a new year month day captured on new year's eve stays on that day`() {
        // Captured 2026-12-31 23:50, page says 1月1日 00:10 — twenty minutes old,
        // not eleven months old. Capture-day inference would also give a false
        // date here, so the yearless date has to be resolved on its own.
        val capture = localTime(2026, 12, 31, 23, 50)
        val page = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("1月1日 00:10", 130),
        )

        val decision = ScreenshotImportPipeline.plan(
            request().copy(capturedAtMs = capture), page, emptyList(), timeZone = zone,
        ).decisions.single()

        assertEquals(localTime(2027, 1, 1, 0, 10), decision.draft.eventTimeMs)
        assertNotEquals(localTime(2026, 1, 1, 0, 10), decision.draft.eventTimeMs)
        assertFalse(
            "a page date must never be replaced by the capture day's clock",
            locallySameDay(decision.draft.eventTimeMs!!, capture),
        )
    }

    private fun locallySameDay(a: Long, b: Long): Boolean {
        val one = java.util.Calendar.getInstance(zone).apply { timeInMillis = a }
        val two = java.util.Calendar.getInstance(zone).apply { timeInMillis = b }
        return one.get(java.util.Calendar.YEAR) == two.get(java.util.Calendar.YEAR) &&
            one.get(java.util.Calendar.DAY_OF_YEAR) == two.get(java.util.Calendar.DAY_OF_YEAR)
    }

    @Test
    fun `a merchant name containing month or day characters keeps a bare clock`() {
        // 每日优鲜 carries 日 and 月饼 carries 月, but neither is a date token.
        // The old guard dropped the whole page's clock because of them.
        val capture = localTime(2026, 11, 20, 22, 0)
        val blocks = listOf(
            block("支付宝", 10),
            block("每日优鲜 月饼礼盒", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("20:10", 130),
        )

        val decision = ScreenshotImportPipeline.plan(
            request().copy(capturedAtMs = capture), blocks, emptyList(), timeZone = zone,
        ).decisions.single()

        assertEquals(localTime(2026, 11, 20, 20, 10), decision.draft.eventTimeMs)
    }

    @Test
    fun `a real date token still blocks the time only fallback`() {
        val capture = localTime(2026, 11, 20, 12, 0)
        val blocks = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("11月29日 20:10", 130),
        )

        val decision = ScreenshotImportPipeline.plan(
            request().copy(capturedAtMs = capture), blocks, emptyList(), timeZone = zone,
        ).decisions.single()

        // 11-29 is nine days in the future: beyond tolerance, still unresolved.
        assertNull(decision.draft.eventTimeMs)
        // And it must not fall back to the capture day's clock either.
        assertNotEquals(localTime(2026, 11, 20, 20, 10), decision.draft.eventTimeMs)
    }

    @Test
    fun `impossible or future month day stays provisional instead of using capture time`() {
        val capture = localTime(2026, 11, 20, 12, 0)
        listOf("2月30日 20:10", "13月1日 20:10", "12月24日 20:10").forEach { stamp ->
            val blocks = listOf(
                block("支付宝", 10),
                block("收款方 便利店", 40),
                block("-¥9.90", 70),
                block("交易成功", 100),
                block(stamp, 130),
            )
            val decision = ScreenshotImportPipeline.plan(
                request().copy(capturedAtMs = capture), blocks, emptyList(), timeZone = zone,
            ).decisions.single()
            val candidate = ScreenshotCandidateFactory.create(
                decision = decision,
                source = IntakeSource.SHARE,
                capturedAtMs = capture,
                imageFingerprint = FINGERPRINT,
            )

            assertNull("$stamp must not be read as a clock", decision.draft.eventTimeMs)
            assertEquals(capture, candidate.eventTimeMs)
            assertEquals(CaptureCandidate.TIME_SOURCE_CAPTURE, candidate.eventTimeSource)
            assertTrue(candidate.eventTimeNeedsConfirmation)
        }
    }

    @Test
    fun `date tokens without a readable clock never become the capture day`() {
        val capture = localTime(2026, 11, 20, 12, 0)
        val blocks = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("11月29日 20:10 自动确认收货后扣款", 130),
            block("2026年12月01日 09:00", 160),
        )

        val decision = ScreenshotImportPipeline.plan(
            request().copy(capturedAtMs = capture), blocks, emptyList(), timeZone = zone,
        ).decisions.single()

        // The month/day pair is only 9 days in the future, still beyond tolerance,
        // and the 年 form is beyond the five-minute future window as well.
        assertNull(decision.draft.eventTimeMs)
    }

    @Test
    fun `explicit yesterday overrides same day time inference near day end`() {
        val capture = localTime(2026, 11, 20, 23, 0)
        val blocks = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("昨天 20:10", 130),
        )

        val draft = ScreenshotPageInterpreter.interpret(blocks, capture, zone).drafts.single()
        assertEquals(localTime(2026, 11, 19, 20, 10), draft.eventTimeMs)
    }

    @Test
    fun `explicit yesterday crosses midnight in the pinned timezone`() {
        val capture = localTime(2026, 11, 20, 0, 2)
        val blocks = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("昨天 23:59", 130),
        )

        val draft = ScreenshotPageInterpreter.interpret(blocks, capture, zone).drafts.single()
        assertEquals(localTime(2026, 11, 19, 23, 59), draft.eventTimeMs)
    }

    @Test
    fun `relative day without a safe clock remains provisional at capture time`() {
        val capture = localTime(2026, 11, 20, 0, 2)
        val blocks = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("昨天", 130),
        )
        val plan = ScreenshotImportPipeline.plan(
            request().copy(capturedAtMs = capture),
            blocks,
            emptyList(),
            timeZone = zone,
        )
        val decision = plan.decisions.single()
        assertNull(decision.draft.eventTimeMs)
        val candidate = ScreenshotCandidateFactory.create(
            decision = decision,
            source = IntakeSource.SHARE,
            capturedAtMs = capture,
            imageFingerprint = FINGERPRINT,
        )
        assertEquals(capture, candidate.eventTimeMs)
        assertEquals(CaptureCandidate.TIME_SOURCE_CAPTURE, candidate.eventTimeSource)
        assertTrue(candidate.eventTimeNeedsConfirmation)
    }

    @Test
    fun `invalid relative clock falls back provisionally without inventing time`() {
        val capture = localTime(2026, 11, 20, 12, 0)
        val blocks = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("昨天 25:00", 130),
        )
        val decision = ScreenshotImportPipeline.plan(
            request().copy(capturedAtMs = capture), blocks, emptyList(), timeZone = zone,
        ).decisions.single()
        val candidate = ScreenshotCandidateFactory.create(
            decision = decision,
            source = IntakeSource.SHARE,
            capturedAtMs = capture,
            imageFingerprint = FINGERPRINT,
        )

        assertNull(decision.draft.eventTimeMs)
        assertEquals(capture, candidate.eventTimeMs)
        assertEquals(CaptureCandidate.TIME_SOURCE_CAPTURE, candidate.eventTimeSource)
        assertTrue(candidate.eventTimeNeedsConfirmation)
    }

    @Test
    fun `ambiguous relative clock stays provisional and needs confirmation`() {
        val capture = localTime(2026, 11, 20, 12, 0)
        val blocks = listOf(
            block("支付宝", 10),
            block("收款方 便利店", 40),
            block("-¥9.90", 70),
            block("交易成功", 100),
            block("昨天 20:10 21:20", 130),
        )
        val decision = ScreenshotImportPipeline.plan(
            request().copy(capturedAtMs = capture), blocks, emptyList(), timeZone = zone,
        ).decisions.single()
        val candidate = ScreenshotCandidateFactory.create(
            decision = decision,
            source = IntakeSource.SHARE,
            capturedAtMs = capture,
            imageFingerprint = FINGERPRINT,
        )

        assertNull(decision.draft.eventTimeMs)
        assertEquals(capture, candidate.eventTimeMs)
        assertTrue(candidate.eventTimeNeedsConfirmation)
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
        val plan = ScreenshotImportPipeline.plan(request(), wechatMerchantSuccess(), emptyList(), timeZone = zone)
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
