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
     * The user's own fonts on this device, or null on a platform that keeps
     * none; the Fonts section of the settings screen composes only with both
     * this and a [rememberFontFilePicker] launcher.
     */
    val userFonts: com.calebc42.jetpacs.companion.host.fonts.UserFonts? get() = null

    /**
     * Remember a launcher for the platform's file picker, which hands the
     * chosen file to [onPicked], or null when the user dismissed it. A
     * platform without a picker returns null and the Fonts section offers
     * no "Add" button. Reading the file happens off the UI thread.
     */
    @Composable
    fun rememberFontFilePicker(
        onPicked: (com.calebc42.jetpacs.companion.host.fonts.PickedFontFile?) -> Unit,
    ): (() -> Unit)? = null

    /**
     * Settings the platform owns, composed first on the Jetpacs Settings
     * screen and ending with their own divider: the bundled Android
     * engine's background running and where its notes live. The default
     * composes nothing.
     */
    @Composable
    fun LeadingSettings(bridge: com.calebc42.jetpacs.companion.host.DeviceBridge) {}

    /**
     * The platform's diagnostics, composed last and starting with their own
     * divider: SPEC §5.1 asks a bundled deployment to show its profile and
     * address somewhere, with the engine's state and maintenance actions.
     * The default composes nothing.
     */
    @Composable
    fun DiagnosticsSettings(bridge: com.calebc42.jetpacs.companion.host.DeviceBridge) {}

    /**
     * Whether "Set up or repair Jetpacs" applies: onboarding pairs a
     * Companion with an Emacs the user installs. A bundled deployment has
     * none to set up, and its engine's maintenance actions are the repair.
     */
    val offersSetup: Boolean get() = true

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
