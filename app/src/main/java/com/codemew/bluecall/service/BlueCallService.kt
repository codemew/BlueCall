package com.codemew.bluecall.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.codemew.bluecall.audio.AudioEngine
import com.codemew.bluecall.audio.AudioRoute
import com.codemew.bluecall.audio.AudioRouter
import com.codemew.bluecall.audio.RouteType
import com.codemew.bluecall.bluetooth.AdvertPayload
import com.codemew.bluecall.bluetooth.BleAdvertiser
import com.codemew.bluecall.bluetooth.BleScanner
import com.codemew.bluecall.bluetooth.BleSighting
import com.codemew.bluecall.bluetooth.BluetoothLink
import com.codemew.bluecall.bluetooth.L2capConnector
import com.codemew.bluecall.bluetooth.L2capServer
import com.codemew.bluecall.bluetooth.LinkTuner
import com.codemew.bluecall.call.CallEffect
import com.codemew.bluecall.call.CallEvent
import com.codemew.bluecall.call.CallState
import com.codemew.bluecall.call.CallStateMachine
import com.codemew.bluecall.call.NearbyPeer
import com.codemew.bluecall.call.PeerDirectory
import com.codemew.bluecall.call.PeerInfo
import com.codemew.bluecall.call.Transport
import com.codemew.bluecall.call.isCallInProgress
import com.codemew.bluecall.call.peer
import com.codemew.bluecall.call.withPeer
import com.codemew.bluecall.data.AppSettings
import com.codemew.bluecall.data.SettingsRepository
import com.codemew.bluecall.power.PowerPolicy
import com.codemew.bluecall.protocol.CallLink
import com.codemew.bluecall.protocol.CallSession
import com.codemew.bluecall.protocol.Message
import com.codemew.bluecall.protocol.PROTOCOL_VERSION
import com.codemew.bluecall.telecom.SystemCall
import com.codemew.bluecall.wifi.AwareConnector
import com.codemew.bluecall.wifi.AwarePayload
import com.codemew.bluecall.wifi.AwarePresence
import com.codemew.bluecall.wifi.AwareResponder
import com.codemew.bluecall.wifi.AwareSighting
import com.codemew.bluecall.wifi.AwareSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.random.Random

/**
 * BlueCall's only long-running component. It exists exactly while BlueCall is
 * online (open, or in a call) and owns everything that uses power: Bluetooth
 * advertising, scanning and the L2CAP server; Wi-Fi Aware publishing,
 * discovery and data links; the call session, audio and wakelocks.
 *
 * [shutdown] is the single exit path. It stops every one of those, unregisters
 * receivers, cancels the alarm and stops the service; on Exit it also ends the
 * process, so nothing at all is left to run or wake the phone.
 *
 * All methods run on the main thread.
 */
class BlueCallService : Service() {

    private enum class ShutdownReason { USER, TASK_REMOVED, IDLE, BLUETOOTH_OFF, ERROR, SYSTEM }

    /** Which end of a Wi-Fi Aware data link this phone is, while a Wi-Fi call is set up or running. */
    private enum class WifiRole { CALLER, CALLEE }

    private class PendingIncoming(val link: CallLink) {
        var hello: Message.Hello? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var notifications: CallNotifications
    private lateinit var power: PowerPolicy
    private lateinit var audioRouter: AudioRouter
    private lateinit var linkTuner: LinkTuner
    private lateinit var systemCall: SystemCall

    private var settings = AppSettings()
    private val sessionId = Random.nextInt()
    private var state: CallState = CallState.Offline

    // Bluetooth
    private var adapter: BluetoothAdapter? = null
    private var advertiser: BleAdvertiser? = null
    private var scanner: BleScanner? = null
    private var server: L2capServer? = null
    private var psm = 0
    private var bleSightings: List<BleSighting> = emptyList()
    private var dialBle: BleSighting? = null

    @Volatile
    private var dialSocket: BluetoothSocket? = null

    // Wi-Fi Aware
    private var aware: AwarePresence? = null
    private var awareSightings: List<AwareSighting> = emptyList()
    private var dialAware: AwareSighting? = null
    private var wifiRole: WifiRole? = null
    private var responder: AwareResponder? = null
    private var dialNonce = 0
    private var dialReply: CompletableDeferred<AwarePayload.Signal>? = null

    // Call
    @Volatile
    private var callSession: CallSession? = null

    @Volatile
    private var audioEngine: AudioEngine? = null

    private val pending = HashMap<CallSession, PendingIncoming>()
    private var dialJob: Job? = null
    private var callId = 0
    private var callFrameMs = 20
    private var peerFrameMs = 20
    private var peerDevice: BluetoothDevice? = null
    private var ringback: ToneGenerator? = null
    private var heldBySystem = false

    // Lifecycle
    private var micForeground = false
    private var shuttingDown = false
    private var exitRequested = false
    private var receiversRegistered = false
    private var lastActivityElapsed = 0L
    private var audioRestarts = 0
    private var statsPlayed = 0L
    private var statsConcealed = 0L

    private val ringTimeout = Runnable { dispatch(CallEvent.RingTimeout) }

