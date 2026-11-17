package com.bookkeeping.app.voice.asr

/** Pure-JVM description of the focus request, kept separate from Android's API objects. */
internal enum class DictationFocusGain { TRANSIENT_EXCLUSIVE }
internal enum class DictationFocusUsage { VOICE_COMMUNICATION }
internal enum class DictationFocusContentType { SPEECH }
internal enum class DictationLegacyFocusStream { MUSIC }

internal data class DictationAudioFocusSpec(
    val gain: DictationFocusGain,
    val usage: DictationFocusUsage,
    val contentType: DictationFocusContentType,
    val legacyStream: DictationLegacyFocusStream,
    val acceptsDelayedFocusGain: Boolean,
)

internal object DictationAudioFocusPolicy {
    val request = DictationAudioFocusSpec(
        gain = DictationFocusGain.TRANSIENT_EXCLUSIVE,
        usage = DictationFocusUsage.VOICE_COMMUNICATION,
        contentType = DictationFocusContentType.SPEECH,
        legacyStream = DictationLegacyFocusStream.MUSIC,
        acceptsDelayedFocusGain = false,
    )
}

internal enum class DictationCaptureSource { VOICE_RECOGNITION, VOICE_COMMUNICATION }

internal object DictationCaptureSourcePolicy {
    /** Voice recognition is preferred with explicit AEC; otherwise try communication then recognition. */
    fun fallbacksAfterVoiceRecognition(aecEnabled: Boolean): List<DictationCaptureSource> =
        if (aecEnabled) {
            emptyList()
        } else {
            listOf(
                DictationCaptureSource.VOICE_COMMUNICATION,
                DictationCaptureSource.VOICE_RECOGNITION,
            )
        }
}
