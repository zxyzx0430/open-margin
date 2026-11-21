package com.bookkeeping.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知重放：同一条通知被系统重复投递时只能记一笔；两笔真实交易永远不能被拦。
 *
 * 现场：微信/支付宝的支付通知先入账 1 行；用户点开交易详情页时，通知被更新/重新
 * 投递，`onNotificationPosted` 再次回调。指纹相同 → 同一条通知；超过
 * `insertIfNotDuplicate` 的 8 秒窗口后，旧实现又插了一行。
 *
 * 这里锁死两条：
 * 1. 同一指纹 + 时间相近（≤ [NoticeReplayPolicy.REPOST_WINDOW_MS]）→ 判定重放，跳过；
 * 2. 两笔真实交易的 `originalTime` 不同 → [NoticeFingerprint] 不同 → **任何**
 *    时间间隔都不能判定为重放。
 *
 * 只测纯逻辑；真实入库 / Room 指纹表属于端到端范围，未在此验证。
 */
class PaymentNotificationReplayTest {

    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val t0 = 1_795_000_000_000L
    private val title = "微信支付"
    private val body = "向 麦当劳 付款 12.30 元"

    private fun fingerprint(
        timeMs: Long,
        packageName: String = wechat,
        text: String = body,
    ): String = NoticeFingerprint.of(
        packageName = packageName,
        title = title,
        body = text,
        originalTimeMs = timeMs,
    )

    // 1. 同一条通知 40 秒后再次回调：判定重放。
    @Test
    fun `same notification reposted seconds later is a repost`() {
        val fp = fingerprint(t0)
        assertTrue(
            NoticeReplayPolicy.isSameNoticeRepost(
                previousFingerprint = fp,
                previousSeenMs = t0,
                fingerprint = fp,
                seenMs = t0 + 40_000L,
            ),
        )
    }

    // 2. 边界：窗口内最后一毫秒仍然算重放，窗口外不再算。
    @Test
    fun `repost window boundary is inclusive and then expires`() {
        val fp = fingerprint(t0)
        assertTrue(
            NoticeReplayPolicy.isSameNoticeRepost(
                fp, t0, fp, t0 + NoticeReplayPolicy.REPOST_WINDOW_MS,
            ),
        )
        assertFalse(
            NoticeReplayPolicy.isSameNoticeRepost(
                fp, t0, fp, t0 + NoticeReplayPolicy.REPOST_WINDOW_MS + 1L,
            ),
        )
    }

    // 3. 核心保护：两笔真实交易，正文/商户/金额完全一样，只有成交时间不同。
    //    指纹必须不同，间隔再短也不能判成重放——否则第二笔真钱就被吞了。
    @Test
    fun `two real payments with different times are never a repost`() {
        val first = fingerprint(t0)
        val second = fingerprint(t0 + 3 * 60_000L)
        assertNotEquals("成交时间不同必须得到不同指纹", first, second)
        assertFalse(
            NoticeReplayPolicy.isSameNoticeRepost(
                previousFingerprint = first,
                previousSeenMs = t0 + 1_000L,
                fingerprint = second,
                seenMs = t0 + 181_000L,
            ),
        )
        // 即使两次回调只隔 1 秒，只要指纹不同就不拦。
        assertFalse(
            NoticeReplayPolicy.isSameNoticeRepost(
                previousFingerprint = first,
                previousSeenMs = t0,
                fingerprint = second,
                seenMs = t0 + 1_000L,
            ),
        )
    }

    // 4. 同额同时但换了支付平台 / 换了金额：都不是同一条通知。
    @Test
    fun `different platform or amount is not a repost`() {
        val wechatFingerprint = fingerprint(t0, packageName = wechat)
        val alipayFingerprint = fingerprint(t0, packageName = alipay)
        assertNotEquals(wechatFingerprint, alipayFingerprint)
        assertFalse(
            NoticeReplayPolicy.isSameNoticeRepost(
                wechatFingerprint, t0, alipayFingerprint, t0 + 5_000L,
            ),
        )
        assertFalse(
            NoticeReplayPolicy.isSameNoticeRepost(
                wechatFingerprint, t0, fingerprint(t0, text = "向 麦当劳 付款 13.30 元"), t0 + 5_000L,
            ),
        )
    }

    // 5. 从没成功入账过（previousSeenMs = null）→ 绝不能判重放：
    //    指纹只在入库成功后写入，写入失败后的重投递必须还能重新入账。
    @Test
    fun `a notification that was never booked is never a repost`() {
        val fp = fingerprint(t0)
        assertFalse(
            NoticeReplayPolicy.isSameNoticeRepost(
                previousFingerprint = fp,
                previousSeenMs = null,
                fingerprint = fp,
                seenMs = t0 + 1_000L,
            ),
        )
    }

    // 6. 系统时间往回走（用户手动改时间）不判重放：宁可多记一行，不能少记一笔。
    @Test
    fun `clock moving backwards is not a repost`() {
        val fp = fingerprint(t0)
        assertFalse(
            NoticeReplayPolicy.isSameNoticeRepost(
                fp, t0, fp, t0 - 60_000L,
            ),
        )
    }

    // 7. 指纹对空白不敏感，但对正文内容敏感：同一通知的不同投递路径（多余空白）
    //    仍算同一条，改了一个字就不是。
    @Test
    fun `fingerprint ignores whitespace noise but not content`() {
        assertEquals(fingerprint(t0, text = body), fingerprint(t0, text = "  向 麦当劳  付款 12.30 元 "))
        assertNotEquals(fingerprint(t0), fingerprint(t0, text = "向 麦当劳 付款 12.30"))
    }
}
