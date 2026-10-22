// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Emacs init snippet every pairing screen shows, per transport profile. */
class PairingSnippetTest {
    @Test
    fun theDefaultPortIsNotNamed() {
        assertEquals(
            "(setq jetpacs-pairing-id \"id\"\n      jetpacs-pairing-token \"tok\")\n(require 'jetpacs)",
            pairingInitSnippet("id", "tok"),
        )
        assertEquals(pairingInitSnippet("id", "tok"), pairingInitSnippet("id", "tok", port = DEFAULT_LOOPBACK_PORT))
    }

    @Test
    fun aConfiguredPortIsNamed() {
        assertEquals(
            "(setq jetpacs-pairing-id \"id\"\n      jetpacs-pairing-token \"tok\"\n      jetpacs-port 8766)\n(require 'jetpacs)",
            pairingInitSnippet("id", "tok", port = 8766),
        )
    }

    @Test
    fun emacsUnixNamesTheTransportAndTheSocketAndNoPort() {
        assertEquals(
            "(setq jetpacs-pairing-id \"id\"\n      jetpacs-pairing-token \"tok\"\n" +
                "      jetpacs-transport 'emacs-unix\n      jetpacs-socket \"/run/user/1000/ebp/ebp.sock\")\n(require 'jetpacs)",
            pairingInitSnippet("id", "tok", port = 8766, transport = "emacs-unix", socket = "/run/user/1000/ebp/ebp.sock"),
        )
    }
}
