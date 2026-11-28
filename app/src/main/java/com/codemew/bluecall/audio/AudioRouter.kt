package com.codemew.bluecall.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor

/**
 * Owns where call audio goes, for the length of a call only, in one of two modes:
 *
 * - **System**: the call is registered with Android's calling system, which
 *   sets the audio mode and focus and switches devices (earphones connecting
 *   take the call by themselves). BlueCall follows what it reports and asks
 *   it to switch.
 * - **App**: no calling system for this call. BlueCall sets communication mode
 *   and focus itself and picks the communication device, retrying for
 *   earphones whose call profile shows up a moment after they connect. Losing
 *   focus (for example to a SIM call) puts the call on hold.
 *
 * Both modes use [RoutePolicy], so earphones that connect mid-call take over
 * from the speaker or earpiece and hand back when they leave. The proximity
 * screen-off lock is held while the earpiece is in use. [end] restores everything.
 *
 * Main thread only.
 */
class AudioRouter(
    context: Context,
    private val onHoldChanged: (Boolean) -> Unit,
    private val onRouteChanged: (current: AudioRoute?, available: List<AudioRoute>) -> Unit,
) {
    private enum class Mode { WAITING_FOR_SYSTEM, SYSTEM, APP }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { handler.post(it) }
    private val policy = RoutePolicy()

    private var focusRequest: AudioFocusRequest? = null
    private var proximityLock: PowerManager.WakeLock? = null
    private var active = false
    private var appStarted = false
    private var mode = Mode.APP
    private var systemExpected = false
    private var systemReady = false
    private var requestSystemRoute: ((AudioRoute) -> Unit)? = null
    private var systemCurrent: AudioRoute? = null
    private var systemAvailable: List<AudioRoute> = emptyList()
    private var pendingTarget: AudioRoute? = null
    private var appTarget: AudioRoute? = null
    private var appAttempts = 0

    var current: AudioRoute? = null
        private set

    var available: List<AudioRoute> = emptyList()
        private set

    /** True while Android's calling system owns this call's audio. */
    val systemOwnsAudio: Boolean get() = active && mode == Mode.SYSTEM

    // ---------------------------------------------------------------------------------------------
    // Call lifecycle
    // ---------------------------------------------------------------------------------------------

    /** Before [begin]: whether Android's calling system is expected to own this call's audio. */
    fun expectSystem(expected: Boolean) {
        systemExpected = expected
        systemReady = false
        requestSystemRoute = null
        if (active && !expected && mode == Mode.WAITING_FOR_SYSTEM) startApp()
    }

    /** The calling system registered the call: from now on it owns mode, focus and routing. */
    fun onSystemReady(requestRoute: (AudioRoute) -> Unit) {
        systemReady = true
        requestSystemRoute = requestRoute
        if (active && mode != Mode.SYSTEM) {
            if (appStarted) stopApp()
            startSystem()
        }
    }

    /** No calling system for this call after all: BlueCall routes audio itself. */
    fun onSystemUnavailable() {
        systemExpected = false
        systemReady = false
        requestSystemRoute = null
        if (active && mode != Mode.APP) {
            cancelPending()
            startApp()
        }
    }

    /** What the calling system reports. Kept even before [begin], so routing starts from it. */
    fun onSystemRoutes(current: AudioRoute?, available: List<AudioRoute>) {
        val changed = available.map { it.id } != systemAvailable.map { it.id }
        systemCurrent = current
        systemAvailable = available
        if (!active || mode != Mode.SYSTEM) return
        val now = SystemClock.elapsedRealtime()
        policy.onRouteInUse(current, now)
        pendingTarget?.let { if (it.id == current?.id) cancelPending() }
        if (changed) policy.onAvailableChanged(available, current, now)?.let(::requestLater)
        report(current, available)
    }

    fun begin() {
        if (active) return
        active = true
        when {
            systemReady -> startSystem()
            systemExpected -> mode = Mode.WAITING_FOR_SYSTEM
            else -> startApp()
        }
    }

    fun end() {
        if (!active) return
        active = false
        cancelPending()
        releaseProximity()
        if (appStarted) stopApp()
        mode = Mode.APP
        policy.reset()
        systemExpected = false
        systemReady = false
        requestSystemRoute = null
        systemCurrent = null
        systemAvailable = emptyList()
        current = null
        available = emptyList()
    }

    // ---------------------------------------------------------------------------------------------
    // User controls
    // ---------------------------------------------------------------------------------------------

    fun select(route: AudioRoute) {
        if (!active) return
        policy.onUserSelected(route, current)
        cancelPending()
        when (mode) {
            Mode.SYSTEM -> requestSystemRoute?.invoke(route)
            Mode.APP -> applyApp(route)
            Mode.WAITING_FOR_SYSTEM -> Unit
        }
    }

    fun select(type: RouteType) {
        available.firstOrNull { it.type == type }?.let(::select)
    }

    fun toggleSpeaker() {
        if (current?.type == RouteType.SPEAKER) {
            policy.offSpeaker(available)?.let(::select)
        } else {
            select(RouteType.SPEAKER)
        }
    }

    /** A car kit or the system may have muted the microphone for every app; undo that when unmuting. */
    fun clearGlobalMicMute() {
        if (audioManager.isMicrophoneMute) runCatching { audioManager.isMicrophoneMute = false }
    }

    // ---------------------------------------------------------------------------------------------
    // System mode
    // ---------------------------------------------------------------------------------------------

    private fun startSystem() {
        mode = Mode.SYSTEM
        val start = policy.start(systemAvailable, systemCurrent)
        policy.onRouteInUse(systemCurrent, SystemClock.elapsedRealtime())
        if (start != null && start.id != systemCurrent?.id) requestSystemRoute?.invoke(start)
        report(systemCurrent, systemAvailable)
    }

    /** Give the system a moment to move to the new device itself before asking it to. */
    private fun requestLater(target: AudioRoute) {
        cancelPending()
        pendingTarget = target
        handler.postDelayed(pendingRequest, SYSTEM_GRACE_MS)
    }

    private val pendingRequest = Runnable {
        val target = pendingTarget ?: return@Runnable
        pendingTarget = null
        if (mode == Mode.SYSTEM && active && systemCurrent?.id != target.id && systemAvailable.any { it.id == target.id }) {
            requestSystemRoute?.invoke(target)
        }
    }

    private fun cancelPending() {
        pendingTarget = null
        handler.removeCallbacks(pendingRequest)
    }

    // ---------------------------------------------------------------------------------------------
    // App mode
    // ---------------------------------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onHoldChanged(true)
            AudioManager.AUDIOFOCUS_GAIN -> onHoldChanged(false)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onAppDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onAppDevicesChanged()
    }

    private val communicationListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (!active || mode != Mode.APP) return@OnCommunicationDeviceChangedListener
        val route = device?.let(::routeOf)
        policy.onRouteInUse(route, SystemClock.elapsedRealtime())
        report(route, appRoutes())
    }

    /** Re-reads the devices shortly after a change: a headset's call profile can appear late. */
    private val reevaluateApp = Runnable { evaluateApp() }

    private val verifyAppRoute = Runnable {
        val target = appTarget ?: return@Runnable
        if (!active || mode != Mode.APP) return@Runnable
        if (audioManager.communicationDevice?.let(::routeOf)?.id == target.id) {
            appTarget = null
        } else if (appAttempts < APP_ATTEMPTS) {
            applyApp(target, retry = true)
        } else {
            Log.w(TAG, "gave up switching to ${target.type}")
            appTarget = null
        }
    }

    private fun startApp() {
        mode = Mode.APP
        appStarted = true
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, communicationListener)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        val routes = appRoutes()
        val inUse = audioManager.communicationDevice?.let(::routeOf)
        val start = policy.start(routes, inUse)
        if (start != null) applyApp(start) else report(inUse, routes)
    }

    private fun stopApp() {
        appStarted = false
        appTarget = null
        handler.removeCallbacks(verifyAppRoute)
        handler.removeCallbacks(reevaluateApp)
        runCatching { audioManager.removeOnCommunicationDeviceChangedListener(communicationListener) }
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { audioManager.clearCommunicationDevice() }
        audioManager.mode = AudioManager.MODE_NORMAL
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun onAppDevicesChanged() {
        if (!active || mode != Mode.APP) return
        evaluateApp()
        handler.removeCallbacks(reevaluateApp)
        handler.postDelayed(reevaluateApp, APP_REEVALUATE_MS)
    }

    private fun evaluateApp() {
        if (!active || mode != Mode.APP) return
        val routes = appRoutes()
        val inUse = audioManager.communicationDevice?.let(::routeOf)
        if (routes.map { it.id } != available.map { it.id }) {
            val target = policy.onAvailableChanged(routes, inUse, SystemClock.elapsedRealtime())
            if (target != null) {
                applyApp(target)
                return
            }
        }
        report(inUse, routes)
    }

    private fun applyApp(route: AudioRoute, retry: Boolean = false) {
        if (!active || mode != Mode.APP) return
        if (!retry) appAttempts = 0
        appAttempts++
        val device = audioManager.availableCommunicationDevices.firstOrNull { routeOf(it)?.id == route.id }
        if (device == null) {
            Log.w(TAG, "route ${route.type} not available yet")
        } else if (!audioManager.setCommunicationDevice(device)) {
            Log.w(TAG, "setCommunicationDevice(${route.type}) refused")
        }
        appTarget = route
        handler.removeCallbacks(verifyAppRoute)
        handler.postDelayed(verifyAppRoute, APP_VERIFY_MS)
        report(audioManager.communicationDevice?.let(::routeOf), appRoutes())
    }

    private fun appRoutes(): List<AudioRoute> =
        audioManager.availableCommunicationDevices.mapNotNull(::routeOf).distinctBy { it.id }

    private fun routeOf(device: AudioDeviceInfo): AudioRoute? {
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteType.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RouteType.SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            -> RouteType.BLUETOOTH

            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> RouteType.WIRED

            else -> return null
        }
        val name = when (type) {
            RouteType.EARPIECE -> "Phone"
            RouteType.SPEAKER -> "Speaker"
            RouteType.BLUETOOTH -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            RouteType.WIRED -> "Headset"
        }
        // Bluetooth devices are told apart by address; built-in ones by kind.
        val id = when (type) {
            RouteType.BLUETOOTH -> "bt:" + device.address.ifBlank { device.id.toString() }
            else -> type.name
        }
        return AudioRoute(type, name, id)
    }

    // ---------------------------------------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------------------------------------

    private fun report(route: AudioRoute?, routes: List<AudioRoute>) {
        current = route
        available = routes
        updateProximity()
        onRouteChanged(route, routes)
    }

    /** Turns the screen off when the phone is held to the ear, only while using the earpiece. */
    private fun updateProximity() {
        if (active && current?.type == RouteType.EARPIECE) acquireProximity() else releaseProximity()
    }

    private fun acquireProximity() {
        if (proximityLock?.isHeld == true) return
        if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        val lock = proximityLock ?: powerManager
            .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "BlueCall:proximity")
            .also { it.setReferenceCounted(false); proximityLock = it }
        lock.acquire(MAX_CALL_MS)
    }

    private fun releaseProximity() {
        proximityLock?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
    }

    private companion object {
        const val TAG = "AudioRouter"
        const val MAX_CALL_MS = 4L * 60 * 60 * 1000
        const val SYSTEM_GRACE_MS = 1_500L
        const val APP_VERIFY_MS = 300L
        const val APP_REEVALUATE_MS = 600L
        const val APP_ATTEMPTS = 3
    }
}
