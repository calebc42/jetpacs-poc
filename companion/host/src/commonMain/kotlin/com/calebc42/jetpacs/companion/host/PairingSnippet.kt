// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

/**
 * The Emacs init snippet a pairing screen shows: the two `jetpacs-init.el`
 * pairing variables, then the transport when it is not SPEC §5.2's default
 * profile on its default port, then `(require 'jetpacs)`. Under
 * `emacs-unix` the socket path is always named, so the two sides cannot
 * compute different defaults in silence. The token travels out of band
 * through this text and nowhere else (SPEC §9.1).
 */
fun pairingInitSnippet(
    pairingId: String,
    token: String,
    port: Int? = null,
    transport: String = "loopback-tcp",
    socket: String? = null,
): String =
    buildString {
        append("(setq jetpacs-pairing-id \"").append(pairingId).append("\"\n")
        append("      jetpacs-pairing-token \"").append(token).append('"')
        if (transport == "emacs-unix" || transport == "loopback-unix") {
            append("\n      jetpacs-transport '").append(transport)
            if (socket != null) append("\n      jetpacs-socket \"").append(socket).append('"')
        } else if (port != null && port != DEFAULT_LOOPBACK_PORT) {
            append("\n      jetpacs-port ").append(port)
        }
        append(")\n(require 'jetpacs)")
    }

/** SPEC §5.2: the loopback-tcp default port. */
const val DEFAULT_LOOPBACK_PORT = 8765
