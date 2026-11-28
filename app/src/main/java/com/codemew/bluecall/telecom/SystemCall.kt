package com.codemew.bluecall.telecom

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.net.toUri
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.codemew.bluecall.audio.AudioRoute
import com.codemew.bluecall.audio.RouteType
import com.codemew.bluecall.call.EndReason
import com.codemew.bluecall.call.PeerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registers BlueCall calls with Android's calling system (Jetpack Telecom), so
 * the phone treats them like real calls:
 *
 * - Bluetooth earphones, car kits and watches can answer and hang up, and take
 *   the call audio as soon as they connect.
 * - Android owns the audio mode, focus and output device; BlueCall asks it to
 *   switch with [requestRoute] and follows what it reports.
 * - A SIM call puts the BlueCall call on hold instead of talking over it.
 *
 * If the phone has no calling system, or registering fails or takes longer
 * than [REGISTER_TIMEOUT_MS], [Listener.onSystemCallReady] reports false and
 * BlueCall routes audio itself for that call.
 *
 * Nothing here runs between calls. All callbacks arrive on the main thread.
 */
class SystemCall(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** True: Android owns this call's audio. False: BlueCall must route audio itself. */
        fun onSystemCallReady(ready: Boolean)
        fun onSystemAnswer()
        fun onSystemDisconnect()
        fun onSystemHold(onHold: Boolean)
        fun onSystemMuted(muted: Boolean)
        fun onSystemRoutes(current: AudioRoute?, available: List<AudioRoute>)
    }

    // Its own scope, so a disconnect still reaches Android while the service shuts down.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callsManager by lazy { CallsManager(context) }
    private var registered = false

    private var callGen = 0
    private var job: Job? = null
    private var control: CallControlScope? = null
    private var incoming = false
    private var activeWanted = false
    private var answeredBySystem = false
    private var endedBySystem = false
    private var endpoints: Map<String, CallEndpointCompat> = emptyMap()

    /** True while Android tracks the current call. */
    val isTracking: Boolean get() = control != null

    fun add(peer: PeerInfo, incoming: Boolean) {
        end(null)
        val gen = ++callGen
        this.incoming = incoming
        activeWanted = false
        answeredBySystem = false
        endedBySystem = false
        if (!register()) {
            listener.onSystemCallReady(false)
            return
        }
        val attributes = CallAttributesCompat(
            peer.name,
            "sip:${Integer.toHexString(peer.sessionId)}@bluecall.invalid".toUri(),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE,
            null,
            true,
        )
        job = scope.launch {
            try {
                callsManager.addCall(
                    attributes,
                    { _ ->
                        onMain(gen) {
                            answeredBySystem = true
                            listener.onSystemAnswer()
                        }
                    },
                    { _ ->
                        onMain(gen) {
                            endedBySystem = true
                            listener.onSystemDisconnect()
                        }
                    },
                    { onMain(gen) { listener.onSystemHold(false) } },
                    { onMain(gen) { listener.onSystemHold(true) } },
                ) {
                    val scopeOfCall = this
                    launch(Dispatchers.Main.immediate) { onRegistered(gen, scopeOfCall) }
                    launch(Dispatchers.Main.immediate) {
                        combine(currentCallEndpoint, availableEndpoints) { current, available -> current to available }
                            .collect { (current, available) -> if (gen == callGen) onEndpoints(current, available) }
                    }
                    launch(Dispatchers.Main.immediate) {
                        isMuted.distinctUntilChanged().collect { if (gen == callGen) listener.onSystemMuted(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the calling system refused the call", e)
                if (gen == callGen && control == null) {
                    callGen++
                    listener.onSystemCallReady(false)
                }
            } finally {
                if (gen == callGen) control = null
            }
        }
        scope.launch {
            delay(REGISTER_TIMEOUT_MS)
            if (gen == callGen && control == null) {
                Log.w(TAG, "the calling system didn't register the call in time")
                abandon()
                listener.onSystemCallReady(false)
            }
        }
    }

    /** The call connected (answered here, or accepted by the other phone). */
    fun setActive() {
        activeWanted = true
        val c = control ?: return
        makeActive(c)
    }

    /** Take the call off hold after the calling system held it. */
    fun resume() {
        val c = control ?: return
        val gen = callGen
        scope.launch {
            val result = runCatching { c.setActive() }.getOrNull()
            if (gen == callGen && result is CallControlResult.Success) listener.onSystemHold(false)
        }
    }

    fun requestRoute(route: AudioRoute) {
        val c = control ?: return
        val endpoint = endpoints[route.id] ?: return
        scope.launch {
            val result = runCatching { c.requestEndpointChange(endpoint) }.getOrNull()
            if (result !is CallControlResult.Success) Log.w(TAG, "couldn't switch to ${route.type}: $result")
        }
    }

    /** The call ended. [reason] null means "replaced by a new call". */
    fun end(reason: EndReason?) {
        callGen++
        val c = control
        val j = job
        control = null
        job = null
        endpoints = emptyMap()
        if (c != null && !endedBySystem) {
            scope.launch {
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { runCatching { c.disconnect(disconnectCause(reason)) } }
                j?.cancel()
            }
        } else {
            j?.cancel()
        }
    }

    private fun abandon() {
        callGen++
        job?.cancel()
        job = null
        control = null
    }

    private fun onRegistered(gen: Int, c: CallControlScope) {
        if (gen != callGen) {
            // Too late, or the call already ended: take it straight back out.
            scope.launch { withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { runCatching { c.disconnect(DisconnectCause(DisconnectCause.LOCAL)) } } }
            return
        }
        control = c
        listener.onSystemCallReady(true)
        if (activeWanted) makeActive(c)
    }

    private fun makeActive(c: CallControlScope) {
        if (incoming && answeredBySystem) return
        val gen = callGen
        scope.launch {
            val result = runCatching {
                if (incoming) c.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) else c.setActive()
            }.getOrNull()
            if (gen == callGen && result !is CallControlResult.Success) Log.w(TAG, "couldn't mark the call active: $result")
        }
    }

    private fun onEndpoints(current: CallEndpointCompat, available: List<CallEndpointCompat>) {
        endpoints = available.associateBy { it.identifier.toString() }
        listener.onSystemRoutes(routeOf(current), available.mapNotNull(::routeOf))
    }

    private suspend fun onMain(gen: Int, block: () -> Unit) {
        withContext(Dispatchers.Main.immediate) { if (gen == callGen) block() }
    }

    private fun register(): Boolean {
        if (registered) return true
        return try {
            callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            registered = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't register with the calling system", e)
            false
        }
    }

    companion object {
        private const val TAG = "SystemCall"
        const val REGISTER_TIMEOUT_MS = 2_000L
        private const val DISCONNECT_TIMEOUT_MS = 2_000L

        /** Whether this phone has Android's calling system at all (some tablets don't). */
        @Suppress("DEPRECATION")
        fun isSupported(context: Context): Boolean {
            val feature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageManager.FEATURE_TELECOM
            } else {
                PackageManager.FEATURE_CONNECTION_SERVICE
            }
            return context.packageManager.hasSystemFeature(feature)
        }

        fun routeOf(endpoint: CallEndpointCompat): AudioRoute? {
            val type = when (endpoint.type) {
                CallEndpointCompat.TYPE_EARPIECE -> RouteType.EARPIECE
                CallEndpointCompat.TYPE_SPEAKER -> RouteType.SPEAKER
                CallEndpointCompat.TYPE_BLUETOOTH -> RouteType.BLUETOOTH
                CallEndpointCompat.TYPE_WIRED_HEADSET -> RouteType.WIRED
                else -> return null
            }
            val name = when (type) {
                RouteType.EARPIECE -> "Phone"
                RouteType.SPEAKER -> "Speaker"
                RouteType.WIRED -> "Headset"
                RouteType.BLUETOOTH -> endpoint.name.toString().ifBlank { "Bluetooth" }
            }
            return AudioRoute(type, name, endpoint.identifier.toString())
        }

        private fun disconnectCause(reason: EndReason?): DisconnectCause = DisconnectCause(
            when (reason) {
                null, EndReason.HUNG_UP -> DisconnectCause.LOCAL
                EndReason.REMOTE_HUNG_UP, EndReason.REMOTE_DECLINED -> DisconnectCause.REMOTE
                EndReason.DECLINED -> DisconnectCause.REJECTED
                EndReason.BUSY -> DisconnectCause.BUSY
                EndReason.MISSED -> DisconnectCause.MISSED
                EndReason.NO_ANSWER, EndReason.CANCELLED -> DisconnectCause.CANCELED
                EndReason.CONNECTION_FAILED, EndReason.LINK_LOST -> DisconnectCause.ERROR
            },
        )
    }
}
