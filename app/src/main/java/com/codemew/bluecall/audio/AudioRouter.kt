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
import android.util.Log
import java.util.concurrent.Executor

enum class RouteType { EARPIECE, SPEAKER, BLUETOOTH, WIRED }

data class AudioRoute(val type: RouteType, val name: String)

/**
 * Owns the phone's audio setup for the length of a call only:
 * communication mode, audio focus, the output device and the proximity
 * screen-off lock while the earpiece is in use. [end] restores everything.
 *
 * Losing audio focus (for example to a cellular call) puts the call on hold.
 */
class AudioRouter(
    context: Context,
    private val onHoldChanged: (Boolean) -> Unit,
    private val onRouteChanged: (current: RouteType?, available: List<AudioRoute>) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { handler.post(it) }
    private var focusRequest: AudioFocusRequest? = null
    private var proximityLock: PowerManager.WakeLock? = null
    private var userChoice: RouteType? = null
    private var active = false

    var current: RouteType? = null
        private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onHoldChanged(true)
            AudioManager.AUDIOFOCUS_GAIN -> onHoldChanged(false)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = applyBestRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = applyBestRoute()
    }

    private val communicationListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        current = device?.let { routeOf(it)?.type }
        updateProximity()
        onRouteChanged(current, availableRoutes())
    }

    fun begin() {
        if (active) return
        active = true
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
        applyBestRoute()
    }

    fun end() {
        if (!active) return
        active = false
        releaseProximity()
        runCatching { audioManager.removeOnCommunicationDeviceChangedListener(communicationListener) }
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { audioManager.clearCommunicationDevice() }
        audioManager.mode = AudioManager.MODE_NORMAL
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        userChoice = null
        current = null
    }

    fun availableRoutes(): List<AudioRoute> =
        audioManager.availableCommunicationDevices.mapNotNull(::routeOf).distinctBy { it.type }

    fun select(type: RouteType) {
        userChoice = type
        applyRoute(type)
    }

    fun toggleSpeaker() {
        if (current == RouteType.SPEAKER) {
            val fallback = preferredNonSpeaker(availableRoutes().map { it.type })
            select(fallback ?: RouteType.EARPIECE)
        } else {
            select(RouteType.SPEAKER)
        }
    }

    private fun applyBestRoute() {
        if (!active) return
        val types = availableRoutes().map { it.type }
        val choice = userChoice?.takeIf { it in types } ?: preferredNonSpeaker(types) ?: RouteType.SPEAKER
        applyRoute(choice)
    }

    private fun preferredNonSpeaker(types: List<RouteType>): RouteType? = when {
        RouteType.BLUETOOTH in types -> RouteType.BLUETOOTH
        RouteType.WIRED in types -> RouteType.WIRED
        RouteType.EARPIECE in types -> RouteType.EARPIECE
        else -> null
    }

    private fun applyRoute(type: RouteType) {
        if (!active) return
        val device = audioManager.availableCommunicationDevices.firstOrNull { routeOf(it)?.type == type }
        if (device == null) {
            Log.w(TAG, "route $type not available")
            return
        }
        if (!audioManager.setCommunicationDevice(device)) Log.w(TAG, "setCommunicationDevice($type) refused")
        current = type
        updateProximity()
        onRouteChanged(current, availableRoutes())
    }

    /** Turns the screen off when the phone is held to the ear, only while using the earpiece. */
    private fun updateProximity() {
        if (active && current == RouteType.EARPIECE) acquireProximity() else releaseProximity()
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
        return AudioRoute(type, name)
    }

    private companion object {
        const val TAG = "AudioRouter"
        const val MAX_CALL_MS = 4L * 60 * 60 * 1000
    }
}
