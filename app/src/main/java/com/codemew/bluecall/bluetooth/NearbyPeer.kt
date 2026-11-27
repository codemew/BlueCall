package com.codemew.bluecall.bluetooth

import android.bluetooth.BluetoothDevice
import com.codemew.bluecall.call.PeerInfo

/** A phone with BlueCall open, seen by the scanner. */
data class NearbyPeer(
    val sessionId: Int,
    val name: String,
    val psm: Int,
    val rssi: Int,
    val lastSeenMs: Long,
    val device: BluetoothDevice,
) {
    /** 1..4 bars, from the received signal strength. */
    val signalBars: Int
        get() = when {
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -82 -> 2
            else -> 1
        }

    fun toPeerInfo() = PeerInfo(sessionId, name)
}
