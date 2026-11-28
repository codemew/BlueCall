package com.codemew.bluecall.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AwareLinks"
private const val CONNECT_TIMEOUT_MS = 5_000
private const val ACCEPT_TIMEOUT_MS = 12_000

private fun awareRequest(specifier: WifiAwareNetworkSpecifier): NetworkRequest =
    NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        .setNetworkSpecifier(specifier)
        .build()

/** Unregisters [callback] once, from any thread. */
private fun releaser(cm: ConnectivityManager, callback: ConnectivityManager.NetworkCallback): () -> Unit {
    val released = AtomicBoolean(false)
    return {
        if (released.compareAndSet(false, true)) runCatching { cm.unregisterNetworkCallback(callback) }
    }
}

/**
 * The caller's side of a Wi-Fi call. Run it after the other phone answered
 * READY: it brings up the data link and connects to the port the other phone
 * is listening on.
 */
object AwareConnector {
    suspend fun connect(
        context: Context,
        session: DiscoverySession,
        peer: PeerHandle,
        passphrase: String,
    ): WifiAwareLink {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val specifier = WifiAwareNetworkSpecifier.Builder(session, peer)
            .setPskPassphrase(passphrase)
            .build()
        val ready = CompletableDeferred<Pair<Network, WifiAwareNetworkInfo>>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                if (info.peerIpv6Addr != null && info.port > 0) ready.complete(network to info)
            }

            override fun onUnavailable() {
                ready.completeExceptionally(IOException("the Wi-Fi link was refused"))
            }

            override fun onLost(network: Network) {
                ready.completeExceptionally(IOException("the Wi-Fi link went down"))
            }
        }
        val release = releaser(cm, callback)
        try {
            cm.requestNetwork(awareRequest(specifier), callback)
            val (network, info) = ready.await()
            val socket = withContext(Dispatchers.IO) {
                val s = network.socketFactory.createSocket()
                try {
                    WifiAwareLink.tune(s)
                    s.connect(InetSocketAddress(info.peerIpv6Addr, info.port), CONNECT_TIMEOUT_MS)
                    s
                } catch (e: IOException) {
                    runCatching { s.close() }
                    throw e
                }
            }
            return WifiAwareLink(socket, release)
        } catch (e: Throwable) {
            release()
            throw e
        }
    }
}

/**
 * The called phone's side of a Wi-Fi call: listens on a port, asks for the
 * data link to the caller (telling it that port), and accepts exactly one
 * connection that arrives over that link. Anything else is turned away.
 *
 * [onAccepted] and [onFailed] run on a background thread; exactly one of them runs,
 * unless [cancel] is called first.
 */
class AwareResponder(
    context: Context,
    private val session: PublishDiscoverySession,
    private val peer: PeerHandle,
    /** The calling phone's session id and its attempt, to recognise repeats and retries. */
    val callerSessionId: Int,
    val nonce: Int,
    private val passphrase: String,
    private val onAccepted: (WifiAwareLink) -> Unit,
    private val onFailed: () -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    /** Set once: by handing the link over, by failing, or by [cancel]. */
    private val finished = AtomicBoolean(false)
    private val interfaceKnown = CountDownLatch(1)

    @Volatile
    private var interfaceName: String? = null

    @Volatile
    private var server: ServerSocket? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            interfaceName = linkProperties.interfaceName
            interfaceKnown.countDown()
        }

        override fun onLost(network: Network) {
            if (server != null) cancel()
        }
    }
    private val release = releaser(cm, callback)

    /** Starts listening and asks for the data link. False if that couldn't start. */
    fun start(): Boolean {
        val socket = try {
            ServerSocket(0)
        } catch (e: IOException) {
            Log.w(TAG, "listen failed", e)
            return false
        }
        server = socket
        try {
            val specifier = WifiAwareNetworkSpecifier.Builder(session, peer)
                .setPskPassphrase(passphrase)
                .setPort(socket.localPort)
                .build()
            cm.requestNetwork(awareRequest(specifier), callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "data link request failed", e)
            runCatching { socket.close() }
            server = null
            release()
            return false
        }
        Thread({ acceptLoop(socket) }, "BlueCall-wifi-accept").apply { isDaemon = true; start() }
        return true
    }

    /** Stops waiting for the caller. Does nothing once the connection has been handed over. */
    fun cancel() {
        if (!finished.compareAndSet(false, true)) return
        server?.let { runCatching { it.close() } }
        server = null
        release()
    }

    private fun acceptLoop(socket: ServerSocket) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACCEPT_TIMEOUT_MS.toLong())
        try {
            while (!finished.get()) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) throw SocketTimeoutException()
                socket.soTimeout = left.toInt()
                val client = socket.accept()
                if (!arrivedOverAware(client)) {
                    Log.w(TAG, "turned away a connection from outside the Wi-Fi Aware link")
                    runCatching { client.close() }
                    continue
                }
                WifiAwareLink.tune(client)
                runCatching { socket.close() }
                server = null
                if (!finished.compareAndSet(false, true)) {
                    runCatching { client.close() }
                    return
                }
                onAccepted(WifiAwareLink(client, release))
                return
            }
        } catch (e: IOException) {
            if (!finished.get()) Log.w(TAG, "no connection from the caller: ${e.message}")
        }
        runCatching { socket.close() }
        server = null
        if (finished.compareAndSet(false, true)) {
            release()
            onFailed()
        }
    }

    /** The server socket listens on every interface, so check the connection came over the Aware link. */
    private fun arrivedOverAware(client: Socket): Boolean {
        interfaceKnown.await(1, TimeUnit.SECONDS)
        val name = interfaceName ?: return false
        val local = client.localAddress as? Inet6Address ?: return false
        val iface = runCatching { NetworkInterface.getByName(name) }.getOrNull() ?: return false
        if (local.scopeId != 0 && local.scopeId == iface.index) return true
        return iface.inetAddresses.toList().any { it.address.contentEquals(local.address) }
    }
}
