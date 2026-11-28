package com.codemew.bluecall.service

import com.codemew.bluecall.audio.AudioRoute
import com.codemew.bluecall.call.CallState
import com.codemew.bluecall.call.NearbyPeer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether one radio can carry BlueCall calls right now, and if not, why. */
enum class RadioStatus {
    /** Working: this phone is reachable over it. */
    ON,

    /** The radio is switched off (Bluetooth or Wi-Fi). */
    OFF,

    /** Android 12 only: Wi-Fi calling needs Location turned on. */
    NEEDS_LOCATION,

    /** The radio is on but can't be used just now (for example a hotspot is on, or the phone is in deep sleep). */
    UNAVAILABLE,

    /** BlueCall doesn't have the permission it needs. */
    NO_PERMISSION,

    /** Turned off in BlueCall's settings. */
    DISABLED,

    /** This phone doesn't have it. */
    UNSUPPORTED,
}

data class PresenceState(
    val online: Boolean = false,
    val name: String = "",
    /** Visible over Bluetooth. */
    val advertising: Boolean = false,
    /** Looking for phones (Bluetooth scan and/or Wi-Fi discovery). */
    val scanning: Boolean = false,
    val bluetooth: RadioStatus = RadioStatus.OFF,
    val wifi: RadioStatus = RadioStatus.UNSUPPORTED,
    val error: String? = null,
)

enum class LinkQuality { UNKNOWN, EXCELLENT, GOOD, POOR }

data class CallUiState(
    val muted: Boolean = false,
    val onHold: Boolean = false,
    val peerMuted: Boolean = false,
    val peerOnHold: Boolean = false,
    val route: AudioRoute? = null,
    val routes: List<AudioRoute> = emptyList(),
    /** Android's calling system put the call on hold (for example for a SIM call); it can be resumed. */
    val canResume: Boolean = false,
    val rttMs: Long? = null,
    val latencyMs: Int? = null,
    val linkQuality: LinkQuality = LinkQuality.UNKNOWN,
    val error: String? = null,
)

/**
 * Process-wide state shared between the service and the UI. It lives only as
 * long as the process; nothing here keeps anything running.
 */
object BlueCall {
    internal val callStateFlow = MutableStateFlow<CallState>(CallState.Offline)
    internal val peersFlow = MutableStateFlow<List<NearbyPeer>>(emptyList())
    internal val presenceFlow = MutableStateFlow(PresenceState())
    internal val callUiFlow = MutableStateFlow(CallUiState())
    private val exitFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val callState: StateFlow<CallState> = callStateFlow.asStateFlow()
    val peers: StateFlow<List<NearbyPeer>> = peersFlow.asStateFlow()
    val presence: StateFlow<PresenceState> = presenceFlow.asStateFlow()
    val callUi: StateFlow<CallUiState> = callUiFlow.asStateFlow()

    /** Emits when the app is exiting; every activity finishes itself. */
    val exitEvents: SharedFlow<Unit> = exitFlow.asSharedFlow()

    /** The running service, or null when BlueCall is offline. Main thread only. */
    @Volatile
    var controller: BlueCallService? = null
        internal set

    private var visibleActivities = 0

    val uiVisible: Boolean get() = visibleActivities > 0

    fun activityStarted() {
        visibleActivities++
        if (visibleActivities == 1) controller?.onUiVisibilityChanged(true)
    }

    fun activityStopped() {
        visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
        if (visibleActivities == 0) controller?.onUiVisibilityChanged(false)
    }

    internal fun signalExit() {
        exitFlow.tryEmit(Unit)
    }
}
