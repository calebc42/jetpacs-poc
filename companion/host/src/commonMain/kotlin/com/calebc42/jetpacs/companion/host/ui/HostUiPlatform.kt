// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.ui

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

    /** The window's current size in dp, read from the platform's own source. */
    @Composable
    fun windowSizeDp(): DpSize

    /**
     * Bring the user to Emacs, or null where this host must not launch or
     * signal one (a desktop Companion, SPEC §5.8). Chrome omits its button then.
     */
    val openEmacs: (() -> Unit)?

    /** The listener's address as the user should type it, for the waiting screen. */
    val listeningEndpoint: String
}

/** The platform behind the window; every host provides it at its root. */
val LocalHostUiPlatform = staticCompositionLocalOf<HostUiPlatform> {
    error("HostUiPlatform is not provided above this composition")
}
