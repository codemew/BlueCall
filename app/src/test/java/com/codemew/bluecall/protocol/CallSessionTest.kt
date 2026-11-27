package com.codemew.bluecall.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CallSessionTest {

    /** Two sessions wired back to back through pipes, like two phones. */
    private class Pair {
        val aToB = PipedOutputStream()
        val bIn = PipedInputStream(aToB, 64 * 1024)
        val bToA = PipedOutputStream()
        val aIn = PipedInputStream(bToA, 64 * 1024)
    }

    private class Recorder : CallSession.Listener {
        val messages = CopyOnWriteArrayList<Message>()
        val closed = CountDownLatch(1)
        val got = CountDownLatch(1)
        var rtt = -1L
        var error: Throwable? = null
        override fun onMessage(session: CallSession, message: Message) {
            messages += message
            got.countDown()
        }

        override fun onRtt(session: CallSession, rttMs: Long) {
            rtt = rttMs
        }

        override fun onClosed(session: CallSession, error: Throwable?) {
            this.error = error
            closed.countDown()
        }
    }

    private fun closeable(vararg c: Closeable) = Closeable { c.forEach { runCatching { it.close() } } }

    @Test
    fun deliversMessagesAndAnswersPings() {
        val p = Pair()
        val ra = Recorder()
        val rb = Recorder()
        val a = CallSession(p.aIn, p.aToB, closeable(p.aIn, p.aToB), ra, pingIntervalMs = 50)
        val b = CallSession(p.bIn, p.bToA, closeable(p.bIn, p.bToA), rb, pingIntervalMs = 50)
        a.start()
        b.start()

        a.send(Message.CallRequest(1, 20))
        assertTrue(rb.got.await(2, TimeUnit.SECONDS))
        assertEquals(Message.CallRequest(1, 20), rb.messages.first())

        Thread.sleep(300)
        assertTrue("expected an RTT sample", ra.rtt >= 0)

        a.send(Message.Hangup(1))
        a.flushAndClose()
        assertTrue(rb.closed.await(2, TimeUnit.SECONDS))
        assertTrue(rb.messages.contains(Message.Hangup(1)))
        assertTrue(ra.closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun closesWithLinkTimeoutWhenPeerGoesSilent() {
        val p = Pair()
        val ra = Recorder()
        // The other end never answers: nothing is ever written into aIn.
        val a = CallSession(
            p.aIn, p.aToB, closeable(p.aIn, p.aToB), ra,
            pingIntervalMs = 30, linkTimeoutMs = 120,
        )
        // Drain what A writes so its writes never block.
        val drain = Thread { runCatching { while (p.bIn.read() >= 0) Unit } }.apply { isDaemon = true; start() }
        a.start()
        assertTrue(ra.closed.await(3, TimeUnit.SECONDS))
        assertTrue(ra.error is LinkTimeoutException)
        drain.interrupt()
    }

    @Test
    fun audioQueueDropsOldestWhenFull() {
        val p = Pair()
        val ra = Recorder()
        val a = CallSession(p.aIn, p.aToB, closeable(p.aIn, p.aToB), ra, maxQueuedAudio = 2)
        // Not started: nothing drains the queue.
        repeat(5) { a.sendAudio(it, byteArrayOf(1, 2), 2) }
        assertEquals(3, a.droppedAudio.get())
        a.close()
    }

    @Test
    fun closeIsIdempotentAndReportsOnce() {
        val p = Pair()
        var count = 0
        val a = CallSession(p.aIn, p.aToB, closeable(p.aIn, p.aToB), object : CallSession.Listener {
            override fun onMessage(session: CallSession, message: Message) = Unit
            override fun onClosed(session: CallSession, error: Throwable?) {
                count++
            }
        })
        a.start()
        a.close()
        a.close()
        Thread.sleep(100)
        assertEquals(1, count)
        assertTrue(a.isClosed)
    }
}
