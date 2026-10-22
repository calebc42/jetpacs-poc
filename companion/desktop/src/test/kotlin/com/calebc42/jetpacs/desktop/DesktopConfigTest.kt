// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopConfigTest {
    private fun parse(args: List<String> = emptyList(), vararg environment: Pair<String, String>): DesktopConfig =
        DesktopConfig.parse(args.toTypedArray(), { environment.toMap()[it] }, osName = "Linux", userHome = "/home/u")

    @Test
    fun defaultsToTheSpecPortAndTheDataDirectoryRule() {
        val config = parse()
        assertEquals(8765, config.port)
        assertEquals(Paths.get("/home/u/.local/share/jetpacs"), config.dataDirectory)
        assertFalse(config.devKnownAnswerPairing)
        assertEquals("127.0.0.1:8765", config.listeningEndpoint)
        assertEquals("Jetpacs", config.windowTitle)
    }

    @Test
    fun thePortComesFromTheArgumentOrTheEnvironment() {
        assertEquals(8766, parse(listOf("--port", "8766")).port)
        assertEquals(8767, parse(environment = arrayOf("JETPACS_PORT" to "8767")).port)
        assertEquals(8766, parse(listOf("--port", "8766"), "JETPACS_PORT" to "8767").port)
        assertEquals("127.0.0.1:8766", parse(listOf("--port", "8766")).listeningEndpoint)
    }

    @Test
    fun theDataDirectoryArgumentWins() {
        assertEquals(Paths.get("/tmp/x"), parse(listOf("--data-dir", "/tmp/x"), "JETPACS_DATA_DIR" to "/tmp/y").dataDirectory)
        assertEquals(Paths.get("/tmp/y"), parse(environment = arrayOf("JETPACS_DATA_DIR" to "/tmp/y")).dataDirectory)
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
    fun badPortsAndArgumentsAreRefused() {
        for (bad in listOf("0", "65536", "abc", "")) {
            assertThrows("port $bad", IllegalArgumentException::class.java) { parse(listOf("--port", bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(listOf("--port")) }
        assertThrows(IllegalArgumentException::class.java) { parse(listOf("--bogus")) }
        assertThrows(IllegalArgumentException::class.java) { DesktopConfig(Paths.get("/tmp"), port = 70_000) }
    }
}
