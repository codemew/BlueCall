package com.codemew.bluecall.call

/** The radio a call runs over. */
enum class Transport {
    /** Bluetooth LE: discovery by advertising, the call over an L2CAP channel. */
    BLUETOOTH,

    /** Wi-Fi Aware: phone-to-phone Wi-Fi with no router, the call over TCP on the Aware link. */
    WIFI,
}
