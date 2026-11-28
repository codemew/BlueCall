package com.codemew.bluecall.wifi

import com.codemew.bluecall.call.Transport
import com.codemew.bluecall.protocol.CallLink
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A call connection over a Wi-Fi Aware data link: a TCP socket on the link,
 * plus the network request that keeps the link up. Closing it releases both,
 * which takes the data link down.
 */
class WifiAwareLink(private val socket: Socket, private val releaseNetwork: () -> Unit) : CallLink {
    override val transport = Transport.WIFI
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        releaseNetwork()
    }

    companion object {
        /** DSCP EF: Wi-Fi carries it in the voice queue, ahead of other traffic. */
        const val VOICE_TRAFFIC_CLASS = 0xB8

        fun tune(socket: Socket) {
            runCatching { socket.tcpNoDelay = true }
            runCatching { socket.trafficClass = VOICE_TRAFFIC_CLASS }
        }
    }
}
