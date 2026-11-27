package com.codemew.bluecall.bluetooth

import com.codemew.bluecall.protocol.PROTOCOL_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertPayloadTest {

    @Test
    fun roundTrip() {
        val payload = AdvertPayload.of(psm = 0x0085, sessionId = -98765)
        val bytes = payload.encode()
        assertEquals(AdvertPayload.SIZE, bytes.size)
        assertEquals(payload, AdvertPayload.decode(bytes))
    }

    @Test
    fun fitsInLegacyAdvertisement() {
        // flags (3) + service data AD: length (1) + type (1) + 128-bit UUID (16) + payload
        val advertisementBytes = 3 + 1 + 1 + 16 + AdvertPayload.SIZE
        assertTrue(advertisementBytes <= 31)
        val scanResponseBytes = 1 + 1 + 16 + AdvertPayload.MAX_NAME_BYTES
        assertTrue(scanResponseBytes <= 31)
    }

    @Test
    fun rejectsOtherVersionsAndGarbage() {
        val bytes = AdvertPayload.of(0x80, 1).encode()
        bytes[0] = (PROTOCOL_VERSION + 1).toByte()
        assertNull(AdvertPayload.decode(bytes))
        assertNull(AdvertPayload.decode(byteArrayOf(1, 2)))
        assertNull(AdvertPayload.decode(null))
        assertNull(AdvertPayload.decode(AdvertPayload(PROTOCOL_VERSION, 0, 1).encode()))
    }

    @Test
    fun nameIsTrimmedToScanResponseSpace() {
        val encoded = AdvertPayload.encodeName("  Alex's Galaxy S25 Ultra  ")
        assertTrue(encoded.size <= AdvertPayload.MAX_NAME_BYTES)
        assertEquals("Alex's Galaxy", AdvertPayload.decodeName(encoded))
        assertNull(AdvertPayload.decodeName(ByteArray(0)))
    }
}
