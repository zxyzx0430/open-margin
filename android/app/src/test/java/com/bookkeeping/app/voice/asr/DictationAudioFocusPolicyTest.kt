package com.bookkeeping.app.voice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DictationAudioFocusPolicyTest {
    @Test
    fun audioFocusRequestUsesExclusiveVoiceCommunicationSpeechAttributes() {
        val request = DictationAudioFocusPolicy.request

        assertEquals(DictationFocusGain.TRANSIENT_EXCLUSIVE, request.gain)
        assertEquals(DictationFocusUsage.VOICE_COMMUNICATION, request.usage)
        assertEquals(DictationFocusContentType.SPEECH, request.contentType)
        assertEquals(DictationLegacyFocusStream.MUSIC, request.legacyStream)
        assertFalse(request.acceptsDelayedFocusGain)
    }

    @Test
    fun captureFallsBackToCommunicationThenRecognitionWhenAecIsMissing() {
        assertEquals(
            listOf(
                DictationCaptureSource.VOICE_COMMUNICATION,
                DictationCaptureSource.VOICE_RECOGNITION,
            ),
            DictationCaptureSourcePolicy.fallbacksAfterVoiceRecognition(aecEnabled = false),
        )
        assertEquals(
            emptyList<DictationCaptureSource>(),
            DictationCaptureSourcePolicy.fallbacksAfterVoiceRecognition(aecEnabled = true),
        )
    }
}
