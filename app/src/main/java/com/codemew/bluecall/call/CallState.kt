package com.codemew.bluecall.call

/** The other phone in a call. [sessionId] is the random id it advertises while online. */
data class PeerInfo(val sessionId: Int, val name: String)

enum class EndReason {
    HUNG_UP,
    REMOTE_HUNG_UP,
    DECLINED,
    REMOTE_DECLINED,
    BUSY,
    NO_ANSWER,
    MISSED,
    CONNECTION_FAILED,
    LINK_LOST,
    CANCELLED,
}

data class CallEnd(val peer: PeerInfo, val reason: EndReason, val atMs: Long)

sealed interface CallState {
    /** Nothing is running: no Bluetooth, no service, no timers. */
    data object Offline : CallState

    /** Online and reachable, no call. [lastEnd] lets the UI show how the previous call ended. */
    data class Idle(val lastEnd: CallEnd? = null) : CallState

    /** Outgoing: opening the Bluetooth channel to the other phone. */
    data class Dialing(val peer: PeerInfo) : CallState

    /** Outgoing: connected, the other phone is ringing. */
    data class Ringing(val peer: PeerInfo) : CallState

    /** Incoming: this phone is ringing. */
    data class Incoming(val peer: PeerInfo) : CallState

    /** Call in progress. [connectedAtMs] is wall-clock time, used for the call timer. */
    data class Active(val peer: PeerInfo, val connectedAtMs: Long) : CallState
}

val CallState.peer: PeerInfo?
    get() = when (this) {
        is CallState.Dialing -> peer
        is CallState.Ringing -> peer
        is CallState.Incoming -> peer
        is CallState.Active -> peer
        else -> null
    }

/** True while a call is being set up or is in progress. */
val CallState.isCallInProgress: Boolean
    get() = peer != null

fun CallState.withPeer(newPeer: PeerInfo): CallState = when (this) {
    is CallState.Dialing -> copy(peer = newPeer)
    is CallState.Ringing -> copy(peer = newPeer)
    is CallState.Incoming -> copy(peer = newPeer)
    is CallState.Active -> copy(peer = newPeer)
    else -> this
}