    // ---------------------------------------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        BlueCall.controller = this
        settingsRepository = SettingsRepository(this)
        notifications = CallNotifications(this)
        power = PowerPolicy(this)
        linkTuner = LinkTuner(this)
        audioRouter = AudioRouter(
            this,
            onHoldChanged = ::onHoldChanged,
            onRouteChanged = { current, routes ->
                BlueCall.callUiFlow.update { it.copy(route = current, routes = routes) }
                refreshNotification()
            },
        )
        systemCall = SystemCall(this, systemCallListener)
        // Started from a visible activity, so the microphone type can be held for the whole
        // session: a call answered later from earphones or a car, with BlueCall in the
        // background, can then still use the microphone. It records only during calls.
        if (!startInForeground(notifications.online(currentName()), microphone = true)) {
            shutdown(exitApp = false, reason = ShutdownReason.ERROR)
            return
        }
        scope.launch { settingsRepository.settings.collect(::onSettings) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> goOnline()
            else -> if (state == CallState.Offline) shutdown(exitApp = false, reason = ShutdownReason.SYSTEM)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Swiping BlueCall away from Recents shuts it down completely. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown(exitApp = true, reason = ShutdownReason.TASK_REMOVED)
    }

    override fun onDestroy() {
        if (!shuttingDown) shutdown(exitApp = false, reason = ShutdownReason.SYSTEM)
        BlueCall.controller = null
        super.onDestroy()
        if (exitRequested) {
            // Everything has been released above. Ending the process guarantees that no
            // thread, timer or callback of any kind can survive the Exit button.
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, KILL_DELAY_MS)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Public controls (UI and notification buttons)
    // ---------------------------------------------------------------------------------------------

    fun dial(peer: NearbyPeer, transport: Transport) {
        if (state !is CallState.Idle) return
        when (transport) {
            Transport.BLUETOOTH -> dialBle = scanner?.peer(peer.sessionId)
            Transport.WIFI -> {
                dialAware = aware?.peer(peer.sessionId)
                wifiRole = WifiRole.CALLER
            }
        }
        promoteMicrophone()
        dispatch(CallEvent.Dial(peer.toPeerInfo(transport)))
    }

    /** Called from the visible call screen or Android's calling system, so the microphone may be started. */
    fun answer() {
        if (state !is CallState.Incoming) return
        promoteMicrophone()
        dispatch(CallEvent.Answer)
    }

    fun decline() {
        if (state is CallState.Incoming) dispatch(CallEvent.Decline)
    }

    fun hangUp() {
        if (state is CallState.Incoming) dispatch(CallEvent.Decline) else dispatch(CallEvent.LocalHangup)
    }

    fun toggleMute() {
        if (state !is CallState.Active) return
        setMuted(!BlueCall.callUi.value.muted)
    }

    fun toggleSpeaker() {
        if (state is CallState.Active || state is CallState.Ringing) audioRouter.toggleSpeaker()
    }

    fun selectRoute(route: AudioRoute) {
        if (state is CallState.Active || state is CallState.Ringing) audioRouter.select(route)
    }

    /** Takes the call off hold after Android's calling system held it (for example for a SIM call). */
    fun resume() {
        if (state is CallState.Active && heldBySystem) systemCall.resume()
    }

    fun exit() = shutdown(exitApp = true, reason = ShutdownReason.USER)

    /** Re-checks both radios, for example after a permission was granted in system settings. */
    fun refreshRadios() = updatePresence()

    fun onUiVisibilityChanged(visible: Boolean) {
        if (state == CallState.Offline || shuttingDown) return
        if (!visible) lastActivityElapsed = SystemClock.elapsedRealtime() else aware?.clearBackoff()
        updatePresence()
        updateIdleCheck()
    }

    // ---------------------------------------------------------------------------------------------
    // Going online / offline
    // ---------------------------------------------------------------------------------------------

    private fun goOnline() {
        if (shuttingDown) return
        if (state != CallState.Offline) {
            updatePresence()
            return
        }
        if (!AppPermissions.hasRequired(this)) {
            fail("BlueCall needs the Nearby devices and Microphone permissions")
            return
        }
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (AwareSupport.isSupported(this)) aware = AwarePresence(this, sessionId, awareListener)
        val bluetoothOn = adapter?.isEnabled == true
        if (!bluetoothOn && wifiStatus() != RadioStatus.ON) {
            fail(if (wifiConfigured()) "Bluetooth and Wi-Fi are off" else "Bluetooth is off")
            return
        }
        if (bluetoothOn && !startBluetooth() && wifiStatus() != RadioStatus.ON) return
        registerReceivers()
        lastActivityElapsed = SystemClock.elapsedRealtime()
        BlueCall.presenceFlow.value = PresenceState(online = true, name = currentName())
        dispatch(CallEvent.WentOnline)
    }

    /** Starts the Bluetooth side. False (with the reason shown) if it couldn't start. */
    private fun startBluetooth(): Boolean {
        val bt = adapter ?: return false
        if (server != null) return true
        val srv = L2capServer(
            bt,
            onAccepted = { socket ->
                // Runs on the accept thread: stay awake just long enough to read the call request.
                power.acquireBriefly(HANDSHAKE_TIMEOUT_MS + 2_000)
                main.post { onIncomingBluetooth(socket) }
            },
            onFailed = { main.post { onBluetoothFailed("Bluetooth stopped listening for calls") } },
        )
        psm = try {
            srv.start()
        } catch (e: IOException) {
            Log.e(TAG, "L2CAP listen failed", e)
            onBluetoothFailed("Couldn't start Bluetooth calling on this phone")
            return false
        } catch (e: SecurityException) {
            Log.e(TAG, "L2CAP listen not permitted", e)
            onBluetoothFailed("BlueCall needs the Nearby devices permission")
            return false
        }
        server = srv
        advertiser = BleAdvertiser(bt) { code ->
            main.post { BlueCall.presenceFlow.update { it.copy(advertising = false, error = "Couldn't make this phone visible ($code)") } }
        }
        scanner = BleScanner(
            bt,
            sessionId,
            onPeersChanged = {
                bleSightings = it
                publishPeers()
            },
            onFailure = { code -> BlueCall.presenceFlow.update { it.copy(error = "Couldn't search for phones ($code)") } },
        )
        return true
    }

    private fun stopBluetooth() {
        advertiser?.stop()
        scanner?.stop()
        server?.stop()
        advertiser = null
        scanner = null
        server = null
        if (bleSightings.isNotEmpty()) {
            bleSightings = emptyList()
            publishPeers()
        }
    }

    /** Bluetooth can't be used. Carry on over Wi-Fi if that's possible, otherwise go offline. */
    private fun onBluetoothFailed(message: String) {
        stopBluetooth()
        if (state == CallState.Offline && wifiStatus() != RadioStatus.ON || state != CallState.Offline && !wifiConfigured()) {
            fail(message)
        } else {
            BlueCall.presenceFlow.update { it.copy(error = message) }
            updatePresence()
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        shutdown(exitApp = false, reason = ShutdownReason.ERROR)
        BlueCall.presenceFlow.value = PresenceState(online = false, error = message)
    }

    /**
     * The single way out. Safe to call from any state and more than once.
     * [exitApp] also closes every activity and ends the process afterwards.
     */
    private fun shutdown(exitApp: Boolean, reason: ShutdownReason) {
        if (shuttingDown) return
        Log.i(TAG, "shutdown: $reason")
        shuttingDown = true
        exitRequested = exitApp

        // Let the state machine end any call properly (HANGUP to the other phone, etc.).
        dispatch(CallEvent.Shutdown)

        // Then make sure nothing survives, whatever state we were in.
        audioEngine?.stop()
        audioEngine = null
        stopRingback()
        audioRouter.end()
        systemCall.end(null)
        linkTuner.release()
        callSession?.flushAndClose()
        callSession = null
        pending.keys.toList().forEach { it.close() }
        pending.clear()
        dialSocket?.let { runCatching { it.close() } }
        dialSocket = null
        dialJob?.cancel()
        stopPresence()
        unregisterReceivers()
        main.removeCallbacksAndMessages(null)
        power.releaseAll()
        notifications.cancelIncoming()
        scope.cancel()

        BlueCall.peersFlow.value = emptyList()
        BlueCall.callUiFlow.value = CallUiState()
        BlueCall.callStateFlow.value = CallState.Offline
        BlueCall.presenceFlow.update { it.copy(online = false, advertising = false, scanning = false) }
        if (exitApp) BlueCall.signalExit()

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------------------------------------
    // State machine
    // ---------------------------------------------------------------------------------------------

    private fun dispatch(event: CallEvent) {
        val transition = CallStateMachine.reduce(state, event, System.currentTimeMillis())
        if (transition.state == state && transition.effects.isEmpty()) return
        val previous = state
        state = transition.state
        BlueCall.callStateFlow.value = state
        transition.effects.forEach(::execute)
        onStateChanged(previous)
    }

    private fun execute(effect: CallEffect) {
        when (effect) {
            CallEffect.StartPresence, CallEffect.PausePresence, CallEffect.ResumePresence -> updatePresence()
            CallEffect.StopPresence -> stopPresence()
            is CallEffect.Connect -> startDial(effect.peer)
            CallEffect.SendCallRequest -> callSession?.let {
                it.send(Message.Hello(PROTOCOL_VERSION, sessionId, currentName()))
                it.send(Message.CallRequest(callId, callFrameMs))
            }

            CallEffect.SendAccept -> callSession?.send(Message.CallAccept(callId, callFrameMs))
            is CallEffect.SendReject -> callSession?.send(
                Message.CallReject(callId, if (effect.busy) Message.REJECT_BUSY else Message.REJECT_DECLINED),
            )

            CallEffect.SendHangup -> callSession?.send(Message.Hangup(callId))
            CallEffect.CloseSession -> closeCallSession()
            CallEffect.StartIncomingRing -> state.peer?.let { notifications.showIncoming(it, RING_TIMEOUT_MS + 5_000) }
            CallEffect.StopIncomingRing -> notifications.cancelIncoming()
            CallEffect.StartRingback -> {
                audioRouter.begin()
                startRingback()
            }

            CallEffect.StopRingback -> stopRingback()
            CallEffect.ArmRingTimeout -> {
                main.removeCallbacks(ringTimeout)
                main.postDelayed(ringTimeout, RING_TIMEOUT_MS)
            }

            CallEffect.CancelRingTimeout -> main.removeCallbacks(ringTimeout)
            CallEffect.StartAudio -> startAudio()
            CallEffect.StopAudio -> stopAudio()
            CallEffect.AcquireCallLocks -> power.acquireCallLock(RING_TIMEOUT_MS + 15_000)
            CallEffect.ReleaseCallLocks -> {
                power.releaseCallLock()
                power.releaseBrief()
            }

            is CallEffect.PostMissedCall -> notifications.showMissed(effect.peer)
            CallEffect.StopService -> Unit // shutdown() does this after the effects have run
            is CallEffect.AddSystemCall -> addSystemCall(effect.incoming)
            CallEffect.SetSystemCallActive -> systemCall.setActive()
            is CallEffect.EndSystemCall -> {
                systemCall.end(effect.reason)
                heldBySystem = false
            }
        }
    }

    private fun onStateChanged(previous: CallState) {
        if (previous.isCallInProgress && !state.isCallInProgress) {
            lastActivityElapsed = SystemClock.elapsedRealtime()
            BlueCall.callUiFlow.value = CallUiState()
            audioRestarts = 0
            dialBle = null
            dialAware = null
            dialReply = null
            wifiRole = null
            peerDevice = null
            heldBySystem = false
            updatePresence()
        }
        refreshNotification()
        updateIdleCheck()
    }

    // ---------------------------------------------------------------------------------------------
    // Presence: advertising, scanning, listening, on both radios
    // ---------------------------------------------------------------------------------------------

    /**
     * Idle + on screen: Bluetooth advertising (balanced) and scanning; Wi-Fi Aware
     * publishing and passive discovery.
     * Idle + background/locked: low-power Bluetooth advertising and Wi-Fi Aware
     * publishing only; no scanning or discovery.
     * Calling: nothing, except the one Wi-Fi Aware session a Wi-Fi call runs over.
     */
    private fun updatePresence() {
        if (state == CallState.Offline || shuttingDown) return
        val idle = state is CallState.Idle
        val visible = BlueCall.uiVisible

        val adv = advertiser
        if (idle && server != null) {
            adv?.start(AdvertPayload.of(psm, sessionId), currentName(), lowPower = !visible)
        } else {
            adv?.stop()
        }
        if (idle && visible) scanner?.start() else scanner?.stop()

        val wifi = wifiStatus()
        aware?.let { a ->
            if (wifi == RadioStatus.ON) {
                val role = wifiRole
                a.update(
                    publish = idle || role == WifiRole.CALLEE,
                    subscribe = (idle && visible) || role == WifiRole.CALLER,
                    displayName = currentName(),
                    inCall = role != null,
                )
            } else {
                a.stop()
                responder?.cancel()
                responder = null
            }
        }

        BlueCall.presenceFlow.update {
            it.copy(
                online = true,
                name = currentName(),
                advertising = adv?.isAdvertising == true,
                scanning = idle && visible && (scanner != null || wifi == RadioStatus.ON),
                bluetooth = bluetoothStatus(),
                wifi = wifi,
            )
        }
    }

    private fun stopPresence() {
        stopBluetooth()
        aware?.stop()
        responder?.cancel()
        responder = null
        bleSightings = emptyList()
        awareSightings = emptyList()
        BlueCall.peersFlow.value = emptyList()
    }

    private fun publishPeers() {
        BlueCall.peersFlow.value = PeerDirectory.merge(
            sessionId,
            bleSightings.map { it.toSighting() },
            awareSightings.map { it.toSighting() },
        )
    }

    private fun bluetoothStatus(): RadioStatus = when {
        adapter == null -> RadioStatus.UNSUPPORTED
        adapter?.isEnabled != true -> RadioStatus.OFF
        server == null -> RadioStatus.UNAVAILABLE
        else -> RadioStatus.ON
    }

    /** Wi-Fi calling is possible on this phone in principle (it may still be off or paused right now). */
    private fun wifiConfigured(): Boolean =
        settings.wifiCalling && AwareSupport.isSupported(this) && AwareSupport.hasPermission(this)

    private fun wifiStatus(): RadioStatus = when {
        !AwareSupport.isSupported(this) -> RadioStatus.UNSUPPORTED
        !settings.wifiCalling -> RadioStatus.DISABLED
        !AwareSupport.hasPermission(this) -> RadioStatus.NO_PERMISSION
        getSystemService(WifiManager::class.java)?.isWifiEnabled != true -> RadioStatus.OFF
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            getSystemService(LocationManager::class.java)?.isLocationEnabled != true -> RadioStatus.NEEDS_LOCATION
        !AwareSupport.isAvailable(this) -> RadioStatus.UNAVAILABLE
        else -> RadioStatus.ON
    }

    // ---------------------------------------------------------------------------------------------
    // Call setup
    // ---------------------------------------------------------------------------------------------

    private fun startDial(peer: PeerInfo) {
        callFrameMs = settings.frameMs
        callId = Random.nextInt()
        when (peer.transport) {
            Transport.BLUETOOTH -> dialBluetooth(peer)
            Transport.WIFI -> dialWifi(peer)
        }
    }

    private fun dialBluetooth(peer: PeerInfo) {
        val target = scanner?.peer(peer.sessionId) ?: dialBle?.takeIf { it.sessionId == peer.sessionId }
        if (target == null) {
            main.post { dispatch(CallEvent.DialFailed) }
            return
        }
        peerDevice = target.device
        dialJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BluetoothLink(L2capConnector.connect(target.device, target.psm) { dialSocket = it }) }
            }
            dialSocket = null
            onDialResult(peer, result.getOrNull(), result.exceptionOrNull())
        }
    }

