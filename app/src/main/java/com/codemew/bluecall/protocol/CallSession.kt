package com.codemew.bluecall.protocol

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class LinkTimeoutException : IOException("no data from the other phone")

/**
 * One connection to another phone. It exists only while a call is being set up
 * or is in progress, so its threads and its ping timer never run while idle.
 *
 * - A reader thread decodes frames and hands messages to [Listener]. Audio is
 *   delivered on this thread directly so it never waits behind the UI thread.
 * - A writer thread sends control messages first, then audio. The audio queue
 *   is small and drops the oldest packet when full: the channel is reliable,
 *   so queueing old audio would only add delay.
 * - The writer sends a ping every [pingIntervalMs] to measure round-trip time,
 *   and closes the session if nothing arrives for [linkTimeoutMs].
 */
class CallSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeable: Closeable,
    private val listener: Listener,
    private val pingIntervalMs: Long = 2_000,
    private val linkTimeoutMs: Long = 6_000,
    private val maxQueuedAudio: Int = 3,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    interface Listener {
        fun onMessage(session: CallSession, message: Message)
        fun onRtt(session: CallSession, rttMs: Long) {}
        fun onClosed(session: CallSession, error: Throwable?)
    }

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val drained = lock.newCondition()
    private val control = ArrayDeque<ByteArray>()
    private val audio = ArrayDeque<ByteArray>()
    private val closedFlag = AtomicBoolean(false)
    private val pingId = AtomicInteger()
    private var writingControl = false
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    @Volatile
    private var lastReceivedMs = clockMs()

    /** Audio packets dropped because the link could not keep up. */
    val droppedAudio = AtomicInteger()

    val isClosed: Boolean get() = closedFlag.get()

    fun start() {
        lastReceivedMs = clockMs()
        readerThread = Thread(::readLoop, "BlueCall-rx").apply { isDaemon = true; start() }
        writerThread = Thread(::writeLoop, "BlueCall-tx").apply { isDaemon = true; start() }
    }

    fun send(message: Message) {
        val bytes = Messages.encode(message)
        lock.withLock {
            if (closedFlag.get()) return
            control.addLast(bytes)
            wake.signal()
        }
    }

    fun sendAudio(seq: Int, data: ByteArray, length: Int) {
        val bytes = Messages.encodeAudio(seq, data, length)
        lock.withLock {
            if (closedFlag.get()) return
            while (audio.size >= maxQueuedAudio) {
                audio.removeFirst()
                droppedAudio.incrementAndGet()
            }
            audio.addLast(bytes)
            wake.signal()
        }
    }

    /** Waits (briefly) for queued control messages such as HANGUP to go out, then closes. */
    fun flushAndClose(timeoutMs: Long = 300) {
        lock.withLock {
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while ((control.isNotEmpty() || writingControl) && !closedFlag.get() && remainingNs > 0) {
                remainingNs = drained.awaitNanos(remainingNs)
            }
        }
        close(null)
    }

    fun close(error: Throwable? = null) {
        if (!closedFlag.compareAndSet(false, true)) return
        lock.withLock {
            control.clear()
            audio.clear()
            wake.signalAll()
            drained.signalAll()
        }
        try {
            closeable.close()
        } catch (_: IOException) {
        }
        val current = Thread.currentThread()
        listOfNotNull(readerThread, writerThread).forEach { t ->
            if (t !== current) {
                try {
                    t.join(500)
                } catch (_: InterruptedException) {
                    current.interrupt()
                }
            }
        }
        listener.onClosed(this, error)
    }

    private fun readLoop() {
        val reader = FrameCodec.Reader(input)
        try {
            while (!closedFlag.get()) {
                val frame = reader.read() ?: break
                lastReceivedMs = clockMs()
                when (val message = Messages.decode(frame)) {
                    null -> Unit
                    is Message.Ping -> send(Message.Pong(message.id, message.timeNanos))
                    is Message.Pong -> {
                        val rtt = (System.nanoTime() - message.timeNanos) / 1_000_000
                        if (rtt >= 0) listener.onRtt(this, rtt)
                    }

                    else -> listener.onMessage(this, message)
                }
            }
            close(null)
        } catch (e: IOException) {
            close(if (closedFlag.get()) null else e)
        }
    }

    private fun writeLoop() {
        var nextPingAt = clockMs() + pingIntervalMs
        try {
            while (!closedFlag.get()) {
                val next: ByteArray? = lock.withLock {
                    while (!closedFlag.get() && control.isEmpty() && audio.isEmpty()) {
                        val waitMs = nextPingAt - clockMs()
                        if (waitMs <= 0) break
                        wake.await(waitMs, TimeUnit.MILLISECONDS)
                    }
                    val controlItem = control.removeFirstOrNull()
                    writingControl = controlItem != null
                    controlItem ?: audio.removeFirstOrNull()
                }
                if (closedFlag.get()) break

                val now = clockMs()
                if (now >= nextPingAt) {
                    if (now - lastReceivedMs > linkTimeoutMs) {
                        close(LinkTimeoutException())
                        return
                    }
                    output.write(Messages.encode(Message.Ping(pingId.incrementAndGet(), System.nanoTime())))
                    nextPingAt = now + pingIntervalMs
                }
                if (next != null) output.write(next)
                output.flush()
                lock.withLock {
                    writingControl = false
                    if (control.isEmpty()) drained.signalAll()
                }
            }
        } catch (e: IOException) {
            close(if (closedFlag.get()) null else e)
        } catch (_: InterruptedException) {
            close(null)
        }
    }
}
