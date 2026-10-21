// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * How a JVM host turns this module's bundled font files into fonts.
 *
 * The JVM jar carries the IBM Plex files as classpath resources
 * (`font/ibm_plex_sans_regular.ttf` and the rest). Turning a resource into a
 * [Font] is desktop-only Compose API that this module cannot name: its JVM
 * target also compiles in builds that see only Google's stubs. So a host
 * installs the one platform call here, once, before its first composition,
 * and [PlexFonts] builds the families through it. With no loader installed
 * the design model's Plex families fall back to the platform's default sans,
 * serif and monospace families.
 */
object JetpacsJvmFonts {
    /** Loads the classpath resource at [resource] as a font of [weight] and [style]. */
    fun interface Loader {
        fun load(resource: String, weight: FontWeight, style: FontStyle): Font
    }

    @Volatile
    var loader: Loader? = null
}
