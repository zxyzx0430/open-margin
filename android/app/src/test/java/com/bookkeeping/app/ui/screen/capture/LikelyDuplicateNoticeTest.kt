package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.notification.capture.CaptureCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「疑似与账本里已有的一笔重复」提示是纯展示：只有 reason 命中才显示，
 * matchedTransactionId 只在有效（>0）时拼进文案，其他候选字段都不参与判断。
 */
class LikelyDuplicateNoticeTest {

    private fun candidate(
        reason: String = CaptureCandidate.REASON_LIKELY_DUPLICATE,
        matchedTransactionId: Long? = 12L,
        amountCents: Long? = 1_524L,
        merchant: String = "星巴克",
        kind: String = CaptureCandidate.KIND_PAYMENT,
        direction: String = CaptureCandidate.DIRECTION_EXPENSE,
        status: String = CaptureCandidate.STATUS_PENDING,
        ledgerWriteAllowed: Boolean = true,
        orderId: String? = "ORDER-1",
    ) = CaptureCandidate(
        packageName = CaptureCandidate.WECHAT,
        pageIdentity = "page-dup-notice",
        amountCents = amountCents,
        merchantCandidates = if (merchant.isBlank()) emptyList() else listOf(merchant),
        orderId = orderId,
        capturedAtMs = 1_700_000_000_000L,
        screenshotPath = null,
        confidence = 0.9f,
        reason = reason,
        status = status,
        kind = kind,
        direction = direction,
        matchedTransactionId = matchedTransactionId,
        ledgerWriteAllowed = ledgerWriteAllowed,
    )

    @Test
    fun duplicateReasonWithValidRecordIdShowsNoticeAndExistingEntry() {
        val notice = likelyDuplicateNoticeText(
            candidate(matchedTransactionId = 12L),
        )
        assertEquals(
            "疑似与账本里已有的一笔重复，请核对后再决定（已有记录 #12）",
            notice,
        )
    }

    @Test
    fun duplicateReasonWithoutRecordIdStillShowsNoticeButNoIdentifier() {
        val missing = likelyDuplicateNoticeText(candidate(matchedTransactionId = null))
        assertEquals("疑似与账本里已有的一笔重复，请核对后再决定", missing)
        assertFalse(missing!!.contains("#"))

        val zero = likelyDuplicateNoticeText(candidate(matchedTransactionId = 0L))
        assertEquals("疑似与账本里已有的一笔重复，请核对后再决定", zero)
        assertFalse(zero!!.contains("#"))

        val negative = likelyDuplicateNoticeText(candidate(matchedTransactionId = -7L))
        assertEquals("疑似与账本里已有的一笔重复，请核对后再决定", negative)
        assertFalse(negative!!.contains("#"))
    }

    @Test
    fun otherReasonsNeverShowTheNotice() {
        assertNull(likelyDuplicateNoticeText(candidate(reason = "")))
        assertNull(likelyDuplicateNoticeText(candidate(reason = "ok")))
        assertNull(likelyDuplicateNoticeText(candidate(reason = "已在账本中")))
        // 只认完整等值，不做前缀/包含匹配。
        assertNull(
            likelyDuplicateNoticeText(
                candidate(reason = CaptureCandidate.REASON_LIKELY_DUPLICATE + "（旧）"),
            ),
        )
    }

    @Test
    fun otherCandidateFieldsDoNotChangeTheDecisionOrText() {
        val rich = candidate()
        val bare = candidate(
            amountCents = null,
            merchant = "",
            kind = CaptureCandidate.KIND_REFUND,
            direction = CaptureCandidate.DIRECTION_UNKNOWN,
            status = CaptureCandidate.STATUS_IGNORED,
            ledgerWriteAllowed = false,
            orderId = null,
        )
        assertEquals(likelyDuplicateNoticeText(rich), likelyDuplicateNoticeText(bare))
        assertEquals(
            "疑似与账本里已有的一笔重复，请核对后再决定（已有记录 #12）",
            likelyDuplicateNoticeText(bare),
        )

        // 反过来：只有 id、reason 不是那个值时不显示，说明 id 本身不是触发条件。
        assertNull(
            likelyDuplicateNoticeText(
                candidate(reason = "ok", matchedTransactionId = 99L),
            ),
        )
    }

    @Test
    fun bothTemplatesHaveEnglishEntries() {
        val base = "疑似与账本里已有的一笔重复，请核对后再决定"
        val withId = "疑似与账本里已有的一笔重复，请核对后再决定（已有记录 #%d）"
        assertTrue(EnglishUiText.hasTranslation(base))
        assertTrue(EnglishUiText.hasTranslation(withId))
        assertEquals(
            "This looks like an entry already in the ledger; check both before you decide",
            EnglishUiText.translate(base),
        )
        assertEquals(
            "This looks like an entry already in the ledger; check both before you decide " +
                "(existing entry #12)",
            EnglishUiText.translate(withId).format(12L),
        )
    }

    /**
     * 同一张截图里出现两条同额同商户记录：这是另一种断言（图里还有一条，不是账本里已有一笔），
     * 所以有自己的常量与文案，也不能凭空拼出账本记录号。
     */
    @Test
    fun sameImageRepeatShowsItsOwnNoticeWithoutALedgerIdentifier() {
        val notice = likelyDuplicateNoticeText(
            candidate(
                reason = CaptureCandidate.REASON_SAME_IMAGE_REPEAT,
                matchedTransactionId = 12L,
            ),
        )
        assertEquals("同一张截图里有金额、商户相同的记录，请核对是否重复", notice)
        assertFalse(notice!!.contains("#"))
        // 与「账本里已有一笔」是两句不同的话，不能混用。
        assertNotEquals(
            likelyDuplicateNoticeText(candidate()),
            notice,
        )
    }

    @Test
    fun sameImageRepeatTemplateHasAnEnglishEntry() {
        val base = "同一张截图里有金额、商户相同的记录，请核对是否重复"
        assertTrue(EnglishUiText.hasTranslation(base))
        assertEquals(
            "This screenshot has records with the same amount and merchant; " +
                "check whether they are duplicates",
            EnglishUiText.translate(base),
        )
    }
}
