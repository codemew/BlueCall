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
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.codemew.bluecall.audio.AudioEngine
import com.codemew.bluecall.audio.AudioRouter
import com.codemew.bluecall.audio.RouteType
import com.codemew.bluecall.bluetooth.AdvertPayload
import com.codemew.bluecall.bluetooth.BleAdvertiser
import com.codemew.bluecall.bluetooth.BleScanner
import com.codemew.bluecall.bluetooth.L2capConnector
import com.codemew.bluecall.bluetooth.L2capServer
import com.codemew.bluecall.bluetooth.LinkTuner
import com.codemew.bluecall.bluetooth.NearbyPeer
import com.codemew.bluecall.call.CallEffect
import com.codemew.bluecall.call.CallEvent
import com.codemew.bluecall.call.CallState
import com.codemew.bluecall.call.CallStateMachine
import com.codemew.bluecall.call.PeerInfo
import com.codemew.bluecall.call.isCallInProgress
import com.codemew.bluecall.call.peer
import com.codemew.bluecall.call.withPeer
import com.codemew.bluecall.data.AppSettings
import com.codemew.bluecall.data.SettingsRepository
import com.codemew.bluecall.power.PowerPolicy
import com.codemew.bluecall.protocol.CallSession
import com.codemew.bluecall.protocol.Message
import com.codemew.bluecall.protocol.PROTOCOL_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.random.Random

/**
 * BlueCall's only long-running component. It exists exactly while BlueCall is
 * online (open, or in a call) and owns everything that uses power:
 * advertising, scanning, the L2CAP server, the call session, audio and
 * wakelocks.
 *
 * [shutdown] is the single exit path. It stops every one of those, unregisters
 * receivers, cancels the alarm and stops the service; on Exit it also ends the
 * process, so nothing at all is left to run or wake the phone.
 *
 * All methods run on the main thread.
 */
class BlueCallService : Service() {

    private enum class ShutdownReason { USER, TASK_REMOVED, IDLE, BLUETOOTH_OFF, ERROR, SYSTEM }

    private class PendingIncoming(val device: BluetoothDevice) {
        var hello: Message.Hello? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var notifications: CallNotifications
    private lateinit var power: PowerPolicy
    private lateinit var audioRouter: AudioRouter
    private lateinit var linkTuner: LinkTuner

    private var settings = AppSettings()
    private var adapter: BluetoothAdapter? = null
    private var advertiser: BleAdvertiser? = null
    private var scanner: BleScanner? = null
    private var server: L2capServer? = null
    private var psm = 0
    private val sessionId = Random.nextInt()

    private var state: CallState = CallState.Offline

    @Volatile
    private var callSession: CallSession? = null

    @Volatile
    private var audioEngine: AudioEngine? = null

    @Volatile
    private var dialSocket: BluetoothSocket? = null

    private val pending = HashMap<CallSession, PendingIncoming>()
    private var dialTarget: NearbyPeer? = null
    private var dialJob: Job? = null
    private var callId = 0
    private var callFrameMs = 20
    private var peerFrameMs = 20
    private var peerDevice: BluetoothDevice? = null
    private var ringback: ToneGenerator? = null
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
        if (!startInForeground(notifications.online(currentName()), microphone = false)) {
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

    fun dial(peer: NearbyPeer) {
        if (state !is CallState.Idle) return
        dialTarget = peer
        promoteMicrophone()
        dispatch(CallEvent.Dial(peer.toPeerInfo()))
    }

    /** Called from the visible call screen, so the microphone may be started. */
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
        val muted = !BlueCall.callUi.value.muted
        BlueCall.callUiFlow.update { it.copy(muted = muted) }
        audioEngine?.muted = muted
        callSession?.send(Message.MuteState(muted))
        refreshNotification()
    }

    fun toggleSpeaker() {
        if (state is CallState.Active || state is CallState.Ringing) audioRouter.toggleSpeaker()
    }

    fun selectRoute(type: RouteType) {
        if (state is CallState.Active || state is CallState.Ringing) audioRouter.select(type)
    }

    fun exit() = shutdown(exitApp = true, reason = ShutdownReason.USER)

