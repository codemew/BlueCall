package com.codemew.bluecall.call

/** One radio's view of a nearby phone. [rssi] is known for Bluetooth only. */
data class Sighting(
    val sessionId: Int,
    val name: String,
    val lastSeenMs: Long,
    val rssi: Int? = null,
)

/**
 * A nearby phone with BlueCall open, as the Nearby list shows it: reachable over
 * Bluetooth ([bluetoothBars] is its signal, 1–4), Wi-Fi, or both.
 */
data class NearbyPeer(
    val sessionId: Int,
    val name: String,
    val bluetoothBars: Int? = null,
    val wifi: Boolean = false,
) {
    val bluetooth: Boolean get() = bluetoothBars != null

    val transports: List<Transport>
        get() = buildList {
            if (bluetooth) add(Transport.BLUETOOTH)
            if (wifi) add(Transport.WIFI)
        }

    fun toPeerInfo(transport: Transport) = PeerInfo(sessionId, name, transport)
}

/**
 * Merges what the Bluetooth scanner and Wi-Fi Aware discovery see into one
 * list, matching phones by the session id both radios carry.
 */
object PeerDirectory {

    fun merge(ownSessionId: Int, bluetooth: List<Sighting>, wifi: List<Sighting>): List<NearbyPeer> {
        val bt = bluetooth.filter { it.sessionId != ownSessionId }.associateBy { it.sessionId }
        val wf = wifi.filter { it.sessionId != ownSessionId }.associateBy { it.sessionId }
        return (bt.keys + wf.keys).map { id ->
            val b = bt[id]
            val w = wf[id]
            NearbyPeer(
                sessionId = id,
                name = bestName(b?.name, w?.name, id),
                bluetoothBars = b?.let { signalBars(it.rssi) },
                wifi = w != null,
            )
        }.sortedWith(compareBy<NearbyPeer>({ it.name.lowercase() }, { it.sessionId }))
    }

    /**
     * The Bluetooth name is cut to 13 bytes to fit the advertisement and Wi-Fi
     * carries up to 32, so the longer of the two is the more complete one.
     */
    private fun bestName(bluetooth: String?, wifi: String?, sessionId: Int): String =
        listOfNotNull(bluetooth, wifi).map { it.trim() }.filter { it.isNotEmpty() }.maxByOrNull { it.length }
            ?: placeholderName(sessionId)

    fun placeholderName(sessionId: Int) = "Phone ${Integer.toHexString(sessionId).takeLast(4).uppercase()}"

    /** 1..4 bars from the received signal strength. Unknown strength shows as 2 bars. */
    fun signalBars(rssi: Int?): Int = when {
        rssi == null -> 2
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -82 -> 2
        else -> 1
    }
}
