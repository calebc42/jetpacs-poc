// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.ui.text.platform.Font
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsJvmFonts

/**
 * The desktop's one platform call for fonts: the Jetpacs renderer ships the
 * IBM Plex files on the classpath and asks the host to turn a resource into a
 * [androidx.compose.ui.text.font.Font] (D2 of docs/PLAN-desktop-companion.md).
 * Install before the first composition.
 */
object DesktopFonts {
    fun install() {
        JetpacsJvmFonts.loader = JetpacsJvmFonts.Loader { resource, weight, style ->
            Font(resource = resource, weight = weight, style = style)
        }
    }
}
