package com.codemew.bluecall.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AwarePayloadTest {

    @Test
    fun infoRoundTrip() {
        val bytes = AwarePayload.encodeInfo(-123456, "Alex's Galaxy")
        assertEquals(AwarePayload.Info(-123456, "Alex's Galaxy"), AwarePayload.decodeInfo(bytes))
    }

    @Test
    fun longNamesAreCutWithoutSplittingCharacters() {
        val name = "Ünïcødé ☎ phone with a very long name indeed"
        val info = AwarePayload.decodeInfo(AwarePayload.encodeInfo(5, name))!!
        assertTrue(info.name.toByteArray(Charsets.UTF_8).size <= AwarePayload.MAX_NAME_BYTES)
        assertTrue(name.startsWith(info.name))
    }

    @Test
    fun otherVersionsAndJunkAreIgnored() {
        val bytes = AwarePayload.encodeInfo(5, "A")
        bytes[0] = 9
        assertNull(AwarePayload.decodeInfo(bytes))
        assertNull(AwarePayload.decodeInfo(null))
        assertNull(AwarePayload.decodeInfo(ByteArray(0)))
        assertNull(AwarePayload.decodeInfo(byteArrayOf(1, 0, 0)))
        // Name length pointing past the end.
        assertNull(AwarePayload.decodeInfo(byteArrayOf(1, 0, 0, 0, 1, 20, 65)))
        assertNull(AwarePayload.decodeSignal(byteArrayOf(1, 77, 0, 0, 0, 0)))
        assertNull(AwarePayload.decodeSignal(byteArrayOf(1)))
    }

    @Test
    fun connectReadyAndBusyRoundTrip() {
        val connect = AwarePayload.Signal.Connect(42, -7, AwarePayload.newPassphrase(Random(1)))
        assertEquals(connect, AwarePayload.decodeSignal(AwarePayload.encode(connect)))
        val ready = AwarePayload.Signal.Ready(-7)
        assertEquals(ready, AwarePayload.decodeSignal(AwarePayload.encode(ready)))
        val busy = AwarePayload.Signal.Busy(-7)
        assertEquals(busy, AwarePayload.decodeSignal(AwarePayload.encode(busy)))
    }

    @Test
    fun connectWithBadPassphraseIsRejected() {
        val short = AwarePayload.encode(AwarePayload.Signal.Connect(1, 2, "short"))
        assertNull(AwarePayload.decodeSignal(short))
    }

    @Test
    fun passphrasesAreValidAndFresh() {
        val a = AwarePayload.newPassphrase()
        val b = AwarePayload.newPassphrase()
        assertEquals(AwarePayload.PASSPHRASE_LENGTH, a.length)
        assertTrue(AwarePayload.isValidPassphrase(a))
        assertTrue(a.all { it.isLetterOrDigit() })
        assertNotEquals(a, b)
    }

    @Test
    fun messagesFitInADiscoveryMessage() {
        // Every Wi-Fi Aware implementation accepts at least 255 bytes of service info.
        val connect = AwarePayload.encode(AwarePayload.Signal.Connect(1, 2, AwarePayload.newPassphrase()))
        assertTrue(connect.size < 255)
        assertTrue(AwarePayload.encodeInfo(1, "x".repeat(100)).size < 255)
    }
}
