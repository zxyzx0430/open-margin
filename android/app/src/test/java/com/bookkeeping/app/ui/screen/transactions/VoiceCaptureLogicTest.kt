package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.voice.asr.AsrErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录音面板的判定逻辑必须是纯 JVM 可测的：CI 里没有麦克风，真机才能验证识别质量，
 * 但「Partial 不能打开表单」「空 Final 不能写进备注」「失败后迟到的事件不能翻盘」
 * 这些一旦写错就会把半句话或空串记进账本，必须在这里钉住。
 */
class VoiceCaptureLogicTest {

    @Test
    fun finalTextIsTrimmedAndBlankBecomesNull() {
        assertEquals("今天中午吃饭花了三十块钱", VoiceCaptureLogic.entryText("  今天中午吃饭花了三十块钱 "))
        assertNull(VoiceCaptureLogic.entryText(""))
        assertNull(VoiceCaptureLogic.entryText("   "))
        assertNull(VoiceCaptureLogic.entryText("\n\t"))
        assertNull(VoiceCaptureLogic.entryText(null))
    }

    @Test
    fun partialOnlyRefreshesPreviewWhileListening() {
        val listening = VoiceCaptureState.Listening("今天中午")
        assertEquals(
            VoiceCaptureState.Listening("今天中午吃饭花了三十"),
            VoiceCaptureLogic.withPartial(listening, " 今天中午吃饭花了三十 "),
        )

        // 收尾/失败/已交付之后到来的 Partial 属于旧会话，不能改写终态。
        val finishing = VoiceCaptureState.Finishing("今天中午")
        assertEquals(finishing, VoiceCaptureLogic.withPartial(finishing, "今天中午吃饭"))
        val failed = VoiceCaptureState.Failed(AsrErrorCode.AUDIO_INIT_FAILED, "麦克风被占用")
        assertEquals(failed, VoiceCaptureLogic.withPartial(failed, "今天中午吃饭"))
        val captured = VoiceCaptureState.Captured("今天中午")
        assertEquals(captured, VoiceCaptureLogic.withPartial(captured, "今天中午吃饭"))
    }

    @Test
    fun stopKeepsLastPartialAndIgnoresRepeats() {
        val listening = VoiceCaptureState.Listening("打车花了二十六块五")
        val finishing = VoiceCaptureLogic.stop(listening)
        assertEquals(VoiceCaptureState.Finishing("打车花了二十六块五"), finishing)
        // 连点两次停止：还停在收尾，最后一版预览不丢。
        assertEquals(finishing, VoiceCaptureLogic.stop(finishing))
        // 准备阶段点不到停止，也不该把状态改成收尾。
        val preparing = VoiceCaptureState.Preparing("正在请求麦克风权限…")
        assertEquals(preparing, VoiceCaptureLogic.stop(preparing))
    }

    @Test
    fun emptyFinalStillClosesSheetWithoutPrefill() {
        // 说完了但一个字都没识别出来：Captured(null) —— 表单照开，只是不预填。
        assertEquals(
            VoiceCaptureState.Captured(null),
            VoiceCaptureLogic.capture(VoiceCaptureState.Finishing(""), ""),
        )
        assertEquals(
            VoiceCaptureState.Captured(null),
            VoiceCaptureLogic.capture(VoiceCaptureState.Listening(""), "   "),
        )
        // 有字就原样（只去首尾空白，中间的空格交给解析器处理）带走。
        assertEquals(
            VoiceCaptureState.Captured("昨天在超市买东西 一共一百二十八块四"),
            VoiceCaptureLogic.capture(
                VoiceCaptureState.Finishing("昨天在超市买东西"),
                " 昨天在超市买东西 一共一百二十八块四 ",
            ),
        )
    }

    @Test
    fun finalOutsideListeningDoesNotOpenForm() {
        // Partial-only 阶段（准备/失败）收到的 Final 一律不认：绝不能凭它开表单。
        assertNull(VoiceCaptureLogic.capture(VoiceCaptureState.Preparing("正在准备语音模型…"), "吃饭三十块"))
        assertNull(
            VoiceCaptureLogic.capture(
                VoiceCaptureState.Failed(AsrErrorCode.AUDIO_INIT_FAILED, "麦克风被占用"),
                "吃饭三十块",
            ),
        )
        assertNull(VoiceCaptureLogic.capture(VoiceCaptureState.Captured("吃饭三十块"), "吃饭三十块"))
    }

    @Test
    fun finishTimeoutOnlyAppliesWhileFinishing() {
        val timeout = VoiceCaptureLogic.finishTimedOut(VoiceCaptureState.Finishing("吃饭"))
        assertTrue(timeout is VoiceCaptureState.Failed)

        val listening = VoiceCaptureState.Listening("吃饭")
        assertEquals(listening, VoiceCaptureLogic.finishTimedOut(listening))
        val captured = VoiceCaptureState.Captured("吃饭")
        assertEquals(captured, VoiceCaptureLogic.finishTimedOut(captured))
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
