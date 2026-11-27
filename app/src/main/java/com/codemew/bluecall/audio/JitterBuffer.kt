package com.codemew.bluecall.audio

import kotlin.math.ceil
import kotlin.math.max

/**
 * Adaptive jitter buffer for Opus packets, keyed by 16-bit sequence number.
 *
 * The playout thread calls [pop] once per frame, paced by the audio output.
 * The buffer:
 *  - waits until [targetDepth] packets are queued before it starts playing,
 *  - reorders packets, and asks for concealment (Opus PLC) for missing ones,
 *  - drops packets that arrive after their play time,
 *  - skips a frame whenever the queue grows well past the target, so delay
 *    from a burst of late packets never builds up,
 *  - adapts [targetDepth] to the arrival jitter it measures, between
 *    [minDepth] and [maxDepth] frames.
 *
 * Not thread-safe; callers synchronise.
 */
class JitterBuffer(
    private val frameMs: Int,
    private val minDepth: Int = 1,
    private val maxDepth: Int = 6,
) {
    sealed interface Item {
        class Packet(val data: ByteArray) : Item
        data object Conceal : Item
        data object Silence : Item
    }

    private val initialTarget = (minDepth + 1).coerceAtMost(maxDepth)
    private val slots = arrayOfNulls<ByteArray>(CAPACITY)
    private val slotSeq = IntArray(CAPACITY) { -1 }
    private var count = 0
    private var started = false
    private var nextSeq = 0
    private var highestSeq = -1
    private var concealStreak = 0
    private var lateStreak = 0

    private var jitterSamples = 0
    private var extHighest = -1L
    private var minTransit = Double.NaN
    private var jitterPeakMs = 0.0

    var targetDepth: Int = initialTarget
        private set
    var played = 0L
        private set
    var concealed = 0L
        private set
    var late = 0L
        private set
    var dropped = 0L
        private set

    val bufferedPackets: Int get() = count

    /** Frames between the next one to play and the newest received, holes included. */
    val depth: Int
        get() = if (highestSeq < 0 || !started) count else max(0, seqDiff(highestSeq, nextSeq) + 1)

    val jitterMs: Double get() = jitterPeakMs

    fun push(seq: Int, data: ByteArray, arrivalMs: Long) {
        val s = seq and 0xFFFF
        updateJitter(s, arrivalMs)
        if (started) {
            val d = seqDiff(s, nextSeq)
            if (d < 0) {
                late++
                if (++lateStreak < RESYNC_AFTER_LATE) return
                resync()
            } else if (d >= CAPACITY) {
                resync()
            }
        }
        lateStreak = 0
        store(s, data)
    }

    fun pop(): Item {
        if (!started) {
            if (count == 0 || count < targetDepth) return Item.Silence
            started = true
            nextSeq = oldestSeq()
            concealStreak = 0
        }

        if (depth > targetDepth + CATCH_UP_MARGIN) {
            removeAt(nextSeq)
            dropped++
            nextSeq = (nextSeq + 1) and 0xFFFF
        }

        val data = removeAt(nextSeq)
        nextSeq = (nextSeq + 1) and 0xFFFF
        if (data != null) {
            concealStreak = 0
            played++
            return Item.Packet(data)
        }

        concealed++
        concealStreak++
        if (count == 0 && concealStreak > MAX_CONCEAL_STREAK) {
            // The other side has gone quiet (link stall). Stop concealing to save
            // CPU, and re-buffer to the target depth when packets come back.
            started = false
            return Item.Silence
        }
        return Item.Conceal
    }

    fun reset() {
        slots.fill(null)
        slotSeq.fill(-1)
        count = 0
        started = false
        highestSeq = -1
        concealStreak = 0
        lateStreak = 0
        extHighest = -1
        minTransit = Double.NaN
        jitterPeakMs = 0.0
        jitterSamples = 0
        targetDepth = initialTarget
    }

    private fun resync() {
        slots.fill(null)
        slotSeq.fill(-1)
        count = 0
        started = false
        highestSeq = -1
        lateStreak = 0
        concealStreak = 0
    }

    private fun store(seq: Int, data: ByteArray) {
        val idx = seq and (CAPACITY - 1)
        if (slots[idx] != null) {
            if (slotSeq[idx] == seq) return // duplicate
        } else {
            count++
        }
        slots[idx] = data
        slotSeq[idx] = seq
        if (highestSeq < 0 || seqDiff(seq, highestSeq) > 0) highestSeq = seq
    }

    private fun removeAt(seq: Int): ByteArray? {
        val idx = seq and (CAPACITY - 1)
        val data = slots[idx] ?: return null
        val stored = slotSeq[idx]
        if (stored == seq) {
            slots[idx] = null
            slotSeq[idx] = -1
            count--
            return data
        }
        if (seqDiff(stored, seq) < 0) {
            // A stale packet older than the play position: discard it.
            slots[idx] = null
            slotSeq[idx] = -1
            count--
            dropped++
        }
        return null
    }

    private fun oldestSeq(): Int {
        var best = highestSeq
        var bestDiff = 0
        for (i in 0 until CAPACITY) {
            if (slots[i] == null) continue
            val d = seqDiff(slotSeq[i], highestSeq)
            if (d < bestDiff) {
                bestDiff = d
                best = slotSeq[i]
            }
        }
        return best
    }

    private fun updateJitter(seq: Int, arrivalMs: Long) {
        val ext = if (extHighest < 0) {
            seq.toLong()
        } else {
            extHighest + seqDiff(seq, (extHighest and 0xFFFF).toInt())
        }
        if (ext > extHighest) extHighest = ext

        val transit = arrivalMs - ext * frameMs.toDouble()
        if (minTransit.isNaN() || transit < minTransit) {
            minTransit = transit
        } else {
            // Slowly forget the old minimum so clock drift between phones is tolerated.
            minTransit += MIN_TRANSIT_DRIFT_MS
        }
        val sample = transit - minTransit
        jitterPeakMs = max(sample, jitterPeakMs * PEAK_DECAY)
        val measured = (ceil(jitterPeakMs / frameMs).toInt() + 1).coerceIn(minDepth, maxDepth)
        // Until about a second of packets has been seen, don't go below the safe starting depth.
        targetDepth = if (++jitterSamples < WARMUP_PACKETS) max(measured, initialTarget) else measured
    }

    companion object {
        private const val CAPACITY = 64 // power of two
        private const val CATCH_UP_MARGIN = 2
        private const val RESYNC_AFTER_LATE = 5
        private const val MAX_CONCEAL_STREAK = 25
        private const val PEAK_DECAY = 0.995
        private const val MIN_TRANSIT_DRIFT_MS = 0.02
        private const val WARMUP_PACKETS = 50

        /** Signed distance a - b between two 16-bit sequence numbers. */
        fun seqDiff(a: Int, b: Int): Int = ((a - b + 0x8000) and 0xFFFF) - 0x8000
    }
}
