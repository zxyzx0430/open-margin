package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.ui.components.HoldRelease
import com.bookkeeping.app.ui.components.QuickEntryRule
import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.voice.asr.AsrErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录音面板的判定逻辑必须是纯 JVM 可测的：CI 里没有麦克风，真机才能验证识别质量，
 * 但「编辑后 Partial 不能覆盖」「录音中不能提交」「Final 不能静默替换用户改的字」
 * 这些一旦写错就会把半句话或用户没确认的文本记进账本，必须在这里钉住。
 */
class VoiceCaptureLogicTest {

    // ---------------------------------------------------------------- 文本清洗

    @Test
    fun finalTextIsTrimmedAndBlankBecomesNull() {
        assertEquals("今天中午吃饭花了三十块钱", VoiceCaptureLogic.entryText("  今天中午吃饭花了三十块钱 "))
        assertNull(VoiceCaptureLogic.entryText(""))
        assertNull(VoiceCaptureLogic.entryText("   "))
        assertNull(VoiceCaptureLogic.entryText("\n\t"))
        assertNull(VoiceCaptureLogic.entryText(null))
    }

    @Test
    fun onlyTextWithLettersOrDigitsCanBeSubmitted() {
        assertFalse(VoiceCaptureLogic.isSubmittable(""))
        assertFalse(VoiceCaptureLogic.isSubmittable("   "))
        // 只剩标点/符号：识别没出字，不能拿去解析，更不能当备注写进去。
        assertFalse(VoiceCaptureLogic.isSubmittable("。"))
        assertFalse(VoiceCaptureLogic.isSubmittable("，、！？…"))
        assertFalse(VoiceCaptureLogic.isSubmittable(" - "))
        assertTrue(VoiceCaptureLogic.isSubmittable("36.5"))
        assertTrue(VoiceCaptureLogic.isSubmittable("麦当劳 36.5"))
        assertTrue(VoiceCaptureLogic.isSubmittable("打车，26.5。"))
    }

    // ---------------------------------------------------------------- Partial

    @Test
    fun partialOnlyRefreshesPreviewWhileListening() {
        val listening = VoiceCaptureState.Listening("今天中午")
        assertEquals(
            VoiceCaptureState.Listening("今天中午吃饭花了三十"),
            VoiceCaptureLogic.withPartial(listening, " 今天中午吃饭花了三十 "),
        )

        // 用户点「停止」后 / 失败 / 已交付 / 已取消：旧会话的 Partial 不能改写任何东西。
        val review = VoiceCaptureState.Review("今天中午")
        assertEquals(review, VoiceCaptureLogic.withPartial(review, "今天中午吃饭"))
        val failed = VoiceCaptureState.Failed(AsrErrorCode.AUDIO_INIT_FAILED, "麦克风被占用")
        assertEquals(failed, VoiceCaptureLogic.withPartial(failed, "今天中午吃饭"))
        val delivered = VoiceCaptureState.Delivered(VoiceEntryRequest("今天中午"))
        assertEquals(delivered, VoiceCaptureLogic.withPartial(delivered, "今天中午吃饭"))
        assertEquals(
            VoiceCaptureState.Cancelled,
            VoiceCaptureLogic.withPartial(VoiceCaptureState.Cancelled, "今天中午吃饭"),
        )
    }

    @Test
    fun editingFreezesPartialRefresh() {
        val listening = VoiceCaptureState.Listening("今天中午")
        val edited = VoiceCaptureLogic.edit(listening, "今天中午吃饭花了三十")
        assertEquals("今天中午吃饭花了三十", (edited as VoiceCaptureState.Listening).text)
        assertFalse(edited.autoFollow)

        // 关键：用户动过键盘之后，后面的 Partial 一个都不能把它盖掉。
        assertEquals(edited, VoiceCaptureLogic.withPartial(edited, "今天中午吃饭花了三十块钱"))
        assertEquals(edited, VoiceCaptureLogic.withPartial(edited, "完全不同的识别结果"))
    }

    @Test
    fun resumeAutoFollowFollowsNewPartialsAgain() {
        val edited = VoiceCaptureLogic.edit(VoiceCaptureState.Listening("我自己写的"), "我自己写的")
        val resumed = VoiceCaptureLogic.resumeAutoFollow(edited)
        assertTrue((resumed as VoiceCaptureState.Listening).autoFollow)
        assertEquals(
            VoiceCaptureState.Listening("识别结果又更新了"),
            VoiceCaptureLogic.withPartial(resumed, "识别结果又更新了"),
        )
    }

    // ---------------------------------------------------------------- 手动停止 → Review

