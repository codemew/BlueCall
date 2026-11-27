package com.codemew.bluecall.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens an L2CAP channel to another phone. Blocking: call from a background thread.
 *
 * Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
object L2capConnector {

    /**
     * Connects to [psm] on [device], retrying once (first LE connections are
     * occasionally refused by the remote controller). [onSocket] receives each
     * socket before it connects, so the caller can close it to cancel.
     */
    @Throws(IOException::class)
    fun connect(
        device: BluetoothDevice,
        psm: Int,
        timeoutMs: Long = 10_000,
        attempts: Int = 2,
        onSocket: (BluetoothSocket) -> Unit = {},
    ): BluetoothSocket {
        var last: IOException? = null
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        repeat(attempts) { attempt ->
            val remaining = deadline - System.nanoTime() / 1_000_000
            if (remaining <= 0) return@repeat
            val socket = device.createInsecureL2capChannel(psm)
            onSocket(socket)
            val timedOut = AtomicBoolean(false)
            val watchdog = Thread {
                try {
                    Thread.sleep(remaining)
                    timedOut.set(true)
                    runCatching { socket.close() }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }
            try {
                socket.connect()
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                if (timedOut.get()) throw IOException("connection timed out", e)
                last = e
                if (attempt + 1 < attempts) Thread.sleep(RETRY_DELAY_MS)
            } finally {
                watchdog.interrupt()
            }
        }
        throw last ?: IOException("connection timed out")
    }

    private const val RETRY_DELAY_MS = 300L
}
