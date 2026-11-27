package com.codemew.bluecall.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class MessagesTest {

    private fun roundTrip(message: Message): Message {
        val bytes = Messages.encode(message)
        val frame = FrameCodec.Reader(ByteArrayInputStream(bytes)).read()!!
        return Messages.decode(frame)!!
    }

    @Test
    fun controlMessagesRoundTrip() {
        val messages = listOf(
            Message.Hello(PROTOCOL_VERSION, -123456, "Galaxy S25 Ultra"),
            Message.CallRequest(42, 20),
            Message.CallAccept(42, 10),
            Message.CallReject(42, Message.REJECT_BUSY),
            Message.Hangup(Int.MIN_VALUE),
            Message.MuteState(true),
            Message.HoldState(false),
            Message.Ping(7, 123_456_789_012L),
            Message.Pong(7, 123_456_789_012L),
        )
        messages.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun audioRoundTripKeepsSequenceAndPayload() {
        val payload = ByteArray(61) { (it * 7).toByte() }
        val decoded = roundTrip(Message.Audio(65535, payload)) as Message.Audio
        assertEquals(65535, decoded.seq)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun encodeAudioMatchesGenericEncoding() {
        val buffer = ByteArray(100) { it.toByte() }
        val fast = Messages.encodeAudio(300, buffer, 40)
        val generic = Messages.encode(Message.Audio(300, buffer.copyOf(40)))
        assertArrayEquals(generic, fast)
    }

    @Test
    fun helloNameIsTruncatedAtCharacterBoundary() {
        val longName = "Ünïcödé-".repeat(20)
        val decoded = roundTrip(Message.Hello(1, 1, longName)) as Message.Hello
        assertTrue(decoded.name.toByteArray().size <= 64)
        assertTrue(longName.startsWith(decoded.name))
    }

    @Test
    fun truncateUtf8NeverSplitsMultiByteCharacters() {
        val text = "ab😀cd"
        for (max in 0..text.toByteArray().size) {
            val bytes = Messages.truncateUtf8(text, max)
            assertTrue(bytes.size <= max)
            val decoded = String(bytes, Charsets.UTF_8)
            assertTrue("bad cut at $max: $decoded", text.startsWith(decoded))
        }
    }

    @Test
    fun multipleFramesReadSequentially() {
        val out = ByteArrayOutputStream()
        out.write(Messages.encode(Message.Hangup(1)))
        out.write(Messages.encodeAudio(5, byteArrayOf(1, 2, 3), 3))
        out.write(Messages.encode(Message.MuteState(false)))
        val reader = FrameCodec.Reader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Message.Hangup(1), Messages.decode(reader.read()!!))
        assertEquals(5, (Messages.decode(reader.read()!!) as Message.Audio).seq)
        assertEquals(Message.MuteState(false), Messages.decode(reader.read()!!))
        assertNull(reader.read())
    }

    @Test
    fun unknownTypeIsIgnored() {
        assertNull(Messages.decode(Frame(0x7F, byteArrayOf(1, 2, 3))))
    }

    @Test(expected = ProtocolException::class)
    fun truncatedPayloadIsRejected() {
        Messages.decode(Frame(MessageTypes.CALL_REQUEST, byteArrayOf(0, 0, 1)))
    }

    @Test(expected = ProtocolException::class)
    fun shortAudioIsRejected() {
        Messages.decode(Frame(MessageTypes.AUDIO, byteArrayOf(1)))
    }

    @Test(expected = ProtocolException::class)
    fun oversizedLengthIsRejected() {
        val bytes = byteArrayOf(MessageTypes.AUDIO.toByte(), 0xFF.toByte(), 0xFF.toByte())
        FrameCodec.Reader(ByteArrayInputStream(bytes)).read()
    }

    @Test(expected = EOFException::class)
    fun streamEndingInsideFrameThrows() {
        val bytes = Messages.encode(Message.Hangup(9)).copyOf(5)
        FrameCodec.Reader(ByteArrayInputStream(bytes)).read()
    }

    @Test
    fun emptyStreamIsCleanEnd() {
        assertNull(FrameCodec.Reader(ByteArrayInputStream(ByteArray(0))).read())
    }
}
