package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.notification.capture.CaptureCandidate
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本层的强身份判重：同商户 + 同金额/方向/币种 + 同交易分钟。
 *
 * 这一层不依赖 Room：拿最近的账本行列表直接比对。它兜住的是「截图管线
 * 没认出来、仍然走到账本写入」的情况——例如管线只看了最近 500 行、或第二次
 * ingest 来自另一条入口。
 */
class SamePaymentIdentityTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun localTime(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 19, hour, minute, second)
            isLenient = false
        }.timeInMillis

    private fun row(
        id: Long = 1,
        amountCents: Long = 500L,
        type: String = "EXPENSE",
        currency: String = "CNY",
        merchant: String = "禧迎迎",
        timestamp: Long = localTime(17, 11),
        source: String = "ALIPAY",
        captureObservedAtMs: Long? = localTime(17, 12),
        eventTimeNeedsConfirmation: Boolean = false,
        duplicateOfId: Long? = null,
    ) = TransactionEntity(
        id = id,
        amountCents = amountCents,
        type = type,
        category = TransactionCategory.OTHER.name,
        merchant = merchant,
        source = source,
        timestamp = timestamp,
        currency = currency,
        captureObservedAtMs = captureObservedAtMs,
        eventTimeNeedsConfirmation = eventTimeNeedsConfirmation,
        duplicateOfId = duplicateOfId,
    )

    /** 17:58 再截 17:11 那笔支付：入账时间仍是 17:11。 */
    private fun incoming(
        timestamp: Long = localTime(17, 11),
        captureObservedAtMs: Long? = localTime(17, 58),
        merchant: String = "禧迎迎",
        amountCents: Long = 500L,
        source: String = "ALIPAY",
        eventTimeNeedsConfirmation: Boolean = false,
    ) = row(
        id = 0L,
        amountCents = amountCents,
        merchant = merchant,
        timestamp = timestamp,
        source = source,
        captureObservedAtMs = captureObservedAtMs,
        eventTimeNeedsConfirmation = eventTimeNeedsConfirmation,
    )

    @Test
    fun `same merchant amount and event minute matches 47 minutes after capture`() {
        val match = SamePaymentIdentity.findMatch(listOf(row()), incoming())

        assertNotNull("the money is already in the ledger", match)
        assertEquals(1L, match?.id)
    }

    @Test
    fun `a different event minute never matches`() {
        val match = SamePaymentIdentity.findMatch(
            candidates = listOf(row(timestamp = localTime(17, 12))),
            incoming = incoming(timestamp = localTime(17, 11)),
        )

        assertNull("same shop, same price, a minute later is a second purchase", match)
    }

    @Test
    fun `a row whose event time needs confirmation never matches`() {
        val match = SamePaymentIdentity.findMatch(
            candidates = listOf(row(eventTimeNeedsConfirmation = true)),
            incoming = incoming(),
        )

        assertNull(match)
    }

    @Test
    fun `an incoming row whose event time needs confirmation never matches`() {
        val match = SamePaymentIdentity.findMatch(
            candidates = listOf(row()),
            incoming = incoming(eventTimeNeedsConfirmation = true),
        )

        assertNull(match)
    }

    @Test
    fun `a row without a merchant never matches`() {
        val match = SamePaymentIdentity.findMatch(
            candidates = listOf(row(merchant = "")),
            incoming = incoming(),
        )

        assertNull(match)
    }

    @Test
    fun `a row on another platform never matches`() {
        val match = SamePaymentIdentity.findMatch(
            candidates = listOf(row(source = "WECHAT")),
            incoming = incoming(source = "ALIPAY"),
        )

        assertNull(match)
    }

    @Test
    fun `another amount or direction never matches`() {
        assertNull(
            SamePaymentIdentity.findMatch(
                candidates = listOf(row(amountCents = 501L)),
                incoming = incoming(),
            ),
        )
    }

    @Test
    fun `an incoming notification row is never identity matched`() {
        // 通知 / 短信不写 capture provenance：它们的原有去重语义不变。
        val match = SamePaymentIdentity.findMatch(
            candidates = listOf(row()),
            incoming = incoming(captureObservedAtMs = null),
        )

        assertNull(match)
    }

    @Test
    fun `duplicate rows that are already excluded do not count`() {
        val match = SamePaymentIdentity.findMatch(
            candidates = listOf(row(duplicateOfId = 99L)),
            incoming = incoming(),
        )

        assertNull(match)
    }

    @Test
    fun `minute precision compares the printed time and refuses second level guesses`() {
        val pageTime = localTime(17, 11)
        assertTrue(SamePaymentIdentity.eventTimesMatch(pageTime, pageTime))
        // 两侧都是同一秒的实时时刻也是同一时间。
        assertTrue(SamePaymentIdentity.eventTimesMatch(localTime(17, 11, 37), localTime(17, 11, 37)))
        // 带秒的实时时刻和整分钟的页面时间不是同一个证据。
        assertFalse(SamePaymentIdentity.eventTimesMatch(pageTime, pageTime + 30_000L))
        assertFalse(SamePaymentIdentity.eventTimesMatch(pageTime, localTime(17, 12)))
        assertFalse(SamePaymentIdentity.eventTimesMatch(null, pageTime))
        assertFalse(SamePaymentIdentity.eventTimesMatch(pageTime, 0L))
    }

    @Test
    fun `identity carries the capture kind and platform`() {
        val identity = SamePaymentIdentity.identityOf(row())

        assertEquals(500L, identity.amountCents)
        assertEquals(CaptureCandidate.PLATFORM_ALIPAY, identity.platform)
        assertEquals(localTime(17, 11), identity.eventTimeMs)
    }
}
