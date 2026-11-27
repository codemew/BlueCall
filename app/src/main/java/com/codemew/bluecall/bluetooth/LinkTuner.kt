package com.codemew.bluecall.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Speeds up the Bluetooth LE link for the length of a call only.
 *
 * L2CAP channels have no API for connection parameters, so a GATT connection
 * is opened on the same link and used to request the high-priority connection
 * interval (about 11–15 ms instead of 30–50 ms) and the 2M PHY. Both are
 * released at hang-up so the radio drops back to its low-power defaults.
 *
 * Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class LinkTuner(private val context: Context) {
    private var gatt: BluetoothGatt? = null

    fun tune(device: BluetoothDevice) {
        release()
        gatt = try {
            if (Build.VERSION.SDK_INT >= 37) {
                val settings = BluetoothGattConnectionSettings.Builder()
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .setAutoConnectEnabled(false)
                    .build()
                device.connectGatt(settings, context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "connectGatt failed", e)
            null
        }
    }

    fun release() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val priority = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
                Log.d(TAG, "link tuned, priority request accepted=$priority")
            }
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.d(TAG, "phy tx=$txPhy rx=$rxPhy status=$status")
        }
    }

    private companion object {
        const val TAG = "LinkTuner"
    }
}
