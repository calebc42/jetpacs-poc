// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.jetpacs.companion.host.DEFAULT_LOOPBACK_PORT
import com.calebc42.jetpacs.core.database.JetpacsDataDirectory
import java.nio.file.Path
import java.nio.file.Paths

/** The SPEC 5 transport profiles this desktop offers, one per run. */
enum class TransportProfile(val id: String) {
    /** SPEC 5.2: the Companion binds 127.0.0.1 and Emacs dials. */
    LOOPBACK_TCP("loopback-tcp"),

    /** SPEC 5.6: Emacs binds a Unix-domain socket and the Companion dials. */
    EMACS_UNIX("emacs-unix");

    companion object {
        fun parse(id: String): TransportProfile? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a desktop Companion run is told from outside: the data directory,
 * the transport profile with its loopback port (SPEC §5.2 allows a
 * configured port when the pairing screen shows it) or Unix socket path
 * (SPEC §5.6), and whether the public known-answer credential replaces a
 * generated pairing, which only a developer asks for and which the window
 * title and pairing screen then say loudly.
 */
data class DesktopConfig(
    val dataDirectory: Path,
    val port: Int = DEFAULT_LOOPBACK_PORT,
    val devKnownAnswerPairing: Boolean = false,
    val transport: TransportProfile = TransportProfile.LOOPBACK_TCP,
    /** The socket Emacs binds; present exactly under [TransportProfile.EMACS_UNIX]. */
    val socket: Path? = null,
) {
    init {
        require(port in 0..65535) { "port must be 0..65535, was $port" }
        require((transport == TransportProfile.EMACS_UNIX) == (socket != null)) {
            "a socket path belongs to emacs-unix and only to it"
        }
        require(socket == null || socket.isAbsolute) { "the socket path must be absolute: $socket" }
    }

    /** The address the user types into Emacs: a loopback port, or the socket Emacs binds. */
    val endpoint: String
        get() = when (transport) {
            TransportProfile.LOOPBACK_TCP -> "127.0.0.1:$port"
            TransportProfile.EMACS_UNIX -> socket.toString()
        }

    /** SPEC §5.1: the pairing screen names the offered profile and its address. */
    val profileDescription: String
        get() = when (transport) {
            TransportProfile.LOOPBACK_TCP -> "loopback-tcp (listening on $endpoint)"
            TransportProfile.EMACS_UNIX -> "emacs-unix (dialing $endpoint)"
        }

    /** The waiting screen's sentence for this transport. */
    val waitingDescription: String
        get() = when (transport) {
            TransportProfile.LOOPBACK_TCP -> "Jetpacs Companion is listening on $endpoint."
            TransportProfile.EMACS_UNIX -> "Jetpacs Companion dials the Emacs socket $endpoint (emacs-unix)."
        }

    val windowTitle: String
        get() = if (devKnownAnswerPairing) "Jetpacs — DEV PAIRING (public credential)" else "Jetpacs"

    companion object {
        const val PORT_VARIABLE = "JETPACS_PORT"
        const val TRANSPORT_VARIABLE = "JETPACS_TRANSPORT"
        const val SOCKET_VARIABLE = "JETPACS_SOCKET"
        const val DEV_PAIRING_VARIABLE = "JETPACS_DEV_PAIRING"

        /** The one value [DEV_PAIRING_VARIABLE] accepts; anything else refuses to start. */
        const val DEV_PAIRING_PUBLIC_KNOWN_ANSWER = "public-known-answer"

        /**
         * `--port N`, `--transport NAME`, `--socket PATH` and `--data-dir PATH`
         * on the command line (each beating its environment variable
         * `JETPACS_PORT`, `JETPACS_TRANSPORT`, `JETPACS_SOCKET`,
         * `JETPACS_DATA_DIR` through [JetpacsDataDirectory]), and
         * `JETPACS_DEV_PAIRING`. Under emacs-unix the socket defaults to
         * ebp.el's own default ([EmacsUnixSocketPath]); where none can be
         * computed the profile is refused, since SPEC §5.6 is not offered on a
         * platform whose Emacs cannot bind a Unix socket. Pure, so a test can
         * run it with any environment; a bad value is an
         * [IllegalArgumentException] whose message is what `main` prints.
         */
        fun parse(
            args: Array<String>,
            environment: (String) -> String?,
            osName: String = System.getProperty("os.name").orEmpty(),
            userHome: String = System.getProperty("user.home"),
            uid: () -> Int? = { EmacsUnixSocketPath.currentUid(Paths.get(userHome)) },
        ): DesktopConfig {
            var portText: String? = environment(PORT_VARIABLE)?.takeIf { it.isNotBlank() }
            var transportText: String? = environment(TRANSPORT_VARIABLE)?.takeIf { it.isNotBlank() }
            var socketText: String? = environment(SOCKET_VARIABLE)?.takeIf { it.isNotBlank() }
            var dataDirectory: String? = null
            var index = 0
            while (index < args.size) {
                when (val argument = args[index]) {
                    "--port" -> portText = args.getOrNull(++index) ?: fail("--port needs a value")
                    "--transport" -> transportText = args.getOrNull(++index) ?: fail("--transport needs a value")
                    "--socket" -> socketText = args.getOrNull(++index) ?: fail("--socket needs a value")
                    "--data-dir" -> dataDirectory = args.getOrNull(++index) ?: fail("--data-dir needs a value")
                    else -> fail(
                        "unknown argument: $argument (expected --port N, --transport loopback-tcp|emacs-unix, " +
                            "--socket PATH or --data-dir PATH)",
                    )
                }
                index++
            }
            val port = portText?.let { text ->
                text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: fail("the port must be a number from 1 to 65535, was '$text'")
            } ?: DEFAULT_LOOPBACK_PORT
            val transport = transportText?.let { text ->
                TransportProfile.parse(text.trim())
                    ?: fail("the transport must be loopback-tcp or emacs-unix, was '$text'")
            } ?: TransportProfile.LOOPBACK_TCP
            val socket = when (transport) {
                TransportProfile.LOOPBACK_TCP -> {
                    if (socketText != null) fail("a socket path needs --transport emacs-unix")
                    null
                }
                TransportProfile.EMACS_UNIX -> {
                    val given = socketText?.let { Paths.get(it.trim()) }
                    val chosen = given ?: EmacsUnixSocketPath.default(environment, uid)
                        ?: fail("no default emacs-unix socket path on this system: pass --socket PATH")
                    if (!chosen.isAbsolute) fail("the socket path must be absolute, was '$chosen'")
                    chosen
                }
            }
            val devPairing = when (val value = environment(DEV_PAIRING_VARIABLE)?.takeIf { it.isNotBlank() }) {
                null -> false
                DEV_PAIRING_PUBLIC_KNOWN_ANSWER -> true
                else -> fail(
                    "$DEV_PAIRING_VARIABLE must be exactly '$DEV_PAIRING_PUBLIC_KNOWN_ANSWER' or unset, was '$value'",
                )
            }
            val directory = dataDirectory?.let(Paths::get)
                ?: JetpacsDataDirectory.resolve(osName, environment, userHome)
            return DesktopConfig(directory, port, devPairing, transport, socket)
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
