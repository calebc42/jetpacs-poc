// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.text.font.FontFamily

/**
 * The IBM Plex families the design model names. Android loads them from this
 * module's font resources; the desktop JVM falls back to the platform's
 * default families until D2 of jetpacs-poc `docs/PLAN-desktop-companion.md`
 * loads the same files through Compose resources.
 */
internal expect object PlexFonts {
    val sans: FontFamily
    val serif: FontFamily
    val mono: FontFamily
}
