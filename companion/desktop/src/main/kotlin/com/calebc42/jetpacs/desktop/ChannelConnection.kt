// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.jetpacs.companion.host.CompanionConnection
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * A [CompanionConnection] over a blocking [SocketChannel], with stream views
 * that read and write the channel directly. `Channels.newInputStream` and
 * its output twin take the channel's blocking lock, so a pending read stalls
 * every write; the ebp-java host tier hit that deadlock between its reader
 * and the engine's sink, and these views are its `UnixChannelStreams` ported.
 */
class ChannelConnection(
    private val channel: SocketChannel,
    override val description: String,
) : CompanionConnection {
    /** True once any byte arrived: the dialer's first-byte deadline lifts on it. */
    @Volatile var bytesSeen: Boolean = false
        private set

    @Volatile override var closedByCompanion: Boolean = false
        private set

    private val writeLock = Any()

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val count = channel.read(ByteBuffer.wrap(b, off, len))
            if (count > 0) bytesSeen = true
            return count
        }

        override fun close() = this@ChannelConnection.close()
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val buffer = ByteBuffer.wrap(b, off, len)
            synchronized(writeLock) {
                while (buffer.hasRemaining()) channel.write(buffer)
            }
        }

        override fun close() = this@ChannelConnection.close()
    }

    override fun abort(reason: String) {
        closedByCompanion = true
        close()
    }

    override fun close() {
        channel.runCatching { close() }
    }
}
