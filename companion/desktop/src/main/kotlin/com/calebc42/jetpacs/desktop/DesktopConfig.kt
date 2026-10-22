// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.jetpacs.companion.host.DEFAULT_LOOPBACK_PORT
import com.calebc42.jetpacs.core.database.JetpacsDataDirectory
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What a desktop Companion run is told from outside: the data directory,
 * the loopback port (SPEC §5.2 allows a configured port when the pairing
 * screen shows it) and whether the public known-answer credential replaces
 * a generated pairing, which only a developer asks for and which the window
 * title and pairing screen then say loudly.
 */
data class DesktopConfig(
    val dataDirectory: Path,
    val port: Int = DEFAULT_LOOPBACK_PORT,
    val devKnownAnswerPairing: Boolean = false,
) {
    init {
        require(port in 0..65535) { "port must be 0..65535, was $port" }
    }

    /** SPEC §5.2: the listener's address as the user types it into Emacs. */
    val listeningEndpoint: String get() = "127.0.0.1:$port"

    val windowTitle: String
        get() = if (devKnownAnswerPairing) "Jetpacs — DEV PAIRING (public credential)" else "Jetpacs"

    companion object {
        const val PORT_VARIABLE = "JETPACS_PORT"
        const val DEV_PAIRING_VARIABLE = "JETPACS_DEV_PAIRING"

        /** The one value [DEV_PAIRING_VARIABLE] accepts; anything else refuses to start. */
        const val DEV_PAIRING_PUBLIC_KNOWN_ANSWER = "public-known-answer"

        /**
         * `--port N` and `--data-dir PATH` on the command line, `JETPACS_PORT`,
         * `JETPACS_DATA_DIR` (through [JetpacsDataDirectory]) and
         * `JETPACS_DEV_PAIRING` in the environment. Pure, so a test can run
         * it with any environment; a bad value is an [IllegalArgumentException]
         * whose message is what `main` prints.
         */
        fun parse(
            args: Array<String>,
            environment: (String) -> String?,
            osName: String = System.getProperty("os.name").orEmpty(),
            userHome: String = System.getProperty("user.home"),
        ): DesktopConfig {
            var portText: String? = environment(PORT_VARIABLE)?.takeIf { it.isNotBlank() }
            var dataDirectory: String? = null
            var index = 0
            while (index < args.size) {
                when (val argument = args[index]) {
                    "--port" -> portText = args.getOrNull(++index) ?: fail("--port needs a value")
                    "--data-dir" -> dataDirectory = args.getOrNull(++index) ?: fail("--data-dir needs a value")
                    else -> fail("unknown argument: $argument (expected --port N or --data-dir PATH)")
                }
                index++
            }
            val port = portText?.let { text ->
                text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: fail("the port must be a number from 1 to 65535, was '$text'")
            } ?: DEFAULT_LOOPBACK_PORT
            val devPairing = when (val value = environment(DEV_PAIRING_VARIABLE)?.takeIf { it.isNotBlank() }) {
                null -> false
                DEV_PAIRING_PUBLIC_KNOWN_ANSWER -> true
                else -> fail(
                    "$DEV_PAIRING_VARIABLE must be exactly '$DEV_PAIRING_PUBLIC_KNOWN_ANSWER' or unset, was '$value'",
                )
            }
            val directory = dataDirectory?.let(Paths::get)
                ?: JetpacsDataDirectory.resolve(osName, environment, userHome)
            return DesktopConfig(directory, port, devPairing)
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
