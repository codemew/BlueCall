package com.codemew.bluecall.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** One message on the wire: `[type u8][length u16 big-endian][payload]`. */
class Frame(val type: Int, val payload: ByteArray)

class ProtocolException(message: String) : IOException(message)

object FrameCodec {
    const val HEADER_SIZE = 3
    const val MAX_PAYLOAD = 1200

    fun encode(type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        require(type in 0..0xFF) { "type out of range: $type" }
        require(length in 0..MAX_PAYLOAD) { "payload too large: $length" }
        val out = ByteArray(HEADER_SIZE + length)
        out[0] = type.toByte()
        out[1] = (length ushr 8).toByte()
        out[2] = length.toByte()
        System.arraycopy(payload, offset, out, HEADER_SIZE, length)
        return out
    }

    /**
     * Reads frames from a stream. Not thread-safe: use from a single reader thread.
     */
    class Reader(private val input: InputStream) {
        private val header = ByteArray(HEADER_SIZE)

        /** Returns the next frame, or null on a clean end of stream between frames. */
        fun read(): Frame? {
            if (!readFully(header, HEADER_SIZE, allowCleanEof = true)) return null
            val type = header[0].toInt() and 0xFF
            val length = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
            if (length > MAX_PAYLOAD) throw ProtocolException("frame too large: $length")
            val payload = ByteArray(length)
            if (length > 0) readFully(payload, length, allowCleanEof = false)
            return Frame(type, payload)
        }

        private fun readFully(buffer: ByteArray, length: Int, allowCleanEof: Boolean): Boolean {
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) {
                    if (read == 0 && allowCleanEof) return false
                    throw EOFException("stream ended inside a frame")
                }
                read += n
            }
            return true
        }
    }
}
