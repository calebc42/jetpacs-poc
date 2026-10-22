// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

/**
 * One accepted or dialed EBP connection. Only JDK stream types cross this
 * seam, so the Android host never sees `java.nio.channels` or a Unix-domain
 * address; a transport wraps whatever it opened and the bridge serves it.
 */
interface CompanionConnection : AutoCloseable {
    val input: InputStream
    val output: OutputStream

    /** The peer as a log or status string: an address and port, or a socket path. */
    val description: String

    /** True once [abort] ran: the Companion ended this connection on purpose. */
    val closedByCompanion: Boolean

    /**
     * A close the Companion initiated (supersession, the design-runtime
     * toggle, the password deadline, stop). A dialer redials one of these at
     * once; a close it did not initiate gets the SPEC 5.7 jittered redial.
     */
    fun abort(reason: String)

    /** Cleanup after the reader returned or a write failed; idempotent. */
    override fun close()
}

/** What a served connection amounted to, for the transport that produced it. */
data class ServeOutcome(val reachedReady: Boolean)

/** The bridge's serve thread for one connection, for a transport to wait on. */
interface ServeHandle {
    /**
     * Block, interruptibly, until the serve thread finished and the engine is
     * closed; null when [timeoutMs] elapsed first.
     */
    fun join(timeoutMs: Long = Long.MAX_VALUE): ServeOutcome?
}

/** The bridge's side of the seam: admit a connection and serve it. */
fun interface ConnectionSink {
    /**
     * Admit [connection] synchronously on the caller's thread (newest-wins
     * supersession of the prior connection) and spawn its serve thread.
     * Returns the handle a dialer joins before it redials.
     */
    fun accepted(connection: CompanionConnection): ServeHandle
}

/** What a transport is doing, for the host's chrome and its tests. */
sealed interface TransportStatus {
    /** Not started, or stopped. */
    data object Idle : TransportStatus

    /** SPEC 5.2: bound and accepting on the loopback [port]. */
    data class Listening(val port: Int) : TransportStatus

    /**
     * SPEC 5.6: between dials. [nextAttemptAtMs] is the wall-clock instant of
     * the next attempt, or null while a connect is in progress.
     */
    data class Dialing(val attempt: Int, val nextAttemptAtMs: Long?) : TransportStatus

    /** A connection to [peer] is being served. */
    data class Connected(val peer: String) : TransportStatus

    /** Terminal: the transport refused to continue, for [reason]. */
    data class Failed(val reason: String) : TransportStatus

    /** Whether the transport may still produce a connection. */
    val isActive: Boolean get() = this !is Idle && this !is Failed
}

/**
 * How a Companion host obtains EBP connections: the SPEC 5.2 loopback
 * listener on every host, the SPEC 5.6 Unix-socket dialer on the desktop.
 * The bridge owns the session code; a transport owns only the socket.
 */
interface CompanionTransport {
    /** The SPEC 5 profile identifier, `loopback-tcp` or `emacs-unix`. */
    val profile: String

    /** The bound address or the dialed path, for the pairing screen. */
    val address: String

    val status: StateFlow<TransportStatus>

    /**
     * Produce connections on the caller's thread until [running] is false or
     * the transport fails terminally. Publishes [TransportStatus.Idle] on
     * return unless it failed.
     */
    fun run(running: () -> Boolean, sink: ConnectionSink)

    /** Unblock [run]: close the listener or the current dial and let an interrupt end a sleep. */
    fun stop()
}
