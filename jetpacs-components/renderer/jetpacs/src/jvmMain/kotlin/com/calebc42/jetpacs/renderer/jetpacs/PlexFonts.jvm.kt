// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.text.font.FontFamily

/** D1 placeholder: the platform families stand in for IBM Plex until D2. */
internal actual object PlexFonts {
    actual val sans: FontFamily = FontFamily.SansSerif
    actual val serif: FontFamily = FontFamily.Serif
    actual val mono: FontFamily = FontFamily.Monospace
}
