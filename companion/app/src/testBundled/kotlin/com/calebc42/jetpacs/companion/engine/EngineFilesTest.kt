// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFilesTest {
    @Test
    fun initElNamesThePortTheCredentialFileAndTheKit() {
        val text = EngineFiles.initEl(8766)
        assertTrue(text.contains("(setq jetpacs-port 8766)"))
        assertTrue(text.contains("\"jetpacs/var/pairing\" user-emacs-directory"))
        assertTrue(text.contains("(expand-file-name \"jetpacs/emacs\" user-emacs-directory)"))
        assertTrue(text.contains("(require 'jetpacs)"))
        assertTrue(text.contains(";;; >>> Jetpacs managed: composition root >>>"))
        assertTrue(text.contains(";;; <<< Jetpacs managed: composition root <<<"))
    }

    @Test
    fun initElNamesTheUnixSocketInsteadOfThePortWhenGiven() {
        val text = EngineFiles.initEl(8766, "/data/user/0/x/files/ebp/companion.sock")
        assertTrue(text.contains("(setq jetpacs-transport 'loopback-unix\n      jetpacs-socket \"/data/user/0/x/files/ebp/companion.sock\")"))
        assertTrue(!text.contains("jetpacs-port"))
    }

    @Test
    fun installConfIsFormatThreeWithThePrivateVaultAndBundledHome() {
        val text = EngineFiles.installConf("/data/x/files/vault", "/data/x/files/emacs", "/data/x/files/emacs/.emacs.d/jetpacs")
        assertEquals(
            "format=3\nvault_mode=private\nvault=/data/x/files/vault\nhome_mode=bundled\n" +
                "home=/data/x/files/emacs\nemacs_home=/data/x/files/emacs\nroot=/data/x/files/emacs/.emacs.d/jetpacs\n",
            text,
        )
        assertTrue(EngineFiles.installConf("/sdcard/Jetpacs", "/h", "/r", vaultMode = "shared").contains("vault_mode=shared\nvault=/sdcard/Jetpacs\n"))
    }

    @Test
    fun pairingFileRoundTripsAndRefusesMalformedValues() {
        val id = "101112131415161718191a1b1c1d1e1f"
        val text = EngineFiles.pairingFile(id, "AAECAwQFBgcICQoLDA0ODw")
        assertEquals("pairing_id=$id\ntoken=AAECAwQFBgcICQoLDA0ODw\n", text)
        assertEquals(id, EngineFiles.pairingIdOf(text))
        assertNull(EngineFiles.pairingIdOf("token=AAECAwQFBgcICQoLDA0ODw\n"))
        assertNull(EngineFiles.pairingIdOf("pairing_id=NOT-HEX\n"))
        assertThrows(IllegalArgumentException::class.java) { EngineFiles.pairingFile("abc", "AAECAwQFBgcICQoLDA0ODw") }
        assertThrows(IllegalArgumentException::class.java) { EngineFiles.pairingFile(id, "too-short") }
        assertThrows(IllegalArgumentException::class.java) { EngineFiles.pairingFile(id, "AAECAwQFBgcICQoLDA0ODw==") }
    }
}
