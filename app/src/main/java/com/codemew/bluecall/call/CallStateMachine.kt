package com.codemew.bluecall.call

sealed interface CallEvent {
    data object WentOnline : CallEvent
    data object Shutdown : CallEvent
    data class Dial(val peer: PeerInfo) : CallEvent
    data object DialConnected : CallEvent
    data object DialFailed : CallEvent
    data class IncomingCall(val peer: PeerInfo) : CallEvent
    data object Answer : CallEvent
    data object Decline : CallEvent
    data object RemoteAccepted : CallEvent
    data class RemoteRejected(val busy: Boolean) : CallEvent
    data object LocalHangup : CallEvent
    data object RemoteHangup : CallEvent
    data object LinkLost : CallEvent
    data object RingTimeout : CallEvent
}

/** Side effects the service carries out, in order, after a transition. */
sealed interface CallEffect {
    /** Start listening for calls and make this phone visible (Bluetooth and/or Wi-Fi). */
    data object StartPresence : CallEffect

    /** Stop advertising/scanning while a call is being set up or is active. */
    data object PausePresence : CallEffect

    /** Back to idle: advertise again (and scan if the UI is visible). */
    data object ResumePresence : CallEffect

    /** Stop advertising, scanning and listening on every radio. */
    data object StopPresence : CallEffect

    data class Connect(val peer: PeerInfo) : CallEffect
    data object SendCallRequest : CallEffect
    data object SendAccept : CallEffect
    data class SendReject(val busy: Boolean) : CallEffect
    data object SendHangup : CallEffect
    data object CloseSession : CallEffect
    data object StartIncomingRing : CallEffect
    data object StopIncomingRing : CallEffect
    data object StartRingback : CallEffect
    data object StopRingback : CallEffect
    data object ArmRingTimeout : CallEffect
    data object CancelRingTimeout : CallEffect
    data object StartAudio : CallEffect
    data object StopAudio : CallEffect

    /** Keep the CPU awake (wakelock) for the duration of the call attempt. */
    data object AcquireCallLocks : CallEffect

    /** Drop every call-time wakelock and the microphone foreground-service type. */
    data object ReleaseCallLocks : CallEffect

    data class PostMissedCall(val peer: PeerInfo) : CallEffect
    data object StopService : CallEffect

    /**
     * Register the call with Android's calling system, so earphones, car kits and
     * watches can answer, hang up and take the audio.
     */
    data class AddSystemCall(val incoming: Boolean) : CallEffect

    /** Tell the calling system the call is now connected. */
    data object SetSystemCallActive : CallEffect

    /** Remove the call from the calling system. Emitted exactly once for every call that ends. */
    data class EndSystemCall(val reason: EndReason) : CallEffect
}

data class Transition(val state: CallState, val effects: List<CallEffect>)

/**
 * Pure call state machine. It holds no state of its own and touches no Android
 * APIs, so every transition (and especially every shutdown path) is unit-tested.
 */
object CallStateMachine {

