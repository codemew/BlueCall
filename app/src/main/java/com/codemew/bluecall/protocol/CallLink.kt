package com.codemew.bluecall.protocol

import com.codemew.bluecall.call.Transport
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * One open connection to another phone, whichever radio carries it. A
 * [CallSession] runs over it, and closing it releases everything the
 * connection held (the socket, and on Wi-Fi the data link itself).
 */
interface CallLink : Closeable {
    val transport: Transport
    val input: InputStream
    val output: OutputStream
}
