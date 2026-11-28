package com.codemew.bluecall.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerDirectoryTest {
    private val own = 1

    private fun bt(id: Int, name: String, rssi: Int = -55) = Sighting(id, name, 0, rssi)
    private fun wifi(id: Int, name: String) = Sighting(id, name, 0)

    @Test
    fun samePhoneOnBothRadiosIsOneEntry() {
        val peers = PeerDirectory.merge(own, listOf(bt(7, "Alex's Gala")), listOf(wifi(7, "Alex's Galaxy S25")))
        assertEquals(1, peers.size)
        val p = peers.single()
        assertTrue(p.bluetooth)
        assertTrue(p.wifi)
        assertEquals(listOf(Transport.BLUETOOTH, Transport.WIFI), p.transports)
        // The Wi-Fi name isn't cut short to fit an advertisement.
        assertEquals("Alex's Galaxy S25", p.name)
    }

    @Test
    fun phonesOnOneRadioOnly() {
        val peers = PeerDirectory.merge(own, listOf(bt(2, "Bee")), listOf(wifi(3, "Cee")))
        val bee = peers.first { it.sessionId == 2 }
        val cee = peers.first { it.sessionId == 3 }
        assertTrue(bee.bluetooth)
        assertFalse(bee.wifi)
        assertEquals(listOf(Transport.BLUETOOTH), bee.transports)
        assertFalse(cee.bluetooth)
        assertNull(cee.bluetoothBars)
        assertTrue(cee.wifi)
        assertEquals(listOf(Transport.WIFI), cee.transports)
    }

    @Test
    fun ownPhoneIsNeverListed() {
        val peers = PeerDirectory.merge(own, listOf(bt(own, "Me")), listOf(wifi(own, "Me"), wifi(4, "Dee")))
        assertEquals(listOf(4), peers.map { it.sessionId })
    }

    @Test
    fun orderIsStableByNameThenId() {
        val peers = PeerDirectory.merge(
            own,
            listOf(bt(9, "zed"), bt(5, "Amy")),
            listOf(wifi(6, "amy"), wifi(8, "Bob")),
        )
        assertEquals(listOf(5, 6, 8, 9), peers.map { it.sessionId })
        val again = PeerDirectory.merge(
            own,
            listOf(bt(5, "Amy"), bt(9, "zed")),
            listOf(wifi(8, "Bob"), wifi(6, "amy")),
        )
        assertEquals(peers, again)
    }

    @Test
    fun blankNamesFallBackToAPlaceholder() {
        val peers = PeerDirectory.merge(own, emptyList(), listOf(wifi(0x1234ABCD, "  ")))
        assertEquals("Phone ABCD", peers.single().name)
        val named = PeerDirectory.merge(own, listOf(bt(3, "Kim")), listOf(wifi(3, "")))
        assertEquals("Kim", named.single().name)
    }

    @Test
    fun signalBarsFromRssi() {
        assertEquals(4, PeerDirectory.signalBars(-50))
        assertEquals(3, PeerDirectory.signalBars(-65))
        assertEquals(2, PeerDirectory.signalBars(-75))
        assertEquals(1, PeerDirectory.signalBars(-95))
        assertEquals(3, PeerDirectory.merge(own, listOf(bt(2, "B", rssi = -65)), emptyList()).single().bluetoothBars)
    }

    @Test
    fun peerInfoCarriesTheChosenTransport() {
        val p = PeerDirectory.merge(own, listOf(bt(2, "B")), listOf(wifi(2, "B")))
        assertEquals(PeerInfo(2, "B", Transport.WIFI), p.single().toPeerInfo(Transport.WIFI))
    }
}
