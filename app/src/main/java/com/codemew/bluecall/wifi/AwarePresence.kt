package com.codemew.bluecall.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.codemew.bluecall.call.PeerDirectory
import com.codemew.bluecall.call.Sighting

/** A phone with BlueCall open, found over Wi-Fi Aware. [peer] is valid for our subscribe session only. */
data class AwareSighting(
    val sessionId: Int,
    val name: String,
    val peer: PeerHandle,
    val lastSeenMs: Long,
) {
    fun toSighting() = Sighting(sessionId, name, lastSeenMs)
}

/**
 * Being visible and finding phones over Wi-Fi Aware: the Wi-Fi counterpart of
 * the Bluetooth advertiser and scanner together.
 *
 * - Publishing (others can find and call this phone) runs while BlueCall is
 *   online. The Wi-Fi chip answers on its own in the short discovery windows
 *   Wi-Fi Aware schedules; Android spaces them out when the screen is off.
 * - Subscribing (finding other phones) is passive and runs only while BlueCall
 *   is on screen, like the Bluetooth scan.
 * - When neither is wanted, BlueCall leaves Wi-Fi Aware entirely ([stop]), so
 *   nothing of it runs.
 *
 * All methods and callbacks run on the main thread. Callers must hold
 * NEARBY_WIFI_DEVICES (Android 13+) or ACCESS_FINE_LOCATION (Android 12).
 */
