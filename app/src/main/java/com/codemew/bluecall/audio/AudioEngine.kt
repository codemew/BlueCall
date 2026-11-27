package com.codemew.bluecall.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.codemew.bluecall.data.NoiseSettings
import kotlin.math.max

data class AudioStats(
    /** Frames waiting in the jitter buffer. */
    val bufferedFrames: Int,
    val targetFrames: Int,
    val jitterMs: Double,
    val played: Long,
    val concealed: Long,
    val late: Long,
    val dropped: Long,
    /** Estimated delay added on this phone: capture frame + jitter buffer + output buffer. */
    val localDelayMs: Int,
)

/**
 * The real-time audio path for one call.
 *
 * Capture thread: microphone (voice-communication input, so Samsung's voice
 * DSP and Mic mode apply) → low-cut filter → RNNoise → Opus → [sendPacket].
 *
 * Playout thread: jitter buffer → Opus decode (or concealment) → speaker.
 * Writes block on the output, so the output's clock paces playout.
 *
 * Both threads run at urgent-audio priority and exist only during a call.
 */
class AudioEngine(
    private val frameMs: Int,
    private val peerFrameMs: Int,
    noise: NoiseSettings,
    private val sendPacket: (seq: Int, data: ByteArray, length: Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile
    var muted = false

    @Volatile
    var onHold = false

    @Volatile
    private var running = false

    @Volatile
    private var processFlags = flagsFor(noise)

    private val jitter = JitterBuffer(peerFrameMs)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var effects: VoiceEffects? = null
    private var encoder = 0L
    private var decoder = 0L
    private var processor = 0L
    private var captureThread: Thread? = null
    private var playoutThread: Thread? = null
    private var outputDelayMs = 0

    private val frameSamples = SAMPLE_RATE * frameMs / 1000
    private val peerFrameSamples = SAMPLE_RATE * peerFrameMs / 1000

    /** Opens the microphone and speaker. Needs RECORD_AUDIO, checked by the caller. */
    @SuppressLint("MissingPermission")
    fun start(noise: NoiseSettings): Boolean {
        if (running) return true
        if (!NativeAudio.ensureLoaded()) {
            onError("Audio codec failed to load")
            return false
        }
        try {
            encoder = NativeAudio.opusEncoderCreate(SAMPLE_RATE, BITRATE, COMPLEXITY, true)
            decoder = NativeAudio.opusDecoderCreate(SAMPLE_RATE)
            processor = NativeAudio.processorCreate(SAMPLE_RATE, LOW_CUT_HZ)
            if (encoder == 0L || decoder == 0L || processor == 0L) throw IllegalStateException("codec init failed")

            val inMin = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(max(inMin, frameSamples * 2 * 4))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                throw IllegalStateException("microphone unavailable")
            }
            record = rec
            effects = VoiceEffects(rec.audioSessionId).also { it.apply(noise) }

            val outMin = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val trk = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(max(outMin, peerFrameSamples * 2 * 2))
                .build()
            track = trk
            outputDelayMs = trk.bufferSizeInFrames * 1000 / SAMPLE_RATE
        } catch (e: Exception) {
            Log.e(TAG, "audio start failed", e)
            releaseResources()
            onError("Microphone or speaker unavailable")
            return false
        }

        processFlags = flagsFor(noise)
        running = true
        captureThread = Thread(::captureLoop, "BlueCall-capture").apply { start() }
        playoutThread = Thread(::playoutLoop, "BlueCall-playout").apply { start() }
        return true
    }

    fun onPacket(seq: Int, payload: ByteArray) {
        val now = System.nanoTime() / 1_000_000
        synchronized(jitter) { jitter.push(seq, payload, now) }
    }

    fun updateNoise(noise: NoiseSettings) {
        processFlags = flagsFor(noise)
        effects?.apply(noise)
    }

    fun stats(): AudioStats = synchronized(jitter) {
        AudioStats(
            bufferedFrames = jitter.depth,
            targetFrames = jitter.targetDepth,
            jitterMs = jitter.jitterMs,
            played = jitter.played,
            concealed = jitter.concealed,
            late = jitter.late,
            dropped = jitter.dropped,
            localDelayMs = frameMs + jitter.depth * peerFrameMs + outputDelayMs,
        )
    }

    fun stop() {
        if (!running && record == null && track == null) return
        running = false
        runCatching { record?.stop() } // unblocks a pending read
        runCatching {
            track?.pause() // unblocks a pending write
            track?.flush()
        }
        val threadsDone = listOfNotNull(captureThread, playoutThread).all { t ->
            try {
                t.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            !t.isAlive
        }
        captureThread = null
        playoutThread = null
        if (threadsDone) {
            releaseResources()
        } else {
            // Never free native state that a stuck thread might still touch.
            Log.e(TAG, "audio threads did not stop in time; leaking codec state")
            runCatching { record?.release() }
            runCatching { track?.release() }
            record = null
            track = null
        }
    }

    private fun releaseResources() {
        effects?.release()
        effects = null
        runCatching { record?.release() }
        record = null
        runCatching { track?.release() }
        track = null
        if (encoder != 0L) NativeAudio.opusEncoderDestroy(encoder)
        if (decoder != 0L) NativeAudio.opusDecoderDestroy(decoder)
        if (processor != 0L) NativeAudio.processorDestroy(processor)
        encoder = 0
        decoder = 0
        processor = 0
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = record ?: return
        val pcm = ShortArray(frameSamples)
        val out = ByteArray(NativeAudio.MAX_PACKET_BYTES)
        var seq = 0
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording failed", e)
            onError("Microphone unavailable")
            return
        }
        while (running) {
            var read = 0
            while (read < frameSamples && running) {
                val n = rec.read(pcm, read, frameSamples - read, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    if (running) onError("Microphone stopped ($n)")
                    return
                }
                read += n
            }
            if (!running) break

            if (muted || onHold) {
                // Encode silence: Opus DTX turns it into tiny packets, keeping the stream continuous.
                pcm.fill(0)
            } else {
                val flags = processFlags
                if (flags != 0) NativeAudio.processorProcess(processor, pcm, frameSamples, flags)
            }
            val length = NativeAudio.opusEncode(encoder, pcm, frameSamples, out)
            if (length > 0) {
                sendPacket(seq, out, length)
                seq = (seq + 1) and 0xFFFF
            }
        }
    }

    private fun playoutLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val trk = track ?: return
        val pcm = ShortArray(NativeAudio.MAX_FRAME_SAMPLES)
        try {
            trk.play()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "play failed", e)
            onError("Speaker unavailable")
            return
        }
        while (running) {
            val item = synchronized(jitter) { jitter.pop() }
            var samples = when (item) {
                is JitterBuffer.Item.Packet ->
                    NativeAudio.opusDecode(decoder, item.data, 0, item.data.size, pcm, NativeAudio.MAX_FRAME_SAMPLES)

                JitterBuffer.Item.Conceal ->
                    NativeAudio.opusDecode(decoder, null, 0, 0, pcm, peerFrameSamples)

                JitterBuffer.Item.Silence -> 0
            }
            if (samples <= 0) {
                samples = peerFrameSamples
                pcm.fill(0, 0, samples)
            }
            if (onHold) pcm.fill(0, 0, samples)

            var offset = 0
            while (offset < samples && running) {
                val written = trk.write(pcm, offset, samples - offset, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    if (running) onError("Speaker stopped ($written)")
                    return
                }
                if (written == 0) break
                offset += written
            }
        }
    }

    private fun flagsFor(noise: NoiseSettings): Int {
        var flags = 0
        if (noise.lowCutFilter) flags = flags or NativeAudio.FLAG_LOW_CUT
        if (noise.aiNoiseReduction) flags = flags or NativeAudio.FLAG_DENOISE
        return flags
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val BITRATE = 24_000
        const val COMPLEXITY = 5
        const val LOW_CUT_HZ = 100f
        private const val JOIN_TIMEOUT_MS = 500L
        private const val TAG = "AudioEngine"
    }
}
