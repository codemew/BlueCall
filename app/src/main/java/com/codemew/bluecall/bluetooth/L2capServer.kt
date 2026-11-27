package com.codemew.bluecall.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException

/**
 * Listens for incoming calls on a Bluetooth LE L2CAP channel (no pairing).
 *
 * The accept thread sits blocked in the kernel and uses no CPU until another
 * phone connects; the Bluetooth stack wakes the device for that.
 *
 * Callers must hold BLUETOOTH_CONNECT (checked before the service goes online).
 */
@SuppressLint("MissingPermission")
class L2capServer(
    private val adapter: BluetoothAdapter,
    private val onAccepted: (BluetoothSocket) -> Unit,
    private val onFailed: (Throwable) -> Unit,
) {
    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var stopping = false
    private var thread: Thread? = null

    /** Starts listening and returns the dynamically assigned PSM to advertise. */
    @Throws(IOException::class)
    fun start(): Int {
        stop()
        stopping = false
        val socket = adapter.listenUsingInsecureL2capChannel()
        serverSocket = socket
        thread = Thread({ acceptLoop(socket) }, "BlueCall-accept").apply {
            isDaemon = true
            start()
        }
        return socket.psm
    }

    private fun acceptLoop(socket: BluetoothServerSocket) {
        try {
            while (!stopping) {
                val client = socket.accept()
                if (stopping) {
                    runCatching { client.close() }
                    break
                }
                onAccepted(client)
            }
        } catch (e: IOException) {
            if (!stopping) {
                Log.w(TAG, "accept failed", e)
                onFailed(e)
            }
        }
    }

    fun stop() {
        stopping = true
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        thread?.let {
            if (it !== Thread.currentThread()) {
                try {
                    it.join(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        thread = null
    }

    private companion object {
        const val TAG = "L2capServer"
    }
}
