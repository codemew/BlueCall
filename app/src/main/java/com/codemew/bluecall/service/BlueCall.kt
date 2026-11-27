package com.codemew.bluecall.service

import com.codemew.bluecall.audio.AudioRoute
import com.codemew.bluecall.audio.RouteType
import com.codemew.bluecall.bluetooth.NearbyPeer
import com.codemew.bluecall.call.CallState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class PresenceState(
    val online: Boolean = false,
    val name: String = "",
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val error: String? = null,
)

enum class LinkQuality { UNKNOWN, EXCELLENT, GOOD, POOR }

data class CallUiState(
    val muted: Boolean = false,
    val onHold: Boolean = false,
    val peerMuted: Boolean = false,
    val peerOnHold: Boolean = false,
    val route: RouteType? = null,
    val routes: List<AudioRoute> = emptyList(),
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
