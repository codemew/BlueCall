package com.codemew.bluecall.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import android.util.Log

/**
 * Makes this phone visible to nearby phones running BlueCall.
 *
 * Advertising is done by the Bluetooth controller itself; the app's CPU is not
 * involved between packets. In the background it uses the low-power interval
 * (about one packet burst per second), which costs very little battery.
 *
 * Callers must hold BLUETOOTH_ADVERTISE (checked before the service goes online).
 */
@SuppressLint("MissingPermission")
class BleAdvertiser(
    private val adapter: BluetoothAdapter,
    private val onFailure: (Int) -> Unit,
) {
    private var callback: AdvertiseCallback? = null
    private var currentKey: String? = null

    val isAdvertising: Boolean get() = callback != null

    fun start(payload: AdvertPayload, name: String, lowPower: Boolean) {
        val key = "$payload|$name|$lowPower"
        if (key == currentKey && callback != null) return
        stop()
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            onFailure(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (lowPower) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                else AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(BlueCallUuids.SERVICE), payload.encode())
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(BlueCallUuids.NAME), AdvertPayload.encodeName(name))
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertising failed: $errorCode")
                if (callback === this) {
                    callback = null
                    currentKey = null
                }
                onFailure(errorCode)
            }
        }
        callback = cb
        currentKey = key
        try {
            advertiser.startAdvertising(settings, data, scanResponse, cb)
        } catch (e: RuntimeException) {
            Log.w(TAG, "startAdvertising threw", e)
            callback = null
            currentKey = null
            onFailure(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        currentKey = null
        try {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(cb)
        } catch (e: RuntimeException) {
            // Bluetooth already off: nothing to stop.
            Log.d(TAG, "stopAdvertising: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "BleAdvertiser"
    }
}