@SuppressLint("MissingPermission")
class AwarePresence(
    context: Context,
    private val ownSessionId: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onPeersChanged(peers: List<AwareSighting>)

        /** A discovery message arrived on our publish session ([viaPublish]) or our subscribe session. */
        fun onSignal(viaPublish: Boolean, peer: PeerHandle, signal: AwarePayload.Signal)

        fun onMessageSent(messageId: Int, success: Boolean)

        /** Attaching failed or Wi-Fi Aware stopped under us (Wi-Fi off, hotspot, deep sleep). */
        fun onAwareLost()
    }

    private val manager: WifiAwareManager? = context.getSystemService(WifiAwareManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val peers = LinkedHashMap<Int, AwareSighting>()

    private var session: WifiAwareSession? = null
    private var attaching = false
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var publishStarting = false
    private var subscribeStarting = false
    private var wantPublish = false
    private var wantSubscribe = false
    private var name = ""
    private var publishedName = ""
    private var generation = 0
    private var nextMessageId = 1
    private var refreshStartedMs = 0L
    private var refreshAllowed = true
    private var lastAttachFailureMs = -ATTACH_RETRY_MS

    /** True while this phone can be found over Wi-Fi. */
    val isPublishing: Boolean get() = publishSession != null
    val isSubscribing: Boolean get() = subscribeSession != null
    val publisher: PublishDiscoverySession? get() = publishSession
    val subscriber: SubscribeDiscoverySession? get() = subscribeSession

    private val refreshTick = object : Runnable {
        override fun run() = refreshSubscription()
    }

    private val pruneTick = Runnable { prune() }

    /**
     * @param inCall a Wi-Fi call runs over one of these sessions, so it must not
     *   be restarted (closing a session can take its data link down with it).
     */
    fun update(publish: Boolean, subscribe: Boolean, displayName: String, inCall: Boolean = false) {
        wantPublish = publish
        wantSubscribe = subscribe
        name = displayName
        refreshAllowed = !inCall
        if (!publish && !subscribe) {
            stop()
            return
        }
        val s = session
        if (s == null) {
            attach()
            return
        }
        apply(s)
    }

    /** Allows an immediate attach again, for example after Wi-Fi Aware became available. */
    fun clearBackoff() {
        lastAttachFailureMs = -ATTACH_RETRY_MS
    }

    /** Leave Wi-Fi Aware completely: close both sessions and detach. */
    fun stop() {
        wantPublish = false
        wantSubscribe = false
        generation++
        handler.removeCallbacks(refreshTick)
        handler.removeCallbacks(pruneTick)
        publishSession?.let { runCatching { it.close() } }
        subscribeSession?.let { runCatching { it.close() } }
        publishSession = null
        subscribeSession = null
        publishStarting = false
        subscribeStarting = false
        session?.let { runCatching { it.close() } }
        session = null
        attaching = false
        clearPeers()
    }

    fun peer(sessionId: Int): AwareSighting? = peers[sessionId]

    /** Sends a discovery message. Returns its id, reported back through [Listener.onMessageSent]. */
    fun send(viaPublish: Boolean, peer: PeerHandle, signal: AwarePayload.Signal): Int? {
        val target = (if (viaPublish) publishSession else subscribeSession) ?: return null
        val id = nextMessageId++
        return try {
            target.sendMessage(peer, id, AwarePayload.encode(signal))
            id
        } catch (e: RuntimeException) {
            Log.w(TAG, "sendMessage failed", e)
            null
        }
    }

    private fun attach() {
        val m = manager ?: return
        if (attaching) return
        // After a failure, wait before trying again rather than retrying in a loop.
        if (SystemClock.elapsedRealtime() - lastAttachFailureMs < ATTACH_RETRY_MS) return
        attaching = true
        val gen = generation
        try {
            m.attach(
                object : AttachCallback() {
                    override fun onAttached(s: WifiAwareSession) {
                        attaching = false
                        if (gen != generation || (!wantPublish && !wantSubscribe)) {
                            runCatching { s.close() }
                            return
                        }
                        session = s
                        apply(s)
                    }

                    override fun onAttachFailed() {
                        attaching = false
                        if (gen != generation) return
                        Log.w(TAG, "attach failed")
                        lastAttachFailureMs = SystemClock.elapsedRealtime()
                        listener.onAwareLost()
                    }

                    override fun onAwareSessionTerminated() {
                        if (gen != generation) return
                        Log.i(TAG, "Wi-Fi Aware session terminated")
                        stop()
                        lastAttachFailureMs = SystemClock.elapsedRealtime()
                        listener.onAwareLost()
                    }
                },
                handler,
            )
        } catch (e: RuntimeException) {
            attaching = false
            Log.w(TAG, "attach threw", e)
            lastAttachFailureMs = SystemClock.elapsedRealtime()
            listener.onAwareLost()
        }
    }

    private fun apply(s: WifiAwareSession) {
        // Publish
        val pub = publishSession
        if (wantPublish) {
            if (pub == null && !publishStarting) {
                publishStarting = true
                runCatching { s.publish(publishConfig(), PublishCallback(generation), handler) }
                    .onFailure { publishStarting = false; Log.w(TAG, "publish threw", it) }
            } else if (pub != null && publishedName != name) {
                runCatching { pub.updatePublish(publishConfig()) }
            }
        } else if (pub != null) {
            runCatching { pub.close() }
            publishSession = null
        }

        // Subscribe
        val sub = subscribeSession
        if (wantSubscribe) {
            if (sub == null && !subscribeStarting) startSubscribe(s)
        } else {
            handler.removeCallbacks(refreshTick)
            handler.removeCallbacks(pruneTick)
            if (sub != null) {
                runCatching { sub.close() }
                subscribeSession = null
            }
            clearPeers()
        }
    }

    private fun startSubscribe(s: WifiAwareSession) {
        subscribeStarting = true
        val config = SubscribeConfig.Builder()
            .setServiceName(AwarePayload.SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .setTerminateNotificationEnabled(false)
            .build()
        runCatching { s.subscribe(config, SubscribeCallback(generation), handler) }
            .onFailure { subscribeStarting = false; Log.w(TAG, "subscribe threw", it) }
    }

    private fun publishConfig(): PublishConfig {
        publishedName = name
        return PublishConfig.Builder()
            .setServiceName(AwarePayload.SERVICE_NAME)
            .setServiceSpecificInfo(AwarePayload.encodeInfo(ownSessionId, name))
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setTerminateNotificationEnabled(false)
            .build()
    }

    /**
     * Android 12 doesn't report phones that went away, so while subscribed the
     * subscription is restarted now and then and phones not heard from again are
     * dropped. Android 13+ reports losses directly.
     */
    private fun refreshSubscription() {
        val s = session ?: return
        if (!wantSubscribe) return
        if (!refreshAllowed) {
            handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)
            return
        }
        subscribeSession?.let { runCatching { it.close() } }
        subscribeSession = null
        refreshStartedMs = SystemClock.elapsedRealtime()
        startSubscribe(s)
        handler.removeCallbacks(pruneTick)
        handler.postDelayed(pruneTick, PRUNE_AFTER_REFRESH_MS)
    }

    private fun prune() {
        val cutoff = refreshStartedMs
        if (peers.values.removeAll { it.lastSeenMs < cutoff }) publishPeers()
    }

    private fun clearPeers() {
        if (peers.isEmpty()) return
        peers.clear()
        listener.onPeersChanged(emptyList())
    }

    private fun publishPeers() = listener.onPeersChanged(peers.values.toList())

    private fun onDiscovered(peer: PeerHandle, info: ByteArray?) {
        val decoded = AwarePayload.decodeInfo(info) ?: return
        if (decoded.sessionId == ownSessionId) return
        val previous = peers[decoded.sessionId]
        val name = decoded.name.ifBlank { previous?.name ?: PeerDirectory.placeholderName(decoded.sessionId) }
        peers[decoded.sessionId] = AwareSighting(decoded.sessionId, name, peer, SystemClock.elapsedRealtime())
        if (previous == null || previous.name != name) publishPeers()
    }

    private fun onLost(peer: PeerHandle) {
        if (peers.values.removeAll { it.peer == peer }) publishPeers()
    }

    private inner class PublishCallback(private val gen: Int) : DiscoverySessionCallback() {
        override fun onPublishStarted(s: PublishDiscoverySession) {
            publishStarting = false
            if (gen != generation || !wantPublish) {
                runCatching { s.close() }
                return
            }
            publishSession = s
            // The name may have changed while the session was starting.
            if (publishedName != name) runCatching { s.updatePublish(publishConfig()) }
        }

        override fun onSessionConfigFailed() {
            publishStarting = false
            Log.w(TAG, "publish failed")
        }

        override fun onSessionTerminated() {
            if (gen == generation) publishSession = null
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            if (gen != generation) return
            AwarePayload.decodeSignal(message)?.let { listener.onSignal(true, peerHandle, it) }
        }

        override fun onMessageSendSucceeded(messageId: Int) = listener.onMessageSent(messageId, true)
        override fun onMessageSendFailed(messageId: Int) = listener.onMessageSent(messageId, false)
    }

    private inner class SubscribeCallback(private val gen: Int) : DiscoverySessionCallback() {
        override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
            subscribeStarting = false
            if (gen != generation || !wantSubscribe) {
                runCatching { s.close() }
                return
            }
            subscribeSession = s
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handler.removeCallbacks(refreshTick)
                handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)
            }
        }

        override fun onSessionConfigFailed() {
            subscribeStarting = false
            Log.w(TAG, "subscribe failed")
        }

        override fun onSessionTerminated() {
            if (gen == generation) subscribeSession = null
        }

        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
            if (gen == generation) onDiscovered(peerHandle, serviceSpecificInfo)
        }

        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
            if (gen == generation) onLost(peerHandle)
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            if (gen != generation) return
            AwarePayload.decodeSignal(message)?.let { listener.onSignal(false, peerHandle, it) }
        }

        override fun onMessageSendSucceeded(messageId: Int) = listener.onMessageSent(messageId, true)
        override fun onMessageSendFailed(messageId: Int) = listener.onMessageSent(messageId, false)
    }

    private companion object {
        const val TAG = "AwarePresence"
        const val REFRESH_INTERVAL_MS = 30_000L
        const val PRUNE_AFTER_REFRESH_MS = 10_000L
        const val ATTACH_RETRY_MS = 30_000L
    }
}
