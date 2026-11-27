package com.codemew.bluecall.audio

import android.util.Log

/** JNI bridge to libbluecall.so (libopus + RNNoise + low-cut filter). */
object NativeAudio {
    const val FLAG_LOW_CUT = 1
    const val FLAG_DENOISE = 2

    /** Largest Opus frame: 120 ms at 48 kHz. */
    const val MAX_FRAME_SAMPLES = 5760

    /** Largest Opus packet. */
    const val MAX_PACKET_BYTES = 1275

    @Volatile
    private var loaded: Boolean? = null

    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = try {
                System.loadLibrary("bluecall")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e("NativeAudio", "native library missing", e)
                false
            }
            loaded = ok
            return ok
        }
    }

    @JvmStatic external fun opusEncoderCreate(sampleRate: Int, bitrate: Int, complexity: Int, dtx: Boolean): Long

    /** Encodes [frameSize] samples of [pcm] into [out]; returns the packet length or a negative error. */
    @JvmStatic external fun opusEncode(handle: Long, pcm: ShortArray, frameSize: Int, out: ByteArray): Int

    @JvmStatic external fun opusEncoderSetBitrate(handle: Long, bitrate: Int)

    @JvmStatic external fun opusEncoderDestroy(handle: Long)

    @JvmStatic external fun opusDecoderCreate(sampleRate: Int): Long

    /**
     * Decodes one packet into [pcm]. Pass null [data] to run packet-loss
     * concealment for [frameSize] samples. Returns samples decoded or a negative error.
     */
    @JvmStatic external fun opusDecode(
        handle: Long,
        data: ByteArray?,
        offset: Int,
        length: Int,
        pcm: ShortArray,
        frameSize: Int,
    ): Int

    @JvmStatic external fun opusDecoderDestroy(handle: Long)

    @JvmStatic external fun processorCreate(sampleRate: Int, cutoffHz: Float): Long

    /** Runs the selected [flags] stages on [pcm] in place. Returns RNNoise voice probability or -1. */
    @JvmStatic external fun processorProcess(handle: Long, pcm: ShortArray, length: Int, flags: Int): Float

    @JvmStatic external fun processorDestroy(handle: Long)

    @JvmStatic external fun opusVersion(): String
}
