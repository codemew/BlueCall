package com.codemew.bluecall.audio

enum class RouteType { EARPIECE, SPEAKER, BLUETOOTH, WIRED }

/** One place call audio can go. [id] tells apart several devices of the same type (two sets of buds). */
data class AudioRoute(val type: RouteType, val name: String, val id: String)

val RouteType.isHeadset: Boolean get() = this == RouteType.BLUETOOTH || this == RouteType.WIRED

/**
 * Decides where call audio goes as devices come and go. It is pure, so the
 * same rules drive both Android's calling system and BlueCall's own routing:
 *
 * - When a call starts: Bluetooth, then wired, then the earpiece, then the speaker.
 * - When a headset connects mid-call, it takes the call, even if the speaker or
 *   earpiece had been picked. The built-in route it replaced is remembered.
 * - When the headset in use goes away, the call moves to another connected
 *   headset, or back to the remembered route (speaker stays speaker).
 * - Anything picked by hand stays until a device connects or disconnects.
 *
 * The system often moves audio off a disappearing headset a moment before it
 * reports the headset gone. A move off a headset that is followed within
 * [SYSTEM_FALLBACK_WINDOW_MS] by the headset disappearing is treated as that
 * fallback, not as the user's choice.
 */
class RoutePolicy {
    private var known: List<AudioRoute> = emptyList()
    private var headsetInUse: AudioRoute? = null
    private var lastBuiltIn: RouteType? = null
    private var tentativeBuiltIn: RouteType? = null
    private var tentativeAtMs = 0L

    fun reset() {
        known = emptyList()
        headsetInUse = null
        lastBuiltIn = null
        tentativeBuiltIn = null
    }

    /** The route to use as a call starts. Keeps [current] if it is already the preferred kind. */
    fun start(available: List<AudioRoute>, current: AudioRoute?): AudioRoute? {
        reset()
        known = available
        val best = best(available) ?: return null
        val pick = if (current != null && current.type == best.type && available.any { it.id == current.id }) current else best
        if (pick.type.isHeadset) headsetInUse = pick else lastBuiltIn = pick.type
        return pick
    }

    /** The route actually in use changed (reported by the system). */
    fun onRouteInUse(route: AudioRoute?, nowMs: Long) {
        if (route == null) return
        if (route.type.isHeadset) {
            headsetInUse = route
            tentativeBuiltIn = null
            return
        }
        val headset = headsetInUse
        if (headset != null && known.any { it.id == headset.id }) {
            // Off a headset that is still there: the user's choice, or the system's
            // fallback just before it reports the headset gone. Decide later.
            if (tentativeBuiltIn == null) {
                tentativeBuiltIn = route.type
                tentativeAtMs = nowMs
            }
        } else {
            headsetInUse = null
            lastBuiltIn = route.type
        }
    }

    /** The user picked [route] in BlueCall while on [current]. */
    fun onUserSelected(route: AudioRoute, current: AudioRoute?) {
        tentativeBuiltIn = null
        if (route.type.isHeadset) {
            if (current != null && !current.type.isHeadset) lastBuiltIn = current.type
            headsetInUse = route
        } else {
            lastBuiltIn = route.type
            headsetInUse = null
        }
    }

    /** The devices changed. Returns the route to switch to, or null to stay on [current]. */
    fun onAvailableChanged(available: List<AudioRoute>, current: AudioRoute?, nowMs: Long): AudioRoute? {
        val previousIds = known.mapTo(HashSet()) { it.id }
        val added = available.filter { it.id !in previousIds }
        known = available

        // A move off a headset long enough ago, with the headset still there, was deliberate.
        tentativeBuiltIn?.let { type ->
            if (nowMs - tentativeAtMs > SYSTEM_FALLBACK_WINDOW_MS) {
                lastBuiltIn = type
                headsetInUse = null
                tentativeBuiltIn = null
            }
        }

        val newHeadset = added.filter { it.type.isHeadset }
            .sortedBy { if (it.type == RouteType.BLUETOOTH) 0 else 1 }
            .firstOrNull()
        if (newHeadset != null) {
            if (current != null && !current.type.isHeadset && tentativeBuiltIn == null) lastBuiltIn = current.type
            headsetInUse = newHeadset
            tentativeBuiltIn = null
            return newHeadset.takeIf { it.id != current?.id }
        }

        val lost = headsetInUse?.takeIf { h -> available.none { it.id == h.id } }
        if (lost != null) {
            headsetInUse = null
            tentativeBuiltIn = null
            val other = available.firstOrNull { it.type.isHeadset }
            if (other != null) {
                headsetInUse = other
                return other.takeIf { it.id != current?.id }
            }
            val target = lastBuiltIn?.let { type -> available.firstOrNull { it.type == type } } ?: bestBuiltIn(available)
            return target?.takeIf { it.id != current?.id }
        }

        if (current == null) return best(available)
        if (available.none { it.id == current.id }) {
            return available.firstOrNull { it.type.isHeadset } ?: bestBuiltIn(available)
        }
        return null
    }

    /** Where the speaker button goes back to when turning the speaker off. */
    fun offSpeaker(available: List<AudioRoute>): AudioRoute? =
        available.firstOrNull { it.type == RouteType.BLUETOOTH }
            ?: available.firstOrNull { it.type == RouteType.WIRED }
            ?: available.firstOrNull { it.type == RouteType.EARPIECE }

    private fun best(available: List<AudioRoute>): AudioRoute? =
        offSpeaker(available) ?: available.firstOrNull { it.type == RouteType.SPEAKER }

    private fun bestBuiltIn(available: List<AudioRoute>): AudioRoute? =
        available.firstOrNull { it.type == RouteType.EARPIECE } ?: available.firstOrNull { it.type == RouteType.SPEAKER }

    companion object {
        const val SYSTEM_FALLBACK_WINDOW_MS = 1_500L
    }
}
