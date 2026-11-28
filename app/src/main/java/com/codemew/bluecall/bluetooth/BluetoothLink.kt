package com.codemew.bluecall.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.codemew.bluecall.call.Transport
import com.codemew.bluecall.protocol.CallLink
import java.io.InputStream
import java.io.OutputStream

/** A call connection over a Bluetooth LE L2CAP channel. */
class BluetoothLink(private val socket: BluetoothSocket) : CallLink {
    override val transport = Transport.BLUETOOTH
    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    val device: BluetoothDevice get() = socket.remoteDevice

    override fun close() {
        runCatching { socket.close() }
    }
}
