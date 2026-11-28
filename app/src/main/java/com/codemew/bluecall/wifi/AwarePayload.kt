package com.codemew.bluecall.wifi

import com.codemew.bluecall.protocol.Messages
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The bytes BlueCall exchanges over Wi-Fi Aware discovery, before any data link exists.
 *
 * - Service info, published while online: `[version][sessionId i32][nameLen u8][name]`.
 *   The session id is the same one the Bluetooth advertisement carries, so the
 *   Nearby list can show one entry per phone.
 * - Discovery messages between caller and callee:
 *   - CONNECT `[version][type][callerSessionId i32][nonce i32][passLen u8][passphrase]`, caller to callee.
 *   - READY `[version][type][nonce i32]`, callee to caller: it is now waiting for the data link.
 *   - BUSY `[version][type][nonce i32]`, callee to caller: already in a call.
 *
 * The passphrase only exists because Android passes the callee's port to the
 * caller on secured Wi-Fi Aware links only. It travels in the clear, so it is
 * not protection against someone listening.
 */
object AwarePayload {
    const val VERSION = 1
    const val SERVICE_NAME = "bluecall"
    const val MAX_NAME_BYTES = 32
    const val PASSPHRASE_LENGTH = 16

    private const val TYPE_CONNECT = 1
    private const val TYPE_BUSY = 2
    private const val TYPE_READY = 3
    private const val PASSPHRASE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    data class Info(val sessionId: Int, val name: String)

    sealed interface Signal {
        data class Connect(val sessionId: Int, val nonce: Int, val passphrase: String) : Signal
        data class Ready(val nonce: Int) : Signal
        data class Busy(val nonce: Int) : Signal
    }

    fun encodeInfo(sessionId: Int, name: String): ByteArray {
        val bytes = Messages.truncateUtf8(name.trim(), MAX_NAME_BYTES)
        return ByteBuffer.allocate(1 + 4 + 1 + bytes.size)
            .put(VERSION.toByte())
            .putInt(sessionId)
            .put(bytes.size.toByte())
            .put(bytes)
            .array()
    }

    fun decodeInfo(bytes: ByteArray?): Info? = parse(bytes) { buf ->
        if (buf.get().toInt() and 0xFF != VERSION) return@parse null
        val sessionId = buf.getInt()
        val name = readString(buf, MAX_NAME_BYTES) ?: return@parse null
        Info(sessionId, name.trim())
    }

    fun encode(signal: Signal): ByteArray = when (signal) {
        is Signal.Connect -> {
            val pass = signal.passphrase.toByteArray(Charsets.US_ASCII)
            ByteBuffer.allocate(2 + 4 + 4 + 1 + pass.size)
                .put(VERSION.toByte())
                .put(TYPE_CONNECT.toByte())
                .putInt(signal.sessionId)
                .putInt(signal.nonce)
                .put(pass.size.toByte())
                .put(pass)
                .array()
        }

        is Signal.Ready -> nonceOnly(TYPE_READY, signal.nonce)
        is Signal.Busy -> nonceOnly(TYPE_BUSY, signal.nonce)
    }

    private fun nonceOnly(type: Int, nonce: Int): ByteArray =
        ByteBuffer.allocate(2 + 4).put(VERSION.toByte()).put(type.toByte()).putInt(nonce).array()

    fun decodeSignal(bytes: ByteArray?): Signal? = parse(bytes) { buf ->
        if (buf.get().toInt() and 0xFF != VERSION) return@parse null
        when (buf.get().toInt() and 0xFF) {
            TYPE_CONNECT -> {
                val sessionId = buf.getInt()
                val nonce = buf.getInt()
                val pass = readString(buf, MAX_PASSPHRASE) ?: return@parse null
                if (!isValidPassphrase(pass)) return@parse null
                Signal.Connect(sessionId, nonce, pass)
            }

            TYPE_READY -> Signal.Ready(buf.getInt())
            TYPE_BUSY -> Signal.Busy(buf.getInt())
            else -> null
        }
    }

    /** A fresh passphrase for one call: Wi-Fi Aware wants 8–63 printable ASCII characters. */
    fun newPassphrase(random: Random = secureRandom): String =
        String(CharArray(PASSPHRASE_LENGTH) { PASSPHRASE_CHARS[random.nextInt(PASSPHRASE_CHARS.length)] })

    fun isValidPassphrase(pass: String): Boolean =
        pass.length in MIN_PASSPHRASE..MAX_PASSPHRASE && pass.all { it in ' '..'~' }

    private const val MIN_PASSPHRASE = 8
    private const val MAX_PASSPHRASE = 63
    private val secureRandom: Random by lazy { SecureRandom().asKotlinRandom() }

    private fun readString(buf: ByteBuffer, maxBytes: Int): String? {
        val length = buf.get().toInt() and 0xFF
        if (length > maxBytes || length > buf.remaining()) return null
        val bytes = ByteArray(length).also { buf.get(it) }
        return String(bytes, Charsets.UTF_8)
    }

    private inline fun <T> parse(bytes: ByteArray?, block: (ByteBuffer) -> T?): T? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            block(ByteBuffer.wrap(bytes))
        } catch (_: BufferUnderflowException) {
            null
        }
    }
}
