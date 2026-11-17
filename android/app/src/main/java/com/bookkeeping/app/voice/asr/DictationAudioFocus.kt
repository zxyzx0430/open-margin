package com.bookkeeping.app.voice.asr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Owns the transient audio focus held only while a dictation capture is active. */
internal class DictationAudioFocus(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "Audio focus changed: $change")
    }

    private var audioFocusRequest: AudioFocusRequest? = null
    private var legacyRequestIssued = false
    private var focusGranted = false

    @Synchronized
    fun request(): Boolean {
        if (focusGranted) return true
        val manager = audioManager
        if (manager == null) {
            Log.w(TAG, "AudioManager unavailable; continuing without audio focus")
            return false
        }

        return try {
            val spec = DictationAudioFocusPolicy.request
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(spec.gain.toPlatformValue())
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(spec.usage.toPlatformValue())
                            .setContentType(spec.contentType.toPlatformValue())
                            .build(),
                    )
                    .setAcceptsDelayedFocusGain(spec.acceptsDelayedFocusGain)
                    .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                    .build()
                audioFocusRequest = request
                manager.requestAudioFocus(request)
            } else {
                legacyRequestIssued = true
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    listener,
                    spec.legacyStream.toPlatformValue(),
                    spec.gain.toPlatformValue(),
                )
            }
            focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusGranted) {
                Log.w(TAG, "Audio focus request was not granted ($result); continuing capture")
                abandonInternal(manager)
            }
            focusGranted
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Audio focus request failed; continuing capture", failure)
            abandonInternal(manager)
            false
        }
    }

    @Synchronized
    fun abandon() {
        audioManager?.let(::abandonInternal)
    }

    private fun abandonInternal(manager: AudioManager) {
        val request = audioFocusRequest
        try {
            if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.abandonAudioFocusRequest(request)
            } else if (legacyRequestIssued) {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(listener)
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Abandoning dictation audio focus failed", failure)
        } finally {
            audioFocusRequest = null
            legacyRequestIssued = false
            focusGranted = false
        }
    }

    private fun DictationFocusGain.toPlatformValue(): Int = when (this) {
        DictationFocusGain.TRANSIENT_EXCLUSIVE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
    }

    private fun DictationFocusUsage.toPlatformValue(): Int = when (this) {
        DictationFocusUsage.VOICE_COMMUNICATION -> AudioAttributes.USAGE_VOICE_COMMUNICATION
    }

    private fun DictationFocusContentType.toPlatformValue(): Int = when (this) {
        DictationFocusContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    private fun DictationLegacyFocusStream.toPlatformValue(): Int = when (this) {
        DictationLegacyFocusStream.MUSIC -> AudioManager.STREAM_MUSIC
    }

    private companion object {
        const val TAG = "DictationAudioFocus"
    }
}
