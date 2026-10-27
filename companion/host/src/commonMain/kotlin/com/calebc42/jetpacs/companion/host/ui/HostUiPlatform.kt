// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.ui

import com.calebc42.jetpacs.companion.host.HostWording
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.DpSize

/**
 * What the shared window composition rents from the platform it is shown
 * on. Android answers with the Activity's back dispatcher, configuration and
 * launcher; the desktop with its window state, no system back and no Emacs
 * launch (SPEC §5.8). Provided through [LocalHostUiPlatform] by the host's
 * root composable.
 */
interface HostUiPlatform {
    /**
     * Run [onBack] for the platform's back gesture while [enabled]. A
     * platform without a system back gesture composes nothing.
     */
    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)

    /**
     * Dim the content behind the dialog this composes inside by [amount]
     * (0..1), where the platform's dialog window can. The default leaves the
     * platform's own dim.
     */
    @Composable
    fun DialogDim(amount: Float) {}

    /** The window's current size in dp, read from the platform's own source. */
    @Composable
    fun windowSizeDp(): DpSize

    /**
     * Bring the user to Emacs, or null where this host must not launch or
     * signal one (a desktop Companion, SPEC §5.8). Chrome omits its button then.
     */
    val openEmacs: (() -> Unit)?

    /** How the window names the Emacs host; a bundled deployment names its engine. */
    val wording: HostWording get() = HostWording.Emacs

    /** The listener's address as the user should type it, for the waiting screen. */
    val listeningEndpoint: String

    /**
     * A settings section the platform owns, composed before "Installation":
     * the bundled Android engine's policy and diagnostics (SPEC §5.1 asks a
     * bundled deployment to show its profile and address somewhere). The
     * default composes nothing.
     */
    @Composable
    fun ExtraSettings(bridge: com.calebc42.jetpacs.companion.host.DeviceBridge) {}

    /**
     * The waiting screen's sentence about this host's transport. The default
     * is the binding host's; a dialing host says what it dials.
     */
    val listeningDescription: String
        get() = "Jetpacs Companion is listening on $listeningEndpoint."
}

/** The platform behind the window; every host provides it at its root. */
val LocalHostUiPlatform = staticCompositionLocalOf<HostUiPlatform> {
    error("HostUiPlatform is not provided above this composition")
}
