// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SPEC 5.2 `loopback-tcp`: the Companion binds 127.0.0.1:[port] and Emacs
 * dials. The listener is process-lifetime state: one accept failure must
 * not end it while a dead server socket keeps the port looking healthy, so
 * each listener is owned with `use` and recreated after a transient bind or
 * accept failure. The newest connection supersedes the prior one inside the
 * bridge's sink.
 */
class LoopbackTcpTransport(
    private val port: Int,
    private val log: HostLog,
) : CompanionTransport {
    override val profile: String = "loopback-tcp"
    override val address: String get() = "127.0.0.1:$port"

    private val _status = MutableStateFlow<TransportStatus>(TransportStatus.Idle)
    override val status: StateFlow<TransportStatus> get() = _status

    /** The bound port while listening, null otherwise: an ephemeral port's one way out. */
    val boundPort: Int? get() = (_status.value as? TransportStatus.Listening)?.port

    @Volatile private var listenerServer: ServerSocket? = null

    override fun run(running: () -> Boolean, sink: ConnectionSink) {
        while (running()) {
            try {
                ServerSocket().use { server ->
                    listenerServer = server
                    server.reuseAddress = true
                    // SPEC 5.2: bind only a loopback interface.
                    server.bind(InetSocketAddress("127.0.0.1", port))
                    _status.value = TransportStatus.Listening(server.localPort)
                    while (running()) {
                        val socket = server.accept()
                        // Request/response frames are small and the peer
                        // answers each one; Nagle would hold a reply behind
                        // an unacknowledged segment (PLAN-responsiveness C7a).
                        socket.runCatching { tcpNoDelay = true }
                        sink.accepted(SocketConnection(socket))
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                if (!running()) return
                // Closing the owned ServerSocket above prevents an orphaned
                // bound port. A short retry keeps process startup and a
                // transient accept failure from making reconnect impossible.
                log.w("EbpBridge", "Listener restarting", e)
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            } finally {
                listenerServer = null
                _status.value = TransportStatus.Idle
            }
        }
    }

    override fun stop() {
        listenerServer?.runCatching { close() }
    }

    /** A TCP peer. The stream getters throw for a closed socket exactly where the socket did. */
    private class SocketConnection(private val socket: Socket) : CompanionConnection {
        override val input: InputStream get() = socket.getInputStream()
        override val output: OutputStream get() = socket.getOutputStream()
        override val description: String =
            "${socket.inetAddress?.hostAddress ?: "127.0.0.1"}:${socket.port}"

        @Volatile override var closedByCompanion: Boolean = false
            private set

        override fun abort(reason: String) {
            closedByCompanion = true
            close()
        }

        override fun close() {
            socket.runCatching { close() }
        }
    }
}
