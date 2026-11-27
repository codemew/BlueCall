package com.codemew.bluecall.call

import com.codemew.bluecall.call.CallEffect.AcquireCallLocks
import com.codemew.bluecall.call.CallEffect.CloseSession
import com.codemew.bluecall.call.CallEffect.ReleaseCallLocks
import com.codemew.bluecall.call.CallEffect.ResumePresence
import com.codemew.bluecall.call.CallEffect.StartAudio
import com.codemew.bluecall.call.CallEffect.StopAudio
import com.codemew.bluecall.call.CallEffect.StopPresence
import com.codemew.bluecall.call.CallEffect.StopService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {
    private val peer = PeerInfo(77, "Alex's phone")
    private val now = 1_000L

    private fun reduce(state: CallState, event: CallEvent) = CallStateMachine.reduce(state, event, now)

    private val allStates = listOf(
        CallState.Offline,
        CallState.Idle(),
        CallState.Dialing(peer),
        CallState.Ringing(peer),
        CallState.Incoming(peer),
        CallState.Active(peer, 0),
    )

    @Test
    fun goingOnlineStartsPresence() {
        val t = reduce(CallState.Offline, CallEvent.WentOnline)
        assertEquals(CallState.Idle(), t.state)
        assertEquals(listOf(CallEffect.StartPresence), t.effects)
    }

    @Test
    fun outgoingCallHappyPath() {
        var t = reduce(CallState.Idle(), CallEvent.Dial(peer))
        assertEquals(CallState.Dialing(peer), t.state)
        assertTrue(t.effects.contains(CallEffect.Connect(peer)))
        assertTrue(t.effects.contains(AcquireCallLocks))
        assertTrue(t.effects.contains(CallEffect.PausePresence))

        t = reduce(t.state, CallEvent.DialConnected)
        assertEquals(CallState.Ringing(peer), t.state)
        assertTrue(t.effects.contains(CallEffect.SendCallRequest))

        t = reduce(t.state, CallEvent.RemoteAccepted)
        assertEquals(CallState.Active(peer, now), t.state)
        assertTrue(t.effects.contains(StartAudio))

        t = reduce(t.state, CallEvent.LocalHangup)
        assertEquals(CallState.Idle(CallEnd(peer, EndReason.HUNG_UP, now)), t.state)
        assertEquals(
            listOf(CallEffect.SendHangup, StopAudio, CloseSession, ReleaseCallLocks, ResumePresence),
            t.effects,
        )
    }

    @Test
    fun incomingCallAnswered() {
        var t = reduce(CallState.Idle(), CallEvent.IncomingCall(peer))
        assertEquals(CallState.Incoming(peer), t.state)
        assertTrue(t.effects.contains(CallEffect.StartIncomingRing))
        t = reduce(t.state, CallEvent.Answer)
        assertEquals(CallState.Active(peer, now), t.state)
        assertEquals(
            listOf(CallEffect.StopIncomingRing, CallEffect.CancelRingTimeout, CallEffect.SendAccept, StartAudio),
            t.effects,
        )
    }

    @Test
    fun incomingCallUnansweredIsMissed() {
        val t = reduce(CallState.Incoming(peer), CallEvent.RingTimeout)
        assertEquals(EndReason.MISSED, (t.state as CallState.Idle).lastEnd?.reason)
        assertTrue(t.effects.contains(CallEffect.PostMissedCall(peer)))
        assertTrue(t.effects.contains(CallEffect.SendReject(busy = false)))
        assertTrue(t.effects.contains(CallEffect.StopIncomingRing))
    }

    @Test
    fun callerHangingUpWhileRingingIsMissed() {
        val t = reduce(CallState.Incoming(peer), CallEvent.RemoteHangup)
        assertEquals(EndReason.MISSED, (t.state as CallState.Idle).lastEnd?.reason)
        assertTrue(t.effects.contains(CallEffect.PostMissedCall(peer)))
    }

    @Test
    fun busyRejection() {
        val t = reduce(CallState.Ringing(peer), CallEvent.RemoteRejected(busy = true))
        assertEquals(EndReason.BUSY, (t.state as CallState.Idle).lastEnd?.reason)
        assertFalse(t.effects.contains(CallEffect.SendHangup))
    }

    @Test
    fun dialFailureReturnsToIdle() {
        val t = reduce(CallState.Dialing(peer), CallEvent.DialFailed)
        assertEquals(EndReason.CONNECTION_FAILED, (t.state as CallState.Idle).lastEnd?.reason)
        assertTrue(t.effects.containsAll(listOf(CloseSession, ReleaseCallLocks, ResumePresence)))
    }

    @Test
    fun linkLossDuringCallEndsIt() {
        val t = reduce(CallState.Active(peer, 0), CallEvent.LinkLost)
        assertEquals(EndReason.LINK_LOST, (t.state as CallState.Idle).lastEnd?.reason)
        assertTrue(t.effects.containsAll(listOf(StopAudio, CloseSession, ReleaseCallLocks)))
    }

    @Test
    fun everyCallEndReleasesLocksAndResumesPresence() {
        val ends = listOf(
            CallState.Dialing(peer) to CallEvent.DialFailed,
            CallState.Dialing(peer) to CallEvent.LocalHangup,
            CallState.Dialing(peer) to CallEvent.RingTimeout,
            CallState.Ringing(peer) to CallEvent.LocalHangup,
            CallState.Ringing(peer) to CallEvent.RingTimeout,
            CallState.Ringing(peer) to CallEvent.RemoteHangup,
            CallState.Ringing(peer) to CallEvent.LinkLost,
            CallState.Ringing(peer) to CallEvent.RemoteRejected(false),
            CallState.Incoming(peer) to CallEvent.Decline,
            CallState.Incoming(peer) to CallEvent.RingTimeout,
            CallState.Incoming(peer) to CallEvent.LinkLost,
            CallState.Active(peer, 0) to CallEvent.LocalHangup,
            CallState.Active(peer, 0) to CallEvent.RemoteHangup,
            CallState.Active(peer, 0) to CallEvent.LinkLost,
        )
        for ((state, event) in ends) {
            val t = reduce(state, event)
            assertTrue("$state + $event", t.state is CallState.Idle)
            assertTrue("$state + $event closes session", t.effects.contains(CloseSession))
            assertTrue("$state + $event releases locks", t.effects.contains(ReleaseCallLocks))
            assertEquals("$state + $event resumes presence last", ResumePresence, t.effects.last())
        }
    }

    @Test
    fun shutdownFromEveryStateLeavesNothingRunning() {
        for (state in allStates) {
            val t = reduce(state, CallEvent.Shutdown)
            assertEquals(CallState.Offline, t.state)
            if (state == CallState.Offline) {
                assertTrue(t.effects.isEmpty())
                continue
            }
            assertTrue("$state stops presence", t.effects.contains(StopPresence))
            assertEquals("$state stops the service last", StopService, t.effects.last())
            assertFalse("$state must not resume presence", t.effects.contains(ResumePresence))
            if (state.isCallInProgress) {
                assertTrue("$state closes session", t.effects.contains(CloseSession))
                assertTrue("$state releases locks", t.effects.contains(ReleaseCallLocks))
            }
            if (state is CallState.Active || state is CallState.Ringing) {
                assertTrue("$state stops audio", t.effects.contains(StopAudio))
                assertTrue("$state tells the peer", t.effects.contains(CallEffect.SendHangup))
            }
            if (state is CallState.Incoming) {
                assertTrue(t.effects.contains(CallEffect.StopIncomingRing))
            }
        }
    }

    @Test
    fun irrelevantEventsAreIgnored() {
        for (state in allStates) {
            for (event in listOf(CallEvent.RemoteAccepted, CallEvent.Answer, CallEvent.DialConnected)) {
                val t = reduce(state, event)
                val handled = (state is CallState.Ringing && event == CallEvent.RemoteAccepted) ||
                    (state is CallState.Incoming && event == CallEvent.Answer) ||
                    (state is CallState.Dialing && event == CallEvent.DialConnected)
                if (!handled) {
                    assertEquals(state, t.state)
                    assertTrue(t.effects.isEmpty())
                }
            }
        }
    }

    @Test
    fun secondIncomingCallWhileBusyDoesNotChangeState() {
        val active = CallState.Active(peer, 0)
        val t = reduce(active, CallEvent.IncomingCall(PeerInfo(1, "Other")))
        assertEquals(active, t.state)
        assertTrue(t.effects.isEmpty())
    }
}
