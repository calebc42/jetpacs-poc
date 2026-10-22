// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.jetpacs.companion.host.TransportStatus
import com.calebc42.jetpacs.core.ebpstore.PrivateFileSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5 end to end without a window, over SPEC 5.2 loopback-tcp: a scripted
 * Emacs dials the desktop Companion's listener on an ephemeral port, pairs
 * with the generated token, receives the welcome the desktop advertises,
 * pushes a surface that reaches the presentation, answers an action the
 * bridge dispatched, and raises a snackbar. Then the Companion reopens on
 * the same directory with the same pairing and no token to show.
 */
class DesktopCompanionTest {
    private val root: Path = Files.createTempDirectory("jetpacs-desktop-e2e-")
    private val data: Path = root.resolve("data")
    private val direct = Executor { it.run() }

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = PrivateFileSecretStore(data.resolve(PrivateFileSecretStore.FILE_NAME))

    @Test
    fun aScriptedEmacsPairsPushesASurfaceAndRoundTripsAnAction() = runBlocking {
        val config = DesktopConfig(data, port = 0)
        val companion = DesktopCompanion.create(config, direct, store())
        val pairingId = companion.pairing.pairingId.value
        try {
            assertTrue(companion.start())
            val listening = withTimeout(10_000) {
                companion.transportStatus.first { it is TransportStatus.Listening }
            } as TransportStatus.Listening
            val token = EbpAuth.decodePairingToken(companion.pairing.freshTokenDisplay!!)
            ScriptedEmacs.dial(listening.port).use { emacs ->
                emacs.runPairingScript(companion, token)
            }
        } finally {
            companion.shutdown()
        }
        assertFalse(companion.transportStatus.value.isActive)

        val again = DesktopCompanion.create(config, direct, store())
        assertEquals(pairingId, again.pairing.pairingId.value)
        assertNull(again.pairing.freshTokenDisplay)
        again.shutdown()
    }
}