    private fun dialWifi(peer: PeerInfo) {
        val target = aware?.peer(peer.sessionId) ?: dialAware?.takeIf { it.sessionId == peer.sessionId }
        if (target == null || aware?.subscriber == null) {
            main.post { dispatch(CallEvent.DialFailed) }
            return
        }
        val passphrase = AwarePayload.newPassphrase()
        val nonce = Random.nextInt()
        dialNonce = nonce
        dialJob = scope.launch {
            var busy = false
            var error: Throwable? = null
            val link = withTimeoutOrNull(WIFI_DIAL_TIMEOUT_MS) {
                when (askToConnect(peer.sessionId, target.peer, nonce, passphrase)) {
                    is AwarePayload.Signal.Busy -> {
                        busy = true
                        null
                    }

                    is AwarePayload.Signal.Ready -> {
                        val subscription = aware?.subscriber
                        val handle = aware?.peer(peer.sessionId)?.peer ?: target.peer
                        subscription?.let { connectWifi(it, handle, passphrase) { e -> error = e } }
                    }

                    else -> null
                }
            }
            dialReply = null
            if (busy) {
                if (isDialing(peer)) dispatch(CallEvent.RemoteRejected(busy = true))
                return@launch
            }
            onDialResult(peer, link, error)
        }
    }

