package com.codemew.bluecall.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.codemew.bluecall.data.NoiseSettings

/** Which of the phone's built-in voice effects exist on this device. */
data class EffectAvailability(
    val echoCancellation: Boolean,
    val noiseSuppression: Boolean,
    val autoGain: Boolean,
) {
    companion object {
        fun query() = EffectAvailability(
            echoCancellation = AcousticEchoCanceler.isAvailable(),
            noiseSuppression = NoiseSuppressor.isAvailable(),
            autoGain = AutomaticGainControl.isAvailable(),
        )
    }
}

/**
 * The phone's own echo canceller, noise suppressor and gain control, attached
 * to the microphone session. On Samsung phones the voice-communication input
 * already runs these in the audio DSP; this class lets each one be switched
 * on or off individually.
 */
class VoiceEffects(audioSessionId: Int) {
    private val aec: AcousticEchoCanceler? = create("AEC") {
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioSessionId) else null
    }
    private val ns: NoiseSuppressor? = create("NS") {
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioSessionId) else null
    }
    private val agc: AutomaticGainControl? = create("AGC") {
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(audioSessionId) else null
    }

    fun apply(settings: NoiseSettings) {
        setEnabled(aec, settings.echoCancellation)
        setEnabled(ns, settings.noiseSuppression)
        setEnabled(agc, settings.autoGain)
    }

    fun release() {
        listOfNotNull(aec, ns, agc).forEach { runCatching { it.release() } }
    }

    private fun setEnabled(effect: AudioEffect?, enabled: Boolean) {
        if (effect == null) return
        try {
            effect.setEnabled(enabled)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not toggle ${effect.javaClass.simpleName}", e)
        }
    }

    private inline fun <T> create(name: String, block: () -> T?): T? = try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "$name unavailable", e)
        null
    }

    private companion object {
        const val TAG = "VoiceEffects"
    }
}
