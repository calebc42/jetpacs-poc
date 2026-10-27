// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

/**
 * How the Companion's own UI names the Emacs host to the user. A Companion
 * that pairs with an Emacs the user runs (the standalone Android app, the
 * desktop) says "Emacs". A bundled deployment (EBP SPEC amendment #209), where
 * Emacs is the application's invisible engine, names that engine instead: its
 * user has no Emacs of their own to open (docs/PLAN-bundled-emacs.md).
 */
data class HostWording(
    /** Title of the banner over a cached surface while no session is live. */
    val offlineTitle: String,
    /** The banner's second line. */
    val offlineDetail: String,
    /** The banner's action that opens or starts the host, where one exists. */
    val openAction: String,
    /** Title of the screen shown before the first surface arrives. */
    val waitingTitle: String,
    /** Toast when a tap is dropped because no session is live (SPEC 15.1). */
    val offlineDropNotice: String,
) {
    companion object {
        /** The wording of a Companion paired with an Emacs the user runs. */
        val Emacs = HostWording(
            offlineTitle = "Emacs is offline",
            offlineDetail = "Showing the last screen Emacs sent.",
            openAction = "Open Emacs",
            waitingTitle = "Waiting for Emacs",
            offlineDropNotice = "Emacs is offline. Nothing was saved or sent.",
        )
    }
}
