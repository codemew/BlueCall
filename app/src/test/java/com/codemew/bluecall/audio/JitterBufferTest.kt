package com.codemew.bluecall.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    private fun packet(seq: Int) = byteArrayOf((seq shr 8).toByte(), seq.toByte())

    private fun JitterBuffer.Item.seq(): Int {
        val data = (this as JitterBuffer.Item.Packet).data
        return ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    }

    @Test
    fun silentUntilTargetDepthReached() {
        val jb = JitterBuffer(frameMs = 20)
        assertEquals(2, jb.targetDepth)
        assertEquals(JitterBuffer.Item.Silence, jb.pop())
        jb.push(0, packet(0), 0)
        assertEquals(JitterBuffer.Item.Silence, jb.pop())
        jb.push(1, packet(1), 20)
        assertEquals(0, jb.pop().seq())
        assertEquals(1, jb.pop().seq())
    }

    @Test
    fun reordersPackets() {
        val jb = JitterBuffer(frameMs = 20)
        jb.push(0, packet(0), 0)
        jb.push(2, packet(2), 40)
        jb.push(1, packet(1), 41)
        assertEquals(0, jb.pop().seq())
        assertEquals(1, jb.pop().seq())
        assertEquals(2, jb.pop().seq())
    }

    @Test
    fun missingPacketIsConcealed() {
        val jb = JitterBuffer(frameMs = 20)
        jb.push(0, packet(0), 0)
        jb.push(2, packet(2), 40)
        assertEquals(0, jb.pop().seq())
        assertEquals(JitterBuffer.Item.Conceal, jb.pop())
        assertEquals(2, jb.pop().seq())
        assertEquals(1, jb.concealed)
    }

    @Test
    fun latePacketIsDropped() {
        val jb = JitterBuffer(frameMs = 20)
        jb.push(0, packet(0), 0)
        jb.push(1, packet(1), 20)
        jb.push(2, packet(2), 40)
        jb.pop() // 0
        jb.pop() // 1
        jb.push(0, packet(0), 60) // arrives after its play time
        assertEquals(1, jb.late)
        assertEquals(2, jb.pop().seq())
    }

    @Test
    fun longUnderrunStopsConcealingAndRebuffers() {
        val jb = JitterBuffer(frameMs = 20)
        jb.push(0, packet(0), 0)
        jb.push(1, packet(1), 20)
        jb.pop()
        jb.pop()
        var silences = 0
        repeat(40) { if (jb.pop() == JitterBuffer.Item.Silence) silences++ }
        assertTrue("should fall back to silence after a long gap", silences > 0)
        // Packets resume with a jump in sequence numbers (sender kept counting).
        jb.push(42, packet(42), 840)
        jb.push(43, packet(43), 860)
        assertEquals(42, jb.pop().seq())
        assertEquals(43, jb.pop().seq())
    }

    @Test
    fun burstIsTrimmedSoDelayStaysBounded() {
        val jb = JitterBuffer(frameMs = 20)
        // 20 packets arrive at once after a stall.
        for (s in 0 until 20) jb.push(s, packet(s), 1000)
        var packets = 0
        repeat(30) {
            if (jb.pop() is JitterBuffer.Item.Packet) packets++
        }
        assertTrue("some frames should be skipped to catch up", jb.dropped > 0)
        assertTrue(jb.depth <= jb.targetDepth + 2)
        assertTrue(packets < 20)
    }

    @Test
    fun steadyArrivalsKeepTargetLow() {
        val jb = JitterBuffer(frameMs = 20)
        for (s in 0 until 200) {
            jb.push(s, packet(s), s * 20L)
            jb.pop()
        }
        assertEquals(1, jb.targetDepth)
    }

    @Test
    fun jitteryArrivalsRaiseTarget() {
        val jb = JitterBuffer(frameMs = 20)
        for (s in 0 until 200) {
            val wobble = if (s % 5 == 0) 70L else 0L
            jb.push(s, packet(s), s * 20L + wobble)
            jb.pop()
        }
        assertTrue("target was ${jb.targetDepth}", jb.targetDepth >= 4)
        assertTrue(jb.targetDepth <= 6)
    }

    @Test
    fun handlesSequenceWraparound() {
        val jb = JitterBuffer(frameMs = 20)
        jb.push(65534, packet(65534), 0)
        jb.push(65535, packet(65535), 20)
        jb.push(0, packet(0), 40)
        jb.push(1, packet(1), 60)
        assertEquals(65534, jb.pop().seq())
        assertEquals(65535, jb.pop().seq())
        assertEquals(0, jb.pop().seq())
        assertEquals(1, jb.pop().seq())
    }

    @Test
    fun duplicatePacketIgnored() {
        val jb = JitterBuffer(frameMs = 20)
        jb.push(0, packet(0), 0)
        jb.push(0, packet(0), 1)
        assertEquals(1, jb.bufferedPackets)
    }

    @Test
    fun seqDiffWraps() {
        assertEquals(1, JitterBuffer.seqDiff(0, 65535))
        assertEquals(-1, JitterBuffer.seqDiff(65535, 0))
        assertEquals(10, JitterBuffer.seqDiff(15, 5))
    }
}