    private suspend fun connectWifi(
        subscription: SubscribeDiscoverySession,
        peer: PeerHandle,
        passphrase: String,
        onError: (Throwable) -> Unit,
    ): CallLink? = try {
        AwareConnector.connect(this, subscription, peer, passphrase)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
        null
    }

    /**
     * Sends CONNECT (up to three times) and waits for READY or BUSY. The peer is
     * looked up again each time: on Android 12 discovery restarts now and then,
     * which gives the other phone a new handle.
     */
    private suspend fun askToConnect(
        peerSessionId: Int,
        fallback: PeerHandle,
        nonce: Int,
        passphrase: String,
    ): AwarePayload.Signal? {
        val reply = CompletableDeferred<AwarePayload.Signal>()
        dialReply = reply
        repeat(CONNECT_ATTEMPTS) {
            val peer = aware?.peer(peerSessionId)?.peer ?: fallback
            aware?.send(viaPublish = false, peer, AwarePayload.Signal.Connect(sessionId, nonce, passphrase))
            withTimeoutOrNull(CONNECT_REPLY_TIMEOUT_MS) { reply.await() }?.let { return it }
        }
        return null
    }

    private fun isDialing(peer: PeerInfo): Boolean {
        val current = state
        return current is CallState.Dialing && current.peer.sessionId == peer.sessionId
    }