    fun reduce(state: CallState, event: CallEvent, nowMs: Long): Transition {
        if (event == CallEvent.Shutdown) {
            if (state == CallState.Offline) return Transition(state, emptyList())
            return Transition(
                CallState.Offline,
                teardown(state) + listOf(CallEffect.StopPresence, CallEffect.StopService),
            )
        }

        return when (state) {
            CallState.Offline -> when (event) {
                CallEvent.WentOnline -> Transition(CallState.Idle(), listOf(CallEffect.StartPresence))
                else -> noChange(state)
            }

            is CallState.Idle -> when (event) {
                is CallEvent.Dial -> Transition(
                    CallState.Dialing(event.peer),
                    listOf(
                        CallEffect.PausePresence,
                        CallEffect.AcquireCallLocks,
                        CallEffect.AddSystemCall(incoming = false),
                        CallEffect.ArmRingTimeout,
                        CallEffect.Connect(event.peer),
                    ),
                )

                is CallEvent.IncomingCall -> Transition(
                    CallState.Incoming(event.peer),
                    listOf(
                        CallEffect.PausePresence,
                        CallEffect.AcquireCallLocks,
                        CallEffect.AddSystemCall(incoming = true),
                        CallEffect.StartIncomingRing,
                        CallEffect.ArmRingTimeout,
                    ),
                )

                else -> noChange(state)
            }

            is CallState.Dialing -> when (event) {
                CallEvent.DialConnected -> Transition(
                    CallState.Ringing(state.peer),
                    listOf(CallEffect.SendCallRequest, CallEffect.StartRingback),
                )

                CallEvent.DialFailed -> endCall(state.peer, EndReason.CONNECTION_FAILED, nowMs, dialingEnd())
                // Over Wi-Fi the other phone can say it is busy before any data link exists.
                is CallEvent.RemoteRejected -> endCall(
                    state.peer,
                    if (event.busy) EndReason.BUSY else EndReason.REMOTE_DECLINED,
                    nowMs,
                    dialingEnd(),
                )
                CallEvent.LocalHangup -> endCall(state.peer, EndReason.CANCELLED, nowMs, dialingEnd())
                CallEvent.RingTimeout -> endCall(state.peer, EndReason.NO_ANSWER, nowMs, dialingEnd())
                CallEvent.LinkLost, CallEvent.RemoteHangup ->
                    endCall(state.peer, EndReason.CONNECTION_FAILED, nowMs, dialingEnd())

                else -> noChange(state)
            }

            is CallState.Ringing -> when (event) {
                CallEvent.RemoteAccepted -> Transition(
                    CallState.Active(state.peer, nowMs),
                    listOf(
                        CallEffect.StopRingback,
                        CallEffect.CancelRingTimeout,
                        CallEffect.SetSystemCallActive,
                        CallEffect.StartAudio,
                    ),
                )

                is CallEvent.RemoteRejected -> endCall(
                    state.peer,
                    if (event.busy) EndReason.BUSY else EndReason.REMOTE_DECLINED,
                    nowMs,
                    ringingEnd(sendHangup = false),
                )

                CallEvent.LocalHangup -> endCall(state.peer, EndReason.CANCELLED, nowMs, ringingEnd(sendHangup = true))
                CallEvent.RingTimeout -> endCall(state.peer, EndReason.NO_ANSWER, nowMs, ringingEnd(sendHangup = true))
                CallEvent.RemoteHangup -> endCall(state.peer, EndReason.REMOTE_HUNG_UP, nowMs, ringingEnd(sendHangup = false))
                CallEvent.LinkLost -> endCall(state.peer, EndReason.LINK_LOST, nowMs, ringingEnd(sendHangup = false))
                else -> noChange(state)
            }

            is CallState.Incoming -> when (event) {
                CallEvent.Answer -> Transition(
                    CallState.Active(state.peer, nowMs),
                    listOf(
                        CallEffect.StopIncomingRing,
                        CallEffect.CancelRingTimeout,
                        CallEffect.SendAccept,
                        CallEffect.SetSystemCallActive,
                        CallEffect.StartAudio,
                    ),
                )

                CallEvent.Decline -> endCall(state.peer, EndReason.DECLINED, nowMs, incomingEnd(reject = true))
                CallEvent.RingTimeout -> endCall(
                    state.peer, EndReason.MISSED, nowMs,
                    incomingEnd(reject = true) + CallEffect.PostMissedCall(state.peer),
                )

                CallEvent.RemoteHangup, CallEvent.LinkLost -> endCall(
                    state.peer, EndReason.MISSED, nowMs,
                    incomingEnd(reject = false) + CallEffect.PostMissedCall(state.peer),
                )

                else -> noChange(state)
            }

            is CallState.Active -> when (event) {
                CallEvent.LocalHangup -> endCall(state.peer, EndReason.HUNG_UP, nowMs, activeEnd(sendHangup = true))
                CallEvent.RemoteHangup -> endCall(state.peer, EndReason.REMOTE_HUNG_UP, nowMs, activeEnd(sendHangup = false))
                CallEvent.LinkLost -> endCall(state.peer, EndReason.LINK_LOST, nowMs, activeEnd(sendHangup = false))
                else -> noChange(state)
            }
        }
    }

    private fun noChange(state: CallState) = Transition(state, emptyList())

    private fun endCall(peer: PeerInfo, reason: EndReason, nowMs: Long, effects: List<CallEffect>) =
        Transition(
            CallState.Idle(CallEnd(peer, reason, nowMs)),
            effects + listOf(CallEffect.EndSystemCall(reason), CallEffect.ReleaseCallLocks, CallEffect.ResumePresence),
        )

    private fun dialingEnd() = listOf(CallEffect.CancelRingTimeout, CallEffect.CloseSession)

    private fun ringingEnd(sendHangup: Boolean) = buildList {
        add(CallEffect.StopRingback)
        add(CallEffect.CancelRingTimeout)
        if (sendHangup) add(CallEffect.SendHangup)
        add(CallEffect.StopAudio)
        add(CallEffect.CloseSession)
    }

    private fun incomingEnd(reject: Boolean) = buildList {
        add(CallEffect.StopIncomingRing)
        add(CallEffect.CancelRingTimeout)
        if (reject) add(CallEffect.SendReject(busy = false))
        add(CallEffect.CloseSession)
    }

    private fun activeEnd(sendHangup: Boolean) = buildList {
        if (sendHangup) add(CallEffect.SendHangup)
        add(CallEffect.StopAudio)
        add(CallEffect.CloseSession)
    }

    /** Everything needed to stop whatever the given state had running. */
    private fun teardown(state: CallState): List<CallEffect> = when (state) {
        CallState.Offline, is CallState.Idle -> emptyList()
        is CallState.Dialing ->
            dialingEnd() + CallEffect.EndSystemCall(EndReason.CANCELLED) + CallEffect.ReleaseCallLocks
        is CallState.Ringing ->
            ringingEnd(sendHangup = true) + CallEffect.EndSystemCall(EndReason.CANCELLED) + CallEffect.ReleaseCallLocks
        is CallState.Incoming ->
            incomingEnd(reject = true) + CallEffect.EndSystemCall(EndReason.DECLINED) + CallEffect.ReleaseCallLocks
        is CallState.Active ->
            activeEnd(sendHangup = true) + CallEffect.EndSystemCall(EndReason.HUNG_UP) + CallEffect.ReleaseCallLocks
    }
}
