package com.codemew.bluecall.protocol

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

const val PROTOCOL_VERSION = 1

/** Messages exchanged over the L2CAP channel. */
sealed interface Message {
    data class Hello(val version: Int, val sessionId: Int, val name: String) : Message
    data class CallRequest(val callId: Int, val frameMs: Int) : Message
    data class CallAccept(val callId: Int, val frameMs: Int) : Message
    data class CallReject(val callId: Int, val reason: Int) : Message
    data class Hangup(val callId: Int) : Message

    /** One Opus packet. [seq] is a 16-bit counter that advances once per encoded frame. */
    class Audio(val seq: Int, val payload: ByteArray) : Message

    data class MuteState(val muted: Boolean) : Message
    data class HoldState(val onHold: Boolean) : Message
    data class Ping(val id: Int, val timeNanos: Long) : Message
    data class Pong(val id: Int, val timeNanos: Long) : Message

    companion object {
        const val REJECT_DECLINED = 1
        const val REJECT_BUSY = 2
    }
}

object MessageTypes {
    const val HELLO = 0x01
    const val CALL_REQUEST = 0x02
    const val CALL_ACCEPT = 0x03
    const val CALL_REJECT = 0x04
    const val HANGUP = 0x05
    const val AUDIO = 0x10
    const val MUTE = 0x11
    const val HOLD = 0x12
    const val PING = 0x20
    const val PONG = 0x21
}

object Messages {
    private const val MAX_NAME_BYTES = 64

    /** Encodes a message into a complete wire frame (header included). */
    fun encode(message: Message): ByteArray = when (message) {
        is Message.Hello -> {
            val name = truncateUtf8(message.name, MAX_NAME_BYTES)
            val buf = ByteBuffer.allocate(1 + 4 + 1 + name.size)
            buf.put(message.version.toByte()).putInt(message.sessionId).put(name.size.toByte()).put(name)
            FrameCodec.encode(MessageTypes.HELLO, buf.array())
        }

        is Message.CallRequest -> FrameCodec.encode(
            MessageTypes.CALL_REQUEST,
            ByteBuffer.allocate(5).putInt(message.callId).put(message.frameMs.toByte()).array(),
        )

        is Message.CallAccept -> FrameCodec.encode(
            MessageTypes.CALL_ACCEPT,
            ByteBuffer.allocate(5).putInt(message.callId).put(message.frameMs.toByte()).array(),
        )

        is Message.CallReject -> FrameCodec.encode(
            MessageTypes.CALL_REJECT,
            ByteBuffer.allocate(5).putInt(message.callId).put(message.reason.toByte()).array(),
        )

        is Message.Hangup -> FrameCodec.encode(
            MessageTypes.HANGUP,
            ByteBuffer.allocate(4).putInt(message.callId).array(),
        )

        is Message.Audio -> encodeAudio(message.seq, message.payload, message.payload.size)

        is Message.MuteState -> FrameCodec.encode(MessageTypes.MUTE, byteArrayOf(if (message.muted) 1 else 0))
        is Message.HoldState -> FrameCodec.encode(MessageTypes.HOLD, byteArrayOf(if (message.onHold) 1 else 0))

        is Message.Ping -> FrameCodec.encode(
            MessageTypes.PING,
            ByteBuffer.allocate(12).putInt(message.id).putLong(message.timeNanos).array(),
        )

        is Message.Pong -> FrameCodec.encode(
            MessageTypes.PONG,
            ByteBuffer.allocate(12).putInt(message.id).putLong(message.timeNanos).array(),
        )
    }

    /** Hot path for audio: builds the frame straight from the encoder's output buffer. */
    fun encodeAudio(seq: Int, data: ByteArray, length: Int): ByteArray {
        require(length in 0..(FrameCodec.MAX_PAYLOAD - 2)) { "audio packet too large: $length" }
        val out = ByteArray(FrameCodec.HEADER_SIZE + 2 + length)
        val payloadLength = 2 + length
        out[0] = MessageTypes.AUDIO.toByte()
        out[1] = (payloadLength ushr 8).toByte()
        out[2] = payloadLength.toByte()
        out[3] = (seq ushr 8).toByte()
        out[4] = seq.toByte()
        System.arraycopy(data, 0, out, 5, length)
        return out
    }

    /**
     * Decodes a frame. Returns null for unknown types (ignored for forward
     * compatibility) and throws [ProtocolException] for malformed payloads.
     */
    fun decode(frame: Frame): Message? {
        val buf = ByteBuffer.wrap(frame.payload)
        return try {
            when (frame.type) {
                MessageTypes.HELLO -> {
                    val version = buf.get().toInt() and 0xFF
                    val sessionId = buf.getInt()
                    val nameLength = buf.get().toInt() and 0xFF
                    if (nameLength > MAX_NAME_BYTES) throw ProtocolException("name too long")
                    val name = ByteArray(nameLength).also { buf.get(it) }
                    Message.Hello(version, sessionId, String(name, Charsets.UTF_8))
                }

                MessageTypes.CALL_REQUEST -> Message.CallRequest(buf.getInt(), buf.get().toInt() and 0xFF)
                MessageTypes.CALL_ACCEPT -> Message.CallAccept(buf.getInt(), buf.get().toInt() and 0xFF)
                MessageTypes.CALL_REJECT -> Message.CallReject(buf.getInt(), buf.get().toInt() and 0xFF)
                MessageTypes.HANGUP -> Message.Hangup(buf.getInt())
                MessageTypes.AUDIO -> {
                    if (frame.payload.size < 2) throw ProtocolException("audio frame too short")
                    val seq = ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF)
                    Message.Audio(seq, frame.payload.copyOfRange(2, frame.payload.size))
                }

                MessageTypes.MUTE -> Message.MuteState(buf.get().toInt() != 0)
                MessageTypes.HOLD -> Message.HoldState(buf.get().toInt() != 0)
                MessageTypes.PING -> Message.Ping(buf.getInt(), buf.getLong())
                MessageTypes.PONG -> Message.Pong(buf.getInt(), buf.getLong())
                else -> null
            }
        } catch (e: BufferUnderflowException) {
            throw ProtocolException("truncated payload for type ${frame.type}")
        }
    }

    /** UTF-8 encodes [text], cutting at a character boundary so it fits in [maxBytes]. */
    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val full = text.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full
        var end = maxBytes
        // Step back over UTF-8 continuation bytes (10xxxxxx) so we don't split a character.
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
        return full.copyOf(end)
    }
}