    private fun onDialResult(peer: PeerInfo, link: CallLink?, error: Throwable?) {
        if (!isDialing(peer)) {
            link?.close()
            return
        }
        if (link == null) {
            Log.w(TAG, "dial over ${peer.transport} failed", error)
            dispatch(CallEvent.DialFailed)
            return
        }
        val session = CallSession(link.input, link.output, link, sessionListener)
        callSession = session
        session.start()
        dispatch(CallEvent.DialConnected)
    }

    private fun onIncomingBluetooth(socket: BluetoothSocket) {
        val link = try {
            BluetoothLink(socket)
        } catch (e: IOException) {
            runCatching { socket.close() }
            power.releaseBrief()
            return
        }
        onIncomingLink(link)
    }

    private fun onIncomingLink(link: CallLink) {
        if (state == CallState.Offline || shuttingDown) {
            link.close()
            power.releaseBrief()
            return
        }
        val session = CallSession(link.input, link.output, link, sessionListener)
        pending[session] = PendingIncoming(link)
        session.start()
        main.postDelayed({
            if (pending.remove(session) != null) {
                closeAsync(session, flush = false)
                power.releaseBrief()
                if (link.transport == Transport.WIFI && state is CallState.Idle) wifiRole = null
                updatePresence()
            }
        }, HANDSHAKE_TIMEOUT_MS)
    }

    private val sessionListener = object : CallSession.Listener {
        override fun onMessage(session: CallSession, message: Message) {
            if (message is Message.Audio) {
                // Straight from the reader thread to the jitter buffer: never wait on the UI thread.
                if (session === callSession) audioEngine?.onPacket(message.seq, message.payload)
                return
            }
            main.post { handleMessage(session, message) }
        }

        override fun onRtt(session: CallSession, rttMs: Long) {
            main.post { if (session === callSession) updateStats(rttMs) }
        }

        override fun onClosed(session: CallSession, error: Throwable?) {
            main.post { handleClosed(session, error) }
        }
    }

    private fun handleMessage(session: CallSession, message: Message) {
        val incoming = pending[session]
        if (incoming != null) {
            when (message) {
                is Message.Hello -> incoming.hello = message
                is Message.CallRequest -> {
                    pending.remove(session)
                    val hello = incoming.hello
                    val transport = incoming.link.transport
                    val peer = PeerInfo(hello?.sessionId ?: 0, hello?.name?.ifBlank { null } ?: "Nearby phone", transport)
                    if (state !is CallState.Idle) {
                        session.send(Message.CallReject(message.callId, Message.REJECT_BUSY))
                        closeAsync(session, flush = true)
                        power.releaseBrief()
                        return
                    }
                    callSession = session
                    callId = message.callId
                    callFrameMs = settings.frameMs
                    peerFrameMs = message.frameMs.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)
                    peerDevice = (incoming.link as? BluetoothLink)?.device
                    if (transport == Transport.WIFI) wifiRole = WifiRole.CALLEE
                    session.send(Message.Hello(PROTOCOL_VERSION, sessionId, currentName()))
                    dispatch(CallEvent.IncomingCall(peer))
                    power.releaseBrief()
                }

                else -> Unit
            }
            return
        }
        if (session !== callSession) return
        when (message) {
            is Message.Hello -> updatePeerName(message.name)
            is Message.CallAccept -> if (message.callId == callId) {
                peerFrameMs = message.frameMs.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)
                dispatch(CallEvent.RemoteAccepted)
            }

