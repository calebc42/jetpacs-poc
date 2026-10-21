// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The same families as the Android actual, built from the bundled files
 * through the host's [JetpacsJvmFonts.loader]. Without a loader, the
 * platform's default families stand in.
 */
internal actual object PlexFonts {
    private class Families(val sans: FontFamily, val serif: FontFamily, val mono: FontFamily)

    private var built: Pair<JetpacsJvmFonts.Loader, Families>? = null

    @Synchronized
    private fun families(): Families? {
        val loader = JetpacsJvmFonts.loader ?: return null
        built?.takeIf { it.first === loader }?.let { return it.second }
        fun font(file: String, weight: FontWeight): Font =
            loader.load("font/$file.ttf", weight, FontStyle.Normal)
        val families = Families(
            sans = FontFamily(
                font("ibm_plex_sans_regular", FontWeight.Normal),
                font("ibm_plex_sans_medium", FontWeight.Medium),
                font("ibm_plex_sans_semibold", FontWeight.SemiBold),
            ),
            serif = FontFamily(
                font("ibm_plex_serif_regular", FontWeight.Normal),
                font("ibm_plex_serif_medium", FontWeight.Medium),
                font("ibm_plex_serif_semibold", FontWeight.SemiBold),
            ),
            mono = FontFamily(
                font("ibm_plex_mono_regular", FontWeight.Normal),
                font("ibm_plex_mono_medium", FontWeight.Medium),
                font("ibm_plex_mono_semibold", FontWeight.SemiBold),
                font("ibm_plex_mono_bold", FontWeight.Bold),
            ),
        )
        built = loader to families
        return families
    }

    actual val sans: FontFamily get() = families()?.sans ?: FontFamily.SansSerif
    actual val serif: FontFamily get() = families()?.serif ?: FontFamily.Serif
    actual val mono: FontFamily get() = families()?.mono ?: FontFamily.Monospace
}