    fun onUiVisibilityChanged(visible: Boolean) {
        if (state == CallState.Offline || shuttingDown) return
        if (!visible) lastActivityElapsed = SystemClock.elapsedRealtime()
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
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null || !bt.isEnabled) {
            fail("Bluetooth is off")
            return
        }
        adapter = bt
        val srv = L2capServer(
            bt,
            onAccepted = { socket ->
                // Runs on the accept thread: stay awake just long enough to read the call request.
                power.acquireBriefly(HANDSHAKE_TIMEOUT_MS + 2_000)
                main.post { onIncomingSocket(socket) }
            },
            onFailed = { main.post { fail("Bluetooth stopped listening for calls") } },
        )
        psm = try {
            srv.start()
        } catch (e: IOException) {
            Log.e(TAG, "L2CAP listen failed", e)
            fail("Couldn't start Bluetooth calling on this phone")
            return
        } catch (e: SecurityException) {
            Log.e(TAG, "L2CAP listen not permitted", e)
            fail("BlueCall needs the Nearby devices permission")
            return
        }
        server = srv
        advertiser = BleAdvertiser(bt) { code ->
            main.post { BlueCall.presenceFlow.update { it.copy(advertising = false, error = "Couldn't make this phone visible ($code)") } }
        }
        scanner = BleScanner(
            bt,
            sessionId,
            onPeersChanged = { BlueCall.peersFlow.value = it },
            onFailure = { code -> BlueCall.presenceFlow.update { it.copy(error = "Couldn't search for phones ($code)") } },
        )
        registerReceivers()
        lastActivityElapsed = SystemClock.elapsedRealtime()
        BlueCall.presenceFlow.value = PresenceState(online = true, name = currentName())
        dispatch(CallEvent.WentOnline)
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
                if (micForeground && !shuttingDown) startInForeground(notifications.online(currentName()), microphone = false)
            }

