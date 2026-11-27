package com.codemew.bluecall.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.codemew.bluecall.protocol.PROTOCOL_VERSION

/**
 * Finds nearby phones with BlueCall open.
 *
 * Scanning is the most power-hungry part of discovery, so the service runs it
 * only while BlueCall is on screen. The filter on our service data is handed
 * to the Bluetooth controller, so other devices' advertisements never wake the
 * app processor.
 *
 * Callers must hold BLUETOOTH_SCAN (checked before the service goes online).
 */
@SuppressLint("MissingPermission")
class BleScanner(
    private val adapter: BluetoothAdapter,
    private val ownSessionId: Int,
    private val onPeersChanged: (List<NearbyPeer>) -> Unit,
    private val onFailure: (Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val peers = LinkedHashMap<Int, NearbyPeer>()
    private val recentStarts = ArrayDeque<Long>()
    private var scanning = false
    private var wanted = false

    private val pruneTick = object : Runnable {
        override fun run() {
            prune()
            if (scanning) handler.postDelayed(this, PRUNE_INTERVAL_MS)
        }
    }

    private val retryStart = Runnable { if (wanted) start() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            handler.removeCallbacks(pruneTick)
            onFailure(errorCode)
        }
    }

    fun start() {
        wanted = true
        if (scanning) return
        val scanner = adapter.bluetoothLeScanner ?: return
        // Android silently blocks apps that start more than 5 scans in 30 seconds.
        val now = SystemClock.elapsedRealtime()
        while (recentStarts.isNotEmpty() && now - recentStarts.first() > SCAN_WINDOW_MS) recentStarts.removeFirst()
        if (recentStarts.size >= MAX_STARTS_PER_WINDOW) {
            handler.removeCallbacks(retryStart)
            handler.postDelayed(retryStart, SCAN_WINDOW_MS - (now - recentStarts.first()) + 250)
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceData(
                ParcelUuid(BlueCallUuids.SERVICE),
                byteArrayOf(PROTOCOL_VERSION.toByte()),
                byteArrayOf(0xFF.toByte()),
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            recentStarts.addLast(now)
            scanning = true
            handler.postDelayed(pruneTick, PRUNE_INTERVAL_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "startScan threw", e)
        }
    }

    fun stop() {
        wanted = false
        handler.removeCallbacks(retryStart)
        handler.removeCallbacks(pruneTick)
        if (scanning) {
            scanning = false
            try {
                adapter.bluetoothLeScanner?.stopScan(callback)
            } catch (e: RuntimeException) {
                Log.d(TAG, "stopScan: ${e.message}")
            }
        }
        if (peers.isNotEmpty()) {
            peers.clear()
            onPeersChanged(emptyList())
        }
    }

    fun peer(sessionId: Int): NearbyPeer? = peers[sessionId]

    private fun handle(result: ScanResult) {
        val record = result.scanRecord ?: return
        val payload = AdvertPayload.decode(record.getServiceData(ParcelUuid(BlueCallUuids.SERVICE))) ?: return
        if (payload.sessionId == ownSessionId) return
        val previous = peers[payload.sessionId]
        val name = AdvertPayload.decodeName(record.getServiceData(ParcelUuid(BlueCallUuids.NAME)))
            ?: previous?.name
            ?: "Phone ${Integer.toHexString(payload.sessionId).takeLast(4).uppercase()}"
        val peer = NearbyPeer(
            sessionId = payload.sessionId,
            name = name,
            psm = payload.psm,
            rssi = result.rssi,
            lastSeenMs = SystemClock.elapsedRealtime(),
            device = result.device,
        )
        peers[payload.sessionId] = peer
        // Only republish when something the UI shows has changed.
        if (previous == null || previous.name != peer.name || previous.signalBars != peer.signalBars ||
            previous.psm != peer.psm
        ) {
            publish()
        }
    }

    private fun prune() {
        val cutoff = SystemClock.elapsedRealtime() - STALE_AFTER_MS
        if (peers.values.removeAll { it.lastSeenMs < cutoff }) publish()
    }

    private fun publish() = onPeersChanged(peers.values.sortedBy { it.name.lowercase() })

    private companion object {
        const val TAG = "BleScanner"
        const val PRUNE_INTERVAL_MS = 2_000L
        const val STALE_AFTER_MS = 8_000L
        const val SCAN_WINDOW_MS = 30_000L
        const val MAX_STARTS_PER_WINDOW = 4
    }
}
