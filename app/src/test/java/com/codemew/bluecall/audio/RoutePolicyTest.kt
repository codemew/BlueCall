package com.codemew.bluecall.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutePolicyTest {
    private val earpiece = AudioRoute(RouteType.EARPIECE, "Phone", "earpiece")
    private val speaker = AudioRoute(RouteType.SPEAKER, "Speaker", "speaker")
    private val buds = AudioRoute(RouteType.BLUETOOTH, "Galaxy Buds3", "bt:buds")
    private val car = AudioRoute(RouteType.BLUETOOTH, "Car", "bt:car")
    private val wired = AudioRoute(RouteType.WIRED, "Headset", "wired")
    private val builtIn = listOf(earpiece, speaker)

    /** A call that started on the earpiece and was switched to the speaker by hand. */
    private fun onSpeaker(): RoutePolicy = RoutePolicy().apply {
        assertEquals(earpiece, start(builtIn, null))
        onUserSelected(speaker, earpiece)
        onRouteInUse(speaker, 0)
    }

    @Test
    fun callStartPrefersHeadsetsThenEarpiece() {
        val policy = RoutePolicy()
        assertEquals(earpiece, policy.start(builtIn, null))
        assertEquals(buds, policy.start(builtIn + buds, null))
        assertEquals(wired, policy.start(builtIn + wired, null))
        assertEquals(buds, policy.start(builtIn + wired + buds, null))
        assertEquals(speaker, policy.start(listOf(speaker), null))
        assertNull(policy.start(emptyList(), null))
    }

    @Test
    fun callStartKeepsTheSystemsChoiceOfTheSameKind() {
        val policy = RoutePolicy()
        assertEquals(car, policy.start(builtIn + buds + car, car))
        assertEquals(buds, policy.start(builtIn + buds, speaker))
    }

    @Test
    fun budsJoinACallOnSpeakerAndHandBackToSpeaker() {
        val policy = onSpeaker()
        assertEquals(buds, policy.onAvailableChanged(builtIn + buds, speaker, 1_000))
        policy.onRouteInUse(buds, 1_100)
        assertEquals(speaker, policy.onAvailableChanged(builtIn, buds, 60_000))
    }

    @Test
    fun budsJoinACallOnEarpieceAndHandBackToEarpiece() {
        val policy = RoutePolicy()
        policy.start(builtIn, earpiece)
        policy.onRouteInUse(earpiece, 0)
        assertEquals(buds, policy.onAvailableChanged(builtIn + buds, earpiece, 1_000))
        policy.onRouteInUse(buds, 1_100)
        assertEquals(earpiece, policy.onAvailableChanged(builtIn, buds, 60_000))
    }

    @Test
    fun systemMovingToTheEarpieceFirstStillReturnsToSpeaker() {
        // Android often moves the call to the earpiece a moment before it reports the buds gone.
        val policy = onSpeaker()
        assertEquals(buds, policy.onAvailableChanged(builtIn + buds, speaker, 1_000))
        policy.onRouteInUse(buds, 1_100)
        policy.onRouteInUse(earpiece, 60_000)
        assertEquals(speaker, policy.onAvailableChanged(builtIn, earpiece, 60_050))
    }

    @Test
    fun systemTakingTheBudsBeforeReportingThemStillRemembersSpeaker() {
        val policy = onSpeaker()
        policy.onRouteInUse(buds, 1_000)
        assertNull(policy.onAvailableChanged(builtIn + buds, buds, 1_010))
        assertEquals(speaker, policy.onAvailableChanged(builtIn, buds, 60_000))
    }

    @Test
    fun choosingTheEarpieceInTheSystemOutputPickerIsKept() {
        val policy = onSpeaker()
        assertEquals(buds, policy.onAvailableChanged(builtIn + buds, speaker, 1_000))
        policy.onRouteInUse(buds, 1_100)
        // The user picks the phone itself while the buds stay connected...
        policy.onRouteInUse(earpiece, 10_000)
        // ...so when the buds later go away, the call stays where it is.
        assertNull(policy.onAvailableChanged(builtIn, earpiece, 60_000))
    }

    @Test
    fun budsTakeOverEvenAfterTheSpeakerWasPickedByHand() {
        val policy = RoutePolicy()
        policy.start(builtIn + buds, buds)
        policy.onUserSelected(speaker, buds)
        policy.onRouteInUse(speaker, 0)
        // The same buds reconnecting count as joining.
        assertNull(policy.onAvailableChanged(builtIn + buds, speaker, 1_000))
        assertNull(policy.onAvailableChanged(builtIn, speaker, 2_000))
        assertEquals(buds, policy.onAvailableChanged(builtIn + buds, speaker, 3_000))
    }

    @Test
    fun handPickedRouteStaysWhileDevicesDontChange() {
        val policy = RoutePolicy()
        policy.start(builtIn + buds, buds)
        policy.onUserSelected(speaker, buds)
        policy.onRouteInUse(speaker, 0)
        assertNull(policy.onAvailableChanged(builtIn + buds, speaker, 1_000))
        assertNull(policy.onAvailableChanged(builtIn, speaker, 2_000))
    }

    @Test
    fun pickingBudsByHandRemembersWhereTheCallWas() {
        val policy = RoutePolicy()
        policy.start(builtIn + buds, buds)
        policy.onUserSelected(speaker, buds)
        policy.onUserSelected(buds, speaker)
        policy.onRouteInUse(buds, 0)
        assertEquals(speaker, policy.onAvailableChanged(builtIn, buds, 60_000))
    }

    @Test
    fun anotherHeadsetTakesOverWhenTheOneInUseLeaves() {
        val policy = RoutePolicy()
        policy.start(builtIn + wired, wired)
        assertEquals(buds, policy.onAvailableChanged(builtIn + wired + buds, wired, 1_000))
        assertEquals(wired, policy.onAvailableChanged(builtIn + wired, buds, 2_000))
        assertEquals(earpiece, policy.onAvailableChanged(builtIn, wired, 3_000))
    }

    @Test
    fun aSecondBluetoothDeviceTakesOverAndTheFirstIsTheFallback() {
        val policy = onSpeaker()
        assertEquals(buds, policy.onAvailableChanged(builtIn + buds, speaker, 1_000))
        assertEquals(car, policy.onAvailableChanged(builtIn + buds + car, buds, 2_000))
        assertEquals(buds, policy.onAvailableChanged(builtIn + buds, car, 3_000))
        // Back to the speaker the call was on, not the earpiece.
        assertEquals(speaker, policy.onAvailableChanged(builtIn, buds, 4_000))
    }

    @Test
    fun unknownCurrentRoutePicksTheBest() {
        val policy = RoutePolicy()
        policy.start(builtIn, null)
        assertEquals(earpiece, policy.onAvailableChanged(builtIn, null, 0))
    }

    @Test
    fun speakerButtonGoesBackToTheHeadsetOrEarpiece() {
        val policy = RoutePolicy()
        assertEquals(buds, policy.offSpeaker(builtIn + buds))
        assertEquals(earpiece, policy.offSpeaker(builtIn))
        assertNull(policy.offSpeaker(listOf(speaker)))
    }
}