    @Test
    fun stopKeepsTextAndIgnoresRepeats() {
        val listening = VoiceCaptureState.Listening("打车花了二十六块五")
        val review = VoiceCaptureLogic.stop(listening)
        assertEquals(
            VoiceCaptureState.Review(
                text = "打车花了二十六块五",
                autoFollow = true,
                waitingFinal = true,
            ),
            review,
        )
        // 连点两次「停止」：还停在 Review，最后一版文本不丢，也不会掉进失败态。
        assertEquals(review, VoiceCaptureLogic.stop(review))
        // 准备阶段没有「停止」操作，状态不变。
        val preparing = VoiceCaptureState.Preparing("正在请求麦克风权限…")
        assertEquals(preparing, VoiceCaptureLogic.stop(preparing))
    }

    @Test
    fun stopNeverDeliversAnything() {
        // 点「停止」只迁移状态：submit 只认 Review，停止本身不产生交付请求。
        val review = VoiceCaptureLogic.stop(VoiceCaptureState.Listening("吃饭三十块"))
        assertTrue(review is VoiceCaptureState.Review)
        assertNull(VoiceCaptureLogic.submit(VoiceCaptureState.Listening("吃饭三十块")))
    }

    @Test
    fun fabReleaseDoesNotEndRecordingButStopButtonDoes() {
        val listening = VoiceCaptureState.Listening("")
        val afterSpeech = VoiceCaptureLogic.withPartial(listening, "打车花了二十六块五")
        assertEquals(VoiceCaptureState.Listening("打车花了二十六块五"), afterSpeech)

        // VoiceFab maps an ordinary post-start release to NOTHING, so no stop transition runs.
        val afterRelease = when (QuickEntryRule.releaseAction(armed = true, cancelled = false)) {
            HoldRelease.TAP -> error("An armed hold cannot become a tap")
            HoldRelease.ABORT_RECORDING -> VoiceCaptureLogic.cancel()
            HoldRelease.NOTHING -> afterSpeech
        }
        assertEquals(afterSpeech, afterRelease)
        assertNull(VoiceCaptureLogic.submit(afterRelease))
        assertEquals(
            VoiceCaptureState.Review("打车花了二十六块五", waitingFinal = true),
            VoiceCaptureLogic.stop(afterRelease),
        )
    }

    @Test
    fun restartClearsPreviousTextAndErrorWithoutPassingThroughFailed() {
        val oldReview = VoiceCaptureState.Review(
            text = "上次识别的旧文本",
            autoFollow = false,
            suggestedReplacement = "旧候选文本",
        )
        val clean = VoiceCaptureLogic.restart(oldReview)
        assertEquals(VoiceCaptureState.Listening(""), clean)

        val retryFromFailure = VoiceCaptureLogic.restart(
            VoiceCaptureState.Failed(AsrErrorCode.AUDIO_INIT_FAILED, "上次错误"),
        )
        assertEquals(VoiceCaptureState.Listening(""), retryFromFailure)
        assertFalse(VoiceCaptureLogic.isCurrentSession(4L, 5L))
        assertTrue(VoiceCaptureLogic.isCurrentSession(5L, 5L))
        assertTrue(VoiceCaptureLogic.isCurrentSession(null, null))
        assertFalse(VoiceCaptureLogic.isCurrentSession(null, 5L))
    }

    @Test
    fun repeatedRestartRequestsDoNotStartOverlappingCycles() {
        var state: VoiceCaptureState = VoiceCaptureState.Review("旧文本")
        var cyclesStarted = 0
        repeat(5) {
            val next = VoiceCaptureLogic.restart(state)
            if (next != null) {
                cyclesStarted++
                state = next
            }
        }
        assertEquals(1, cyclesStarted)
        assertEquals(VoiceCaptureState.Listening(""), state)
        assertNull(VoiceCaptureLogic.restart(state))

        // 新周期明确停止后，下一轮重说可以开始，但仍从干净输入框开始。
        state = VoiceCaptureLogic.stop(state)
        assertEquals(VoiceCaptureState.Listening(""), VoiceCaptureLogic.restart(state))
    }

    // ---------------------------------------------------------------- Final / 冻结

    @Test
    fun finalWithoutEditsReplacesText() {
        val review = VoiceCaptureLogic.stop(VoiceCaptureState.Listening("今天中午吃饭"))
        val settled = VoiceCaptureLogic.onFinal(review, " 今天中午吃饭花了三十块钱 ")
        assertEquals(
            VoiceCaptureState.Review(
                text = "今天中午吃饭花了三十块钱",
                autoFollow = true,
                waitingFinal = false,
            ),
            settled,
        )
    }