            is Message.CallReject ->
                if (message.callId == callId) dispatch(CallEvent.RemoteRejected(message.reason == Message.REJECT_BUSY))

            is Message.Hangup -> if (message.callId == callId) dispatch(CallEvent.RemoteHangup)
            is Message.MuteState -> BlueCall.callUiFlow.update { it.copy(peerMuted = message.muted) }
            is Message.HoldState -> BlueCall.callUiFlow.update { it.copy(peerOnHold = message.onHold) }
            else -> Unit
        }
    }

    private fun handleClosed(session: CallSession, error: Throwable?) {
        if (pending.remove(session) != null) return
        if (session !== callSession) return
        Log.i(TAG, "session closed: ${error?.message ?: "remote closed"}")
        callSession = null
        dispatch(CallEvent.LinkLost)
    }

    private fun updatePeerName(name: String) {
        val current = state.peer ?: return
        if (name.isBlank() || name == current.name) return
        state = state.withPeer(current.copy(name = name))
        BlueCall.callStateFlow.value = state
        refreshNotification()
    }

    private fun closeCallSession() {
        val session = callSession
        callSession = null
        dialSocket?.let { runCatching { it.close() } }
        dialSocket = null
        dialJob?.cancel()
        dialJob = null
        linkTuner.release()
        if (session != null) closeAsync(session, flush = true)
    }

    private fun closeAsync(session: CallSession, flush: Boolean) {
        val close = { if (flush) session.flushAndClose() else session.close() }
        if (shuttingDown) close() else Thread(close, "BlueCall-close").apply { isDaemon = true; start() }
    }

    // ---------------------------------------------------------------------------------------------
    // Wi-Fi Aware discovery messages
    // ---------------------------------------------------------------------------------------------

    private val awareListener = object : AwarePresence.Listener {
        override fun onPeersChanged(peers: List<AwareSighting>) {
            awareSightings = peers
            publishPeers()
        }

        override fun onSignal(viaPublish: Boolean, peer: PeerHandle, signal: AwarePayload.Signal) {
            when (signal) {
                is AwarePayload.Signal.Connect -> if (viaPublish) onConnectRequest(peer, signal)
                is AwarePayload.Signal.Ready -> if (!viaPublish && signal.nonce == dialNonce) dialReply?.complete(signal)
                is AwarePayload.Signal.Busy -> if (!viaPublish && signal.nonce == dialNonce) dialReply?.complete(signal)
            }
        }

        override fun onMessageSent(messageId: Int, success: Boolean) {
            if (!success) Log.d(TAG, "Wi-Fi Aware message $messageId not delivered")
        }

        override fun onAwareLost() {
            awareSightings = emptyList()
            publishPeers()
            responder?.cancel()
            responder = null
            updatePresence()
        }
    }

    /** Another phone wants to call this one over Wi-Fi. */
    private fun onConnectRequest(peer: PeerHandle, request: AwarePayload.Signal.Connect) {
        val a = aware ?: return
        var existing = responder
        if (existing != null && existing.nonce == request.nonce) {
            // A repeated request: the READY may have been lost.
            a.send(viaPublish = true, peer, AwarePayload.Signal.Ready(request.nonce))
            return
        }
        if (existing != null && existing.callerSessionId == request.sessionId && state is CallState.Idle) {
            // The same phone trying again after a failed attempt: drop the stale one.
            existing.cancel()
            responder = null
            existing = null
        }
        val publication = a.publisher
        if (state !is CallState.Idle || existing != null || publication == null || shuttingDown) {
            a.send(viaPublish = true, peer, AwarePayload.Signal.Busy(request.nonce))
            return
        }
        power.acquireBriefly(WIFI_DIAL_TIMEOUT_MS + 2_000)
        var created: AwareResponder? = null
        val r = AwareResponder(
            this,
            publication,
            peer,
            request.sessionId,
            request.nonce,
            request.passphrase,
            onAccepted = { link ->
                main.post {
                    if (responder === created) responder = null
                    onIncomingLink(link)
                }
            },
            onFailed = {
                main.post {
                    if (responder === created) {
                        responder = null
                        if (state is CallState.Idle) wifiRole = null
                        power.releaseBrief()
                        updatePresence()
                    }
                }
            },
        )
        created = r
        if (!r.start()) {
            power.releaseBrief()
            a.send(viaPublish = true, peer, AwarePayload.Signal.Busy(request.nonce))
            return
        }
        responder = r
        wifiRole = WifiRole.CALLEE
        a.send(viaPublish = true, peer, AwarePayload.Signal.Ready(request.nonce))
    }

    // ---------------------------------------------------------------------------------------------
    // Android's calling system
    // ---------------------------------------------------------------------------------------------

    private fun addSystemCall(incoming: Boolean) {
        val peer = state.peer ?: return
        val use = settings.systemCalling && SystemCall.isSupported(this)
        audioRouter.expectSystem(use)
        if (use) systemCall.add(peer, incoming)
    }

    private val systemCallListener = object : SystemCall.Listener {
        override fun onSystemCallReady(ready: Boolean) {
            if (!state.isCallInProgress) return
            if (ready) audioRouter.onSystemReady(systemCall::requestRoute) else audioRouter.onSystemUnavailable()
        }

        override fun onSystemAnswer() = answer()

        override fun onSystemDisconnect() {
            when (state) {
                is CallState.Incoming -> dispatch(CallEvent.Decline)
                is CallState.Dialing, is CallState.Ringing, is CallState.Active -> dispatch(CallEvent.LocalHangup)
                else -> Unit
            }
        }

        override fun onSystemHold(onHold: Boolean) {
            if (state !is CallState.Active) return
            heldBySystem = onHold
            onHoldChanged(onHold)
            BlueCall.callUiFlow.update { it.copy(canResume = onHold) }
            refreshNotification()
        }

        override fun onSystemMuted(muted: Boolean) {
            if (state is CallState.Active && muted != BlueCall.callUi.value.muted) setMuted(muted)
        }

        override fun onSystemRoutes(current: AudioRoute?, available: List<AudioRoute>) {
            audioRouter.onSystemRoutes(current, available)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Audio
    // ---------------------------------------------------------------------------------------------

    private fun newEngine(): AudioEngine {
        val wifi = state.peer?.transport == Transport.WIFI
        val ui = BlueCall.callUi.value
        return AudioEngine(
            frameMs = callFrameMs,
            peerFrameMs = peerFrameMs,
            noise = settings.noise,
            sendPacket = { seq, data, length -> callSession?.sendAudio(seq, data, length) },
            onError = { message -> main.post { onAudioError(message) } },
            bitrate = if (wifi) AudioEngine.WIFI_BITRATE else AudioEngine.BITRATE,
        ).apply {
            muted = ui.muted
            onHold = ui.onHold
        }
    }

    private fun startAudio() {
        if (!AppPermissions.granted(this, Manifest.permission.RECORD_AUDIO)) {
            BlueCall.callUiFlow.update { it.copy(error = "Microphone permission is off") }
        }
        audioRouter.begin()
        val engine = newEngine()
        audioEngine = if (engine.start(settings.noise)) engine else null
        power.acquireCallLock(MAX_CALL_MS)
        peerDevice?.let { linkTuner.tune(it) }
        statsPlayed = 0
        statsConcealed = 0
    }

    private fun stopAudio() {
        audioEngine?.stop()
        audioEngine = null
        stopRingback()
        audioRouter.end()
        linkTuner.release()
    }

    private fun onAudioError(message: String) {
        if (state !is CallState.Active) return
        Log.w(TAG, "audio error: $message")
        if (audioRestarts < MAX_AUDIO_RESTARTS) {
            audioRestarts++
            audioEngine?.stop()
            val engine = newEngine()
            audioEngine = if (engine.start(settings.noise)) engine else null
        } else {
            BlueCall.callUiFlow.update { it.copy(error = message) }
        }
    }

    private fun setMuted(muted: Boolean) {
        BlueCall.callUiFlow.update { it.copy(muted = muted) }
        audioEngine?.muted = muted
        if (!muted && audioRouter.systemOwnsAudio) audioRouter.clearGlobalMicMute()
        callSession?.send(Message.MuteState(muted))
        refreshNotification()
    }

    private fun onHoldChanged(onHold: Boolean) {
        if (state !is CallState.Active) return
        if (BlueCall.callUi.value.onHold == onHold) return
        audioEngine?.onHold = onHold
        BlueCall.callUiFlow.update { it.copy(onHold = onHold, canResume = onHold && heldBySystem) }
        callSession?.send(Message.HoldState(onHold))
        refreshNotification()
    }

    private fun updateStats(rttMs: Long) {
        val stats = audioEngine?.stats()
        var quality = LinkQuality.UNKNOWN
        var latency: Int? = null
        if (stats != null) {
            val played = stats.played - statsPlayed
            val concealed = stats.concealed - statsConcealed
            statsPlayed = stats.played
            statsConcealed = stats.concealed
            val total = played + concealed
            if (total > 0) {
                val lossRatio = concealed.toDouble() / total
                quality = when {
                    lossRatio < 0.02 -> LinkQuality.EXCELLENT
                    lossRatio < 0.08 -> LinkQuality.GOOD
                    else -> LinkQuality.POOR
                }
            }
            latency = (rttMs / 2).toInt() + stats.localDelayMs + DEVICE_AUDIO_PATH_MS
        }
        BlueCall.callUiFlow.update { it.copy(rttMs = rttMs, latencyMs = latency, linkQuality = quality) }
    }

    private fun startRingback() {
        if (ringback != null) return
        ringback = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "ringback unavailable", e)
            null
        }
    }

    private fun stopRingback() {
        ringback?.let {
            runCatching { it.stopTone() }
            runCatching { it.release() }
        }
        ringback = null
    }

    // ---------------------------------------------------------------------------------------------
    // Foreground service, notification, receivers, idle timer
    // ---------------------------------------------------------------------------------------------

    private fun startInForeground(notification: Notification, microphone: Boolean): Boolean {
        val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val type = if (microphone) connected or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else connected
        return try {
            ServiceCompat.startForeground(this, CallNotifications.ID_SERVICE, notification, type)
            micForeground = microphone
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(mic=$microphone) failed", e)
            if (!microphone) return false
            // Couldn't add the microphone type (e.g. the permission is off):
            // run as a connected-device service and try again when a call starts.
            runCatching { ServiceCompat.startForeground(this, CallNotifications.ID_SERVICE, notification, connected) }
            micForeground = false
            true
        }
    }

    private fun promoteMicrophone() {
        if (!micForeground) startInForeground(serviceNotification() ?: notifications.online(currentName()), microphone = true)
    }

    private fun serviceNotification(): Notification? {
        val ui = BlueCall.callUi.value
        return when (val s = state) {
            CallState.Offline -> null
            is CallState.Idle, is CallState.Incoming -> notifications.online(currentName())
            is CallState.Dialing -> notifications.outgoing(s.peer, ringing = false)
            is CallState.Ringing -> notifications.outgoing(s.peer, ringing = true)
            is CallState.Active -> notifications.ongoing(
                s.peer, s.connectedAtMs, ui.muted, ui.route?.type == RouteType.SPEAKER, ui.onHold, ui.canResume,
            )
        }
    }

    private fun refreshNotification() {
        if (shuttingDown) return
        serviceNotification()?.let { notifications.updateService(it) }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                        BluetoothAdapter.STATE_ON -> onBluetoothOn()
                    }
                }

                WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED, LocationManager.MODE_CHANGED_ACTION -> {
                    aware?.clearBackoff()
                    updatePresence()
                }
                Intent.ACTION_SCREEN_ON -> checkIdle()
            }
        }
    }

    private fun onBluetoothOff() {
        if (shuttingDown || state == CallState.Offline) return
        stopBluetooth()
        if (!wifiConfigured()) {
            shutdown(exitApp = false, reason = ShutdownReason.BLUETOOTH_OFF)
            BlueCall.presenceFlow.value = PresenceState(online = false, error = "Bluetooth was turned off")
        } else {
            updatePresence()
        }
    }

    private fun onBluetoothOn() {
        if (shuttingDown || state == CallState.Offline || server != null) return
        if (startBluetooth()) BlueCall.presenceFlow.update { it.copy(error = null) }
        updatePresence()
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            if (aware != null) {
                addAction(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) addAction(LocationManager.MODE_CHANGED_ACTION)
            }
        }
        ContextCompat.registerReceiver(this, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        runCatching { unregisterReceiver(systemReceiver) }
        receiversRegistered = false
    }

    /**
     * Arms the auto-offline check while idle in the background. It uses a
     * non-wakeup alarm, plus a check whenever the screen turns on, so BlueCall
     * never wakes a sleeping phone just to turn itself off.
     */
    private fun updateIdleCheck() {
        if (shuttingDown) return
        val minutes = settings.autoOfflineMinutes
        if (state is CallState.Idle && !BlueCall.uiVisible && minutes > 0) {
            power.scheduleIdleCheck(lastActivityElapsed + minutes * 60_000L) { checkIdle() }
        } else {
            power.cancelIdleCheck()
        }
    }

    private fun checkIdle() {
        val minutes = settings.autoOfflineMinutes
        if (shuttingDown || state !is CallState.Idle || BlueCall.uiVisible || minutes <= 0) return
        if (SystemClock.elapsedRealtime() - lastActivityElapsed >= minutes * 60_000L) {
            notifications.showWentOffline(minutes)
            shutdown(exitApp = true, reason = ShutdownReason.IDLE)
        } else {
            updateIdleCheck()
        }
    }

    private fun onSettings(new: AppSettings) {
        val old = settings
        settings = new
        if (old.noise != new.noise) audioEngine?.updateNoise(new.noise)
        if (old.displayName != new.displayName || old.wifiCalling != new.wifiCalling) {
            if (old.wifiCalling != new.wifiCalling) aware?.clearBackoff()
            updatePresence()
            refreshNotification()
        }
        if (old.wifiCalling && !new.wifiCalling && state != CallState.Offline && server == null && !shuttingDown) {
            // Wi-Fi calling switched off while Bluetooth is off: nothing left to be reachable on.
            fail("Bluetooth is off")
            return
        }
        if (old.autoOfflineMinutes != new.autoOfflineMinutes) updateIdleCheck()
    }

    private fun currentName(): String = settingsRepository.effectiveName(settings)

    companion object {
        const val ACTION_START = "com.codemew.bluecall.action.START"
        private const val TAG = "BlueCallService"
        private const val RING_TIMEOUT_MS = 45_000L
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val WIFI_DIAL_TIMEOUT_MS = 15_000L
        private const val CONNECT_ATTEMPTS = 3
        private const val CONNECT_REPLY_TIMEOUT_MS = 1_500L
        private const val MAX_CALL_MS = 4L * 60 * 60 * 1000
        private const val KILL_DELAY_MS = 400L
        private const val MAX_AUDIO_RESTARTS = 3
        private const val MIN_FRAME_MS = 10
        private const val MAX_FRAME_MS = 60

        /** Rough fixed audio-path delay of the two phones' audio hardware, for the latency readout. */
        private const val DEVICE_AUDIO_PATH_MS = 25

        /** Goes online. Call only from a visible activity. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BlueCallService::class.java).setAction(ACTION_START),
            )
        }
    }
}
