package com.codemew.bluecall.bluetooth

import android.bluetooth.BluetoothDevice
import com.codemew.bluecall.call.Sighting

/** A phone with BlueCall open, seen by the Bluetooth scanner, and how to connect to it. */
data class BleSighting(
    val sessionId: Int,
    val name: String,
    val psm: Int,
    val rssi: Int,
    val lastSeenMs: Long,
    val device: BluetoothDevice,
) {
    fun toSighting() = Sighting(sessionId, name, lastSeenMs, rssi)
}
