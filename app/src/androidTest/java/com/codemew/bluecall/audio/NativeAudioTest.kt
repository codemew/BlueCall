package com.codemew.bluecall.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** On-device smoke test of the native audio library (run with connectedDebugAndroidTest). */
@RunWith(AndroidJUnit4::class)
class NativeAudioTest {

    private val rate = AudioEngine.SAMPLE_RATE
    private val frame = rate / 50 // 20 ms

    private fun tone(frames: Int): ShortArray = ShortArray(frames * frame) { i ->
        (8000 * sin(2 * PI * 440 * i / rate)).toInt().toShort()
    }

    @Test
    fun opusRoundTrip() {
        assertTrue(NativeAudio.ensureLoaded())
        val enc = NativeAudio.opusEncoderCreate(rate, AudioEngine.BITRATE, AudioEngine.COMPLEXITY, true)
        val dec = NativeAudio.opusDecoderCreate(rate)
        assertNotEquals(0L, enc)
        assertNotEquals(0L, dec)
        val input = tone(50)
        val packet = ByteArray(NativeAudio.MAX_PACKET_BYTES)
        val pcm = ShortArray(NativeAudio.MAX_FRAME_SAMPLES)
        var energy = 0L
        for (f in 0 until 50) {
            val chunk = input.copyOfRange(f * frame, (f + 1) * frame)
            val n = NativeAudio.opusEncode(enc, chunk, frame, packet)
            assertTrue("encode failed: $n", n > 0)
            assertTrue("24 kbps should give small packets, got $n", n < 200)
            val samples = NativeAudio.opusDecode(dec, packet, 0, n, pcm, NativeAudio.MAX_FRAME_SAMPLES)
            assertEquals(frame, samples)
            if (f > 10) for (i in 0 until samples) energy += abs(pcm[i].toInt())
        }
        assertTrue("decoded audio should not be silent", energy > 0)

        // Packet-loss concealment produces a full frame without input.
        assertEquals(frame, NativeAudio.opusDecode(dec, null, 0, 0, pcm, frame))

        NativeAudio.opusEncoderDestroy(enc)
        NativeAudio.opusDecoderDestroy(dec)
    }

    @Test
    fun voiceProcessorRuns() {
        assertTrue(NativeAudio.ensureLoaded())
        val proc = NativeAudio.processorCreate(rate, AudioEngine.LOW_CUT_HZ)
        assertNotEquals(0L, proc)
        val pcm = tone(1)
        val vad = NativeAudio.processorProcess(proc, pcm, pcm.size, NativeAudio.FLAG_LOW_CUT or NativeAudio.FLAG_DENOISE)
        assertTrue("RNNoise should report a probability, got $vad", vad in 0f..1f)
        assertEquals(-1f, NativeAudio.processorProcess(proc, pcm, pcm.size, NativeAudio.FLAG_LOW_CUT))
        NativeAudio.processorDestroy(proc)
    }

    @Test
    fun opusVersionIsVendoredRelease() {
        assertTrue(NativeAudio.ensureLoaded())
        assertTrue(NativeAudio.opusVersion().contains("1.5.2"))
    }
}