            is CallEffect.PostMissedCall -> notifications.showMissed(effect.peer)
            CallEffect.StopService -> Unit // shutdown() does this after the effects have run
        }
    }

    private fun onStateChanged(previous: CallState) {
        if (previous.isCallInProgress && !state.isCallInProgress) {
            lastActivityElapsed = SystemClock.elapsedRealtime()
            BlueCall.callUiFlow.value = CallUiState()
            audioRestarts = 0
            dialTarget = null
            peerDevice = null
        }
        refreshNotification()
        updateIdleCheck()
    }

    // ---------------------------------------------------------------------------------------------
    // Presence: advertising, scanning, listening
    // ---------------------------------------------------------------------------------------------

    /**
     * Idle + on screen: advertise (balanced) and scan.
     * Idle + background/locked: advertise at low power only; no scanning.
     * Calling: neither.
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
        BlueCall.presenceFlow.update {
            it.copy(
                online = true,
                name = currentName(),
                advertising = adv?.isAdvertising == true,
                scanning = idle && visible,
            )
        }
    }

    private fun stopPresence() {
        advertiser?.stop()
        scanner?.stop()
        server?.stop()
        server = null
        BlueCall.peersFlow.value = emptyList()
    }

    // ---------------------------------------------------------------------------------------------
    // Call setup
    // ---------------------------------------------------------------------------------------------

    private fun startDial(peer: PeerInfo) {
        val target = scanner?.peer(peer.sessionId) ?: dialTarget?.takeIf { it.sessionId == peer.sessionId }
        if (target == null) {
            main.post { dispatch(CallEvent.DialFailed) }
            return
        }
        callFrameMs = settings.frameMs
        callId = Random.nextInt()
        peerDevice = target.device
        dialJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { L2capConnector.connect(target.device, target.psm) { dialSocket = it } }
            }
            dialSocket = null
            val socket = result.getOrNull()
            val current = state
            if (current !is CallState.Dialing || current.peer.sessionId != peer.sessionId) {
                socket?.let { runCatching { it.close() } }
                return@launch
            }
            if (socket == null) {
                Log.w(TAG, "dial failed", result.exceptionOrNull())
                dispatch(CallEvent.DialFailed)
                return@launch
            }
            val session = try {
                CallSession(socket.inputStream, socket.outputStream, socket, sessionListener)
            } catch (e: IOException) {
                runCatching { socket.close() }
                dispatch(CallEvent.DialFailed)
                return@launch
            }
            callSession = session
            session.start()
            dispatch(CallEvent.DialConnected)
        }
    }

    private fun onIncomingSocket(socket: BluetoothSocket) {
        if (state == CallState.Offline || shuttingDown) {
            runCatching { socket.close() }
            power.releaseBrief()
            return
        }
        val session = try {
            CallSession(socket.inputStream, socket.outputStream, socket, sessionListener)
        } catch (e: IOException) {
            runCatching { socket.close() }
            power.releaseBrief()
            return
        }
        pending[session] = PendingIncoming(socket.remoteDevice)
        session.start()
        main.postDelayed({
            if (pending.remove(session) != null) {
                closeAsync(session, flush = false)
                power.releaseBrief()
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
                    val peer = PeerInfo(hello?.sessionId ?: 0, hello?.name?.ifBlank { null } ?: "Nearby phone")
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
                    peerDevice = incoming.device
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
        linkTuner.release()
        if (session != null) closeAsync(session, flush = true)
    }

    private fun closeAsync(session: CallSession, flush: Boolean) {
        val close = { if (flush) session.flushAndClose() else session.close() }
        if (shuttingDown) close() else Thread(close, "BlueCall-close").apply { isDaemon = true; start() }
    }

    // ---------------------------------------------------------------------------------------------
    // Audio
    // ---------------------------------------------------------------------------------------------

    private fun startAudio() {
        if (!AppPermissions.granted(this, Manifest.permission.RECORD_AUDIO)) {
            BlueCall.callUiFlow.update { it.copy(error = "Microphone permission is off") }
        }
        audioRouter.begin()
        val ui = BlueCall.callUi.value
        val engine = AudioEngine(
            frameMs = callFrameMs,
            peerFrameMs = peerFrameMs,
            noise = settings.noise,
            sendPacket = { seq, data, length -> callSession?.sendAudio(seq, data, length) },
            onError = { message -> main.post { onAudioError(message) } },
        )
        engine.muted = ui.muted
        engine.onHold = ui.onHold
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
            val ui = BlueCall.callUi.value
            val engine = AudioEngine(
                callFrameMs, peerFrameMs, settings.noise,
                sendPacket = { seq, data, length -> callSession?.sendAudio(seq, data, length) },
                onError = { msg -> main.post { onAudioError(msg) } },
            )
            engine.muted = ui.muted
            engine.onHold = ui.onHold
            audioEngine = if (engine.start(settings.noise)) engine else null
        } else {
            BlueCall.callUiFlow.update { it.copy(error = message) }
        }
    }

    private fun onHoldChanged(onHold: Boolean) {
        if (state !is CallState.Active) return
        audioEngine?.onHold = onHold
        BlueCall.callUiFlow.update { it.copy(onHold = onHold) }
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
            // Couldn't add the microphone type (e.g. not started from the foreground):
            // keep running as a connected-device service.
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
                s.peer, s.connectedAtMs, ui.muted, ui.route == RouteType.SPEAKER, ui.onHold,
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
                    val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (s == BluetoothAdapter.STATE_TURNING_OFF || s == BluetoothAdapter.STATE_OFF) {
                        shutdown(exitApp = false, reason = ShutdownReason.BLUETOOTH_OFF)
                        BlueCall.presenceFlow.value = PresenceState(online = false, error = "Bluetooth was turned off")
                    }
                }

                Intent.ACTION_SCREEN_ON -> checkIdle()
            }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
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
        if (old.displayName != new.displayName) {
            updatePresence()
            refreshNotification()
        }
        if (old.autoOfflineMinutes != new.autoOfflineMinutes) updateIdleCheck()
    }

    private fun currentName(): String = settingsRepository.effectiveName(settings)

    companion object {
        const val ACTION_START = "com.codemew.bluecall.action.START"
        private const val TAG = "BlueCallService"
        private const val RING_TIMEOUT_MS = 45_000L
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
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