    @Test
    fun emptyFinalKeepsTextAndDeliversNothing() {
        // 空 Final：保留已经看到的文本（不是把它清空），也不产生交付。
        val review = VoiceCaptureLogic.stop(VoiceCaptureState.Listening("今天中午"))
        val settled = VoiceCaptureLogic.onFinal(review, "   ")
        assertEquals("今天中午", (settled as VoiceCaptureState.Review).text)
        assertFalse(settled.waitingFinal)
        // 一个字都没有时仍然是空文本：submit 被挡，UI 把主按钮换成「重说」。
        val blank = VoiceCaptureLogic.onFinal(VoiceCaptureLogic.stop(VoiceCaptureState.Listening("")), "")
        assertEquals("", (blank as VoiceCaptureState.Review).text)
        assertNull(VoiceCaptureLogic.submit(blank))
    }

    @Test
    fun finalThatDiffersFromEditedTextIsSuggestedNotApplied() {
        val edited = VoiceCaptureLogic.edit(VoiceCaptureState.Listening("吃饭三十"), "吃饭三十块")
        val settled = VoiceCaptureLogic.onFinal(edited, "今天中午吃饭花了三十块钱")
        assertTrue(settled is VoiceCaptureState.Review)
        val review = settled as VoiceCaptureState.Review
        // 用户改的字必须原样保留，Final 只能变成一条待点的 chip。
        assertEquals("吃饭三十块", review.text)
        assertEquals("今天中午吃饭花了三十块钱", review.suggestedReplacement)
        assertFalse(review.waitingFinal)
    }

    @Test
    fun finalThatMatchesEditedTextDoesNotSuggestReplacement() {
        val edited = VoiceCaptureLogic.edit(VoiceCaptureState.Listening(""), "吃饭三十块")
        val settled = VoiceCaptureLogic.onFinal(edited, " 吃饭三十块 ")
        assertEquals("吃饭三十块", (settled as VoiceCaptureState.Review).text)
        assertNull(settled.suggestedReplacement)
    }

    @Test
    fun acceptingReplacementUsesRecognizedTextAndStopsOverwriting() {
        val edited = VoiceCaptureLogic.edit(VoiceCaptureState.Listening(""), "吃饭三十块")
        val settled = VoiceCaptureLogic.onFinal(edited, "今天中午吃饭花了三十块钱")
        val accepted = VoiceCaptureLogic.acceptReplacement(settled)
        assertEquals("今天中午吃饭花了三十块钱", (accepted as VoiceCaptureState.Review).text)
        assertNull(accepted.suggestedReplacement)
        // 已经收过 Final：再来一条迟到的不改字。
        assertEquals(accepted, VoiceCaptureLogic.onFinal(accepted, "又变了"))
    }

    @Test
    fun resumeAutoFollowPicksUpPendingReplacement() {
        val edited = VoiceCaptureLogic.edit(VoiceCaptureState.Listening(""), "我自己写的")
        val settled = VoiceCaptureLogic.onFinal(edited, "识别出来的句子")
        val resumed = VoiceCaptureLogic.resumeAutoFollow(settled)
        // 恢复自动跟随 = 接受最新的识别结果，并且 chip 消失。
        assertEquals("识别出来的句子", (resumed as VoiceCaptureState.Review).text)
        assertNull(resumed.suggestedReplacement)
        assertTrue(resumed.autoFollow)
    }

    @Test
    fun finalOutsideListeningOrReviewIsIgnored() {
        assertNull(
            VoiceCaptureLogic.submit(
                VoiceCaptureLogic.onFinal(VoiceCaptureState.Preparing("正在准备语音模型…"), "吃饭三十块"),
            ),
        )
        assertEquals(
            VoiceCaptureState.Failed(AsrErrorCode.AUDIO_INIT_FAILED, "麦克风被占用"),
            VoiceCaptureLogic.onFinal(
                VoiceCaptureState.Failed(AsrErrorCode.AUDIO_INIT_FAILED, "麦克风被占用"),
                "吃饭三十块",
            ),
        )
        val delivered = VoiceCaptureState.Delivered(VoiceEntryRequest("吃饭三十块"))
        assertEquals(delivered, VoiceCaptureLogic.onFinal(delivered, "别的句子"))
        assertEquals(
            VoiceCaptureState.Cancelled,
            VoiceCaptureLogic.onFinal(VoiceCaptureState.Cancelled, "别的句子"),
        )
    }

    // ---------------------------------------------------------------- 交付 / 取消

