// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

internal actual object PlexFonts {
    actual val sans = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    )
    actual val serif = FontFamily(
        Font(R.font.ibm_plex_serif_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_serif_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_serif_semibold, FontWeight.SemiBold),
    )
    actual val mono = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
    )
}
