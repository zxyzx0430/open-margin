package com.bookkeeping.app

import com.bookkeeping.app.notification.NotificationTextExtractor
import org.junit.Assert.*
import org.junit.Test

class NotificationTextExtractorTest {

    @Test fun bigTextPreferredOverShortTextWhenItHasTheAmount() {
        val body = NotificationTextExtractor.bestBody(
            text = "你收到一条消息",
            bigText = "成功收款 1.00 元",
            textLines = emptyList(),
            messages = emptyList(),
        )
        assertEquals("成功收款 1.00 元", body)
    }

    @Test fun textLinesUsedWhenBigTextMissingAndDuplicatesRemoved() {
        val body = NotificationTextExtractor.bestBody(
            text = "微信支付",
            bigText = null,
            textLines = listOf(
                "向 麦当劳 付款 12.30 元",
                "",
                "向 麦当劳 付款 12.30 元",
                "已到账",
            ),
            messages = emptyList(),
        )
        assertEquals("向 麦当劳 付款 12.30 元 已到账", body)
    }

    @Test fun messagesUsedWhenTextIsOnlyATitle() {
        val body = NotificationTextExtractor.bestBody(
            text = "2 条新消息",
            bigText = null,
            textLines = emptyList(),
            messages = listOf("张三: 已收款 8.80 元", "李四: 好的"),
        )
        assertEquals("张三: 已收款 8.80 元 李四: 好的", body)
    }

    @Test fun digitLessCandidatesSkippedInFavourOfLaterDigitBearingOne() {
        val body = NotificationTextExtractor.bestBody(
            text = "付款成功 25.00 元",
            bigText = "付款成功",
            textLines = listOf("微信支付"),
            messages = emptyList(),
        )
        assertEquals("付款成功 25.00 元", body)
    }

    @Test fun digitLessCandidatesFallBackToFirstNonBlank() {
        val body = NotificationTextExtractor.bestBody(
            text = "交易提醒",
            bigText = "付款成功",
            textLines = emptyList(),
            messages = emptyList(),
        )
        assertEquals("付款成功", body)
    }

    @Test fun allBlankReturnsNull() {
        assertNull(
            NotificationTextExtractor.bestBody(
                text = "   ",
                bigText = "",
                textLines = listOf("", "   "),
                messages = emptyList(),
            )
        )
        assertNull(NotificationTextExtractor.bestBody(null, null, emptyList(), emptyList()))
    }

    @Test fun resolveOriginalTimePicksLatestMessageTimeWithin24h() {
        val postTime = 1791512160000L
        val resolved = NotificationTextExtractor.resolveOriginalTimeMs(
            postTimeMs = postTime,
            notificationWhenMs = postTime - 600_000L,          // 10 min earlier
            messageTimesMs = listOf(postTime - 3_600_000L, postTime - 60_000L),
        )
        assertEquals(postTime - 60_000L, resolved)
    }

    @Test fun resolveOriginalTimeRejectsTimesAfterPostTime() {
        val postTime = 1791512160000L
        val resolved = NotificationTextExtractor.resolveOriginalTimeMs(
            postTimeMs = postTime,
            notificationWhenMs = postTime + 600_000L,          // future, rejected
            messageTimesMs = listOf(postTime + 30_000L, postTime - 5_000L),
        )
        assertEquals(postTime - 5_000L, resolved)
    }

    @Test fun resolveOriginalTimeRejectsTimesOlderThan24h() {
        val postTime = 1791512160000L
        val tooOld = postTime - 25L * 60 * 60 * 1000
        val resolved = NotificationTextExtractor.resolveOriginalTimeMs(
            postTimeMs = postTime,
            notificationWhenMs = tooOld,
            messageTimesMs = listOf(tooOld, postTime - 1_000L),
        )
        assertEquals(postTime - 1_000L, resolved)
    }

    @Test fun resolveOriginalTimeFallsBackToPostTimeWhenUnknown() {
        val postTime = 1791512160000L
        // when_=0（未设置）且没有 message 时间 -> 保持 postTime。
        assertEquals(
            postTime,
            NotificationTextExtractor.resolveOriginalTimeMs(postTime, 0L, emptyList())
        )
        // 只有超过 24h 的旧时间也不采用。
        assertEquals(
            postTime,
            NotificationTextExtractor.resolveOriginalTimeMs(
                postTime, 0L, listOf(postTime - 24L * 60 * 60 * 1000 - 1L))
        )
    }
}
