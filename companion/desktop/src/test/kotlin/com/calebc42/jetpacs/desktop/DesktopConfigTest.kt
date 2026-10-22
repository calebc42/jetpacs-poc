// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopConfigTest {
    private fun parse(
        args: List<String> = emptyList(),
        vararg environment: Pair<String, String>,
        uid: Int? = 1000,
    ): DesktopConfig = DesktopConfig.parse(
        args.toTypedArray(),
        { environment.toMap()[it] },
        osName = "Linux",
        userHome = "/home/u",
        uid = { uid },
    )

    @Test
    fun defaultsToLoopbackOnTheSpecPortAndTheDataDirectoryRule() {
        val config = parse()
        assertEquals(TransportProfile.LOOPBACK_TCP, config.transport)
        assertNull(config.socket)
        assertEquals(8765, config.port)
        assertEquals(Paths.get("/home/u/.local/share/jetpacs"), config.dataDirectory)
        assertFalse(config.devKnownAnswerPairing)
        assertEquals("127.0.0.1:8765", config.endpoint)
        assertEquals("loopback-tcp (listening on 127.0.0.1:8765)", config.profileDescription)
        assertEquals("Jetpacs Companion is listening on 127.0.0.1:8765.", config.waitingDescription)
        assertEquals("Jetpacs", config.windowTitle)
    }

    @Test
    fun thePortComesFromTheArgumentOrTheEnvironment() {
        assertEquals(8766, parse(listOf("--port", "8766")).port)
        assertEquals(8767, parse(environment = arrayOf("JETPACS_PORT" to "8767")).port)
        assertEquals(8766, parse(listOf("--port", "8766"), "JETPACS_PORT" to "8767").port)
        assertEquals("127.0.0.1:8766", parse(listOf("--port", "8766")).endpoint)
    }

    @Test
    fun theDataDirectoryArgumentWins() {
        assertEquals(Paths.get("/tmp/x"), parse(listOf("--data-dir", "/tmp/x"), "JETPACS_DATA_DIR" to "/tmp/y").dataDirectory)
        assertEquals(Paths.get("/tmp/y"), parse(environment = arrayOf("JETPACS_DATA_DIR" to "/tmp/y")).dataDirectory)
    }

    @Test
    fun emacsUnixDefaultsToTheEmacsSocketPath() {
        val runtime = parse(listOf("--transport", "emacs-unix"), "XDG_RUNTIME_DIR" to "/run/user/1000")
        assertEquals(TransportProfile.EMACS_UNIX, runtime.transport)
        assertEquals(Paths.get("/run/user/1000/ebp/ebp.sock"), runtime.socket)
        assertEquals("/run/user/1000/ebp/ebp.sock", runtime.endpoint)
        assertEquals("emacs-unix (dialing /run/user/1000/ebp/ebp.sock)", runtime.profileDescription)
        assertEquals(
            "Jetpacs Companion dials the Emacs socket /run/user/1000/ebp/ebp.sock (emacs-unix).",
            runtime.waitingDescription,
        )
        assertEquals(Paths.get("/t/ebp1000/ebp.sock"), parse(environment = arrayOf("JETPACS_TRANSPORT" to "emacs-unix", "TMPDIR" to "/t")).socket)
        assertEquals(Paths.get("/tmp/ebp1000/ebp.sock"), parse(environment = arrayOf("JETPACS_TRANSPORT" to "emacs-unix")).socket)
        // A relative XDG_RUNTIME_DIR is ignored, as ebp.el ignores it.
        assertEquals(Paths.get("/tmp/ebp1000/ebp.sock"), parse(listOf("--transport", "emacs-unix"), "XDG_RUNTIME_DIR" to "relative").socket)
    }

    @Test
    fun theSocketComesFromTheArgumentOrTheEnvironment() {
        assertEquals(Paths.get("/s/a.sock"), parse(listOf("--transport", "emacs-unix", "--socket", "/s/a.sock"), "JETPACS_SOCKET" to "/s/b.sock").socket)
        assertEquals(Paths.get("/s/b.sock"), parse(environment = arrayOf("JETPACS_TRANSPORT" to "emacs-unix", "JETPACS_SOCKET" to "/s/b.sock")).socket)
    }

    @Test
    fun theDevelopmentPairingVariableMustBeTheExactLiteral() {
        val dev = parse(environment = arrayOf("JETPACS_DEV_PAIRING" to "public-known-answer"))
        assertTrue(dev.devKnownAnswerPairing)
        assertTrue(dev.windowTitle.contains("DEV PAIRING"))
        assertThrows(IllegalArgumentException::class.java) {
            parse(environment = arrayOf("JETPACS_DEV_PAIRING" to "yes"))
        }
        assertFalse(parse(environment = arrayOf("JETPACS_DEV_PAIRING" to "")).devKnownAnswerPairing)
    }

    @Test
    fun badValuesAreRefused() {
        for (bad in listOf("0", "65536", "abc", "")) {
            assertThrows("port $bad", IllegalArgumentException::class.java) { parse(listOf("--port", bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(listOf("--port")) }
        assertThrows(IllegalArgumentException::class.java) { parse(listOf("--bogus")) }
        assertThrows(IllegalArgumentException::class.java) { parse(listOf("--transport", "bogus")) }
        assertThrows(IllegalArgumentException::class.java) { parse(listOf("--socket", "/s/a.sock")) }
        assertThrows(IllegalArgumentException::class.java) { parse(listOf("--transport", "emacs-unix", "--socket", "relative.sock")) }
        // Nothing to compute a default from: no runtime directory and no uid (Windows).
        assertThrows(IllegalArgumentException::class.java) { parse(listOf("--transport", "emacs-unix"), uid = null) }
        assertThrows(IllegalArgumentException::class.java) { DesktopConfig(Paths.get("/tmp"), port = 70_000) }
        assertThrows(IllegalArgumentException::class.java) { DesktopConfig(Paths.get("/tmp"), socket = Paths.get("/s/a.sock")) }
        assertThrows(IllegalArgumentException::class.java) { DesktopConfig(Paths.get("/tmp"), transport = TransportProfile.EMACS_UNIX) }
    }
}