    @Test
    fun submitOnlyFromReviewAndOnlyWithMeaningfulText() {
        val review = VoiceCaptureState.Review("  今天中午吃饭花了三十块钱 ")
        assertEquals(
            VoiceCaptureState.Delivered(VoiceEntryRequest("今天中午吃饭花了三十块钱")),
            VoiceCaptureLogic.submit(review),
        )
        // 录音中不能交付：必须先点面板「停止」进入 Review。
        assertNull(VoiceCaptureLogic.submit(VoiceCaptureState.Listening("吃饭三十块")))
        // 准备 / 失败 / 取消 都不交付。
        assertNull(VoiceCaptureLogic.submit(VoiceCaptureState.Preparing("正在准备语音模型…")))
        assertNull(
            VoiceCaptureLogic.submit(
                VoiceCaptureState.Failed(AsrErrorCode.AUDIO_INIT_FAILED, "麦克风被占用"),
            ),
        )
        assertNull(VoiceCaptureLogic.submit(VoiceCaptureState.Cancelled))
        // 空文本 / 纯标点不能提交。
        assertNull(VoiceCaptureLogic.submit(VoiceCaptureState.Review("")))
        assertNull(VoiceCaptureLogic.submit(VoiceCaptureState.Review("   ")))
        assertNull(VoiceCaptureLogic.submit(VoiceCaptureState.Review("。")))
        assertNull(VoiceCaptureLogic.submit(VoiceCaptureState.Review("，、！？")))
    }

    @Test
    fun cancelIsTerminalAndNeverDelivers() {
        val cancelled = VoiceCaptureLogic.cancel()
        assertEquals(VoiceCaptureState.Cancelled, cancelled)
        assertNull(VoiceCaptureLogic.submit(cancelled))
        assertEquals(cancelled, VoiceCaptureLogic.withPartial(cancelled, "还在说话"))
        assertEquals(cancelled, VoiceCaptureLogic.onFinal(cancelled, "说完了"))
        assertEquals(cancelled, VoiceCaptureLogic.stop(cancelled))
        assertEquals(cancelled, VoiceCaptureLogic.edit(cancelled, "改字"))
    }

    // ---------------------------------------------------------------- 超时 / 错误 / 文案

    @Test
    fun finishTimeoutOnlyAppliesWhileWaitingForFinal() {
        val waiting = VoiceCaptureState.Review("吃饭", waitingFinal = true)
        val timedOut = VoiceCaptureLogic.finishTimedOut(waiting)
        assertEquals(VoiceCaptureState.Review("吃饭", waitingFinal = false), timedOut)
        // 文本保住：超时不等于失败，用户还能直接点「填入账单」。
        assertTrue(VoiceCaptureLogic.submit(timedOut) is VoiceCaptureState.Delivered)

        val listening = VoiceCaptureState.Listening("吃饭")
        assertEquals(listening, VoiceCaptureLogic.finishTimedOut(listening))
        assertEquals(timedOut, VoiceCaptureLogic.finishTimedOut(timedOut))
    }

    @Test
    fun errorKeepsEditableTextWhenAlreadyInReview() {
        val review = VoiceCaptureState.Review("吃饭三十块", waitingFinal = true)
        val failedModel = VoiceCaptureLogic.onError(review, AsrErrorCode.ENGINE_CLOSED)
        assertEquals(
            VoiceCaptureState.Review("吃饭三十块", waitingFinal = false),
            failedModel,
        )
        // 还在听的时候引擎挂掉：转可重试失败态。
        val listeningError = VoiceCaptureLogic.onError(
            VoiceCaptureState.Listening("吃饭"),
            AsrErrorCode.AUDIO_INIT_FAILED,
        )
        assertEquals(AsrErrorCode.AUDIO_INIT_FAILED, (listeningError as VoiceCaptureState.Failed).code)
    }

    @Test
    fun everyErrorCodeHasDistinctReadableMessage() {
        val messages = AsrErrorCode.entries.map { VoiceCaptureLogic.messageFor(it) }
        assertEquals(AsrErrorCode.entries.size, messages.toSet().size)
        messages.forEach { message ->
            assertTrue("错误提示不该是空串", message.isNotBlank())
            assertTrue("错误提示必须是中文原文", message.any { it.code in 0x4E00..0x9FFF })
        }
        // 拿不到错误码（异常里没带 AsrModelException）时按「没听清」处理，不能崩。
        assertEquals(AsrErrorCode.INTERNAL, VoiceCaptureLogic.forError(null).code)
        assertNotEquals(
            VoiceCaptureLogic.forError(AsrErrorCode.PERMISSION_DENIED),
            VoiceCaptureLogic.forError(AsrErrorCode.MODEL_CORRUPTED),
        )
    }

    @Test
    fun everyErrorMessageHasEnglishTranslation() {
        // 这些字符串不走 tr("字面量")，TranslationCoverageTest 扫不到；用这张表补上。
        AsrErrorCode.entries.forEach { code ->
            val message = VoiceCaptureLogic.messageFor(code)
            assertTrue("缺少英文翻译：$message", EnglishUiText.hasTranslation(message))
        }
    }

    @Test
    fun permissionFailureKeepsTheSettingsEscapeHatch() {
        val denied = VoiceCaptureLogic.permissionDenied()
        assertEquals(AsrErrorCode.PERMISSION_DENIED, denied.code)
        assertEquals(VoiceCaptureLogic.messageFor(AsrErrorCode.PERMISSION_DENIED), denied.message)
    }
}
