// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontFamily

/**
 * The families a device installed beyond the bundled ones: the user's own
 * font files, turned into Compose families by the host.
 *
 * [named] maps a bounded identifier a design may carry as a `font-family`
 * value (`DesignFontFamily.Named`) to the family built from the user's files.
 * [sans], [serif] and [mono] replace the device's family for a whole role:
 * `plex-sans` and `system` resolve to [sans], `plex-serif` to [serif],
 * `plex-mono` and `system-mono` to [mono], and the renderer's own typography
 * follows the same roles, so one setting re-fonts every design and every
 * Jetpacs control at once. A null role keeps the bundled IBM Plex family, or
 * the platform default, as before. A named family this device lacks resolves
 * to the sans role, so a design authored against one device's fonts still
 * renders on another.
 *
 * Font bytes never travel over EBP: the wire carries only the name, and the
 * files stay where the host keeps them.
 */
data class JetpacsFontSet(
    val named: Map<String, FontFamily> = emptyMap(),
    val sans: FontFamily? = null,
    val serif: FontFamily? = null,
    val mono: FontFamily? = null,
) {
    companion object {
        /** No user fonts: every family is the bundled or platform one. */
        val None = JetpacsFontSet()
    }
}

/**
 * The process-wide installed font set. Like [JetpacsJvmFonts] this is a seam
 * the host fills: the renderer cannot read files or know where a user keeps
 * fonts. [install] replaces the whole set. Every design style, text style and
 * theme reads [current] under composition and keys its remembered Style on
 * it, so an installed set re-resolves the on-screen text without a
 * reconnect, the way a theme push does.
 */
object JetpacsFonts {
    private val state = mutableStateOf(JetpacsFontSet.None)

    /** The installed set; a snapshot-state read, so composition observes it. */
    val current: JetpacsFontSet
        get() = state.value

    /** Replace the installed set; may be called from any thread. */
    fun install(set: JetpacsFontSet) {
        state.value = set
    }
}
