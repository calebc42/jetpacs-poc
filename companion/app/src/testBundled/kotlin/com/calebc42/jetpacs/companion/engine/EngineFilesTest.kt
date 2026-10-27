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

    /**
     * Settings changes survive engine starts: Customize saves into user
     * state, never into this supervisor-rewritten file, and that file loads
     * after the kit defines the options it sets.
     */
    @Test
    fun initElSavesCustomizationsOutsideItselfAndLoadsThemAfterTheKit() {
        val text = EngineFiles.initEl(8766)
        assertTrue(
            text.contains(
                "(setq custom-file (expand-file-name \"jetpacs/custom.el\" user-emacs-directory))",
            ),
        )
        val kit = text.indexOf("(require 'jetpacs)")
        val custom = text.indexOf("(load custom-file 'noerror 'nomessage)")
        assertTrue(kit >= 0 && custom > kit)
        assertTrue(text.indexOf("(setq custom-file") < kit)
    }

    @Test
    fun initElNamesTheUnixSocketInsteadOfThePortWhenGiven() {
        val text = EngineFiles.initEl(8766, "/data/user/0/x/files/ebp/companion.sock")
        assertTrue(text.contains("(setq jetpacs-transport 'loopback-unix\n      jetpacs-socket \"/data/user/0/x/files/ebp/companion.sock\")"))
        assertTrue(!text.contains("jetpacs-port"))
    }

    @Test
    fun siteInitPreloadsTheKitLibrariesButNeverTheCompositionRoot() {
        val text = EngineFiles.siteInit()
        assertTrue(text.contains("(dolist (feature '(ebp-store ebp-sqlite ebp "))
        assertTrue(text.contains(" jetpacs-org-clock ebp-complete))"))
        // Loading these interactively seeds Org files and paths into the
        // loadup-time org-directory (~/org), not the vault: never preloaded.
        for (seeding in listOf("jetpacs-org-settings", "jetpacs-org-mode", "jetpacs-org-dialogs",
                "jetpacs-org-render", "jetpacs-reader-org", "jetpacs-editor-org", "jetpacs-org-table")) {
            assertTrue(seeding, seeding !in EngineFiles.PRELOAD)
        }
        assertTrue(!EngineFiles.PRELOAD.contains("jetpacs-init"))
        assertTrue(!EngineFiles.PRELOAD.contains("jetpacs"))
        assertTrue(!EngineFiles.PRELOAD.contains("jetpacs-credentials"))
        assertTrue(EngineFiles.PRELOAD.none { it.startsWith("glasspane") || it.startsWith("orgzly") || it.startsWith("harp") || it.startsWith("bloom") })
        assertTrue(text.contains("(condition-case err"))
    }

    /**
     * The composition root seeds preloaded libraries' variables with defvar
     * before it loads them; the dump must leave those unbound or the seeds are
     * no-ops (found on the Pixel Tablet: no navigation rail, no Settings, Org
     * outside the vault). The unbind follows the preload and precedes the dump.
     */
    @Test
    fun siteInitUnbindsTheRootsDefvarSeedsAfterThePreload() {
        val text = EngineFiles.siteInit()
        val preload = text.indexOf("(require feature)")
        val unbind = text.indexOf("(makunbound (cadr form))")
        val overlays = text.indexOf("(remove-overlays)")
        assertTrue(preload in 0 until unbind)
        assertTrue(unbind < overlays)
        assertTrue(text.contains("(expand-file-name \"jetpacs-init.el\" kit)"))
        assertTrue(text.contains("(eq (car-safe form) 'defvar) (cddr form) (boundp (cadr form))"))
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
