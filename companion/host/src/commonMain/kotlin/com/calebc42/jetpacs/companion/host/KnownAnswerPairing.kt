// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

/**
 * The public SPEC §9.3 known-answer credential. It is printed in the
 * specification, so it secures nothing; it exists so a developer can drive a
 * Companion from the smoke scripts and the default `jetpacs-init.el` values
 * before a generated pairing is in place. A host that uses it must say so
 * where the user can see it.
 */
object KnownAnswerPairing {
    const val PAIRING_ID = "101112131415161718191a1b1c1d1e1f"
    const val TOKEN = "AAECAwQFBgcICQoLDA0ODw"
}
