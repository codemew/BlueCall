package com.codemew.bluecall.bluetooth

import com.codemew.bluecall.protocol.Messages
import com.codemew.bluecall.protocol.PROTOCOL_VERSION
import java.nio.ByteBuffer
import java.util.UUID

object BlueCallUuids {
    /** Service data in the advertisement: version, L2CAP PSM, session id. */
    val SERVICE: UUID = UUID.fromString("b10eca11-7c3a-4f5e-9d2a-3e8f6a1c5b70")

    /** Service data in the scan response: the display name. */
    val NAME: UUID = UUID.fromString("b10eca12-7c3a-4f5e-9d2a-3e8f6a1c5b70")
}

/**
 * What a phone with BlueCall open advertises. It must fit next to the flags
 * and a 128-bit UUID in a 31-byte legacy advertisement, so it is 8 bytes:
 * `[version u8][psm u16][sessionId i32][flags u8]`.
 */
data class AdvertPayload(
    val version: Int,
    val psm: Int,
    val sessionId: Int,
    val flags: Int = 0,
) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE)
        .put(version.toByte())
        .putShort(psm.toShort())
        .putInt(sessionId)
        .put(flags.toByte())
        .array()

    companion object {
        const val SIZE = 8

        /** Name bytes that fit in the scan response next to a 128-bit UUID. */
        const val MAX_NAME_BYTES = 13

        fun of(psm: Int, sessionId: Int) = AdvertPayload(PROTOCOL_VERSION, psm, sessionId)

        fun decode(bytes: ByteArray?): AdvertPayload? {
            if (bytes == null || bytes.size < SIZE) return null
            val buf = ByteBuffer.wrap(bytes)
            val version = buf.get().toInt() and 0xFF
            if (version != PROTOCOL_VERSION) return null
            val psm = buf.getShort().toInt() and 0xFFFF
            if (psm == 0) return null
            val sessionId = buf.getInt()
            val flags = buf.get().toInt() and 0xFF
            return AdvertPayload(version, psm, sessionId, flags)
        }

        fun encodeName(name: String): ByteArray = Messages.truncateUtf8(name.trim(), MAX_NAME_BYTES)

        fun decodeName(bytes: ByteArray?): String? {
            if (bytes == null || bytes.isEmpty()) return null
            return String(bytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }
    }
}
